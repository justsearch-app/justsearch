package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.EnrichmentProgressViewBuilder;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.StageCompletenessView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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

@DisplayName("WorkerSnapshotTap")
final class WorkerSnapshotTapTest {

  private static final Instant T0 = Instant.parse("2026-04-30T11:59:00Z");
  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");

  private ConditionStore conditions;
  private OccurrenceLog occurrences;
  private HealthEventChangeRegistry changes;
  private RecordingListener listener;
  private MutableClock clock;
  private WorkerSnapshotTap tap;

  @BeforeEach
  void setUp() {
    conditions = new ConditionStore();
    occurrences = new OccurrenceLog();
    changes = new HealthEventChangeRegistry();
    listener = new RecordingListener();
    changes.subscribeTyped(listener);
    clock = new MutableClock(T0);
    tap = new WorkerSnapshotTap(conditions, occurrences, changes, HEAD_SRC, clock);
  }

  // ----- helpers -----

  private static WorkerOperationalView view(
      CompatibilityStatusView compat, QueueDbStatusView queueDb, FailureTrackingView failure) {
    return view(compat, queueDb, failure, EnrichmentProgressView.empty());
  }

  private static WorkerOperationalView view(
      CompatibilityStatusView compat,
      QueueDbStatusView queueDb,
      FailureTrackingView failure,
      EnrichmentProgressView enrichment) {
    return WorkerOperationalViewBuilder.builder()
        .core(io.justsearch.app.api.status.CoreIndexView.fallback("READY"))
        .failure(failure)
        .migration(io.justsearch.app.api.status.MigrationGenerationView.empty())
        .compatibility(compat)
        .queueDb(queueDb)
        .enrichment(enrichment)
        .gpu(io.justsearch.app.api.status.GpuDiagnosticsView.empty())
        .vectorFormat(io.justsearch.app.api.status.VectorFormatView.empty())
        .telemetry(io.justsearch.app.api.status.TelemetryMetricsView.empty())
        .searchConfig(io.justsearch.app.api.status.SearchConfigView.empty())
        .build();
  }

  private static CompatibilityStatusView compat(
      String schemaState, String embeddingState, boolean reindexRequired) {
    return compat(schemaState, embeddingState, reindexRequired, "");
  }

  private static CompatibilityStatusView compat(
      String schemaState, String embeddingState, boolean reindexRequired, String reason) {
    return new CompatibilityStatusView(
        embeddingState,
        "",
        "",
        "",
        "",
        "",
        schemaState,
        reindexRequired,
        reason);
  }

  private static QueueDbStatusView queueDb(boolean healthy, boolean lastQuickCheckOk) {
    return new QueueDbStatusView(healthy, 0, 0, lastQuickCheckOk, 0);
  }

  private static FailureTrackingView failure(
      String path, String errorMessage, long lastFailedAtMs, long nextRetryAtMs) {
    return new FailureTrackingView(
        lastFailedAtMs > 0 ? 1 : 0,
        path,
        errorMessage,
        lastFailedAtMs,
        nextRetryAtMs,
        0,
        Map.of());
  }

  private static WorkerOperationalView healthyView() {
    return view(
        compat("COMPATIBLE", "COMPATIBLE", false),
        queueDb(true, true),
        failure("", "", 0, 0));
  }

  private static WorkerOperationalView schemaBlocked(String state) {
    return view(
        compat(state, "COMPATIBLE", false),
        queueDb(true, true),
        failure("", "", 0, 0));
  }

  private static WorkerOperationalView embeddingBlocked(String state) {
    return view(
        compat("COMPATIBLE", state, false),
        queueDb(true, true),
        failure("", "", 0, 0));
  }

  // ----- enrichment completeness helpers (tempdoc 821 §3-C3 follow-on) -----

  private static StageCompletenessView stage(
      String stageId, long expected, long present, long failed) {
    return new StageCompletenessView(
        stageId, "ARTIFACT", expected, present, expected - present - failed, failed);
  }

