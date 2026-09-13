package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.State;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 517 verification — §A.12 E2 query matrix executed against the
 * in-process gRPC + real Lucene surface (Tier-3a).
 *
 * <p>The 7 {@code LegSet} variants + {@code EmptyQueryDecision} +
 * {@code BlockedDecision} are the decision tree's possible outcomes. This test
 * covers what is reachable without mocking dense / SPLADE encoders:
 *
 * <ul>
 *   <li>{@code EmptyQueryDecision} — blank query, sparse-only pipeline.
 *   <li>{@code BlockedDecision} — vector-only request, compat gate blocking.
 *   <li>{@code SparseShortcut} — sparse-only request (its own path, distinct
 *       from BM25-only via hybrid degradation).
 *   <li>{@code MultiLegDecision(LegSet.Bm25Only)} — hybrid request degraded to
 *       BM25-only (vector encoding failed → no embedding service).
 * </ul>
 *
 * <p>The remaining 6 {@code LegSet} variants ({@code DenseOnly},
 * {@code SpladeOnly}, {@code Bm25Dense}, {@code Bm25Splade},
 * {@code DenseSplade}, {@code ThreeWay}) require working dense + SPLADE
 * encoders for the planner to produce {@code Encoding.Success} states; that
 * is the unit-test gap §B.6 names. They are exercised end-to-end via
 * {@code SearchOrchestratorComposablePathTest.PipelineDegradation} (degradation
 * fall-back paths) and via the live dev-stack (Tier-3b).
 *
 * <p>Wire fields asserted per decision per §"Response field sources" in the
 * tempdoc body: {@code effective_mode}, {@code vector_blocked},
 * {@code vector_blocked_reason}, {@code hybrid_fallback},
 * {@code chunk_merge_applied}, {@code chunk_merge_reason}.
 */
@DisplayName("SearchExecutor LegSet matrix (tempdoc 517 §A.12 E2 Tier-3a)")
final class SearchExecutorLegSetMatrixTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @Test
  @DisplayName("EmptyQueryDecision: blank query + sparse-only pipeline → 0 hits + SKIPPED_EMPTY_QUERY")
  void emptyQueryDecision() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertNotNull(response);
      assertEquals(0, response.getTotalHits(), "Empty query yields 0 hits");
      assertEquals(
          "SKIPPED_EMPTY_QUERY",
          TraceStageAccess.chunkMergeReason(response),
          "Empty query short-circuits to chunk_merge_reason=SKIPPED_EMPTY_QUERY");
      assertFalse(TraceStageAccess.chunkMergeApplied(response));
      assertFalse(TraceStageAccess.correctionApplied(response));
      assertFalse(response.getSearchTrace().getDegradation().getSpladeExecuted());
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("BlockedDecision: vector-only request + compat gate → vectorBlocked + SKIPPED_VECTOR_BLOCKED")
  void blockedDecisionViaCompat() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      EmbeddingCompatibilityController controller =
          new EmbeddingCompatibilityController(Map::of, () -> 1L);
      forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
      service.setEmbeddingCompatController(controller);

      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("ignored")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setDenseEnabled(true).build())
                  .build());
      assertNotNull(response);
      assertTrue(response.getSearchTrace().getDegradation().getVectorBlocked(), "Vector-only request must mark vectorBlocked");
      assertEquals(
          "LEGACY_INDEX_NO_FINGERPRINT",
          response.getSearchTrace().getDegradation().getVectorBlockedReason(),
          "BlockedDecision carries the compat reason verbatim");
      assertEquals("VECTOR", response.getSearchTrace().getEffectiveMode());
      assertEquals(
          "SKIPPED_VECTOR_BLOCKED",
          TraceStageAccess.chunkMergeReason(response),
          "Blocked path skips chunk merge");
      assertFalse(TraceStageAccess.chunkMergeApplied(response));
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("SparseShortcut: sparse-only request → TEXT, no degradation, no chunk merge on short corpus")
  void sparseShortcutDecision() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("Hello")
                  .setLimit(10)
                  .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).build())
                  .build());
      assertNotNull(response);
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      assertFalse(response.getSearchTrace().getDegradation().getVectorBlocked());
      assertFalse(response.getSearchTrace().getDegradation().getHybridFallback());
      assertFalse(response.getSearchTrace().getDegradation().getSpladeExecuted(), "SparseShortcut path: splade never executes");
      // Chunk merge gates on hasChunkDocs which is false for this corpus → SKIPPED_NO_CHUNK_DOCS.
      assertEquals(
          "SKIPPED_NO_CHUNK_DOCS",
          TraceStageAccess.chunkMergeReason(response),
          "Single-doc corpus without chunks → SKIPPED_NO_CHUNK_DOCS");
      assertFalse(TraceStageAccess.chunkMergeApplied(response));
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("MultiLegDecision(Bm25Only): hybrid request → vector failed → BM25-only path")
  void multiLegBm25OnlyViaDegradedHybrid() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-2", "Lorem ipsum")) {
      WorkerSearchService service = new WorkerSearchService(lifecycle);
      // Hybrid request with no embedding service → vector encoding fails → degraded BM25-only.
      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("Lorem")
                  .setLimit(10)
                  .setPipeline(
                      PipelineConfig.newBuilder()
                          .setSparseEnabled(true)
                          .setDenseEnabled(true)
                          .build())
                  .build());
      assertNotNull(response);
      assertTrue(response.getSearchTrace().getDegradation().getHybridFallback(), "Hybrid degraded to BM25 → hybridFallback");
      assertEquals("NO_EMBEDDING_SERVICE", response.getSearchTrace().getDegradation().getHybridFallbackReason());
      // Effective mode reflects what executed.
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      assertFalse(response.getSearchTrace().getDegradation().getVectorBlocked(), "vectorBlocked is for BlockedDecision, not MultiLeg fallback");
      assertFalse(response.getSearchTrace().getDegradation().getSpladeExecuted());
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  // ============================================================
  // Helpers (duplicated per tempdoc §B.4 — copy-paste pattern for test isolation)
  // ============================================================

  private static SearchResponse invokeSearch(WorkerSearchService service, SearchRequest request) {
    SearchResponse response = service.search(request, CallContext.none());
    assertNotNull(response);
    return response;
  }

  private RunningRuntime newLifecycleWithOneDoc(String docId, String content)
      throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    Path base = Files.createTempDirectory("justsearch-legset-matrix-test-");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: composable\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, docId,
                    SchemaFields.DOC_UID, docId + "#0",
                    SchemaFields.PATH, docId,
                    SchemaFields.CONTENT, content)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static void forceEmbeddingCompatState(
      EmbeddingCompatibilityController controller, State state, String reasonCode)
      throws Exception {
    Field stateField = EmbeddingCompatibilityController.class.getDeclaredField("state");
    stateField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<State> stateRef = (AtomicReference<State>) stateField.get(controller);
    stateRef.set(state);

    Field reasonField = EmbeddingCompatibilityController.class.getDeclaredField("reasonCode");
    reasonField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<String> reasonRef = (AtomicReference<String>) reasonField.get(controller);
    reasonRef.set(reasonCode);
  }
}
