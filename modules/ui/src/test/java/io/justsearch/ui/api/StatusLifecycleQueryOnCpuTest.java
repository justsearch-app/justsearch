/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.services.lifecycle.LifecycleProjection;
import io.justsearch.core.component.ComponentState;
import io.justsearch.ort.DeviceGatePolicy;
import io.justsearch.ort.QueryCpuRecourse;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contract X2 of b6d536, Head side: while searches the GPU turned away are being answered on the
 * CPU (the recourse took a query leg within {@link QueryCpuRecourse#RECENT_MS}), the {@code
 * embedding} component is READY with {@code encoders.health.query_on_cpu}, so status does not read
 * as if semantic search were unavailable; a GPU stuck meanwhile stays visible as {@code
 * encoders.health.semantic_stuck} on the {@code gpu} component. The envelope is built by the
 * production {@link StatusLifecycleHandler#buildReadinessEnvelope} with the handler's clock fixed.
 */
final class StatusLifecycleQueryOnCpuTest {

  private static final String QUERY_ON_CPU = "encoders.health.query_on_cpu";
  private static final String SEMANTIC_STUCK = "encoders.health.semantic_stuck";

  private final StatusComponentFixture components = new StatusComponentFixture();

  @AfterEach
  void close() throws Exception {
    components.close();
  }

  @Test
  void searchesAnsweredOnTheCpuKeepTheEmbeddingReadyWithTheQueryOnCpuCode() {
    StatusLifecycleHandler handler = newHandler();
    long now = 1_800_000_000_000L;
    handler.setClockForTesting(() -> now);

    ReadinessEnvelopeView recent =
        envelope(handler, workerView(null, now - QueryCpuRecourse.RECENT_MS + 1));
    assertEquals("READY", recent.components().get("embedding").state());
    assertEquals(QUERY_ON_CPU, recent.components().get("embedding").reasonCode());
    assertEquals("READY", recent.composites().get("aiFeatures").state(),
        "semantic search answered on the CPU is not a degraded AI feature");
    assertTrue(recent.composites().get("aiFeatures").reasonCodes().contains(QUERY_ON_CPU));

    ReadinessEnvelopeView past =
        envelope(handler, workerView(null, now - QueryCpuRecourse.RECENT_MS));
    assertEquals("READY", past.components().get("embedding").state());
    assertNull(past.components().get("embedding").reasonCode(),
        "the code clears RECENT_MS after the recourse last took a query leg");
  }

  @Test
  void aStuckGpuStaysVisibleBesideSearchesAnsweredOnTheCpu() {
    StatusLifecycleHandler handler = newHandler();
    long now = 1_800_000_000_000L;
    handler.setClockForTesting(() -> now);
    long standing = now - DeviceGatePolicy.DEFAULTS.breakerStandingMs();

    ReadinessEnvelopeView both = envelope(handler, workerView(standing, now - 1_000L));
    assertEquals("READY", both.components().get("embedding").state());
    assertEquals(QUERY_ON_CPU, both.components().get("embedding").reasonCode());
    assertEquals("DEGRADED", both.components().get("gpu").state());
    assertEquals(SEMANTIC_STUCK, both.components().get("gpu").reasonCode());
    assertTrue(both.composites().get("aiFeatures").reasonCodes().contains(QUERY_ON_CPU));
    assertTrue(both.composites().get("aiFeatures").reasonCodes().contains(SEMANTIC_STUCK));

    // Without searches answered on the CPU, the stuck GPU degrades the embedding as before (S3).
    ReadinessEnvelopeView stuckOnly =
        envelope(handler, workerView(standing, now - QueryCpuRecourse.RECENT_MS));
    assertEquals("DEGRADED", stuckOnly.components().get("embedding").state());
    assertEquals(SEMANTIC_STUCK, stuckOnly.components().get("embedding").reasonCode());
    assertEquals("READY", stuckOnly.components().get("gpu").state());
  }

  @Test
  void cpuMissesDoNotClaimServiceButOneSuccessfulCpuLegDoes() throws Exception {
    var markerField = QueryCpuRecourse.class.getDeclaredField("taken");
    markerField.setAccessible(true);
    boolean previous = markerField.getBoolean(null);
    var nanosField = QueryCpuRecourse.class.getDeclaredField("LAST_TAKEN_NANOS");
    nanosField.setAccessible(true);
    var nanos = (java.util.concurrent.atomic.AtomicLong) nanosField.get(null);
    long previousNanos = nanos.get();
    markerField.setBoolean(null, false);
    var released = new java.util.concurrent.CountDownLatch(1);
    var stall = new java.util.concurrent.atomic.AtomicBoolean(false);
    var nativeReturns = new java.util.concurrent.CountDownLatch(2);
    var gpu = mock(ai.onnxruntime.OrtSession.class);
    var cpu = mock(ai.onnxruntime.OrtSession.class);
    org.mockito.Mockito.when(cpu.run(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(call -> {
      if (stall.get()) {
        try { released.await(); }
        finally { nativeReturns.countDown(); }
      }
      return mock(ai.onnxruntime.OrtSession.Result.class);
    });
    var model = java.nio.file.Path.of("missing", "model.onnx");
    var policy = io.justsearch.ort.ModelSessionPolicy.forFallback(
        new io.justsearch.ort.GpuSessionConfig(161, 512L * 1024 * 1024), null, true, false, 60_000L);
    try (var handle = io.justsearch.ort.OrtSessionAssembler.buildManager("status-cpu-misses",
        new io.justsearch.ort.Composition(io.justsearch.ort.RuntimePolicy.defaults(), policy,
            new io.justsearch.ort.ModelArtifacts(model, model)), () -> true)) {
      setHandleField(handle, "gpuSession", gpu);
      setHandleField(handle, "gpuSessionAttempted", true);
      setHandleField(handle, "gpuAvailable", true);
      setHandleField(handle, "cpuSession", cpu);
      try (var warm = handle.acquireCpu(io.justsearch.ort.SessionAcquisitionRequest.within(
          io.justsearch.ort.SessionAcquisitionRequest.Urgency.FOREGROUND, java.time.Duration.ofSeconds(3)));
          var result = warm.run(java.util.Map.of())) {
        assertTrue(warm.isCpu());
        assertTrue(result != null);
      }
      var handler = newHandler();
      stall.set(true);
      try (var bulk = handle.acquire(io.justsearch.ort.SessionAcquisitionRequest.within(
          io.justsearch.ort.SessionAcquisitionRequest.Urgency.BACKGROUND, java.time.Duration.ofMinutes(1)))) {
        assertTrue(!bulk.isCpu());
        for (int i = 0; i < 2; i++) {
          try (var observed = QueryCpuRecourse.observe(); var lease = handle.acquire(
              cpuQuery(java.time.Duration.ofMillis(500)))) {
            org.junit.jupiter.api.Assertions.assertThrows(
                io.justsearch.ort.SessionAcquireDeadlineExceededException.class,
                () -> lease.run(java.util.Map.of()));
            assertEquals("cpu", observed.served().device());
          }
        }
        assertNull(envelope(handler, currentCpuWorker()).components().get("embedding").reasonCode(),
            "the real status assembler must not label two CPU misses as CPU-served searches");
        released.countDown();
        assertTrue(nativeReturns.await(5, java.util.concurrent.TimeUnit.SECONDS));
        stall.set(false);
        try (var observed = QueryCpuRecourse.observe(); var lease = handle.acquire(
            cpuQuery(java.time.Duration.ofSeconds(3))); var result = lease.run(java.util.Map.of())) {
          assertTrue(result != null);
          // Success is recorded at the query encoding boundary; older revisions record at acquire.
          try { observed.getClass().getMethod("succeeded").invoke(observed); }
          catch (NoSuchMethodException beforeRepair) {
            // The baseline records at acquisition and has no success notification API.
          }
        }
        assertEquals(QUERY_ON_CPU,
            envelope(handler, currentCpuWorker()).components().get("embedding").reasonCode());
      } finally { released.countDown(); }
    } finally {
      released.countDown();
      nanos.set(previousNanos);
      markerField.setBoolean(null, previous);
    }
  }

  private static io.justsearch.ort.SessionAcquisitionRequest cpuQuery(java.time.Duration timeout) {
    return new io.justsearch.ort.SessionAcquisitionRequest(
        io.justsearch.ort.SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + timeout.toNanos(), () -> false, false, true);
  }

  private static void setHandleField(Object handle, String name, Object value) throws Exception {
    var field = handle.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(handle, value);
  }

  private static WorkerOperationalView currentCpuWorker() throws Exception {
    var method = io.justsearch.indexerworker.services.WorkerHealthService.class
        .getDeclaredMethod("queryCpuRecourseAtMs");
    method.setAccessible(true);
    long at = (long) method.invoke(null);
    return workerView(null, at == 0 ? null : at);
  }

  private ReadinessEnvelopeView envelope(StatusLifecycleHandler handler, WorkerOperationalView view) {
    return handler.buildReadinessEnvelope(
        view, readyProjection(), StatusLifecycleHandler.WorkerContact.observed(System.currentTimeMillis()), false);
  }

  /** A real four-component READY projection (so INDEX_SERVING is not gated by index state). */
  private LifecycleProjection.Projection readyProjection() {
    for (String name : java.util.List.of("api", "index", "encoders", "generative")) {
      components.transition(name, ComponentState.READY, null, name + " ready");
    }
    return components.projection();
  }

  /** Minimal handler — buildReadinessEnvelope null-guards every non-retrieval dimension's suppliers. */
  private StatusLifecycleHandler newHandler() {
    var graph = components.capabilities();
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
    components.attach(handler);
    return handler;
  }

  /** A serving, compatible worker whose embedding is ready, with the breaker and recourse times. */
  private static WorkerOperationalView workerView(Long semanticStuckSinceMs, Long queryCpuRecourseAtMs) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 0, 0, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationView.empty())
        .compatibility(
            new CompatibilityStatusView("COMPATIBLE", "", "", "", "", "", "COMPATIBLE", false, ""))
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
        .searchConfig(SearchConfigView.empty())
        .embeddingReady(true)
        .semanticStuckSinceMs(semanticStuckSinceMs)
        .queryCpuRecourseAtMs(queryCpuRecourseAtMs)
        .build();
  }
}
