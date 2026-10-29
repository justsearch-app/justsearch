/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.ByteRange;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.TimeRangeMs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cross-package preservation: real Worker planning, capture, retrieval, counts and facets. */
class SearchSyntaxRestrictionIntegrationTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path directory;
  private RunningRuntime runtime;
  private WorkerSearchService service;

  @BeforeEach
  void open() throws Exception {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(0).fields());
    fields.add(new FieldDef("author", "text", true, false, List.of("highlight"), null, "icu", false));
    runtime = IndexSchema.fromCatalog(new FieldCatalogDef("syntax-restrictions", fields))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(runtime);
    index("/inside/target", 100L, 50L, "budget review " + "ordinary ".repeat(300), "budget", "Ada");
    for (int i = 0; i < 40; i++) {
      index("/outside/decoy-" + i, 200L, 100L, "budget review priority",
          "budget review priority", "Ada priority");
    }
    refresh();
  }

  @AfterEach
  void close() {
    if (runtime != null) runtime.close();
  }

  @Test
  void phraseHonoursEachRestrictionBeforeCandidateBudget() throws Exception {
    crossed("\"budget review\" priority");
  }

  @Test
  void exclusionHonoursEachRestrictionBeforeCandidateBudget() throws Exception {
    index("/inside/excluded", 100L, 50L, "budget priority", "embargo", "Ada");
    refresh();
    crossed("budget -embargo priority");
  }

  @Test
  void titleHonoursEachRestrictionBeforeCandidateBudget() throws Exception {
    index("/inside/content-only", 100L, 50L, "budget priority", "ordinary", "Nobody");
    refresh();
    crossed("title:budget priority");
  }

  @Test
  void authorHonoursEachRestrictionBeforeCandidateBudget() throws Exception {
    index("/inside/content-only", 100L, 50L, "Ada priority", "ordinary", "Nobody");
    refresh();
    crossed("author:Ada priority");
  }

  @Test
  void combinedBoundsExcludeEachCrossedDecoyAndUnknownWithoutBroadening() throws Exception {
    index("/outside/folder-only", 100L, 50L, "budget review priority", "budget", "Ada");
    index("/inside/date-only", 101L, 50L, "budget review priority", "budget", "Ada");
    index("/inside/size-only", 100L, 51L, "budget review priority", "budget", "Ada");
    index("/inside/unknown", null, null, "budget review priority", "budget", "Ada");
    index("/inside/zero", 100L, 0L, "budget review priority", "budget", "Ada");
    refresh();
    for (String query : List.of("\"budget review\"", "budget -embargo", "title:budget", "author:Ada")) {
      assertPopulation(query, allBounds(), Set.of("/inside/target"));
      assertPopulation(query, allBounds().toBuilder().setPathPrefix("/missing").build(), Set.of());
      assertPopulation(query, allBounds().toBuilder()
          .setSizeBytes(ByteRange.newBuilder().setFromBytes(0).setToBytes(0)).build(), Set.of("/inside/zero"));
    }
  }

  @Test
  void plainSearchWithRestrictionsEqualsSimpleIncludingCountsAndFacets() throws Exception {
    var request = request("budget", allBounds(), true).build();
    SearchResponse search = service.search(request, CallContext.none());
    SearchResponse simple = service.search(request.toBuilder()
        .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE).build(), CallContext.none());
    assertEquals(Set.of("/inside/target"), ids(search));
    assertEquals(simple.getResultsList(), search.getResultsList());
    assertEquals(simple.getTotalHits(), search.getTotalHits());
    assertEquals(simple.getMatchCount(), search.getMatchCount());
    assertEquals(simple.getFacetsMap(), search.getFacetsMap());
  }

  private void crossed(String query) throws Exception {
    var unrestricted = service.search(request(query, SearchFilters.getDefaultInstance(), false).build(), CallContext.none());
    assertTrue(unrestricted.getResults(0).getId().startsWith("/outside/"),
        "precondition: stronger decoys win the one-candidate budget without restrictions");
    var bounds = allBounds();
    for (SearchFilters filter : List.of(
        SearchFilters.newBuilder().setPathPrefix("/inside").build(),
        SearchFilters.newBuilder().setModifiedAt(bounds.getModifiedAt()).build(),
        SearchFilters.newBuilder().setSizeBytes(bounds.getSizeBytes()).build(), bounds)) {
      assertPopulation(query, filter, Set.of("/inside/target"));
    }
  }

  private void assertPopulation(String query, SearchFilters filters, Set<String> expected) {
    for (boolean facets : List.of(false, true)) {
      var response = service.search(request(query, filters, facets).build(), CallContext.none());
      assertEquals(expected, ids(response), query + " " + filters);
      assertEquals(expected.size(), response.getTotalHits(), query);
      assertEquals(expected.size(), response.getMatchCount(), query);
      if (facets && !expected.isEmpty()) {
        assertEquals((long) expected.size(), response.getFacetsOrThrow(SchemaFields.MIME)
            .getCountsOrThrow("text/plain"));
      }
    }
  }

  private static SearchRequest.Builder request(String query, SearchFilters filters, boolean facets) {
    var request = SearchRequest.newBuilder().setQuery(query).setLimit(1)
        .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SEARCH).setFilters(filters)
        // The Worker must still enforce strict lexical retrieval for an explicit semantic pipeline.
        .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true)
            .setSpladeEnabled(true).setExpansionEnabled(true).setFusionAlgorithm("rrf"));
    if (facets) request.setFacets(FacetSpec.newBuilder().setInclude(true)
        .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(10)));
    return request;
  }

  private static SearchFilters allBounds() {
    return SearchFilters.newBuilder().setPathPrefix("/inside")
        .setModifiedAt(TimeRangeMs.newBuilder().setFromMs(100).setToMs(100))
        .setSizeBytes(ByteRange.newBuilder().setFromBytes(50).setToBytes(50)).build();
  }

  private static Set<String> ids(SearchResponse response) {
    return response.getResultsList().stream().map(hit -> hit.getId()).collect(Collectors.toSet());
  }

  private void index(String id, Long date, Long size, String content, String title, String author) {
    var fields = new HashMap<String, Object>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, id);
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.TITLE, title);
    fields.put(SchemaFields.AUTHOR, author);
    fields.put(SchemaFields.MIME, "text/plain");
    if (date != null) fields.put(SchemaFields.MODIFIED_AT, date);
    if (size != null) fields.put(SchemaFields.SIZE_BYTES, size);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private void refresh() throws Exception {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
