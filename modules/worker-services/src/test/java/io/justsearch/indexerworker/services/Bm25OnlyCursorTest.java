/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real capture/planner/executor/response paths with explicit vectors; no native encoders. */
final class Bm25OnlyCursorTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path temp;
  private String previous;
  private RunningRuntime runtime;
  private WorkerSearchService service;

  @BeforeEach
  void open() throws Exception {
    previous = System.getProperty("justsearch.config");
    Path config = temp.resolve("config.yaml");
    // Quote correction flags because their YAML reader accepts textual values only.
    Files.writeString(config, "app:\n  data_dir: '" + temp.toString().replace('\\', '/')
        + "'\nindex:\n  collections:\n    - name: bm25cursor\n      roots: ['ignored']\n"
        + "vector:\n  dimension: 4\nsearch:\n  corrections:\n    enabled: 'true'\n"
        + "    zero_hit_retry_enabled: 'true'\n    max_edit_distance: 1\n    df_threshold: 2\n");
    System.setProperty("justsearch.config", config.toString());
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).ephemeral()
        .withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(runtime);
  }

  @AfterEach
  void close() {
    try { if (runtime != null) runtime.close(); }
    finally {
      if (previous == null) System.clearProperty("justsearch.config");
      else System.setProperty("justsearch.config", previous);
    }
  }

  @Test
  void deliberateDenseSkipConsumesItsCursorAndKeepsItsReason() {
    seed(101);
    // An explicit vector reaches the planner with a successful encoding without any native work.
    SearchRequest request = request(true, true, false).toBuilder()
        .addAllVector(List.of(1f, 0f, 0f, 0f)).build();
    Set<String> seen = new HashSet<>();
    for (int page = 0; page < 6; page++) {
      SearchResponse response = search(request);
      assertEquals(101, response.getTotalHits());
      assertEquals(Math.min(20, 101 - page * 20), response.getResultsCount());
      assertFalse(response.getSearchTrace().getDegradation().getHybridFallback());
      var dense = response.getSearchTrace().getStagesList().stream()
          .filter(s -> s.getId().equals("dense-retrieval")).findFirst().orElseThrow();
      assertEquals("skipped", dense.getStatus());
      assertEquals("SKIPPED_NO_DISCRIMINATIVE_TERM", dense.getReason());
      for (var hit : response.getResultsList()) assertTrue(seen.add(hit.getId()), "repeated parent");
      if (page < 5) assertFalse(response.getNextCursor().isBlank());
      else assertTrue(response.getNextCursor().isBlank());
      request = request.toBuilder().setCursor(response.getNextCursor()).build();
    }
    assertEquals(new HashSet<>(IntStream.range(0, 101).mapToObj(i -> "doc-" + i).toList()), seen);
  }

  @Test
  void absentSpladeKeywordFallbackConsumesItsCursorAndKeepsItsReason() {
    seed(30);
    SearchRequest request = request(false, false, true);
    SearchResponse first = search(request);
    SearchResponse next = search(request.toBuilder().setCursor(first.getNextCursor()).build());
    assertEquals(20, first.getResultsCount());
    assertEquals(10, next.getResultsCount());
    assertEquals(30, next.getTotalHits());
    assertTrue(next.getNextCursor().isBlank());
    Set<String> seen = new HashSet<>();
    for (var response : List.of(first, next)) {
      assertEquals("NO_EMBEDDING_SERVICE", response.getSearchTrace().getDegradation().getSpladeSkipReason());
      assertFalse(response.getSearchTrace().getDegradation().getSpladeExecuted());
      for (var hit : response.getResultsList()) assertTrue(seen.add(hit.getId()));
    }
    assertEquals(30, seen.size());
  }

  @Test
  void genuineDenseFusionStaysCursorlessAndIgnoresInboundCursor() {
    seed(30);
    SearchRequest request = request(true, true, false).toBuilder()
        .addAllVector(List.of(1f, 0f, 0f, 0f)).build();
    SearchResponse first = search(request);
    SearchResponse withCursor = search(request.toBuilder().setCursor("invalid").build());
    assertEquals("HYBRID", first.getSearchTrace().getEffectiveMode());
    assertTrue(first.getNextCursor().isBlank());
    assertTrue(withCursor.getNextCursor().isBlank());
    assertEquals(first.getResultsList().stream().map(h -> h.getId()).toList(),
        withCursor.getResultsList().stream().map(h -> h.getId()).toList());
    assertEquals(first.getTotalHits(), withCursor.getTotalHits());
  }

  @Test
  void textKeepsFuzzyRetriesWhileBm25FallbackDoesNotGainThem() {
    assertTrue(runtime.resolvedConfig().search().corrections().enabled(), "fixture enables corrections");
    assertTrue(runtime.resolvedConfig().search().corrections().zeroHitRetryEnabled(),
        "fixture enables the existing zero-hit retry");
    seed(30);
    SearchResponse text = search(request(true, false, false).toBuilder().setQuery("quokkb").build());
    SearchResponse fallback = search(request(true, true, false).toBuilder().setQuery("quokkb").build());
    assertEquals(30, text.getTotalHits());
    assertTrue(TraceStageAccess.correctionApplied(text));
    assertEquals(0, fallback.getTotalHits());
    assertFalse(TraceStageAccess.correctionApplied(fallback));
  }

  @Test
  void malformedRawSyntaxRetainsItsExistingInvalidArgument() {
    seed(1);
    for (boolean dense : List.of(false, true)) {
      var invalid = request(true, dense, false).toBuilder().setQuery("\"")
          .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE).build();
      WorkerServiceException failure = assertThrows(WorkerServiceException.class, () -> search(invalid));
      assertEquals(WorkerServiceException.Status.INVALID_ARGUMENT, failure.status());
      assertTrue(failure.getMessage().startsWith("Invalid query syntax:"));
    }
  }

  private static SearchRequest request(boolean sparse, boolean dense, boolean splade) {
    return SearchRequest.newBuilder().setQuery("quokka").setLimit(20).setPipeline(
        PipelineConfig.newBuilder().setSparseEnabled(sparse).setDenseEnabled(dense)
            .setSpladeEnabled(splade)).build();
  }

  private SearchResponse search(SearchRequest request) { return service.search(request, CallContext.none()); }

  private void seed(int count) {
    for (int i = 0; i < count; i++) {
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "doc-" + i, SchemaFields.DOC_UID, "doc-" + i + "#0",
          SchemaFields.PATH, "/doc-" + i, SchemaFields.CONTENT, "quokka telemetry stable parent",
          SchemaFields.VECTOR, new float[] {1f, 0f, 0f, 0f})));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
