package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.ipc.CoreStatus;
import io.justsearch.ipc.EnrichmentCoverage;
import io.justsearch.ipc.GpuDiagnostics;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.OrtCudaProbeResult;
import io.justsearch.ipc.SearchConfig;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.VisualExtractionStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class WorkerStatusMapperTest {

  @Test
  void retiredEntityBoostCannotLeakFromAnOlderWorker() {
    StatusResponse status =
        StatusResponse.newBuilder()
            .setCore(CoreStatus.newBuilder().build())
            .setSearchConfig(SearchConfig.newBuilder().setEntityBoost(2.0).build())
            .build();

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status, null);

    assertEquals(0.0, out.searchConfig().entityBoost(), 0.0);
  }

  /**
   * Task t-20261013-ef3c41, S5 — the Worker's "could not count" marker reaches /api/status as
   * {@code indexedDocumentsUnknown}, so the 0 kept in {@code indexedDocuments} for older readers is
   * never shown as an empty index. Built and read through descriptors and record components so the
   * check compiles against a revision without the field and fails there by assertion.
   */
  @Test
  void anUncountedIndexReachesStatusAsUnknown() throws Exception {
    com.google.protobuf.Descriptors.FieldDescriptor unknownField =
        CoreStatus.getDescriptor().findFieldByName("doc_count_unknown");
    org.junit.jupiter.api.Assertions.assertNotNull(unknownField, "the Worker cannot say a count is unknown");
    StatusResponse status =
        StatusResponse.newBuilder()
            .setCore(CoreStatus.newBuilder().setDocCount(0).setField(unknownField, Boolean.TRUE).build())
            .build();

    Object core = WorkerStatusMapper.toUiStatusMap(status, null).core();

    java.lang.reflect.RecordComponent component = java.util.Arrays.stream(core.getClass().getRecordComponents())
        .filter(c -> c.getName().equals("indexedDocumentsUnknown"))
        .findFirst()
        .orElse(null);
    org.junit.jupiter.api.Assertions.assertNotNull(component, "/api/status cannot say a count is unknown");
    assertEquals(Boolean.TRUE, component.getAccessor().invoke(core));
  }

  @Test
  void toUiStatusMapIncludesReadinessFieldsWhenHealthProvided() {
    StatusResponse status =
        StatusResponse.newBuilder()
            .setCore(CoreStatus.newBuilder().setIsHealthy(true).setDocCount(3).setQueueDepth(1).build())
            .build();
    HealthCheckResponse health =
        HealthCheckResponse.newBuilder().setAiReady(true).setEmbeddingReady(false).build();

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status, health);

    assertTrue(out.core().indexHealthy());
    assertEquals(3L, out.core().indexedDocuments());
    assertEquals(1L, out.core().pendingJobs());
    assertTrue(out.aiReady());
    assertFalse(out.embeddingReady());
  }

  @Test
  void buildHealthNodeIncludesReadinessFields() {
    HealthCheckResponse health =
        HealthCheckResponse.newBuilder()
            .setServing(true)
            .setVersion("test")
            .setPid(123)
            .setWorkerState("IDLE")
            .setAiReady(false)
            .setEmbeddingReady(true)
            .build();

    io.justsearch.app.api.status.HealthNodeView node = WorkerStatusMapper.buildHealthNode(health);

    assertTrue(node.serving());
    assertEquals("test", node.version());
    assertEquals(123L, node.pid());
    assertEquals("IDLE", node.workerState());
    assertFalse(node.aiReady());
    assertTrue(node.embeddingReady());
  }

  @Test
  void visualExtractionStatusMapsToWorkerOperationalView() {
    StatusResponse status =
        StatusResponse.newBuilder()
            .setCore(CoreStatus.newBuilder().build())
            .setVisualExtraction(VisualExtractionStatus.newBuilder()
                .setOcrEnabled(true)
                .setOcrEngineAvailable(false)
                .setOcrEngine("tesseract")
                .setOcrBlockedReason("ocr.engine_missing")
                .setVisualTextNeededCount(7)
                .setVisualEnrichmentNeededCount(3)
                .setVduBlockedReason("vdu.circuit_open")
                .build())
            .build();

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status);

    assertTrue(out.visualExtraction().ocrEnabled());
    assertFalse(out.visualExtraction().ocrEngineAvailable());
    assertEquals("tesseract", out.visualExtraction().ocrEngine());
    assertEquals("ocr.engine_missing", out.visualExtraction().ocrBlockedReason());
    assertEquals(7L, out.visualExtraction().visualTextNeededCount());
    assertEquals(3L, out.visualExtraction().visualEnrichmentNeededCount());
    assertEquals("vdu.circuit_open", out.visualExtraction().vduBlockedReason());
  }

  // ==================== Enrichment / backfillMode passthrough tests (tempdoc 710 Move 2) ====================

  @Test
  @DisplayName("backfillMode round-trips from EnrichmentCoverage proto to EnrichmentProgressView")
  void backfillModeRoundTripsFromWireToView() {
    StatusResponse status =
        StatusResponse.newBuilder()
            .setCore(CoreStatus.newBuilder().build())
            .setEnrichment(EnrichmentCoverage.newBuilder().setBackfillMode("combined").build())
            .build();

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status);

    assertEquals("combined", out.enrichment().backfillMode());
  }

  @Test
  @DisplayName("backfillMode passes through \"individual\" and \"idle\" values unmodified")
  void backfillModePassesThroughOtherModes() {
    for (String mode : new String[] {"individual", "idle"}) {
      StatusResponse status =
          StatusResponse.newBuilder()
              .setCore(CoreStatus.newBuilder().build())
              .setEnrichment(EnrichmentCoverage.newBuilder().setBackfillMode(mode).build())
              .build();

      WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status);

      assertEquals(mode, out.enrichment().backfillMode());
    }
  }

  // ==================== ORT CUDA passthrough tests ====================

  @Test
  @DisplayName("configured=true, attempted=false passes through as pending (not 'not configured')")
  void ortCudaConfiguredPendingPassesThrough() {
    OrtCudaProbeResult probe =
        OrtCudaProbeResult.newBuilder()
            .setConfigured(true)
            .setAttempted(false)
            .setAvailable(false)
            .setFailureReason("GPU session not yet initialized (lazy)")
            .build();
    StatusResponse status = statusWithSpladeProbe(probe);

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status);
    OrtCudaView splade = out.gpu().spladeOrtCuda();

    assertTrue(splade.configured());
    assertFalse(splade.attempted());
    assertFalse(splade.available());
    assertEquals("GPU session not yet initialized (lazy)", splade.failureReason());
  }

  @Test
  @DisplayName("configured=false, attempted=false maps to genuinely not configured")
  void ortCudaNotConfiguredPassesThrough() {
    OrtCudaProbeResult probe =
        OrtCudaProbeResult.newBuilder()
            .setConfigured(false)
            .setAttempted(false)
            .setAvailable(false)
            .setFailureReason("GPU not configured")
            .build();
    StatusResponse status = statusWithSpladeProbe(probe);

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status);
    OrtCudaView splade = out.gpu().spladeOrtCuda();

    assertFalse(splade.configured());
    assertFalse(splade.attempted());
  }

  @Test
  @DisplayName("configured=true, attempted=true, available=true maps to active GPU")
  void ortCudaActiveGpuPassesThrough() {
    OrtCudaProbeResult probe =
        OrtCudaProbeResult.newBuilder()
            .setConfigured(true)
            .setAttempted(true)
            .setAvailable(true)
            .setVariantId("onnxruntime-gpu")
            .setNativePath("/cuda/native")
            .build();
    StatusResponse status = statusWithSpladeProbe(probe);

    WorkerOperationalView out = WorkerStatusMapper.toUiStatusMap(status);
    OrtCudaView splade = out.gpu().spladeOrtCuda();

    assertTrue(splade.configured());
    assertTrue(splade.attempted());
    assertTrue(splade.available());
    assertEquals("onnxruntime-gpu", splade.variantId());
    assertEquals("/cuda/native", splade.nativePath());
  }

  /** Helper: builds a minimal StatusResponse with the given SPLADE ORT probe. */
  private static StatusResponse statusWithSpladeProbe(OrtCudaProbeResult probe) {
    return StatusResponse.newBuilder()
        .setCore(CoreStatus.newBuilder().build())
        .setGpu(GpuDiagnostics.newBuilder().setSpladeOrtCuda(probe).build())
        .build();
  }
}
