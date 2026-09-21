package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.services.lifecycle.LifecycleProjection;
import io.justsearch.app.api.status.ChunkCoverageView;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.EnrichmentProgressViewBuilder;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("StatusLifecycleHandler unit tests")
final class StatusLifecycleHandlerTest {

  @Test
  @DisplayName("healthHttpStatus returns 503 for null state")
  void nullStateReturns503() {
    assertEquals(503, StatusLifecycleHandler.healthHttpStatus(null));
  }

  @ParameterizedTest
  @EnumSource(value = LifecycleState.class, names = {"LIFECYCLE_STATE_READY", "LIFECYCLE_STATE_DEGRADED"})
  @DisplayName("healthHttpStatus returns 200 for READY and DEGRADED")
  void healthyStatesReturn200(LifecycleState state) {
    assertEquals(200, StatusLifecycleHandler.healthHttpStatus(state));
  }

  @ParameterizedTest
  @EnumSource(value = LifecycleState.class, names = {"LIFECYCLE_STATE_STARTING", "LIFECYCLE_STATE_ERROR", "LIFECYCLE_STATE_STOPPING", "LIFECYCLE_STATE_STOPPED"})
  @DisplayName("healthHttpStatus returns 503 for non-healthy states")
  void unhealthyStatesReturn503(LifecycleState state) {
    assertEquals(503, StatusLifecycleHandler.healthHttpStatus(state));
  }

  @Test
  @DisplayName("throughputReadinessReason returns stalled reason when active work has stalled")
  void throughputReadinessReasonReturnsStalled() {
    WorkerOperationalView workerView = workerView(12, 6, 2, "STALLED");
    assertEquals(
        "worker.throughput_stalled",
        StatusLifecycleHandler.throughputReadinessReason(workerView));
  }

