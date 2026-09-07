/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV1;
import io.justsearch.app.api.lifecycle.ReadinessDimension;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.ReadinessComponentView;
import io.justsearch.app.api.status.ReadinessCompositeView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.contract.wire.LifecycleState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 821 §3-C1: {@code ReadinessComponentView.stale} / {@code stalenessMs} carry the Head's
 * Worker-contact fact per dimension, and (§P P1) {@code ReadinessCompositeView.stale} /
 * {@code maxStalenessMs} aggregate that fact for the composites the FE actually consumes.
 * Assertions are on the emitted DTO ({@link StatusResponse} and {@link ReadinessEnvelopeView}), not
 * on the handler's internals.
 */
@DisplayName("Readiness per-dimension staleness")
final class StatusReadinessStalenessTest {

  /**
   * The classification {@code stale} depends on. Kept as data here rather than reaching into the
   * handler's private switch: a new {@link ReadinessDimension} constant makes the size assertion
   * below fail, which is the point — the new dimension has to be classified deliberately.
   */
  private static final Map<String, Boolean> WORKER_OBSERVED = workerObservedByKey();

  private static Map<String, Boolean> workerObservedByKey() {
    Map<String, Boolean> m = new LinkedHashMap<>();
    m.put("indexServing", true);
    m.put("embedding", true);
    m.put("chunkEmbedding", true);
    m.put("visualTextExtraction", true);
    m.put("visualDocumentUnderstanding", true);
    // Head-local NVML sample, but its saturation-suppression gate reads the Worker's
    // processingJobsCount — a fallback view zeroes that term and can produce a false DEGRADED.
    m.put("gpu", true);
    m.put("workerControlPlane", false);
    m.put("ai", false);
    m.put("lambdamartModel", false);
    m.put("telemetry", false);
    return m;
  }

  @Test
  @DisplayName("every readiness dimension is classified worker-observed or head-local")
  void everyDimensionIsClassified() {
    assertEquals(
        ReadinessDimension.values().length,
        WORKER_OBSERVED.size(),
        "a new ReadinessDimension must be classified worker-observed or head-local here"
            + " (and in StatusLifecycleHandler.workerObserved)");
    for (ReadinessDimension dim : ReadinessDimension.values()) {
      assertNotNull(WORKER_OBSERVED.get(dim.key()), "unclassified dimension: " + dim.key());
    }

    // The classification is observable: under a lost contact, exactly the worker-observed
    // dimensions report stale.
    StatusLifecycleHandler handler = newHandler(null, Instant.now());
    long now = System.currentTimeMillis();
    ReadinessEnvelopeView env =
        handler.buildReadinessEnvelope(
            healthyWorkerView(),
            readySnapshot(),
            StatusLifecycleHandler.WorkerContact.lost(now - 5_000L, now, now - 60_000L));

    for (ReadinessDimension dim : ReadinessDimension.values()) {
      assertEquals(
          WORKER_OBSERVED.get(dim.key()),
          env.components().get(dim.key()).stale(),
          "stale flag for " + dim.key());
    }
  }

  @Test
  @DisplayName("worker RPC failure marks worker-observed dimensions stale and leaves head-local fresh")
  void workerRpcFailureMarksWorkerDimensionsStale(@TempDir Path indexBase) {
    Instant headStart = Instant.now().minusSeconds(60);
    KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    when(ks.client()).thenThrow(new IllegalStateException("worker gone"));

    StatusLifecycleHandler handler = newHandler(indexBase, headStart, true);
    handler.setKnowledgeServer(ks, null);

    StatusResponse response = handler.buildStatusSnapshot();
    Map<String, ReadinessComponentView> components = response.readiness().components();

    assertTrue(response.meta().workerRpcStale(), "the response's own contact fact");

    for (ReadinessDimension dim : ReadinessDimension.values()) {
      ReadinessComponentView comp = components.get(dim.key());
      if (Boolean.TRUE.equals(WORKER_OBSERVED.get(dim.key()))) {
        assertTrue(comp.stale(), dim.key() + " should be stale when the Worker was not observed");
        // Never observed in this process: staleness measures from Head start (a lower bound),
        // and observedAt is omitted rather than fabricated.
        assertTrue(
            comp.stalenessMs() >= 60_000L,
            dim.key() + " staleness should span the Head's lifetime, was " + comp.stalenessMs());
        assertNull(comp.observedAt(), dim.key() + " must not claim an observation it never made");
      } else {
        assertFalse(comp.stale(), dim.key() + " is head-local and is observed at response time");
        assertEquals(0L, comp.stalenessMs(), dim.key() + " stalenessMs");
        assertEquals(
            response.readiness().observedAt(),
            comp.observedAt(),
            dim.key() + " keeps the response-build observedAt");
      }
    }
  }

