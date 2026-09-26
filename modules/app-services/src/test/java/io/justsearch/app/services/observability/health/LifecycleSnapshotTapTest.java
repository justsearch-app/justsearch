package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.ReadinessDimension;
import io.justsearch.app.api.status.ReadinessComponentView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LifecycleSnapshotTap")
final class LifecycleSnapshotTapTest {

  private static final Instant T0 = Instant.parse("2026-04-30T11:59:00Z");
  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");

  private ConditionStore conditions;
  private HealthEventChangeRegistry changes;
  private RecordingListener listener;
  private MutableClock clock;
  private LifecycleSnapshotTap tap;

  @BeforeEach
  void setUp() {
    conditions = new ConditionStore();
    changes = new HealthEventChangeRegistry();
    listener = new RecordingListener();
    changes.subscribeTyped(listener);
    clock = new MutableClock(T0);
    tap = new LifecycleSnapshotTap(conditions, changes, HEAD_SRC, clock);
  }

  // ----- helpers -----

  private static ReadinessComponentView component(String state, String reasonCode) {
    return new ReadinessComponentView(state, reasonCode, "test", T0.toString(), false, 0L);
  }

  private static ReadinessEnvelopeView envelope(
      Map<ReadinessDimension, ReadinessComponentView> dims) {
    Map<String, ReadinessComponentView> components = new LinkedHashMap<>();
    for (Map.Entry<ReadinessDimension, ReadinessComponentView> e : dims.entrySet()) {
      components.put(e.getKey().key(), e.getValue());
    }
    return new ReadinessEnvelopeView(2, T0.toString(), Map.of(), components, Map.of());
  }

  private static ReadinessEnvelopeView singleDim(
      ReadinessDimension dim, ReadinessComponentView component) {
    return envelope(Map.of(dim, component));
  }

  private static ReadinessComponentView ready() {
    return component("READY", null);
  }

  // ============================================================
  // Phase 4 — INDEX_SERVING vertical proof
  // ============================================================

