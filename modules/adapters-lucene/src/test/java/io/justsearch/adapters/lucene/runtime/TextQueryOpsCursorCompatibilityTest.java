/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.QuerySyntax;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Preservation checks use the historical signatures, so they also run on the before-state. */
final class TextQueryOpsCursorCompatibilityTest extends RuntimeTestBase {
  private RunningRuntime runtime;

  @BeforeEach
  void open() throws Exception {
    Path config = writeConfig("app:\n  data_dir: '" + dataDir().toString().replace('\\', '/')
        + "'\nindex:\n  collections:\n    - name: cursorcompat\n      roots: ['ignored']\n"
        + "vector:\n  dimension: 4\n");
    System.setProperty("justsearch.config", config.toString());
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
    index("a", "quokka telemetry", "ordinary");
    index("b", "quokka telemetry", "favored");
    index("c", "quokka unrelated", "favored");
    index("outside", "quokka telemetry", "favored");
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  @AfterEach
  void close() {
    if (runtime != null) runtime.close();
  }

  @Test
  void oldSignatureKeepsRawParsingHardFiltersSoftBoostsAndItsOutboundToken() {
    var filters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .docIds(List.of("/a", "/b", "/c")).build();
    var boosts = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .metaAuthor(List.of("favored")).build();
    var text = runtime.textQueryOps();
    var first = text.searchText("+quokka +telemetry", 1, filters, boosts, QuerySyntax.LUCENE);
    assertEquals(2, first.totalHits(), "raw required clauses and hard scope both constrain matches");
    assertEquals(List.of("b"), first.hits().stream().map(h -> h.docId()).toList(),
        "the soft boost selects a unique target without excluding the ordinary author");
    assertNotNull(first.nextCursor());
    var decoded = SearchAfterCursorHelper.decode(first.nextCursor());
    assertEquals(RuntimeSearchSort.RELEVANCE, decoded.sort());
    assertEquals("b", decoded.docId());
    assertEquals(first.hits().getFirst().score(), decoded.score());
    assertTrue(first.nextCursor().startsWith("safter-v1:relevance:"));
    var repeated = text.searchText("+quokka +telemetry", 1, filters, boosts, QuerySyntax.LUCENE);
    assertEquals(first.nextCursor(), repeated.nextCursor(), "old callers still have no inbound cursor");
    assertEquals(first.hits(), repeated.hits());
    var all = text.searchText("+quokka +telemetry", 20, filters, boosts, QuerySyntax.LUCENE);
    assertEquals(List.of("b", "a"), all.hits().stream().map(h -> h.docId()).toList());
    assertNull(all.nextCursor(), "lookahead only emits a token when another match exists");
  }

  @Test
  void readPathStillOwnsProjectionLimitsAndHistoricalRelevanceTokens() throws Exception {
    var query = runtime.textQueryOps().buildTextQuery("telemetry", null);
    var read = runtime.readPathOps();
    var first = read.search(query, 1, Set.of(SchemaFields.PATH), RuntimeSearchSort.RELEVANCE, "");
    assertEquals(3, first.totalHits());
    assertEquals(1, first.hits().size());
    assertEquals(Set.of(SchemaFields.PATH), first.hits().getFirst().fields().keySet());
    // This historical v1 value is consumed directly, without writing another token format.
    String token = "safter-v1:relevance:YQ:" + first.hits().getFirst().score() + ":_:_";
    assertEquals("a", first.hits().getFirst().docId());
    assertEquals(token, first.nextCursor());
    var next = read.search(query, 2, Set.of(SchemaFields.PATH), RuntimeSearchSort.RELEVANCE, token);
    assertEquals(3, next.totalHits());
    assertEquals(Set.of("b", "outside"), new HashSet<>(
        next.hits().stream().map(h -> h.docId()).toList()));
    assertNull(next.nextCursor());
  }

  private void index(String id, String content, String author) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, "/" + id,
        SchemaFields.CONTENT, content, SchemaFields.META_AUTHOR, author)));
  }
}
