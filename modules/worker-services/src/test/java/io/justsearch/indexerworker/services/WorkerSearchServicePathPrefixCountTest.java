/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human-validation finding 4 (round 14): a {@code pathPrefix}-filtered search returned rows that were
 * all inside the prefix, but a count computed over a different population — moving by 2 and 3 where 6
 * and 5 out-of-prefix documents existed, and once reporting MORE matches filtered than unfiltered.
 *
 * <p>This is the served-path regression home for the counting half: on the sparse path the response's
 * {@code totalHits} and {@code matchCount} are both exact, filter-respecting counts, so a filtered
 * search must report exactly the in-prefix matching population and never more than the unfiltered
 * search did. ({@code totalHits} on the multi-leg path is deliberately the bounded fused-candidate
 * union — see {@code KnowledgeSearchResponse#totalHits} / tempdoc 597 — which is why the FE headline
 * binds to {@code matchCount}; this test pins the population question on the path where both are
 * exact.)
 */
@DisplayName("WorkerSearchService — pathPrefix scopes the counts, not just the rows")
final class WorkerSearchServicePathPrefixCountTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private RunningRuntime runtime;
  private String prevConfig;

  private static final String INSIDE_DIR = QueryFilterBuilder.normalizePathPrefix("corpus/inside");
  private static final String OUTSIDE_DIR = QueryFilterBuilder.normalizePathPrefix("corpus/outside");

  @BeforeEach
  void setup() throws Exception {
    prevConfig = System.getProperty("justsearch.config");
    Path tempDir = Files.createTempDirectory("justsearch-pathprefix-count-test-");
    String yaml =
        "app:\n  data_dir: " + tempDir.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: pathprefixtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-pathprefix-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
    if (prevConfig == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("filtered totalHits/matchCount equal the in-prefix population and never exceed unfiltered")
  void filteredCountsDescribeTheFilteredPopulation() throws Exception {
    // 3 matching documents inside the requested prefix, 5 matching documents outside it.
    for (int i = 0; i < 3; i++) {
      indexDoc(INSIDE_DIR + "in-" + i + ".md", "troubleshooting help for the indexer");
    }
    for (int i = 0; i < 5; i++) {
      indexDoc(OUTSIDE_DIR + "out-" + i + ".md", "troubleshooting help for the indexer");
    }
    commitAndRefresh();

    SearchResponse unfiltered = search(baseRequest().build());
    SearchResponse filtered =
        search(
            baseRequest()
                .setFilters(SearchFilters.newBuilder().setPathPrefix("corpus/inside").build())
                .build());

    assertEquals(8L, unfiltered.getTotalHits(), "all 8 documents match the query when unscoped");

    for (SearchResult hit : filtered.getResultsList()) {
      assertTrue(
          hit.getId().startsWith(INSIDE_DIR),
          "every returned row must live under the requested prefix; got: " + hit.getId());
    }
    assertEquals(
        3L,
        filtered.getTotalHits(),
        "totalHits must count the SAME population the rows are drawn from — the 3 in-prefix "
            + "documents, not a population the filter never touched");
    assertEquals(
        3L,
        filtered.getMatchCount(),
        "matchCount is the true matched-document total and must respect the pathPrefix filter");
    assertTrue(
        filtered.getTotalHits() <= unfiltered.getTotalHits(),
        "narrowing a corpus cannot increase a match count (filtered="
            + filtered.getTotalHits()
            + ", unfiltered="
            + unfiltered.getTotalHits()
            + ")");
  }

  @Test
  @DisplayName("a chunk of an out-of-prefix parent is not retrievable under the prefix")
  void chunkLegIsScopedByPathPrefix() throws Exception {
    String insideDoc = INSIDE_DIR + "in-0.md";
    String outsideDoc = OUTSIDE_DIR + "out-0.md";
    indexDoc(insideDoc, "troubleshooting help for the indexer");
    indexChunk(insideDoc, "troubleshooting help passage");
    indexDoc(outsideDoc, "troubleshooting help for the indexer");
    indexChunk(outsideDoc, "troubleshooting help passage");
    commitAndRefresh();

    var filters =
        io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .pathPrefix("corpus/inside")
            .build();
    var chunkFilter = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(chunkFilter, "pathPrefix must survive into the chunk-branch filter");

    var chunkHits = runtime.chunkSearchOps().searchChunksText("troubleshooting", 10, chunkFilter);
    assertEquals(
        1,
        chunkHits.hits().size(),
        "the chunk branch feeds the fused candidate union totalHits reports — an out-of-prefix "
            + "parent's chunk reaching it is exactly how the count population diverges from the rows");
    assertEquals(
        insideDoc, chunkHits.hits().get(0).fields().get(SchemaFields.PARENT_DOC_ID));
  }

  private SearchRequest.Builder baseRequest() {
    return SearchRequest.newBuilder()
        .setQuery("troubleshooting")
        .setLimit(2)
        .setMode(SearchMode.SEARCH_MODE_TEXT);
  }

  private SearchResponse search(SearchRequest request) {
    var service = new WorkerSearchService(runtime);
    SearchResponse response = service.search(request, CallContext.none());
    assertNotNull(response, "search() produced no response");
    return response;
  }

  private void indexDoc(String docId, String content) {
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, docId,
                    SchemaFields.DOC_UID, docId + "#0",
                    SchemaFields.PATH, docId,
                    SchemaFields.CONTENT, content)));
  }

  private void indexChunk(String parentDocId, String chunkContent) {
    String chunkId = parentDocId + "#chunk_0";
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, chunkId,
                    SchemaFields.DOC_UID, chunkId + "#0",
                    SchemaFields.IS_CHUNK, "true",
                    SchemaFields.PARENT_DOC_ID, parentDocId,
                    SchemaFields.CHUNK_INDEX, "0",
                    SchemaFields.CHUNK_TOTAL, "1",
                    SchemaFields.CHUNK_CONTENT, chunkContent,
                    SchemaFields.PATH, parentDocId)));
  }

  private void commitAndRefresh() throws Exception {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