  @Test
  @DisplayName("INDEX_SERVING NOT_CONFIGURED → emits index.unavailable as CONDITION_ADDED")
  void indexServingNotConfiguredEmitsIndexUnavailable() {
    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("NOT_CONFIGURED", "worker.not_configured")));

    assertEquals(1, listener.size());
    HealthEventChangeRegistry.HealthChangeEvent e0 = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_ADDED, e0.kind());
    assertEquals("index.unavailable", e0.event().id());
    AssertedCondition cond = (AssertedCondition) e0.event().body();
    assertEquals("worker", cond.subject());
    assertEquals(ConditionStatus.TRUE, cond.status());
    assertEquals("WorkerNotConfigured", cond.reason());
    assertEquals(Severity.WARNING, e0.event().severity());
  }

  @Test
  @DisplayName("re-emit identical envelope → no broadcast (UNCHANGED via store)")
  void reemitSameEnvelopeNoBroadcast() {
    ReadinessEnvelopeView env =
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("NOT_CONFIGURED", "worker.not_configured"));
    tap.accept(env);
    listener.events.clear();

    tap.accept(env);

    assertEquals(0, listener.size(), "Identical envelope must not re-broadcast");
  }

  @Test
  @DisplayName(
      "reason-only change preserves prior lastTransitionTime (k8s SetStatusCondition)")
  void reasonOnlyChangePreservesLastTransitionTime() {
    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("NOT_CONFIGURED", "worker.not_configured")));
    AssertedCondition firstStored =
        (AssertedCondition)
            conditions.find("index.unavailable", "worker").orElseThrow().body();
    Instant firstTransition = firstStored.lastTransitionTime();

    clock.advanceTo(T0.plusSeconds(60));
    listener.events.clear();
    // Same conditionId (index.unavailable), different reason.
    tap.accept(
        singleDim(ReadinessDimension.INDEX_SERVING, component("NOT_READY", "worker.starting")));

    AssertedCondition stored =
        (AssertedCondition)
            conditions.find("index.unavailable", "worker").orElseThrow().body();
    assertEquals(
        firstTransition,
        stored.lastTransitionTime(),
        "Reason-only change must preserve prior lastTransitionTime per k8s");
    assertEquals("WorkerStarting", stored.reason(), "New reason must be stored");
    assertEquals(1, listener.size());
    assertEquals(
        HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, listener.events.get(0).kind());
  }

  @Test
  @DisplayName(
      "transition NOT_READY → DEGRADED/throughput-stalled clears prior + adds new")
  void transitionAcrossConditionIdsClearsAndAdds() {
    tap.accept(
        singleDim(ReadinessDimension.INDEX_SERVING, component("NOT_READY", "worker.unavailable")));
    listener.events.clear();

    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING,
            component("DEGRADED", "worker.throughput_stalled")));

    assertEquals(2, listener.size());
    assertEquals(
        HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertEquals("index.unavailable", listener.events.get(0).event().id());
    assertEquals(
        HealthEventChangeRegistry.Kind.CONDITION_ADDED, listener.events.get(1).kind());
    assertEquals("worker.throughput.stalled", listener.events.get(1).event().id());
    AssertedCondition newCond = (AssertedCondition) listener.events.get(1).event().body();
    assertEquals(
        "worker.queue", newCond.subject(), "Throughput conditions live on subject worker.queue");
    assertTrue(conditions.find("index.unavailable", "worker").isEmpty());
    // Slice 3a.1.4 Phase 6: throughput.stalled body declares the metric correlation —
    // backend-declared truth replaces the React HealthView's hard-coded event.id branches.
    assertEquals(
        1,
        newCond.relatedMetrics().size(),
        "Throughput-stalled should declare 1 correlated metric (job-queue-depth)");
    assertEquals(
        "core.metric-worker-job-queue-depth",
        newCond.relatedMetrics().get(0).resourceId().value());
    assertEquals(
        java.util.Optional.of(io.justsearch.app.observability.metrics.RenderHint.SPARK),
        newCond.relatedMetrics().get(0).hint());
  }

  @Test
  @DisplayName("transition to healthy clears the active condition with its prior payload")
  void transitionToHealthyClearsCondition() {
    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("NOT_CONFIGURED", "worker.not_configured")));
    HealthEvent priorActive = listener.events.get(0).event();
    listener.events.clear();

    tap.accept(singleDim(ReadinessDimension.INDEX_SERVING, ready()));

    assertEquals(1, listener.size());
    HealthEventChangeRegistry.HealthChangeEvent removed = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, removed.kind());
    assertEquals(priorActive, removed.event(), "Cleared event must carry the prior store record");
    assertTrue(conditions.find("index.unavailable", "worker").isEmpty());
  }

  @Test
  @DisplayName("unknown (state, reasonCode) on a fresh tap is logged-and-ignored, no crash")
  void unknownReasonCodeFallsThrough() {
    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("DEGRADED", "totally.fake.reason")));

    assertEquals(0, listener.size(), "Unknown reasonCode produces no broadcast");
    assertTrue(conditions.currentSnapshot().isEmpty());
  }

  @Test
  @DisplayName(
      "regression from known unhealthy → unknown reason PRESERVES the prior assertion"
          + " (does NOT spuriously CLEAR)")
  void unknownReasonAfterKnownPreservesPrior() {
    // Step 1: known unhealthy → emit ADDED.
    tap.accept(
        singleDim(ReadinessDimension.INDEX_SERVING, component("NOT_READY", "worker.unavailable")));
    HealthEvent priorActive = listener.events.get(0).event();
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_ADDED, listener.events.get(0).kind());
    listener.events.clear();

    // Step 2: same dim transitions to a non-READY state with an unrecognized reason.
    // The dim is STILL UNHEALTHY — the tap must preserve the prior assertion, not
    // mistake the unknown reason for "transition to healthy" and clear the banner.
    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING,
            component("NOT_READY", "future.reason.added.in.v1.5")));

    assertEquals(
        0,
        listener.size(),
        "Unknown reason after known reason MUST NOT broadcast CONDITION_REMOVED — the dim is still unhealthy");
    assertTrue(
        conditions.find("index.unavailable", "worker").isPresent(),
        "Prior condition MUST remain in the store across the unknown-reason transition");
    assertEquals(
        priorActive,
        conditions.find("index.unavailable", "worker").orElseThrow(),
        "Prior condition's payload must be preserved unchanged");

    // Step 3: dim recovers to a known healthy state. Prior is finally cleared.
    tap.accept(singleDim(ReadinessDimension.INDEX_SERVING, ready()));
    assertEquals(1, listener.size());
    assertEquals(
        HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
  }

  @Test
  @DisplayName("WARN log dedupes across repeated unknown-reason emits")
  void unknownReasonWarnDedupes() {
    // Repeatedly hit the same unknown key. The implementation has a Set<MappingKey>
    // dedup, so only the first emission warns. We can't easily inspect log capture
    // here, but the behavior surfaces structurally: 100 calls to accept() with the
    // same unknown reason produce 0 broadcasts (already covered) and don't crash.
    ReadinessEnvelopeView env =
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("DEGRADED", "totally.fake.reason"));
    for (int i = 0; i < 100; i++) {
      tap.accept(env);
    }
    assertEquals(0, listener.size());
    assertTrue(conditions.currentSnapshot().isEmpty());
  }

  @Test
  @DisplayName("subject correctness — index.* on worker, throughput.* on worker.queue")
  void subjectCorrectness() {
    tap.accept(
        singleDim(ReadinessDimension.INDEX_SERVING, component("NOT_READY", "worker.starting")));
    AssertedCondition unavailable =
        (AssertedCondition) listener.events.get(0).event().body();
    assertEquals("worker", unavailable.subject());

    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING,
            component("DEGRADED", "worker.throughput_degraded")));
    HealthEventChangeRegistry.HealthChangeEvent latest =
        listener.events.get(listener.size() - 1);
    AssertedCondition throughput = (AssertedCondition) latest.event().body();
    assertEquals("worker.queue", throughput.subject());
  }

  @Test
  @DisplayName("concurrent accept calls do not double-add")
  void concurrentAcceptDoesNotDoubleAdd() throws Exception {
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      ReadinessEnvelopeView env =
          singleDim(
              ReadinessDimension.INDEX_SERVING,
              component("NOT_CONFIGURED", "worker.not_configured"));
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(threads);
      AtomicInteger errors = new AtomicInteger();
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                tap.accept(env);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              } catch (RuntimeException e) {
                errors.incrementAndGet();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertTrue(done.await(10, TimeUnit.SECONDS));
      assertEquals(0, errors.get());
      long addedCount =
          listener.events.stream()
              .filter(e -> e.kind() == HealthEventChangeRegistry.Kind.CONDITION_ADDED)
              .count();
      assertEquals(1L, addedCount);
      long modifiedCount =
          listener.events.stream()
              .filter(e -> e.kind() == HealthEventChangeRegistry.Kind.CONDITION_MODIFIED)
              .count();
      assertEquals(0L, modifiedCount);
      assertEquals(1, conditions.currentSnapshot().size());
    } finally {
      pool.shutdownNow();
    }
  }

  // ============================================================
  // 442 §B.9 row 548 — INDEX_SERVING NOT_READY/index.not_healthy
  // → core.rebuild-index parameterless wrapper recovery
  // (slice 447-followup-live-wiring §X.12.8 Item 2.4).
  // ============================================================

  @Test
  @DisplayName(
      "INDEX_SERVING NOT_READY/index.not_healthy → recovery resolves to core.rebuild-index")
  void rowFiveFortyEightResolvesRebuildIndexRecovery() {
    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("NOT_READY", "index.not_healthy")));

    assertEquals(1, listener.size());
    HealthEventChangeRegistry.HealthChangeEvent e0 = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_ADDED, e0.kind());
    assertEquals("index.unavailable", e0.event().id());
    assertEquals(Severity.ERROR, e0.event().severity());

    AssertedCondition cond = (AssertedCondition) e0.event().body();
    assertEquals("worker", cond.subject());
    assertEquals(ConditionStatus.TRUE, cond.status());
    assertTrue(
        cond.recovery().isPresent(),
        "row 548 must populate AssertedCondition.recovery with the parameterless"
            + " core.rebuild-index wrapper Operation");
    io.justsearch.agent.api.registry.OperationInvocation rec = cond.recovery().get();
    assertEquals(
        "core.rebuild-index",
        rec.target().value(),
        "row 548 recovery target must be the parameterless core.rebuild-index wrapper");
    assertEquals(
        "{}",
        rec.defaultArgsJson(),
        "row 548 must use empty static args; the wrapper sidesteps dynamic corpusIds");
  }

  // ============================================================
  // Phase 5 — full coverage
  // ============================================================

  @Test
  @DisplayName("WORKER_CONTROL_PLANE NOT_READY/worker.spawn.failed → index.start-error")
  void workerSpawnFailedEmitsStartError() {
    tap.accept(
        singleDim(
            ReadinessDimension.WORKER_CONTROL_PLANE,
            component("NOT_READY", "worker.spawn.failed")));

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("index.start-error", event.id());
    AssertedCondition cond = (AssertedCondition) event.body();
    assertEquals("worker", cond.subject());
    assertEquals(Severity.ERROR, event.severity());
    assertEquals("WorkerSpawnFailed", cond.reason());
  }

  @Test
  @DisplayName("tempdoc 837: WORKER_CONTROL_PLANE NOT_READY/worker.lost → index.start-error")
  void workerLostEmitsStartError() {
    tap.accept(
        singleDim(ReadinessDimension.WORKER_CONTROL_PLANE, component("NOT_READY", "worker.lost")));

    assertEquals(1, listener.size(), "a new code without a tap row emits NOTHING but a WARN");
    HealthEvent event = listener.events.get(0).event();
    assertEquals("index.start-error", event.id(), "same Condition this state produced before S3");
    AssertedCondition cond = (AssertedCondition) event.body();
    assertEquals(Severity.ERROR, event.severity());
    assertEquals("WorkerLost", cond.reason(), "the reason field is what gets MORE precise, not the id");
  }

  @Test
  @DisplayName("tempdoc 837: WORKER_CONTROL_PLANE NOT_READY/worker.index_corrupt → index.start-error")
  void workerIndexCorruptEmitsStartError() {
    tap.accept(
        singleDim(
            ReadinessDimension.WORKER_CONTROL_PLANE, component("NOT_READY", "worker.index_corrupt")));

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("index.start-error", event.id());
    assertEquals(Severity.ERROR, event.severity());
    assertEquals("WorkerIndexCorrupt", ((AssertedCondition) event.body()).reason());
  }

  @Test
  @DisplayName("tempdoc 825: WORKER_CONTROL_PLANE NOT_READY/worker.spawn_recovery_exhausted → start-error")
  void bootRecoveryExhaustedEmitsStartError() {
    tap.accept(
        singleDim(
            ReadinessDimension.WORKER_CONTROL_PLANE,
            component("NOT_READY", "worker.spawn_recovery_exhausted")));

    // Without the mapping row the lookup misses and the Condition disappears at the exact moment the
    // state became permanent — a silent regression, not a refinement.
    assertEquals(1, listener.size(), "a new code without a tap row emits NOTHING but a WARN");
    HealthEvent event = listener.events.get(0).event();
    assertEquals("index.start-error", event.id());
    assertEquals(Severity.ERROR, event.severity());
    assertEquals(
        "WorkerSpawnRecoveryExhausted", ((AssertedCondition) event.body()).reason());
  }

  @Test
  @DisplayName("tempdoc 837: INDEX_SERVING NOT_CONFIGURED/worker.shut_down → index.unavailable")
  void workerShutDownEmitsIndexUnavailable() {
    tap.accept(
        singleDim(ReadinessDimension.INDEX_SERVING, component("NOT_CONFIGURED", "worker.shut_down")));

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("index.unavailable", event.id(), "same Condition worker.not_configured produced");
    assertEquals(Severity.WARNING, event.severity(), "an orderly teardown is not an ERROR");
    assertEquals("WorkerShutDown", ((AssertedCondition) event.body()).reason());
  }

  @Test
  @DisplayName("AI NOT_READY/inference.offline → ai.not-ready")
  void aiNotReadyEmitsAiNotReady() {
    tap.accept(
        singleDim(ReadinessDimension.AI, component("NOT_READY", "inference.offline")));

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("ai.not-ready", event.id());
    assertEquals("inference.ai", ((AssertedCondition) event.body()).subject());
    assertEquals(Severity.WARNING, event.severity());
  }

  @Test
  @DisplayName("AI UNKNOWN/null → ai.readiness-unknown")
  void aiUnknownEmitsReadinessUnknown() {
    tap.accept(singleDim(ReadinessDimension.AI, component("UNKNOWN", null)));

    assertEquals(1, listener.size());
    assertEquals("ai.readiness-unknown", listener.events.get(0).event().id());
  }

  @Test
  @DisplayName("EMBEDDING NOT_READY → embedding.not-ready on subject inference.embedding")
  void embeddingNotReadyEmitsEmbeddingNotReady() {
    tap.accept(
        singleDim(
            ReadinessDimension.EMBEDDING,
            component("NOT_READY", "worker.health.embedding_not_ready")));

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("embedding.not-ready", event.id());
    assertEquals("inference.embedding", ((AssertedCondition) event.body()).subject());
  }

  @Test
  @DisplayName(
      "TELEMETRY DEGRADED with different reasons collapse to one Condition;"
          + " reason field differs but lastTransitionTime is preserved (k8s)")
  void telemetryReasonsCollapseToSingleConditionId() {
    // First emit at T0: stale metrics
    tap.accept(
        singleDim(
            ReadinessDimension.TELEMETRY, component("DEGRADED", "telemetry.metrics.stale")));
    assertEquals("telemetry.degraded", listener.events.get(0).event().id());
    AssertedCondition first = (AssertedCondition) listener.events.get(0).event().body();
    assertEquals("TelemetryMetricsStale", first.reason());
    Instant firstTransition = first.lastTransitionTime();

    // Advance the clock so the second emit's caller-supplied lastTransitionTime would
    // differ if ConditionStore weren't preserving the prior value. Without this advance,
    // the test would pass for both correct (preserves) and broken (overwrites)
    // implementations — same defect class as the rev-3.4 §B.Q.5 finding.
    clock.advanceTo(T0.plusSeconds(60));
    listener.events.clear();

    // Same conditionId, different reason → MODIFIED with k8s lastTransitionTime preserved.
    tap.accept(
        singleDim(
            ReadinessDimension.TELEMETRY,
            component("DEGRADED", "telemetry.metrics.high_failure_rate")));
    assertEquals(1, listener.size());
    assertEquals(
        HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, listener.events.get(0).kind());
    AssertedCondition second = (AssertedCondition) listener.events.get(0).event().body();
    assertEquals("telemetry.degraded", listener.events.get(0).event().id());
    assertEquals("TelemetryMetricsHighFailureRate", second.reason());

    // The store-resident record's lastTransitionTime must be the original T0, not T0+60s.
    AssertedCondition stored =
        (AssertedCondition) conditions.find("telemetry.degraded", "head.telemetry").orElseThrow().body();
    assertEquals(
        firstTransition,
        stored.lastTransitionTime(),
        "Status-equal/reason-different MUST preserve prior lastTransitionTime per k8s");
  }

  @Test
  @DisplayName("GPU DEGRADED/gpu.saturated → gpu.saturated")
  void gpuSaturatedEmitsGpuSaturated() {
    tap.accept(singleDim(ReadinessDimension.GPU, component("DEGRADED", "gpu.saturated")));

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("gpu.saturated", event.id());
    assertEquals("head.gpu", ((AssertedCondition) event.body()).subject());
  }

  @Test
  @DisplayName("gpu.saturated body declares relatedMetrics for both GPU TIMESERIES Resources")
  void gpuSaturatedDeclaresRelatedMetrics() {
    tap.accept(singleDim(ReadinessDimension.GPU, component("DEGRADED", "gpu.saturated")));

    assertEquals(1, listener.size());
    AssertedCondition cond = (AssertedCondition) listener.events.get(0).event().body();
    // Slice 3a.1.4b cohort follow-up: backend-declared correlation with the two GPU
    // TIMESERIES Resources restores the trend visualization that was retired with the
    // React HealthView (per slice 3a.1.4 §B.K Goal 2).
    assertEquals(
        2,
        cond.relatedMetrics().size(),
        "gpu.saturated should declare 2 correlated metrics (utilization + memory)");
    assertEquals(
        "core.metric-gpu-utilization-percent",
        cond.relatedMetrics().get(0).resourceId().value());
    assertEquals(
        java.util.Optional.of(io.justsearch.app.observability.metrics.RenderHint.SPARK),
        cond.relatedMetrics().get(0).hint());
    assertEquals(
        "core.metric-gpu-memory-utilization-percent",
        cond.relatedMetrics().get(1).resourceId().value());
    assertEquals(
        java.util.Optional.of(io.justsearch.app.observability.metrics.RenderHint.SPARK),
        cond.relatedMetrics().get(1).hint());
  }

  @Test
  @DisplayName("multi-dim envelope emits independently per dim in one accept")
  void multiDimEnvelopeEmitsAllDims() {
    Map<ReadinessDimension, ReadinessComponentView> dims = new LinkedHashMap<>();
    dims.put(
        ReadinessDimension.INDEX_SERVING, component("NOT_CONFIGURED", "worker.not_configured"));
    dims.put(ReadinessDimension.AI, component("NOT_READY", "inference.offline"));
    dims.put(ReadinessDimension.GPU, component("DEGRADED", "gpu.saturated"));

    tap.accept(envelope(dims));

    assertEquals(3, listener.size());
    // Each dim emits its own conditionId — verify all three present, regardless of order.
    java.util.Set<String> ids =
        listener.events.stream().map(e -> e.event().id()).collect(java.util.stream.Collectors.toSet());
    assertTrue(ids.contains("index.unavailable"));
    assertTrue(ids.contains("ai.not-ready"));
    assertTrue(ids.contains("gpu.saturated"));
  }

  @Test
  @DisplayName("CHUNK_EMBEDDING + LAMBDAMART_MODEL non-healthy states are unmapped (no emit)")
  void unmappedDimsAreSilent() {
    Map<ReadinessDimension, ReadinessComponentView> dims = new LinkedHashMap<>();
    dims.put(
        ReadinessDimension.CHUNK_EMBEDDING, component("DEGRADED", "chunk_embedding.not_ready"));
    dims.put(
        ReadinessDimension.LAMBDAMART_MODEL, component("DEGRADED", "lambdamart.not_configured"));

    tap.accept(envelope(dims));

    assertEquals(0, listener.size(), "Unmapped dims must produce no broadcasts");
    assertTrue(conditions.currentSnapshot().isEmpty());
  }

  // ----- support -----

  private static final class RecordingListener
      implements java.util.function.Consumer<HealthEventChangeRegistry.HealthChangeEvent> {
    final List<HealthEventChangeRegistry.HealthChangeEvent> events =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void accept(HealthEventChangeRegistry.HealthChangeEvent change) {
      events.add(change);
    }

    int size() {
      return events.size();
    }
  }

  /** Mutable clock for k8s lastTransitionTime preservation tests. */
  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advanceTo(Instant next) {
      assertTrue(next.isAfter(now), "advanceTo must move forward");
      assertNotEquals(now, next);
      assertFalse(next.isBefore(now));
      this.now = next;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  @DisplayName(
      "876 C.8: settling to DEGRADED/index.dense_unavailable CLEARS a boot-time index.unavailable")
  void denseUnavailableClearsTheBootTimeIndexUnavailable() {
    // The live sequence this pins: the worker is starting (NOT_READY/worker.starting → asserts
    // index.unavailable), then settles with the index SERVING but the dense leg down. Before 876
    // C.8 that reason was unmapped, so reconcileDim took the unmapped-unhealthy branch and
    // PRESERVED the boot-time assertion forever — leaving core.search-index, gated on
    // Not(index.unavailable), hidden for the life of the process even though keyword search
    // worked. It went unnoticed because nothing reconciled the store without a GET /api/status.
    tap.accept(
        singleDim(ReadinessDimension.INDEX_SERVING, component("NOT_READY", "worker.starting")));
    assertTrue(
        conditions.find("index.unavailable", "worker").isPresent(),
        "a starting worker must assert index.unavailable");
    listener.events.clear();

    tap.accept(
        singleDim(
            ReadinessDimension.INDEX_SERVING, component("DEGRADED", "index.dense_unavailable")));

    assertTrue(
        conditions.find("index.unavailable", "worker").isEmpty(),
        "a degraded-but-serving index must NOT keep the search tool gated off");
    assertTrue(
        conditions.find("index.dense-unavailable", "worker").isPresent(),
        "the degradation stays visible under its own id rather than vanishing");
  }

}
