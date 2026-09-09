/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.observability.health.LifecycleSnapshotTap;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 885 item 6 — the internal Worker status sampler.
 *
 * <p>The property under test is a negative one: {@code GET /api/status} must no longer perform the
 * {@code IndexStatus} unary on the request thread. A test that only asserted "the response still
 * has a worker view" would pass on the pre-item code, so every assertion here is on the
 * <em>interaction count</em> with the mocked {@link KnowledgeClient}, which distinguishes
 * "served from the sample" from "fetched again".
 */
@DisplayName("Worker status sampler (885 item 6)")
final class WorkerStatusSamplerTest {

  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");

  @Test
  @DisplayName("a sampler tick feeds the health taps with no status request anywhere")
  void samplerTickFeedsTapsWithoutAnyStatusRequest(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    // A real capability, starting PENDING exactly as it is before the Worker connects.
    var worker = new io.justsearch.app.services.lifecycle.WorkerCapability();
    StatusLifecycleHandler handler = handlerWith(indexBase, client, worker);

    ConditionStore conditions = new ConditionStore();
    handler.setLifecycleSnapshotTap(
        new LifecycleSnapshotTap(
            conditions, new HealthEventChangeRegistry(), HEAD_SRC, Clock.systemUTC()));

    // First tick with the worker capability still PENDING: the tap must ASSERT index.unavailable.
    // Asserting only the cleared end-state would be vacuous — "absent" is also what a tap that
    // never ran leaves behind.
    handler.sampleAndBuildStatusSnapshot();
    assertTrue(
        conditions.find("index.unavailable", "worker").isPresent(),
        "a NOT_READY worker must make the tap assert index.unavailable");

    // Now the worker is reachable and READY; the next tick must CLEAR it. Only a tap that really
    // ran on both ticks can produce the transition.
    worker.transition(CapabilityHealth.READY, null);
    StatusResponse sampled = handler.sampleAndBuildStatusSnapshot();

    verify(client, times(1)).getWorkerOperationalView(TestRequestContexts.internal());
    assertNotNull(sampled.meta(), "the tick must produce a snapshot");
    assertFalse(sampled.meta().workerRpcStale(), "a reached Worker is not stale");
    assertTrue(
        conditions.find("index.unavailable", "worker").isEmpty(),
        "a healthy sample must clear index.unavailable");
  }

  @Test
  @DisplayName("a status read after a sample performs zero Worker RPCs")
  void statusReadPerformsNoWorkerRpc(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);

    StatusResponse sampled = handler.sampleAndBuildStatusSnapshot();
    verify(client, times(1)).getWorkerOperationalView(TestRequestContexts.internal());

    StatusResponse first = handler.buildStatusSnapshot();
    StatusResponse second = handler.buildStatusSnapshot();
    StatusResponse third = handler.buildStatusSnapshot();