  @Test
  @DisplayName("a reachable Worker leaves every dimension fresh")
  void reachableWorkerLeavesEveryDimensionFresh(@TempDir Path indexBase) {
    StatusLifecycleHandler handler = reachableHandler(indexBase, Instant.now().minusSeconds(60));

    StatusResponse response = handler.buildStatusSnapshot();

    assertFalse(response.meta().workerRpcStale());
    for (ReadinessDimension dim : ReadinessDimension.values()) {
      ReadinessComponentView comp = response.readiness().components().get(dim.key());
      assertFalse(comp.stale(), dim.key() + " should be fresh");
      assertEquals(0L, comp.stalenessMs(), dim.key() + " stalenessMs");
      assertEquals(
          response.readiness().observedAt(), comp.observedAt(), dim.key() + " observedAt");
    }
  }

  @Test
  @DisplayName("after contact is lost, staleness is measured from the last successful observation")
  void stalenessMeasuresFromLastSuccessfulObservation(@TempDir Path indexBase) {
    // Head started long ago; the Worker answered just now. If staleness fell back to Head start
    // the numbers below would be ~10 minutes, so this distinguishes the right reason.
    Instant headStart = Instant.now().minusSeconds(600);
    KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView()).thenReturn(healthyWorkerView());
    when(ks.client()).thenReturn(client);

    StatusLifecycleHandler handler = newReachableHandler(indexBase, headStart, ks);

    long beforeSuccess = System.currentTimeMillis();
    StatusResponse fresh = handler.buildStatusSnapshot();
    assertFalse(fresh.meta().workerRpcStale(), "first call should reach the Worker");

    // Contact is lost after that successful observation. Tempdoc 885 item 6: contact loss is
    // discovered by the internal sampler, not by a request — a request only reports what the last
    // sample found, so the sampler is what has to run here.
    when(ks.client()).thenThrow(new IllegalStateException("worker gone"));
    StatusResponse stale = handler.sampleAndBuildStatusSnapshot();
    long afterFailure = System.currentTimeMillis();

    assertTrue(stale.meta().workerRpcStale());
    ReadinessComponentView indexServing = stale.readiness().components().get("indexServing");
    assertTrue(indexServing.stale());
    assertNotNull(indexServing.observedAt(), "the successful observation has a timestamp");