  /** A view whose enrichment carries the given audit rows and per-stage PENDING counts. */
  private static WorkerOperationalView enrichmentView(
      List<StageCompletenessView> completeness,
      long embedPending,
      long spladePending,
      long nerPending) {
    EnrichmentProgressView enrichment =
        EnrichmentProgressViewBuilder.builder(EnrichmentProgressView.empty())
            .completeness(completeness)
            .embeddingPendingCount(embedPending)
            .spladePendingCount(spladePending)
            .pendingNerCount(nerPending)
            .build();
    return view(
        compat("COMPATIBLE", "COMPATIBLE", false),
        queueDb(true, true),
        failure("", "", 0, 0),
        enrichment);
  }

  /** embed stage only: {@code expected} docs, {@code present} done, {@code pending} queued. */
  private static WorkerOperationalView embedCompleteness(
      long expected, long present, long pending) {
    return enrichmentView(List.of(stage("embed", expected, present, 0)), pending, 0, 0);
  }

  // ============================================================
  // Per-field mapping (Conditions)
  // ============================================================

  @Test
  @DisplayName("schema BLOCKED_LEGACY → schema.blocked ADDED on worker.schema (ERROR)")
  void schemaBlockedLegacy() {
    tap.accept(schemaBlocked("BLOCKED_LEGACY"), false);

    assertEquals(1, listener.size());
    HealthEventChangeRegistry.HealthChangeEvent e0 = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_ADDED, e0.kind());
    assertEquals("schema.blocked", e0.event().id());
    AssertedCondition cond = (AssertedCondition) e0.event().body();
    assertEquals("worker.schema", cond.subject());
    assertEquals(ConditionStatus.TRUE, cond.status());
    assertEquals("BlockedLegacy", cond.reason());
    assertEquals(Severity.ERROR, e0.event().severity());
  }

  @Test
  @DisplayName("schema BLOCKED_MISMATCH → schema.blocked ADDED with reason BlockedMismatch")
  void schemaBlockedMismatch() {
    tap.accept(schemaBlocked("BLOCKED_MISMATCH"), false);

    assertEquals(1, listener.size());
    AssertedCondition cond = (AssertedCondition) listener.events.get(0).event().body();
    assertEquals("schema.blocked", listener.events.get(0).event().id());
    assertEquals("BlockedMismatch", cond.reason());
  }

  @Test
  @DisplayName("reindexRequired=true → schema.reindex-required ADDED on worker.schema (WARNING)")
  void reindexRequired() {
    tap.accept(
        view(compat("COMPATIBLE", "COMPATIBLE", true), queueDb(true, true), failure("", "", 0, 0)),
        false);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("schema.reindex-required", event.id());
    AssertedCondition cond = (AssertedCondition) event.body();
    assertEquals("worker.schema", cond.subject());
    assertEquals(Severity.WARNING, event.severity());
  }

  @Test
  @DisplayName("embedding BLOCKED_LEGACY → embedding.blocked ADDED on worker.embedding (ERROR)")
  void embeddingBlockedLegacy() {
    tap.accept(embeddingBlocked("BLOCKED_LEGACY"), false);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("embedding.blocked", event.id());
    AssertedCondition cond = (AssertedCondition) event.body();
    assertEquals("worker.embedding", cond.subject());
    assertEquals(Severity.ERROR, event.severity());
  }

  @Test
  @DisplayName("embedding BLOCKED_MISMATCH → embedding.blocked ADDED")
  void embeddingBlockedMismatch() {
    tap.accept(embeddingBlocked("BLOCKED_MISMATCH"), false);
    assertEquals(1, listener.size());
    assertEquals("embedding.blocked", listener.events.get(0).event().id());
  }

  @Test
  @DisplayName(
      "embedding REBUILDING → embedding.not-ready ADDED on worker.embedding (WARNING), not unmapped"
          + " (726 F5/T4)")
  void embeddingRebuildingMapsToNotReady() {
    tap.accept(embeddingBlocked("REBUILDING"), false);

    // A mapped state emits a CONDITION event; the unmapped branch would emit nothing (see
    // unknownSchemaStateAfterKnownPreservesPrior) — so a fired event proves no unmapped-WARN.
    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("embedding.not-ready", event.id());
    AssertedCondition cond = (AssertedCondition) event.body();
    assertEquals("worker.embedding", cond.subject());
    assertEquals(Severity.WARNING, event.severity());
  }

  @Test
  @DisplayName("embedding UNAVAILABLE → embedding.not-ready ADDED (WARNING) (726 F5/T4)")
  void embeddingUnavailableMapsToNotReady() {
    tap.accept(embeddingBlocked("UNAVAILABLE"), false);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("embedding.not-ready", event.id());
    assertEquals(Severity.WARNING, event.severity());
  }

  @Test
  @DisplayName(
      "embedding INITIALIZING → embedding.not-ready ADDED on worker.embedding (WARNING), not"
          + " unmapped (734)")
  void embeddingInitializingMapsToNotReady() {
    // INITIALIZING is IndexStatusOps.safeEmbeddingCompatState's boot-window sentinel (controller
    // not yet wired). A mapped state emits a CONDITION event; the unmapped branch would emit
    // nothing — so a fired event proves this doesn't hit the unmapped-WARN/preserve branch.
    tap.accept(embeddingBlocked("INITIALIZING"), false);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("embedding.not-ready", event.id());
    AssertedCondition cond = (AssertedCondition) event.body();
    assertEquals("worker.embedding", cond.subject());
    assertEquals(Severity.WARNING, event.severity());
  }

  @Test
  @DisplayName(
      "embedding BLOCKED_LEGACY → REBUILDING swaps embedding.blocked to embedding.not-ready"
          + " (no freeze; 726 F5)")
  void embeddingRebuildingSwapsPriorBlockedInsteadOfFreezing() {
    // The bug: REBUILDING hit the unmapped-WARN branch and FROZE the prior embedding.blocked
    // assertion on the Head side (early return preserving prior). Now it must clear blocked and
    // assert embedding.not-ready.
    tap.accept(embeddingBlocked("BLOCKED_LEGACY"), false);
    assertTrue(conditions.find("embedding.blocked", "worker.embedding").isPresent());
    listener.events.clear();

    tap.accept(embeddingBlocked("REBUILDING"), false);

    assertTrue(
        conditions.find("embedding.blocked", "worker.embedding").isEmpty(),
        "prior embedding.blocked must be CLEARED on the move to REBUILDING, not frozen");
    assertTrue(
        conditions.find("embedding.not-ready", "worker.embedding").isPresent(),
        "REBUILDING must assert embedding.not-ready");
  }

  @Test
  @DisplayName("embedding REBUILDING → COMPATIBLE clears embedding.not-ready (726 F5)")
  void embeddingNotReadyClearsWhenCompatible() {
    tap.accept(embeddingBlocked("REBUILDING"), false);
    assertTrue(conditions.find("embedding.not-ready", "worker.embedding").isPresent());
    listener.events.clear();

    tap.accept(healthyView(), false);

    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertEquals("embedding.not-ready", listener.events.get(0).event().id());
    assertTrue(conditions.find("embedding.not-ready", "worker.embedding").isEmpty());
  }

  @Test
  @DisplayName("queueDbHealthy=false → queue-db.unhealthy ADDED on worker.queue-db (ERROR)")
  void queueDbUnhealthy() {
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(false, true),
            failure("", "", 0, 0)),
        false);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("queue-db.unhealthy", event.id());
    AssertedCondition cond = (AssertedCondition) event.body();
    assertEquals("worker.queue-db", cond.subject());
    assertEquals(Severity.ERROR, event.severity());
  }

  @Test
  @DisplayName("queueDbLastQuickCheckOk=false → queue-db.check-failed ADDED (WARNING)")
  void queueDbCheckFailed() {
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(true, false),
            failure("", "", 0, 0)),
        false);

    assertEquals(1, listener.size());
    HealthEvent event = listener.events.get(0).event();
    assertEquals("queue-db.check-failed", event.id());
    assertEquals(Severity.WARNING, event.severity());
  }

  // ============================================================
  // Healthy transitions (clears)
  // ============================================================

  @Test
  @DisplayName("schema COMPATIBLE after BLOCKED_LEGACY → CONDITION_REMOVED with prior payload")
  void schemaHealthyClearsPrior() {
    tap.accept(schemaBlocked("BLOCKED_LEGACY"), false);
    HealthEvent priorActive = listener.events.get(0).event();
    listener.events.clear();

    tap.accept(healthyView(), false);

    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertEquals(priorActive, listener.events.get(0).event());
    assertTrue(conditions.find("schema.blocked", "worker.schema").isEmpty());
  }

  @Test
  @DisplayName("reindexRequired=false after =true → schema.reindex-required REMOVED")
  void reindexClearsPrior() {
    tap.accept(
        view(compat("COMPATIBLE", "COMPATIBLE", true), queueDb(true, true), failure("", "", 0, 0)),
        false);
    listener.events.clear();

    tap.accept(healthyView(), false);

    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertEquals("schema.reindex-required", listener.events.get(0).event().id());
  }

  @Test
  @DisplayName("queueDbHealthy=true after =false → queue-db.unhealthy REMOVED")
  void queueDbHealthyClearsPrior() {
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(false, true),
            failure("", "", 0, 0)),
        false);
    listener.events.clear();

    tap.accept(healthyView(), false);

    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertEquals("queue-db.unhealthy", listener.events.get(0).event().id());
  }

  // ============================================================
  // rev-3.6 §B.S regression patterns (mandatory per §B.T.6)
  // ============================================================

  @Test
  @DisplayName(
      "reason-only schema change preserves prior lastTransitionTime (k8s SetStatusCondition)")
  void schemaReasonOnlyChangePreservesLastTransitionTime() {
    tap.accept(schemaBlocked("BLOCKED_LEGACY"), false);
    AssertedCondition firstStored =
        (AssertedCondition) conditions.find("schema.blocked", "worker.schema").orElseThrow().body();
    Instant firstTransition = firstStored.lastTransitionTime();

    clock.advanceTo(T0.plusSeconds(60));
    listener.events.clear();
    tap.accept(schemaBlocked("BLOCKED_MISMATCH"), false);

    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, listener.events.get(0).kind());
    AssertedCondition stored =
        (AssertedCondition) conditions.find("schema.blocked", "worker.schema").orElseThrow().body();
    assertEquals(
        firstTransition,
        stored.lastTransitionTime(),
        "Status-equal/reason-different MUST preserve prior lastTransitionTime per k8s");
    assertEquals("BlockedMismatch", stored.reason());
  }

  @Test
  @DisplayName(
      "regression from known schema state → unknown state PRESERVES prior assertion")
  void unknownSchemaStateAfterKnownPreservesPrior() {
    tap.accept(schemaBlocked("BLOCKED_MISMATCH"), false);
    HealthEvent priorActive = listener.events.get(0).event();
    listener.events.clear();

    // Future / unrecognized state value — tap must NOT clear the prior assertion.
    tap.accept(schemaBlocked("FUTURE_BLOCKED_VALUE"), false);

    assertEquals(0, listener.size(), "Unmapped state must NOT trigger CONDITION_REMOVED");
    assertTrue(conditions.find("schema.blocked", "worker.schema").isPresent());
    assertEquals(
        priorActive,
        conditions.find("schema.blocked", "worker.schema").orElseThrow(),
        "Prior payload must be preserved unchanged");
  }

  // ============================================================
  // Re-emit semantics
  // ============================================================

  @Test
  @DisplayName("re-emit identical view → no broadcast (UNCHANGED via store)")
  void identicalViewNoBroadcast() {
    WorkerOperationalView v = schemaBlocked("BLOCKED_LEGACY");
    tap.accept(v, false);
    listener.events.clear();

    tap.accept(v, false);

    assertEquals(0, listener.size());
  }

  // ============================================================
  // Enrichment completeness (tempdoc 821 §3-C3 follow-on)
  // ============================================================

  @Test
  @DisplayName("quiescent missing>0 → enrichment.incomplete ADDED on worker.enrichment/embed")
  void quiescentMissingAssertsCondition() {
    tap.accept(embedCompleteness(340, 328, 0), false);

    assertEquals(1, listener.size());
    HealthEventChangeRegistry.HealthChangeEvent e0 = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_ADDED, e0.kind());
    assertEquals("enrichment.incomplete", e0.event().id());
    assertEquals(Severity.WARNING, e0.event().severity());
    AssertedCondition cond = (AssertedCondition) e0.event().body();
    assertEquals("worker.enrichment/embed", cond.subject());
    assertEquals(ConditionStatus.TRUE, cond.status());
    assertEquals("MissingAtQuiescence", cond.reason());
    assertTrue(
        cond.message().orElse("").contains("12 of 340"),
        "message must carry {missing, expected}; was: " + cond.message());
  }

  @Test
  @DisplayName("mid-ingest (pending>0) with the SAME missing count → no condition")
  void pendingWorkDoesNotAssertCondition() {
    // 12 documents are "missing" purely because they are queued: missing counts PENDING docs
    // (missing = expected - present - failed), so this is the every-ingest false positive the
    // quiescence gate exists to suppress.
    tap.accept(embedCompleteness(340, 328, 12), false);

    assertEquals(0, listener.size(), "in-flight work must not read as loss");
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/embed").isEmpty());
  }

  @Test
  @DisplayName("missing → 0 after firing → enrichment.incomplete REMOVED")
  void repairedStageClearsCondition() {
    tap.accept(embedCompleteness(340, 328, 0), false);
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/embed").isPresent());
    listener.events.clear();

    tap.accept(embedCompleteness(340, 340, 0), false);

    assertEquals(1, listener.size());
    assertEquals(
        HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/embed").isEmpty());
  }

  @Test
  @DisplayName("new ingest starts after firing (pending>0) → condition clears, no longer quiescent")
  void resumedIngestClearsCondition() {
    tap.accept(embedCompleteness(340, 328, 0), false);
    listener.events.clear();

    tap.accept(embedCompleteness(400, 328, 60), false);

    assertEquals(
        HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/embed").isEmpty());
  }

  @Test
  @DisplayName("per-stage instances are independent: quiescent embed fires, pending splade doesn't")
  void perStageInstancesAreIndependent() {
    tap.accept(
        enrichmentView(
            List.of(stage("embed", 100, 90, 0), stage("splade", 100, 40, 0)), 0, 60, 0),
        false);

    assertEquals(1, listener.size());
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/embed").isPresent());
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/splade").isEmpty());
  }

  @Test
  @DisplayName("empty completeness list → prior assertion preserved (unknown ≠ healthy)")
  void absentAuditRowsPreservePriorAssertion() {
    tap.accept(embedCompleteness(340, 328, 0), false);
    listener.events.clear();

    // A pre-821 worker (or a failed count query) reports no audit rows at all. Absence of data
    // is not evidence of completeness.
    tap.accept(enrichmentView(List.of(), 0, 0, 0), false);

    assertEquals(0, listener.size());
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/embed").isPresent());
  }

  @Test
  @DisplayName("unknown stageId → no condition asserted (quiescence unknowable)")
  void unknownStageIdDoesNotAssert() {
    tap.accept(enrichmentView(List.of(stage("future_stage", 100, 10, 0)), 0, 0, 0), false);

    assertEquals(0, listener.size());
    assertTrue(
        conditions.find("enrichment.incomplete", "worker.enrichment/future_stage").isEmpty());
  }

  @Test
  @DisplayName("stale view does NOT clear a prior enrichment.incomplete")
  void staleViewPreservesEnrichmentCondition() {
    tap.accept(embedCompleteness(340, 328, 0), false);
    listener.events.clear();

    tap.accept(embedCompleteness(340, 340, 0), true);

    assertEquals(0, listener.size());
    assertTrue(conditions.find("enrichment.incomplete", "worker.enrichment/embed").isPresent());
  }

  // ============================================================
  // Stale / null view (§B.T.5 — unknown ≠ healthy)
  // ============================================================

  @Test
  @DisplayName("accept(view, stale=true) → 0 broadcasts; prior preserved")
  void staleViewSkipsEmission() {
    tap.accept(schemaBlocked("BLOCKED_LEGACY"), false);
    listener.events.clear();

    // Stale view that LOOKS healthy (compat=COMPATIBLE, queueDbHealthy=true) — without
    // the stale short-circuit, the tap would CLEAR the prior schema.blocked condition.
    tap.accept(healthyView(), true);

    assertEquals(0, listener.size(), "Stale view must NOT emit");
    assertTrue(
        conditions.find("schema.blocked", "worker.schema").isPresent(),
        "Stale view must NOT clear prior assertion (unknown ≠ healthy)");
  }

  @Test
  @DisplayName("accept(null, stale=false) → 0 broadcasts; prior preserved")
  void nullViewSkipsEmission() {
    tap.accept(schemaBlocked("BLOCKED_LEGACY"), false);
    listener.events.clear();

    tap.accept(null, false);

    assertEquals(0, listener.size());
    assertTrue(conditions.find("schema.blocked", "worker.schema").isPresent());
  }

  @Test
  @DisplayName(
      "fallback view (queueDbHealthy=true) under stale=true does NOT clear queue-db.unhealthy")
  void fallbackViewUnderStaleDoesNotClearQueueDbUnhealthy() {
    // Stage: real queue-db.unhealthy was asserted earlier.
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(false, true),
            failure("", "", 0, 0)),
        false);
    assertEquals(1, listener.size());
    listener.events.clear();

    // Worker becomes unreachable → handler emits a fallback view with queueDbHealthy=true
    // but workerRpcStale=true. Without the §B.T.5 stale guard, the tap would falsely
    // clear the queue-db.unhealthy condition, masking the real worker problem.
    tap.accept(WorkerOperationalView.fallback("UNAVAILABLE"), true);

    assertEquals(0, listener.size());
    assertTrue(conditions.find("queue-db.unhealthy", "worker.queue-db").isPresent());
  }

  // ============================================================
  // Occurrence determinism
  // ============================================================

  @Test
  @DisplayName("lastFailedAtMs=0 → no occurrence emitted")
  void zeroLastFailedNoOccurrence() {
    tap.accept(healthyView(), false);
    assertEquals(0, listener.size());
    assertTrue(occurrences.recent().isEmpty());
  }

  @Test
  @DisplayName(
      "lastFailedAtMs first non-zero → worker.job.failed Occurrence with body attributes")
  void firstFailureEmitsOccurrence() {
    long ts = T0.plusSeconds(30).toEpochMilli();
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(true, true),
            failure("/docs/file.pdf", "parse error", ts, 0)),
        false);

    assertEquals(1, listener.size());
    HealthEventChangeRegistry.HealthChangeEvent change = listener.events.get(0);
    assertEquals(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, change.kind());
    assertEquals("worker.job.failed", change.event().id());
    assertEquals(Severity.INFO, change.event().severity());
    LifecycleEvent body = (LifecycleEvent) change.event().body();
    assertEquals("/docs/file.pdf", body.attributes().get("path"));
    assertEquals("parse error", body.attributes().get("errorMessage"));
    assertEquals(ts, ((Number) body.attributes().get("atMs")).longValue());
    assertEquals(1, occurrences.recent().size());
  }

  @Test
  @DisplayName("re-emit same lastFailedAtMs → no re-broadcast (deterministic)")
  void sameLastFailedNoReemit() {
    long ts = T0.plusSeconds(30).toEpochMilli();
    WorkerOperationalView v =
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(true, true),
            failure("/docs/a.pdf", "err", ts, 0));
    tap.accept(v, false);
    listener.events.clear();

    tap.accept(v, false);

    assertEquals(0, listener.size());
  }

  @Test
  @DisplayName("new lastFailedAtMs (different path) → emits new occurrence")
  void newFailureEmitsOccurrence() {
    long t1 = T0.plusSeconds(30).toEpochMilli();
    long t2 = T0.plusSeconds(60).toEpochMilli();
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(true, true),
            failure("/docs/a.pdf", "err", t1, 0)),
        false);
    listener.events.clear();

    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(true, true),
            failure("/docs/b.pdf", "err2", t2, 0)),
        false);

    assertEquals(1, listener.size());
    LifecycleEvent body = (LifecycleEvent) listener.events.get(0).event().body();
    assertEquals("/docs/b.pdf", body.attributes().get("path"));
  }

  @Test
  @DisplayName("nextRetryAtMs tracking is independent of lastFailedAtMs")
  void retryTrackingIndependent() {
    long failedAt = T0.plusSeconds(30).toEpochMilli();
    long retryAt1 = T0.plusSeconds(60).toEpochMilli();
    long retryAt2 = T0.plusSeconds(120).toEpochMilli();

    // First emit: both lastFailed AND nextRetry are new → 2 broadcasts.
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(true, true),
            failure("/docs/a.pdf", "err", failedAt, retryAt1)),
        false);
    assertEquals(2, listener.size());
    listener.events.clear();

    // Second emit: lastFailed unchanged, nextRetry advanced → 1 broadcast (retry only).
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", false),
            queueDb(true, true),
            failure("/docs/a.pdf", "err", failedAt, retryAt2)),
        false);
    assertEquals(1, listener.size());
    assertEquals("worker.job.retry-scheduled", listener.events.get(0).event().id());
  }

  // ============================================================
  // Concurrency
  // ============================================================

  @Test
  @DisplayName("concurrent accept calls do not double-add")
  void concurrentAcceptDoesNotDoubleAdd() throws Exception {
    int threads = 32;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      WorkerOperationalView v = schemaBlocked("BLOCKED_MISMATCH");
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(threads);
      AtomicInteger errors = new AtomicInteger();
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                tap.accept(v, false);
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
      assertEquals(1, conditions.currentSnapshot().size());
    } finally {
      pool.shutdownNow();
    }
  }

  // ============================================================
  // WARN dedup
  // ============================================================

  @Test
  @DisplayName("repeated unmapped schema state → 0 broadcasts, no crash")
  void unmappedStateDedup() {
    WorkerOperationalView v = schemaBlocked("UNRECOGNIZED_STATE");
    for (int i = 0; i < 100; i++) {
      tap.accept(v, false);
    }
    assertEquals(0, listener.size());
    assertTrue(conditions.currentSnapshot().isEmpty());
  }

  // ============================================================
  // Multi-field
  // ============================================================

  @Test
  @DisplayName("multi-field view emits all transitions independently in one accept")
  void multiFieldEmissions() {
    long failedAt = T0.plusSeconds(30).toEpochMilli();
    tap.accept(
        view(
            compat("BLOCKED_MISMATCH", "BLOCKED_MISMATCH", true),
            queueDb(false, false),
            failure("/docs/a.pdf", "err", failedAt, 0)),
        false);

    // Expect 6 events: schema.blocked + schema.reindex-required + embedding.blocked +
    // queue-db.unhealthy + queue-db.check-failed (5 ADDED) + worker.job.failed (1 OCCURRENCE_APPENDED).
    assertEquals(6, listener.size());
    java.util.Set<String> ids =
        listener.events.stream()
            .map(e -> e.event().id())
            .collect(java.util.stream.Collectors.toSet());
    assertTrue(ids.contains("schema.blocked"));
    assertTrue(ids.contains("schema.reindex-required"));
    assertTrue(ids.contains("embedding.blocked"));
    assertTrue(ids.contains("queue-db.unhealthy"));
    assertTrue(ids.contains("queue-db.check-failed"));
    assertTrue(ids.contains("worker.job.failed"));
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
  @DisplayName("reindexRequiredReason routes every reason to its exact recovery operation")
  void reindexRecoveryMappingCoversAllReasons() {
    Map<String, String> expectedTargets =
        Map.of(
            "embedding_mismatch", "core.rebuild-index",
            "embedding_legacy", "core.rebuild-index",
            "rebuild_brake_exhausted", "core.rebuild-index",
            "schema_mismatch", "core.reindex",
            "legacy_index", "core.reindex",
            "unknown_reason", "core.reindex",
            "", "core.reindex");
    Map<String, String> expectedDefaults =
        Map.of(
            "embedding_mismatch", "{}",
            "embedding_legacy", "{}",
            "rebuild_brake_exhausted", "{}",
            "schema_mismatch", "{\"force\":true}",
            "legacy_index", "{\"force\":true}",
            "unknown_reason", "{\"force\":true}",
            "", "{\"force\":true}");

    for (String reason : expectedTargets.keySet()) {
      ConditionStore localConditions = new ConditionStore();
      WorkerSnapshotTap localTap =
          new WorkerSnapshotTap(
              localConditions,
              new OccurrenceLog(),
              new HealthEventChangeRegistry(),
              HEAD_SRC,
              clock);
      localTap.accept(
          view(
              compat("COMPATIBLE", "COMPATIBLE", true, reason),
              queueDb(true, true),
              failure("", "", 0, 0)),
          false);

      HealthEvent event =
          localConditions.find("schema.reindex-required", "worker.schema").orElseThrow();
      AssertedCondition condition = (AssertedCondition) event.body();
      assertEquals("schema.reindex-required", event.id());
      assertEquals("worker.schema", condition.subject());
      assertEquals(Severity.WARNING, event.severity());
      assertTrue(condition.recovery().isPresent(), "required reindex must expose a recovery");
      assertEquals(expectedTargets.get(reason), condition.recovery().get().target().value());
      assertEquals(expectedDefaults.get(reason), condition.recovery().get().defaultArgsJson());
    }
  }

  @Test
  @DisplayName("reindex reason changes modify the event, switch actions, and still clear")
  void reindexReasonChangeModifiesAndClearPreservesConditionSlot() {
    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", true, "legacy_index"),
            queueDb(true, true),
            failure("", "", 0, 0)),
        false);
    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_ADDED, listener.events.get(0).kind());
    assertEquals(
        "core.reindex",
        ((AssertedCondition) listener.events.get(0).event().body())
            .recovery()
            .orElseThrow()
            .target()
            .value());
    listener.events.clear();

    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", true, "schema_mismatch"),
            queueDb(true, true),
            failure("", "", 0, 0)),
        false);
    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, listener.events.get(0).kind());
    AssertedCondition sameAction = (AssertedCondition) listener.events.get(0).event().body();
    assertEquals("core.reindex", sameAction.recovery().orElseThrow().target().value());
    assertEquals("{\"force\":true}", sameAction.recovery().orElseThrow().defaultArgsJson());
    listener.events.clear();

    tap.accept(
        view(
            compat("COMPATIBLE", "COMPATIBLE", true, "embedding_mismatch"),
            queueDb(true, true),
            failure("", "", 0, 0)),
        false);
    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, listener.events.get(0).kind());
    AssertedCondition switched = (AssertedCondition) listener.events.get(0).event().body();
    assertEquals("schema.reindex-required", listener.events.get(0).event().id());
    assertEquals("worker.schema", switched.subject());
    assertEquals("core.rebuild-index", switched.recovery().orElseThrow().target().value());
    assertEquals("{}", switched.recovery().orElseThrow().defaultArgsJson());
    listener.events.clear();

    tap.accept(healthyView(), false);
    assertEquals(1, listener.size());
    assertEquals(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, listener.events.get(0).kind());
    assertEquals("schema.reindex-required", listener.events.get(0).event().id());
    assertTrue(conditions.find("schema.reindex-required", "worker.schema").isEmpty());
  }

}