    verifyNoMoreInteractions(client);
    assertFalse(first.meta().workerRpcStale(), "a fresh sample is not stale");
    // workerRpcAtMs is the SAMPLE time, identical across reads — that identity is what proves the
    // reads did not re-observe (a per-request timestamp would differ).
    assertEquals(sampled.meta().workerRpcAtMs(), first.meta().workerRpcAtMs());
    assertEquals(first.meta().workerRpcAtMs(), second.meta().workerRpcAtMs());
    assertEquals(first.meta().workerRpcAtMs(), third.meta().workerRpcAtMs());
    assertTrue(
        System.currentTimeMillis() - third.meta().workerRpcAtMs() >= 0,
        "the response carries the sample's age");
  }

  @Test
  @DisplayName("the taps do not reconcile on the read path")
  void readPathDoesNotFeedTaps(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);
    handler.sampleAndBuildStatusSnapshot();

    // The index-drift tap is the second Worker RPC this item takes off the request thread: it
    // pulls the watched roots itself. Wiring it as a throwing tap makes a read-path invocation
    // observable — the handler swallows tap failures, so the counter is the witness.
    int[] driftCalls = {0};
    handler.setIndexDriftTap(
        new io.justsearch.app.services.observability.health.IndexDriftHealthTap(
            new ConditionStore(),
            new io.justsearch.app.observability.health.OccurrenceLog(),
            new HealthEventChangeRegistry(),
            HEAD_SRC,
            Clock.systemUTC(),
            () -> {
              driftCalls[0]++;
              return java.util.List.of();
            }));

    handler.buildStatusSnapshot();
    assertEquals(0, driftCalls[0], "a status read must not run the index-drift tap");

    handler.sampleAndBuildStatusSnapshot();
    assertEquals(1, driftCalls[0], "the sampler must run the index-drift tap");
  }

  @Test
  @DisplayName("the first read before any sample takes exactly one synchronous sample")
  void firstReadBeforeAnySampleObservesOnce(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);

    handler.buildStatusSnapshot();
    handler.buildStatusSnapshot();
    handler.buildStatusSnapshot();

    // The boot window fallback fires at most once per process: after it, every read is cached.
    verify(client, times(1)).getWorkerOperationalView(TestRequestContexts.internal());
  }

  @Test
  @DisplayName("a failed sample is still a sample, and reads report it stale without re-calling")
  void failedSampleIsCachedAndReportedStale(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenThrow(new IllegalStateException("worker gone"));
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);

    StatusResponse sampled = handler.sampleAndBuildStatusSnapshot();
    StatusResponse read = handler.buildStatusSnapshot();

    verify(client, times(1)).getWorkerOperationalView(TestRequestContexts.internal());
    assertTrue(read.meta().workerRpcStale(), "a failed sample reads stale");
    // The exception text reaches the emitted DTO, not just an internal field — that is what a
    // consumer diagnosing a Worker outage actually sees.
    assertNotNull(sampled.indexStatusReason());
    assertTrue(sampled.indexStatusReason().contains("worker gone"), sampled.indexStatusReason());
    assertEquals(sampled.indexStatusReason(), read.indexStatusReason(), "the read serves the same sample");
  }

  @Test
  @DisplayName("a sample older than SAMPLE_STALE_PERIODS periods reads stale; one within does not")
  void ageBasedStalenessCrossesAtThreePeriods(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);

    long[] now = {1_000_000_000_000L};
    handler.setClockForTesting(() -> now[0]);
    handler.sampleAndBuildStatusSnapshot();
    verify(client, times(1)).getWorkerOperationalView(TestRequestContexts.internal());

    // The boundary is asserted as a LITERAL, not derived from the constants under test: deriving it
    // makes the test move with the value and stay green for any value at all (which is exactly what
    // it did on the first attempt — inflating SAMPLE_STALE_PERIODS to 3_000_000 left it passing).
    assertEquals(3, StatusLifecycleHandler.SAMPLE_STALE_PERIODS, "the pinned period multiplier");
    assertEquals(
        10_000L, StatusLifecycleHandler.SAMPLER_IDLE_PERIOD_MS, "the pinned idle period");
    long window = 30_000L;

    // Just inside the window: a succeeded observation is still trustworthy.
    now[0] += window - 1;
    assertFalse(
        handler.buildStatusSnapshot().meta().workerRpcStale(),
        "a sample within " + StatusLifecycleHandler.SAMPLE_STALE_PERIODS + " periods is fresh");

    // One millisecond past it: the sampler has plainly missed its schedule, so the snapshot must
    // stop presenting itself as current rather than serve a frozen view as fresh.
    now[0] += 2;
    assertTrue(
        handler.buildStatusSnapshot().meta().workerRpcStale(),
        "a sample older than the window must read stale");

    // The boundary is the ONLY thing that changed — no re-observation happened on either read.
    verifyNoMoreInteractions(client);
  }

  @Test
  @DisplayName("an aged-out sample recovers to fresh when the sampler ticks again")
  void agedSampleRecoversOnTheNextTick(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);

    long[] now = {2_000_000_000_000L};
    handler.setClockForTesting(() -> now[0]);
    handler.sampleAndBuildStatusSnapshot();

    now[0] += 35_000L;
    assertTrue(handler.buildStatusSnapshot().meta().workerRpcStale(), "aged out");

    handler.sampleAndBuildStatusSnapshot();
    assertFalse(
        handler.buildStatusSnapshot().meta().workerRpcStale(),
        "a fresh tick must clear the age-based stale flag");
  }

  @Test
  @DisplayName("handleStatus routes ?fresh=true to a sample and anything else to the cache")
  void handleStatusRoutesTheFreshParam(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);
    handler.sampleAndBuildStatusSnapshot();
    verify(client, times(1)).getWorkerOperationalView(TestRequestContexts.internal());

    // The HANDLER, not buildStatusMap — the query-param routing is the part a request exercises,
    // and it is the only place `fresh` is read.
    io.javalin.http.Context ctx = mock(io.javalin.http.Context.class);
    when(ctx.queryParam("fresh")).thenReturn(null);
    handler.handleStatus(ctx);
    verifyNoMoreInteractions(client);

    when(ctx.queryParam("fresh")).thenReturn("false");
    handler.handleStatus(ctx);
    verifyNoMoreInteractions(client);

    when(ctx.queryParam("fresh")).thenReturn("true");
    handler.handleStatus(ctx);
    verify(client, times(2)).getWorkerOperationalView(TestRequestContexts.internal());

    // Case-insensitive, and still exactly one sample per call.
    when(ctx.queryParam("fresh")).thenReturn("TRUE");
    handler.handleStatus(ctx);
    verify(client, times(3)).getWorkerOperationalView(TestRequestContexts.internal());
  }

  @Test
  @DisplayName("the sampling period is 2 s while index work is in flight and 10 s when idle")
  void samplingPeriodTracksInFlightIndexWork(@TempDir Path indexBase) {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(healthyWorkerView());
    StatusLifecycleHandler handler = reachableHandler(indexBase, client);

    assertEquals(
        StatusLifecycleHandler.SAMPLER_IDLE_PERIOD_MS,
        handler.samplingPeriodMs(),
        "before any sample the period is the idle one");

    handler.sampleAndBuildStatusSnapshot();
    assertEquals(StatusLifecycleHandler.SAMPLER_IDLE_PERIOD_MS, handler.samplingPeriodMs());

    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(busyWorkerView());
    handler.sampleAndBuildStatusSnapshot();
    assertEquals(
        StatusLifecycleHandler.SAMPLER_BUSY_PERIOD_MS,
        handler.samplingPeriodMs(),
        "processing jobs in flight must shorten the period");

    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenThrow(new IllegalStateException("worker gone"));
    handler.sampleAndBuildStatusSnapshot();
    assertEquals(
        StatusLifecycleHandler.SAMPLER_IDLE_PERIOD_MS,
        handler.samplingPeriodMs(),
        "a failed sample must not pin the monitor at the fast cadence");
  }

  // ---------------------------------------------------------------- helpers

  private static StatusLifecycleHandler reachableHandler(
      Path indexBase, KnowledgeClient client) {
    io.justsearch.app.services.lifecycle.WorkerCapability worker =
        mock(io.justsearch.app.services.lifecycle.WorkerCapability.class);
    when(worker.available()).thenReturn(true);
    when(worker.health()).thenReturn(CapabilityHealth.READY);
    return handlerWith(indexBase, client, worker);
  }

  /** Variant over a REAL {@link io.justsearch.app.services.lifecycle.WorkerCapability}, so a test
   * can drive an actual capability transition rather than restub a mock. */
  private static StatusLifecycleHandler handlerWith(
      Path indexBase,
      KnowledgeClient client,
      io.justsearch.app.services.lifecycle.WorkerCapability worker) {
    KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    when(ks.client()).thenReturn(client);

    io.justsearch.app.services.lifecycle.InferenceCapability inference =
        mock(io.justsearch.app.services.lifecycle.InferenceCapability.class);
    when(inference.health()).thenReturn(CapabilityHealth.READY);

    StatusLifecycleHandler handler =
        new StatusLifecycleHandler(
            mock(io.justsearch.app.api.OnlineAiService.class),
            mock(io.justsearch.agent.api.AgentService.class),
            () -> null,
            null,
            null,
            indexBase,
            Instant.now(),
            () -> "OK",
            null,
            null,
            null,
            worker,
            inference);
    handler.setKnowledgeServer(ks, null);
    return handler;
  }

  private static WorkerOperationalView healthyWorkerView() {
    return workerView(MigrationGenerationView.empty());
  }

  private static WorkerOperationalView busyWorkerView() {
    return workerView(
        MigrationGenerationViewBuilder.builder()
            .processingJobsCount(4L)
            .pendingJobsCount(120L)
            .migrationEnumerator(
                new io.justsearch.app.api.status.MigrationEnumeratorView(
                    false, false, 0, 0, 0, 0, 0, 0, ""))
            .build());
  }

  private static WorkerOperationalView workerView(MigrationGenerationView migration) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(migration)
        .compatibility(CompatibilityStatusView.empty())
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
        .searchConfig(SearchConfigView.empty())
        .visualExtraction(VisualExtractionView.empty())
        .embeddingReady(Boolean.TRUE)
        .build();
  }
}