  @Test
  @DisplayName("throughputReadinessReason ignores throughput state when no index work is active")
  void throughputReadinessReasonIgnoresIdleBackends() {
    WorkerOperationalView workerView = workerView(0, 0, 0, "STALLED");
    assertNull(StatusLifecycleHandler.throughputReadinessReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason maps embedding BLOCKED_LEGACY → index.embedding_legacy (600 Design A)")
  void compatBlockedReasonMapsEmbeddingLegacy() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("BLOCKED_LEGACY", "LEGACY_INDEX_NO_FINGERPRINT", "", "", "", "", "", true, "embedding_legacy"));
    assertEquals("index.embedding_legacy", StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason maps schema BLOCKED_LEGACY → index.blocked_legacy, prioritized over embedding")
  void compatBlockedReasonMapsSchemaLegacyFirst() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("BLOCKED_MISMATCH", "x", "", "", "", "", "BLOCKED_LEGACY", true, "legacy_index"));
    // Schema is checked before embedding (mirrors the worker's reindexRequiredReason precedence).
    assertEquals("index.blocked_legacy", StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason returns null for a COMPATIBLE / empty compat state")
  void compatBlockedReasonNullWhenCompatible() {
    WorkerOperationalView workerView = compatWorkerView(CompatibilityStatusView.empty());
    assertNull(StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason ignores REBUILDING (owned by embeddingRebuildReason — no reindex remedy)")
  void compatBlockedReasonIgnoresRebuilding() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("REBUILDING", "REBUILD_IN_PROGRESS", "", "", "", "", "COMPATIBLE", false, ""));
    assertNull(StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("BLOCKED_LEGACY worker view rolls up to retrieval composite DEGRADED + the compat reason (600 Design A end-to-end)")
  void blockedLegacyRollsUpToRetrievalCompositeDegraded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        compatWorkerView(
            new CompatibilityStatusView(
                "BLOCKED_LEGACY", "LEGACY_INDEX_NO_FINGERPRINT", "", "", "", "", "COMPATIBLE", true, "embedding_legacy"));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    // The INDEX_SERVING component is DEGRADED with the specific compat reason...
    assertEquals("DEGRADED", env.components().get("indexServing").state());
    assertEquals("index.embedding_legacy", env.components().get("indexServing").reasonCode());
    // ...and it rolls up into the `retrieval` composite the 595 verdict consumes. INDEX_SERVING's
    // DEGRADED is the composite's floor here: no sibling retrieval dim reaches a higher-precedence
    // state on this fixture, so nothing masks the compat reason.
    assertEquals("DEGRADED", env.composites().get("retrieval").state());
    assertTrue(
        env.composites().get("retrieval").reasonCodes().contains("index.embedding_legacy"),
        "retrieval composite must carry the compat reason so the verdict can name it");
  }

  @Test
  @DisplayName("COMPATIBLE worker view leaves INDEX_SERVING READY — compat does not degrade it (negative control)")
  void compatibleWorkerViewLeavesIndexServingReady() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view = compatWorkerView(CompatibilityStatusView.empty());
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("READY", env.components().get("indexServing").state());
    String reason = env.components().get("indexServing").reasonCode();
    assertFalse(reason != null && reason.startsWith("index."), "no compat reason when COMPATIBLE");
  }

  @ParameterizedTest
  @EnumSource(ComponentState.class)
  void aiDiagnosticPreservesOptionalIntentAndFailureClasses(ComponentState state) {
    StatusLifecycleHandler handler = newHandler();
    readyProjection();
    String reason = state == ComponentState.READY ? null : "inference." + state.name().toLowerCase();
    COMPONENTS.transition("generative", state, reason, "captured generative state");

    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(
        compatWorkerView(CompatibilityStatusView.empty()),
        COMPONENTS.projection(),
        freshContact());

    String expected = switch (state) {
      case READY -> "READY";
      case ABSENT -> "NOT_CONFIGURED";
      case STARTING -> "NOT_READY";
      case RELOADING, FAILED, UNAVAILABLE -> "DEGRADED";
    };
    assertEquals(expected, env.components().get("ai").state());
    assertEquals(reason, env.components().get("ai").reasonCode());
  }

  @Test
  @DisplayName("F-021: an unconfigured LambdaMART reranker reads READY + informational reason, leaving retrieval READY")
  void unconfiguredLambdamartLeavesRetrievalCompositeReady() {
    // newHandler() wires no LambdaMART/GPL suppliers — the shipped default (F-021: the reranker
    // measured harmful and is absent by design), which previously pinned `retrieval` to DEGRADED
    // on every install.
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withChunkCoverage(
            compatWorkerView(CompatibilityStatusView.empty()),
            /* indexedDocuments= */ 0,
            ChunkCoverageView.empty());
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("READY", env.components().get("lambdamartModel").state());
    assertEquals(
        "lambdamart.not_configured", env.components().get("lambdamartModel").reasonCode());
    assertEquals("READY", env.components().get("indexServing").state());
    assertEquals(
        "READY",
        env.composites().get("retrieval").state(),
        "an install without LambdaMART must not read as permanently degraded retrieval");
  }

  // ===== Tempdoc 598 reopen (B-3): dense-serviceability projection onto the retrieval composite =====

  @Test
  @DisplayName("denseUnavailableReason maps UNAVAILABLE compat (no embedding model) → index.dense_unavailable")
  void denseUnavailableReasonMapsUnavailableCompat() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("UNAVAILABLE", "NO_EMBEDDING_MODEL", "", "", "", "", "COMPATIBLE", false, ""));
    assertEquals("index.dense_unavailable", StatusLifecycleHandler.denseUnavailableReason(workerView));
    // It is NOT a compatBlockedReason (no rebuild remedy — a reindex won't add a missing model).
    assertNull(StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("denseUnavailableReason maps COMPATIBLE-but-embedder-down (embeddingReady=false) → index.dense_unavailable")
  void denseUnavailableReasonMapsCompatibleButEmbedderDown() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""),
            false);
    assertEquals("index.dense_unavailable", StatusLifecycleHandler.denseUnavailableReason(workerView));
  }

  @Test
  @DisplayName("denseUnavailableReason returns null for COMPATIBLE + embedder ready, and for unknown (null embeddingReady)")
  void denseUnavailableReasonNullWhenServiceableOrUnknown() {
    // COMPATIBLE + embeddingReady=true → dense serviceable → no reason (no false alarm).
    assertNull(
        StatusLifecycleHandler.denseUnavailableReason(
            compatWorkerView(
                new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""),
                true)));
    // COMPATIBLE + embeddingReady=null (unknown) → we never alarm on "don't know".
    assertNull(
        StatusLifecycleHandler.denseUnavailableReason(
            compatWorkerView(
                new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""))));
  }

  @Test
  @DisplayName("denseUnavailableReason does NOT fire for REBUILDING (owned by embeddingRebuildReason)")
  void denseUnavailableReasonIgnoresRebuilding() {
    assertNull(
        StatusLifecycleHandler.denseUnavailableReason(
            compatWorkerView(
                new CompatibilityStatusView("REBUILDING", "REBUILD_IN_PROGRESS", "", "", "", "", "COMPATIBLE", false, ""),
                false)));
  }

  @Test
  @DisplayName("UNAVAILABLE compat rolls up to retrieval composite DEGRADED + index.dense_unavailable (end-to-end)")
  void unavailableCompatRollsUpToRetrievalCompositeDegraded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        compatWorkerView(
            new CompatibilityStatusView("UNAVAILABLE", "NO_EMBEDDING_MODEL", "", "", "", "", "COMPATIBLE", false, ""));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("DEGRADED", env.components().get("indexServing").state());
    assertEquals("index.dense_unavailable", env.components().get("indexServing").reasonCode());
    assertEquals("DEGRADED", env.composites().get("retrieval").state());
  }

  @Test
  @DisplayName("COMPATIBLE-but-embedder-down rolls up to retrieval composite DEGRADED + index.dense_unavailable (end-to-end)")
  void compatibleButEmbedderDownRollsUpToRetrievalCompositeDegraded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        compatWorkerView(
            new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""),
            false);
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("DEGRADED", env.components().get("indexServing").state());
    assertEquals("index.dense_unavailable", env.components().get("indexServing").reasonCode());
    assertEquals("DEGRADED", env.composites().get("retrieval").state());
  }

  // ===== In-place embedding rebuild (embeddingCompatState=REBUILDING) is visible to readiness =====

  @Test
  @DisplayName("embeddingRebuildReason maps REBUILDING → index.embedding_rebuilding")
  void embeddingRebuildReasonMapsRebuilding() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("REBUILDING", "REBUILD_IN_PROGRESS", "", "", "", "", "COMPATIBLE", false, ""));
    assertEquals(
        "index.embedding_rebuilding", StatusLifecycleHandler.embeddingRebuildReason(workerView));
  }

  @Test
  @DisplayName("embeddingRebuildReason returns null for COMPATIBLE, both BLOCKED_* states, and a null compat")
  void embeddingRebuildReasonNullOutsideRebuilding() {
    assertNull(
        StatusLifecycleHandler.embeddingRebuildReason(
            compatWorkerView(
                new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""))));
    assertNull(
        StatusLifecycleHandler.embeddingRebuildReason(
            compatWorkerView(
                new CompatibilityStatusView("BLOCKED_LEGACY", "LEGACY_INDEX_NO_FINGERPRINT", "", "", "", "", "COMPATIBLE", true, "embedding_legacy"))));
    assertNull(
        StatusLifecycleHandler.embeddingRebuildReason(
            compatWorkerView(
                new CompatibilityStatusView("BLOCKED_MISMATCH", "FINGERPRINT_MISMATCH", "", "", "", "", "COMPATIBLE", true, "embedding_mismatch"))));
    assertNull(StatusLifecycleHandler.embeddingRebuildReason(compatWorkerView(null)));
  }

  @Test
  @DisplayName("REBUILDING rolls up to indexServing + embedding DEGRADED and both composites carry index.embedding_rebuilding (end-to-end)")
  void embeddingRebuildRollsUpToBothComposites() {
    StatusLifecycleHandler handler = newHandler();
    // The live-observed shape: the encoder is loaded (embeddingReady=true) while the Worker refuses
    // dense queries because the embeddings are being rebuilt in place. The empty-corpus chunk
    // coverage keeps CHUNK_EMBEDDING READY, so the composite's DEGRADED is attributable to this
    // branch rather than to a sibling dimension that was already degraded on the base fixture.
    WorkerOperationalView view =
        withChunkCoverage(
            compatWorkerView(
                new CompatibilityStatusView("REBUILDING", "REBUILD_IN_PROGRESS", "", "", "", "", "COMPATIBLE", false, ""),
                true),
            /* indexedDocuments= */ 0,
            ChunkCoverageView.empty());
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("DEGRADED", env.components().get("indexServing").state());
    assertEquals(
        "index.embedding_rebuilding", env.components().get("indexServing").reasonCode());
    // The encoder-loaded probe must no longer read READY while queries cannot use embeddings.
    assertEquals("DEGRADED", env.components().get("embedding").state());
    assertEquals("index.embedding_rebuilding", env.components().get("embedding").reasonCode());
    // Pins the attribution: every other retrieval dimension is READY on this fixture, so a
    // DEGRADED composite can only have come from the two arms above.
    assertEquals("READY", env.components().get("chunkEmbedding").state());
    assertEquals("READY", env.components().get("workerControlPlane").state());
    assertEquals("READY", env.components().get("visualTextExtraction").state());
    assertEquals("READY", env.components().get("lambdamartModel").state());
    assertEquals("DEGRADED", env.composites().get("retrieval").state());
    assertTrue(
        env.composites().get("retrieval").reasonCodes().contains("index.embedding_rebuilding"),
        "the retrieval composite must name the rebuild so the verdict can word it");
    assertEquals("DEGRADED", env.composites().get("aiFeatures").state());
  }

  // ===== Tempdoc 804 §D1: an EMPTY corpus is not a chunk-embedding degradation =====

  @Test
  @DisplayName("804 §D1: an empty corpus (no chunks, no documents) does NOT emit chunk_embedding.not_ready")
  void emptyCorpusDoesNotClaimChunkEmbeddingNotReady() {
    StatusLifecycleHandler handler = newHandler();
    // A fresh install: worker serving, nothing indexed, no chunk passages, no chunk vectors.
    WorkerOperationalView view =
        withChunkCoverage(
            compatWorkerView(CompatibilityStatusView.empty()),
            /* indexedDocuments= */ 0,
            ChunkCoverageView.empty());
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("READY", env.components().get("chunkEmbedding").state());
    assertNull(env.components().get("chunkEmbedding").reasonCode());
    assertFalse(
        env.composites().get("retrieval").reasonCodes().contains("chunk_embedding.not_ready"),
        "a fresh empty install must not present as a retrieval degradation");
  }

  @Test
  @DisplayName("804 §D1: a NON-empty corpus with 0% chunk coverage still emits chunk_embedding.not_ready")
  void nonEmptyCorpusStillClaimsChunkEmbeddingNotReady() {
    StatusLifecycleHandler handler = newHandler();
    // Documents are indexed but no chunk vectors exist yet — genuinely pending work.
    WorkerOperationalView view =
        withChunkCoverage(
            compatWorkerView(CompatibilityStatusView.empty()),
            /* indexedDocuments= */ 42,
            new ChunkCoverageView(0, 0, 0, 0, 0.0, false, false, 0, 0, 0.0));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("DEGRADED", env.components().get("chunkEmbedding").state());
    assertEquals("chunk_embedding.not_ready", env.components().get("chunkEmbedding").reasonCode());
    assertTrue(
        env.composites().get("retrieval").reasonCodes().contains("chunk_embedding.not_ready"));
  }

  @Test
  @DisplayName("804 §D1: chunk passages exist but no documents counted → still not_ready (inconsistency is not hidden)")
  void chunksWithoutParentDocumentsStillClaimNotReady() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withChunkCoverage(
            compatWorkerView(CompatibilityStatusView.empty()),
            /* indexedDocuments= */ 0,
            new ChunkCoverageView(7, 0, 7, 0, 0.0, false, false, 0, 0, 0.0));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("DEGRADED", env.components().get("chunkEmbedding").state());
    assertEquals("chunk_embedding.not_ready", env.components().get("chunkEmbedding").reasonCode());
  }

  @Test
  @DisplayName("OCR blocker degrades retrieval only when visual text extraction is needed")
  void ocrBlockerDegradesRetrievalWhenVisualTextNeeded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, false, "tesseract", "ocr.engine_missing", 2L, 0L, null, false));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("DEGRADED", env.components().get("visualTextExtraction").state());
    assertEquals("ocr.engine_missing", env.components().get("visualTextExtraction").reasonCode());
    assertTrue(env.composites().get("retrieval").reasonCodes().contains("ocr.engine_missing"));
  }

  @Test
  @DisplayName("OCR blocker is ignored when no visual text extraction is pending")
  void ocrBlockerIgnoredWhenNoVisualTextNeeded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, false, "tesseract", "ocr.engine_missing", 0L, 0L, null, false));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("READY", env.components().get("visualTextExtraction").state());
    assertFalse(env.composites().get("retrieval").reasonCodes().contains("ocr.engine_missing"));
  }

  @Test
  @DisplayName("VDU blocker degrades retrieval when baseline visual text still needs VDU")
  void vduBlockerDegradesRetrievalWhenBaselineTextNeedsVdu() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, true, "tesseract", null, 2L, 0L, "vdu.circuit_open", false));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("DEGRADED", env.components().get("visualTextExtraction").state());
    assertEquals("vdu.circuit_open", env.components().get("visualTextExtraction").reasonCode());
    assertTrue(env.composites().get("retrieval").reasonCodes().contains("vdu.circuit_open"));
  }

  @Test
  @DisplayName("VDU enrichment blocker degrades aiFeatures without degrading retrieval")
  void vduEnrichmentBlockerDegradesAiFeaturesOnly() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, true, "tesseract", null, 0L, 4L, "vdu.missing_mmproj", false));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readyProjection(), freshContact());

    assertEquals("READY", env.components().get("visualTextExtraction").state());
    assertEquals("DEGRADED", env.components().get("visualDocumentUnderstanding").state());
    assertEquals(
        "vdu.missing_mmproj", env.components().get("visualDocumentUnderstanding").reasonCode());
    assertFalse(env.composites().get("retrieval").reasonCodes().contains("vdu.missing_mmproj"));
    assertTrue(env.composites().get("aiFeatures").reasonCodes().contains("vdu.missing_mmproj"));
  }

  @Test
  @DisplayName("tempdoc 837: an orderly shutdown reaches INDEX_SERVING as NOT_CONFIGURED, not an error")
  void workerShutDownIsANotConfiguredVerdictNotAnError() {
    StatusLifecycleHandler handler = newHandler();
    COMPONENTS.transition(
        "index", ComponentState.ABSENT, "worker.shut_down", "orderly shutdown");

    ReadinessEnvelopeView env =
        handler.buildReadinessEnvelope(
            compatWorkerView(CompatibilityStatusView.empty()),
            COMPONENTS.projection(),
            freshContact());

    // The branch that used to key on worker.not_configured alone: without shut_down joining it, an
    // orderly teardown would fall through to the ERROR-shaped branches and the tap row for
    // (INDEX_SERVING, NOT_CONFIGURED, worker.shut_down) would be dead on arrival.
    assertEquals("NOT_CONFIGURED", env.components().get("indexServing").state());
    assertEquals("worker.shut_down", env.components().get("indexServing").reasonCode());
  }

  /** Worker answered during this response build — the provenance these state/reason tests assume. */
  private static StatusLifecycleHandler.WorkerContact freshContact() {
    return StatusLifecycleHandler.WorkerContact.observed(System.currentTimeMillis());
  }

  private static final StatusComponentFixture COMPONENTS = new StatusComponentFixture();

  /** A real four-component READY projection (so INDEX_SERVING is not gated by index state). */
  private static LifecycleProjection.Projection readyProjection() {
    for (String name : java.util.List.of("api", "index", "encoders", "generative")) {
      COMPONENTS.transition(name, ComponentState.READY, null, name + " ready");
    }
    return COMPONENTS.projection();
  }

  /** Minimal handler — buildReadinessEnvelope null-guards every non-retrieval dimension's suppliers. */
  private static StatusLifecycleHandler newHandler() {
    var graph = COMPONENTS.capabilities();
    StatusLifecycleHandler handler = new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> null,
        null,
        null,
        null,
        Instant.now(),
        () -> "OK",
        null,
        null,
        null,
        graph.worker(),
        graph.inference());
    COMPONENTS.attach(handler);
    return handler;
  }

  private static WorkerOperationalView compatWorkerView(CompatibilityStatusView compat) {
    return compatWorkerView(compat, null);
  }

  /** Tempdoc 598 reopen: overload that sets the health-derived embeddingReady (== isAvailable() on the Worker). */
  private static WorkerOperationalView compatWorkerView(
      CompatibilityStatusView compat, Boolean embeddingReady) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationView.empty())
        .compatibility(compat)
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
        .searchConfig(SearchConfigView.empty())
        .embeddingReady(embeddingReady)
        .build();
  }

  /** Tempdoc 804 §D1: overrides the two counts the CHUNK_EMBEDDING dimension reads. */
  private static WorkerOperationalView withChunkCoverage(
      WorkerOperationalView view, long indexedDocuments, ChunkCoverageView chunk) {
    CoreIndexView core = view.core();
    return WorkerOperationalViewBuilder.builder()
        .core(
            new CoreIndexView(
                core.indexHealthy(),
                indexedDocuments,
                core.pendingJobs(),
                core.indexState(),
                core.indexSizeBytes(),
                core.pendingVduCount()))
        .failure(view.failure())
        .migration(view.migration())
        .compatibility(view.compatibility())
        .queueDb(view.queueDb())
        .enrichment(
            EnrichmentProgressViewBuilder.builder(view.enrichment()).chunk(chunk).build())
        .gpu(view.gpu())
        .vectorFormat(view.vectorFormat())
        .telemetry(view.telemetry())
        .searchConfig(view.searchConfig())
        .visualExtraction(view.visualExtraction())
        .buildStamp(view.buildStamp())
        .aiReady(view.aiReady())
        .embeddingReady(view.embeddingReady())
        .build();
  }

  private static WorkerOperationalView withVisualExtraction(
      WorkerOperationalView view, VisualExtractionView visualExtraction) {
    return WorkerOperationalViewBuilder.builder()
        .core(view.core())
        .failure(view.failure())
        .migration(view.migration())
        .compatibility(view.compatibility())
        .queueDb(view.queueDb())
        .enrichment(view.enrichment())
        .gpu(view.gpu())
        .vectorFormat(view.vectorFormat())
        .telemetry(view.telemetry())
        .searchConfig(view.searchConfig())
        .visualExtraction(visualExtraction)
        .buildStamp(view.buildStamp())
        .aiReady(view.aiReady())
        .embeddingReady(view.embeddingReady())
        .build();
  }

  /**
   * Tempdoc 837 S6 (§2.1/§2.2) — the retirement of {@code index.rebuilding}, asserted positively.
   *
   * <p>{@code compatBlockedReason} used to emit that code whenever a corruption-triggered rebuild was
   * MIGRATING or SWITCHING. That is exactly the window the FE verdict forces to {@code transitioning},
   * where the readiness notice returns null — so the code was published on the wire and no surface
   * could ever word it. It is gone, and the reason travels as a facet of the transition instead. This
   * pins the removal: if the branch comes back, this test goes red rather than the code silently
   * re-appearing on a composite nothing renders.
   */
  @Test
  void corruptIndexRebuildNoLongerProducesACompatBlockedReason() {
    for (String migrationState : new String[] {"MIGRATING", "SWITCHING"}) {
      WorkerOperationalView view =
          WorkerOperationalViewBuilder.builder()
              .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
              .failure(FailureTrackingView.empty())
              .migration(
                  MigrationGenerationViewBuilder.builder()
                      .migrationState(migrationState)
                      .migrationSource("corrupt_index_rebuild")
                      .migrationEnumerator(MigrationGenerationView.empty().migrationEnumerator())
                      .build())
              .compatibility(CompatibilityStatusView.empty())
              .queueDb(QueueDbStatusView.healthy())
              .enrichment(EnrichmentProgressView.empty())
              .gpu(GpuDiagnosticsView.empty())
              .vectorFormat(VectorFormatView.empty())
              .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
              .searchConfig(SearchConfigView.empty())
              .build();

      assertNull(
          StatusLifecycleHandler.compatBlockedReason(view),
          migrationState + ": a generation rebuild is a transition, not a compat block");
    }
  }

  private static WorkerOperationalView workerView(
      long pendingJobsCount, long processingJobsCount, long pendingReadyJobsCount, String throughputWindowState) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 10, pendingJobsCount, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationViewBuilder.builder()
            .pendingJobsCount(pendingJobsCount)
            .processingJobsCount(processingJobsCount)
            .pendingReadyJobsCount(pendingReadyJobsCount)
            .migrationEnumerator(MigrationGenerationView.empty().migrationEnumerator())
            .build())
        .compatibility(CompatibilityStatusView.empty())
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, throughputWindowState))
        .searchConfig(SearchConfigView.empty())
        .build();
  }
}