    long observedAtMs = Instant.parse(indexServing.observedAt()).toEpochMilli();
    assertTrue(
        observedAtMs >= beforeSuccess && observedAtMs <= afterFailure,
        "observedAt should be the last successful observation, was " + indexServing.observedAt());
    assertTrue(
        indexServing.stalenessMs() < 60_000L,
        "staleness should be measured from that observation, not from Head start, was "
            + indexServing.stalenessMs());
    assertTrue(
        afterFailure - observedAtMs >= indexServing.stalenessMs(),
        "staleness cannot exceed the elapsed gap");
  }

  @Test
  @DisplayName("composites aggregate member staleness: any-stale, max-age, head-local stays fresh")
  void compositesAggregateMemberStaleness() {
    StatusLifecycleHandler handler = newHandler(null, Instant.now());
    long now = System.currentTimeMillis();
    ReadinessEnvelopeView env =
        handler.buildReadinessEnvelope(
            healthyWorkerView(),
            readySnapshot(),
            StatusLifecycleHandler.WorkerContact.lost(now - 5_000L, now, now - 60_000L));

    for (var entry : env.composites().entrySet()) {
      String composite = entry.getKey();
      ReadinessCompositeView view = entry.getValue();

      long expectedMax = 0L;
      boolean expectedStale = false;
      for (ReadinessDimension dim : ReadinessDimension.values()) {
        if (!composite.equals(dim.composite())) {
          continue;
        }
        ReadinessComponentView member = env.components().get(dim.key());
        if (member.stale()) {
          expectedStale = true;
          expectedMax = Math.max(expectedMax, member.stalenessMs());
        }
      }

      assertEquals(expectedStale, view.stale(), composite + " composite stale");
      assertEquals(expectedMax, view.maxStalenessMs(), composite + " composite maxStalenessMs");
    }

    // The named cases the aggregate exists for, asserted directly rather than only against the
    // loop's own derivation: retrieval has worker-observed members, telemetry has none.
    ReadinessCompositeView retrieval = env.composites().get("retrieval");
    assertTrue(retrieval.stale(), "retrieval has worker-observed members and contact is lost");
    assertEquals(
        env.components().get("indexServing").stalenessMs(),
        retrieval.maxStalenessMs(),
        "retrieval carries its worker-observed members' age");
    assertTrue(retrieval.maxStalenessMs() > 0L, "a lost contact has a measurable age");

    ReadinessCompositeView telemetry = env.composites().get("telemetry");
    assertFalse(telemetry.stale(), "telemetry's only member is head-local — a Worker outage cannot"
        + " make it stale");
    assertEquals(0L, telemetry.maxStalenessMs(), "telemetry composite maxStalenessMs");
  }

  @Test
  @DisplayName("a reachable Worker leaves every composite fresh")
  void reachableWorkerLeavesEveryCompositeFresh(@TempDir Path indexBase) {
    StatusLifecycleHandler handler = reachableHandler(indexBase, Instant.now().minusSeconds(60));

    StatusResponse response = handler.buildStatusSnapshot();

    assertFalse(response.meta().workerRpcStale());
    Map<String, ReadinessCompositeView> composites = response.readiness().composites();
    assertTrue(composites.containsKey("retrieval"), "retrieval composite is emitted");
    for (var entry : composites.entrySet()) {
      assertFalse(entry.getValue().stale(), entry.getKey() + " should be fresh");
      assertEquals(0L, entry.getValue().maxStalenessMs(), entry.getKey() + " maxStalenessMs");
    }
  }

  // ---------------------------------------------------------------- helpers

  private static StatusLifecycleHandler reachableHandler(Path indexBase, Instant headStart) {
    KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView()).thenReturn(healthyWorkerView());
    when(ks.client()).thenReturn(client);
    return newReachableHandler(indexBase, headStart, ks);
  }

  private static StatusLifecycleHandler newReachableHandler(
      Path indexBase, Instant headStart, KnowledgeServerBootstrap ks) {
    StatusLifecycleHandler handler = newHandler(indexBase, headStart, true);
    handler.setKnowledgeServer(ks, null);
    return handler;
  }

  private static StatusLifecycleHandler newHandler(Path indexBase, Instant headStart) {
    return newHandler(indexBase, headStart, false);
  }

  private static StatusLifecycleHandler newHandler(
      Path indexBase, Instant headStart, boolean workerAvailable) {
    io.justsearch.app.services.lifecycle.WorkerCapability worker =
        mock(io.justsearch.app.services.lifecycle.WorkerCapability.class);
    when(worker.available()).thenReturn(workerAvailable);
    when(worker.health())
        .thenReturn(workerAvailable ? CapabilityHealth.READY : CapabilityHealth.OFFLINE);
    io.justsearch.app.services.lifecycle.InferenceCapability inference =
        mock(io.justsearch.app.services.lifecycle.InferenceCapability.class);
    when(inference.health()).thenReturn(CapabilityHealth.READY);

    return new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> null,
        null,
        null,
        indexBase,
        headStart,
        () -> "OK",
        null,
        null,
        null,
        worker,
        inference);
  }

  private static LifecycleSnapshotV1 readySnapshot() {
    LifecycleSnapshotV1.Component ready =
        new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY);
    return LifecycleSnapshotV1.now(
        new LifecycleSnapshotV1.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY),
        new LifecycleSnapshotV1.Components(ready, ready, ready));
  }

  private static WorkerOperationalView healthyWorkerView() {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationView.empty())
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
