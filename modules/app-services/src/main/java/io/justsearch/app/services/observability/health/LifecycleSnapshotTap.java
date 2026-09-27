/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observes {@link ReadinessEnvelopeView} transitions on each
 * {@code StatusLifecycleHandler} health-sampler pass (885 item 6) and emits {@link HealthEvent}
 * records as {@link AssertedCondition} deltas through the substrate.
 *
 * <p>Per tempdoc 430 §A.10 Phase 4 (vertical proof) + §B.K (rev 3.1): the tap reads the
 * already-classified readiness state from the existing
 * {@code StatusLifecycleHandler.computeComponent()} switch and translates per-dimension
 * {@code (state, reasonCode)} into one of the catalog's HealthEvent IDs. The
 * classification machinery is preserved; this tap is an adapter, not a re-implementation.
 *
 * <p>Per-dim state model: each dimension maintains a single "active conditionId". On
 * each snapshot the tap computes the new condition for the dimension from the current
 * envelope and reconciles against the per-dim memory:
 *
 * <ul>
 *   <li>Prior conditionId differs from new (or new is null) → clear the prior
 *       condition; broadcast {@code CONDITION_REMOVED} carrying the removed event.
 *   <li>New conditionId is non-null → upsert; broadcast {@code CONDITION_ADDED} on a
 *       fresh insert, {@code CONDITION_MODIFIED} on a real change, no broadcast on
 *       UNCHANGED. {@link ConditionStore} enforces k8s {@code SetStatusCondition}
 *       semantics — reason-only updates preserve the prior {@code lastTransitionTime}.
 * </ul>
 *
 * <p>{@link ConditionStatus#TRUE} on the wire means "the named condition holds" per k8s
 * convention — e.g., {@code id="index.unavailable"} with {@code status=TRUE} means the
 * indexer IS unavailable. The FE renders the banner when the condition is asserted with
 * status TRUE; absent records mean healthy.
 *
 * <p>Phase 4 covers only {@link ReadinessDimension#INDEX_SERVING}. Phase 5 expands the
 * mapping table to AI, EMBEDDING, TELEMETRY, GPU. Three catalog IDs
 * ({@code api.unreachable}, {@code ai.not-configured}, {@code embedding.not-configured})
 * have no backend-emittable state and remain FE-derived.
 *
 * <p>Thread safety: {@link #accept(ReadinessEnvelopeView)} is {@code synchronized}. Snapshots
 * are computed per {@code /api/status} request (low-frequency); contention is negligible.
 * {@link ConditionStore} is independently thread-safe.
 */
public final class LifecycleSnapshotTap {

  private static final Logger log = LoggerFactory.getLogger(LifecycleSnapshotTap.class);

  /** Lookup key into {@link #MAPPING_TABLE} — {@code (dim, state, reasonCode)}. */
  private record MappingKey(ReadinessDimension dim, String state, String reasonCode) {}

  /** Resolved condition target for an envelope component. */
  /**
   * Mapping from a readiness signal to a Health Condition shape.
   *
   * <p>Slice 3a.1.4 Phase 6: extended with {@code relatedMetrics} so the backend can
   * declare which TIMESERIES Resources correlate with each Condition. The FE iterates
   * {@code AssertedCondition.relatedMetrics()} and dispatches each through the
   * Resource-view renderer registry — replacing the React HealthView's hard-coded
   * {@code event.id === 'index-throughput-stalled' || ...} branches with backend-declared
   * truth (per slice 3a.1.4 §B.2 goal 2).
   *
   * <p>Default {@code relatedMetrics = List.of()} for Conditions without correlated
   * metrics. The MAPPING_TABLE entries that don't construct via the four-arg path use
   * the three-arg compatibility constructor.
   */
  private record ConditionMapping(
      String conditionId,
      String subject,
      Severity severity,
      List<io.justsearch.app.observability.metrics.MetricRef> relatedMetrics,
      Optional<io.justsearch.agent.api.registry.OperationInvocation> recovery) {

    /** Backwards-compatible constructor for Conditions without relatedMetrics or recovery. */
    ConditionMapping(String conditionId, String subject, Severity severity) {
      this(conditionId, subject, severity, List.of(), Optional.empty());
    }

    /** Constructor for Conditions with relatedMetrics but no default recovery. */
    ConditionMapping(
        String conditionId,
        String subject,
        Severity severity,
        List<io.justsearch.app.observability.metrics.MetricRef> relatedMetrics) {
      this(conditionId, subject, severity, relatedMetrics, Optional.empty());
    }
  }

  /** Read-only mapping table populated in {@code static {}} below. */
  private static final Map<MappingKey, ConditionMapping> MAPPING_TABLE = new HashMap<>();

  // Mapping table entries derived from StatusLifecycleHandler.computeComponent() — verbatim
  // (state, reasonCode) pairs the readiness envelope produces today. State values come from
  // StatusLifecycleHandler's READINESS_* constants ("READY", "DEGRADED", "NOT_READY",
  // "NOT_CONFIGURED", "UNKNOWN"); reasonCode values come from LifecycleReasonCode enum codes
  // and a few inline string literals (worker.starting, worker.unavailable, etc.).
  static {
    // ----- WORKER_CONTROL_PLANE: only the bootstrap-failure case (the others overlap
    // with INDEX_SERVING and would double-broadcast). -----
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.WORKER_CONTROL_PLANE, "NOT_READY", "worker.spawn.failed"),
        new ConditionMapping("index.start-error", "worker", Severity.ERROR));
    // Tempdoc 837 §3.4: worker.lost and worker.index_corrupt are the two causes that USED to reach
    // this dimension collapsed onto worker.spawn.failed, so they keep the same conditionId and
    // severity — behaviour-preserving — while the Condition's `reason` field (PascalCase of the code)
    // now says WorkerLost / WorkerIndexCorrupt instead of the false WorkerSpawnFailed. Without these
    // rows the lookup misses and the Condition disappears entirely (an unmapped key emits nothing but
    // a once-per-startup WARN), which would be a silent regression rather than a refinement.
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.WORKER_CONTROL_PLANE, "NOT_READY", "worker.lost"),
        new ConditionMapping("index.start-error", "worker", Severity.ERROR));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.WORKER_CONTROL_PLANE, "NOT_READY", "worker.index_corrupt"),
        new ConditionMapping("index.start-error", "worker", Severity.ERROR));
    // Tempdoc 825: the terminal twin of worker.spawn.failed — the worker never started AND the
    // bounded boot-recovery budget is spent. Same conditionId and severity (the 837 precedent: a
    // refinement of the same condition, not a new one); without the row the lookup misses and the
    // Condition disappears exactly when the state became permanent.
    MAPPING_TABLE.put(
        new MappingKey(
            ReadinessDimension.WORKER_CONTROL_PLANE, "NOT_READY", "worker.spawn_recovery_exhausted"),
        new ConditionMapping("index.start-error", "worker", Severity.ERROR));

    // ----- INDEX_SERVING: worker availability + throughput. -----
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "NOT_CONFIGURED", "worker.not_configured"),
        new ConditionMapping("index.unavailable", "worker", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "NOT_CONFIGURED", "worker.not_started"),
        new ConditionMapping("index.unavailable", "worker", Severity.WARNING));
    // Tempdoc 837 §3.4: an orderly teardown now says worker.shut_down where it used to say
    // worker.not_configured — same NOT_CONFIGURED verdict, same Condition, more honest reason.
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "NOT_CONFIGURED", "worker.shut_down"),
        new ConditionMapping("index.unavailable", "worker", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "NOT_READY", "worker.starting"),
        new ConditionMapping("index.unavailable", "worker", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "NOT_READY", "worker.unavailable"),
        new ConditionMapping("index.unavailable", "worker", Severity.ERROR));
    // Slice 447-followup §X.11.5 Phase 7: 442 §B.9 row 548 ships end-to-end via the
    // parameterless `core.rebuild-index` wrapper Operation. Static defaultArgsJson
    // sidesteps the dynamic-corpusIds limitation that blocked impl-B.
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "NOT_READY", "index.not_healthy"),
        new ConditionMapping(
            "index.unavailable",
            "worker",
            Severity.ERROR,
            List.of(),
            Optional.of(
                io.justsearch.agent.api.registry.OperationInvocation.of(
                    new io.justsearch.agent.api.registry.OperationRef(
                        "core.rebuild-index")))));
    // Slice 3a.1.4 Phase 6: throughput Conditions correlate with the
    // core.metric-worker-job-queue-depth TIMESERIES Resource. The FE iterates
    // AssertedCondition.relatedMetrics() and dispatches via the Resource-view registry
    // — replacing the React HealthView's hard-coded event.id branches with
    // backend-declared truth (per §B.2 goal 2). Other trend metrics (docs_per_sec,
    // gpu utilization) are declared at the recipe level but not yet shipped as
    // producer-attached instances; relatedMetrics references only the ID that
    // resolves to a registered Resource for V1.
    // Tempdoc 876 §C.8: the dense/semantic leg is down but the index still SERVES — AUTO has
    // degraded to keyword (StatusLifecycleHandler.denseUnavailableReason). This row exists so the
    // reason is MAPPED rather than unmapped-unhealthy: reconcileDim preserves the prior assertion
    // for an unmapped unhealthy reason (correct — unknown is not healthy), which meant a boot-time
    // `index.unavailable` from worker.starting was never cleared once the dimension settled here,
    // and core.search-index — gated on Not(index.unavailable) — stayed hidden for the life of the
    // process even though keyword search worked. Mapping to its OWN id clears that stale gate
    // (reconcileDim step 1 swaps a differing prior) while keeping the degradation visible.
    // WARNING, not ERROR: search works, it is just keyword-only.
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "DEGRADED", "index.dense_unavailable"),
        new ConditionMapping("index.dense-unavailable", "worker", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "DEGRADED", "index.embedding_rebuilding"),
        new ConditionMapping("index.embedding-rebuilding", "worker", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "DEGRADED", "worker.throughput_stalled"),
        new ConditionMapping(
            "worker.throughput.stalled",
            "worker.queue",
            Severity.WARNING,
            List.of(
                new io.justsearch.app.observability.metrics.MetricRef(
                    io.justsearch.app.observability.metrics.JobQueueDepthMetricResourceCatalog
                        .JOB_QUEUE_DEPTH_ID,
                    Optional.of(
                        new io.justsearch.agent.api.registry.I18nKey(
                            "registry-resource.metric.job-queue-depth.label")),
                    Optional.of(
                        io.justsearch.app.observability.metrics.RenderHint.SPARK)))));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.INDEX_SERVING, "DEGRADED", "worker.throughput_degraded"),
        new ConditionMapping(
            "worker.throughput.degraded",
            "worker.queue",
            Severity.INFO,
            List.of(
                new io.justsearch.app.observability.metrics.MetricRef(
                    io.justsearch.app.observability.metrics.JobQueueDepthMetricResourceCatalog
                        .JOB_QUEUE_DEPTH_ID,
                    Optional.of(
                        new io.justsearch.agent.api.registry.I18nKey(
                            "registry-resource.metric.job-queue-depth.label")),
                    Optional.of(
                        io.justsearch.app.observability.metrics.RenderHint.SPARK)))));

    // ----- AI: inference runtime readiness. -----
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "NOT_READY", "inference.starting"),
        new ConditionMapping("ai.not-ready", "inference.ai", Severity.INFO));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "NOT_READY", "inference.offline"),
        new ConditionMapping("ai.not-ready", "inference.ai", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "DEGRADED", "inference.offline"),
        new ConditionMapping("ai.not-ready", "inference.ai", Severity.WARNING));
    // Tempdoc 837 §3.4 / S5: the two causes that used to reach this dimension collapsed onto
    // inference.offline. An unmapped (dimension, state, code) key emits NO Condition at all — only a
    // once-per-startup WARN — so refining the code without a row here would delete the Condition
    // instead of sharpening it. inference.crashed keeps offline's conditionId and WARNING severity
    // (behaviour-preserving; only the Condition's `reason` gets more precise), while
    // inference.deactivated drops to INFO: it is the user's own choice, and emitting a WARNING for a
    // state somebody deliberately selected is the alarm-blindness this slice exists to stop. That
    // matches its `info` CAUSE_ROWS severity and the sibling inference.starting row above.
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "NOT_READY", "inference.crashed"),
        new ConditionMapping("ai.not-ready", "inference.ai", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "DEGRADED", "inference.crashed"),
        new ConditionMapping("ai.not-ready", "inference.ai", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "NOT_READY", "inference.deactivated"),
        new ConditionMapping("ai.not-ready", "inference.ai", Severity.INFO));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "DEGRADED", "inference.deactivated"),
        new ConditionMapping("ai.not-ready", "inference.ai", Severity.INFO));
    // UNKNOWN reachable only when the inference component state is null (boot path).
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.AI, "UNKNOWN", null),
        new ConditionMapping("ai.readiness-unknown", "inference.ai", Severity.WARNING));

    // ----- EMBEDDING: worker-side embedding probe. -----
    MAPPING_TABLE.put(
        new MappingKey(
            ReadinessDimension.EMBEDDING, "NOT_READY", "worker.health.embedding_not_ready"),
        new ConditionMapping("embedding.not-ready", "inference.embedding", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(
            ReadinessDimension.EMBEDDING, "UNKNOWN", "worker.health.embedding_probe_missing"),
        new ConditionMapping(
            "embedding.readiness-unknown", "inference.embedding", Severity.WARNING));

    // ----- Visual extraction: OCR affects baseline retrieval; VDU may affect either baseline
    // retrieval or AI-only enrichment depending on the readiness dimension.
    for (String reason : List.of("ocr.disabled", "ocr.engine_missing", "ocr.language_missing")) {
      MAPPING_TABLE.put(
          new MappingKey(ReadinessDimension.VISUAL_TEXT_EXTRACTION, "DEGRADED", reason),
          new ConditionMapping("ocr.unavailable", "worker.ocr", Severity.WARNING));
    }
    for (String reason :
        List.of(
            "vdu.ai_offline",
            "vdu.insufficient_vram",
            "vdu.missing_mmproj",
            "vdu.circuit_open")) {
      MAPPING_TABLE.put(
          new MappingKey(ReadinessDimension.VISUAL_TEXT_EXTRACTION, "DEGRADED", reason),
          new ConditionMapping("vdu.unavailable", "vdu", Severity.WARNING));
      MAPPING_TABLE.put(
          new MappingKey(ReadinessDimension.VISUAL_DOCUMENT_UNDERSTANDING, "DEGRADED", reason),
          new ConditionMapping("vdu.unavailable", "vdu", Severity.WARNING));
    }

    // ----- TELEMETRY: classifier produces only DEGRADED (with one of 4 reason codes). -----
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.TELEMETRY, "DEGRADED", "telemetry.metrics.stale"),
        new ConditionMapping("telemetry.degraded", "head.telemetry", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(
            ReadinessDimension.TELEMETRY, "DEGRADED", "telemetry.metrics.high_failure_rate"),
        new ConditionMapping("telemetry.degraded", "head.telemetry", Severity.WARNING));
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.TELEMETRY, "DEGRADED", "telemetry.disk_space_low"),
        new ConditionMapping("telemetry.degraded", "head.telemetry", Severity.WARNING));
    // NOTE: TELEMETRY_UNAVAILABLE / "telemetry.unavailable" is unreachable from the current
    // StatusLifecycleHandler.computeComponent() path — when the snapshot is null, the handler
    // emits READY/null directly rather than DEGRADED/telemetry.unavailable. If a future
    // refactor surfaces this reason via the readiness envelope, add the mapping here. For
    // now: YAGNI.

    // ----- GPU: SaturationMonitor → DEGRADED with single reason. -----
    // Slice 3a.1.4b cohort follow-up: declare relatedMetrics so HealthLitView restores
    // the GPU sparkline that was retired with the React HealthView (per slice 3a.1.4
    // §B.K Goal 2). Both GPU TIMESERIES Resources are referenced — a saturated GPU
    // condition is correlated with both compute utilization and memory pressure.
    MAPPING_TABLE.put(
        new MappingKey(ReadinessDimension.GPU, "DEGRADED", "gpu.saturated"),
        new ConditionMapping(
            "gpu.saturated",
            "head.gpu",
            Severity.WARNING,
            List.of(
                new io.justsearch.app.observability.metrics.MetricRef(
                    io.justsearch.app.observability.metrics.GpuUtilizationMetricResourceCatalog
                        .GPU_UTILIZATION_ID,
                    Optional.of(
                        new io.justsearch.agent.api.registry.I18nKey(
                            "registry-resource.metric.gpu-utilization.label")),
                    Optional.of(
                        io.justsearch.app.observability.metrics.RenderHint.SPARK)),
                new io.justsearch.app.observability.metrics.MetricRef(
                    io.justsearch.app.observability.metrics
                        .GpuMemoryUtilizationMetricResourceCatalog.GPU_MEMORY_UTILIZATION_ID,
                    Optional.of(
                        new io.justsearch.agent.api.registry.I18nKey(
                            "registry-resource.metric.gpu-memory-utilization.label")),
                    Optional.of(
                        io.justsearch.app.observability.metrics.RenderHint.SPARK)))));
  }

  /**
   * Returns the catalog IDs this tap can emit, derived from {@link #MAPPING_TABLE}.
   *
   * <p>Per tempdoc 430 §A.10 Phase 10 + rev 3.15 §B.AB.1: exposed for {@code
   * HealthEventEmitCoverageTest} to assert every catalog ID in §A.2 has an emit site.
   */
  public static Set<String> emittableIds() {
    return MAPPING_TABLE.values().stream()
        .map(ConditionMapping::conditionId)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private final ConditionStore conditions;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;

  /** Per-dim memory of the most recently active conditionId; null entry = healthy. */
  private final EnumMap<ReadinessDimension, String> activeConditionPerDim =
      new EnumMap<>(ReadinessDimension.class);

  /**
   * Once-per-startup dedup for unmapped {@code (dim, state, reasonCode)} combinations.
   * Without this, every {@code /api/status} call WARN-spams the same key. Bounded
   * growth: at most one entry per unrecognized {@link MappingKey}.
   */
  private final Set<MappingKey> warnedKeys = ConcurrentHashMap.newKeySet();

  public LifecycleSnapshotTap(
      ConditionStore conditions,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Reconciles each readiness dimension's current state with the {@link ConditionStore}.
   * Called by {@code StatusLifecycleHandler} after {@code buildReadinessEnvelope(...)}
   * returns. Idempotent — re-calling with the same envelope produces no broadcasts.
   */
  public synchronized void accept(ReadinessEnvelopeView envelope) {
    Objects.requireNonNull(envelope, "envelope");
    for (ReadinessDimension dim : ReadinessDimension.values()) {
      ReadinessComponentView component = envelope.components().get(dim.key());
      reconcileDim(dim, component);
    }
  }

  private void reconcileDim(ReadinessDimension dim, ReadinessComponentView component) {
    boolean healthy = isHealthy(component);
    ConditionMapping target = healthy ? null : lookupMapping(dim, component);
    boolean unmappedUnhealthy = !healthy && target == null;

    // Tri-state outcome:
    //   healthy            → clear prior assertion (dim is genuinely OK)
    //   mapped (target!=null) → upsert / swap to the new conditionId
    //   unmappedUnhealthy  → preserve prior assertion (dim is unhealthy with an unknown
    //                        reason; do NOT mistake unknown-reason for healthy and clear)
    if (unmappedUnhealthy) {
      warnUnmappedOnce(dim, component);
      return;
    }

    String priorConditionId = activeConditionPerDim.get(dim);

    // Step 1: clear the prior condition if it differs from (or replaces) the new target.
    if (priorConditionId != null
        && (target == null || !priorConditionId.equals(target.conditionId()))) {
      clearPrior(dim, priorConditionId);
    }

    // Step 2: assert the new condition (if any).
    if (target != null) {
      assertCondition(dim, component, target);
    } else {
      activeConditionPerDim.remove(dim);
    }
  }

  private static boolean isHealthy(ReadinessComponentView component) {
    if (component == null) {
      // Absent component is treated as "no signal to assert" — equivalent to healthy
      // for prior-clearing purposes. Production's exhaustive iteration over
      // ReadinessDimension.values() in buildReadinessEnvelope ensures every dim has a
      // component; this branch primarily covers test envelopes that only set a subset.
      return true;
    }
    String state = component.state();
    return state == null || state.isEmpty() || "READY".equals(state);
  }

  private static ConditionMapping lookupMapping(
      ReadinessDimension dim, ReadinessComponentView component) {
    return MAPPING_TABLE.get(new MappingKey(dim, component.state(), component.reasonCode()));
  }

  private void warnUnmappedOnce(ReadinessDimension dim, ReadinessComponentView component) {
    MappingKey key = new MappingKey(dim, component.state(), component.reasonCode());
    if (warnedKeys.add(key)) {
      log.warn(
          "LifecycleSnapshotTap: no mapping for dim={} state={} reasonCode={};"
              + " preserving any prior assertion (unknown reason ≠ healthy)",
          dim,
          component.state(),
          component.reasonCode());
    }
  }

  private void clearPrior(ReadinessDimension dim, String priorConditionId) {
    String subject = subjectForActive(dim, priorConditionId);
    if (subject == null) {
      // Should not happen — defensive.
      activeConditionPerDim.remove(dim);
      return;
    }
    Optional<HealthEvent> removed = conditions.clear(priorConditionId, subject);
    removed.ifPresent(event -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, event));
    activeConditionPerDim.remove(dim);
  }

  private void assertCondition(
      ReadinessDimension dim, ReadinessComponentView component, ConditionMapping target) {
    AssertedCondition body =
        new AssertedCondition(
            target.subject(),
            ConditionStatus.TRUE, // k8s: status TRUE means "the named condition holds"
            toPascalReason(component.reasonCode()),
            clock.instant(),
            Optional.empty(),
            target.recovery(), // 442 §B.9 + 447-impl-B: now carries OperationInvocation when mapping declared one
            target.relatedMetrics()); // slice 3a.1.4 Phase 6 — backend-declared metric correlation
    HealthEvent event =
        new HealthEvent(
            target.conditionId(),
            clock.instant(),
            source,
            target.severity(),
            Optional.of("health-events." + target.conditionId() + ".message"),
            body);
    ConditionStore.Transition transition = conditions.upsert(event);
    switch (transition) {
      case ADDED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
      case MODIFIED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      case UNCHANGED -> {
        // No broadcast; ConditionStore preserved the prior record.
      }
    }
    activeConditionPerDim.put(dim, target.conditionId());
  }

  /**
   * Reverse-lookup: find the subject for the previously-active conditionId on the dim.
   * Walks the mapping table values matching the conditionId; deterministic because each
   * conditionId resolves to a unique subject within a dim's possible mappings.
   */
  private String subjectForActive(ReadinessDimension dim, String conditionId) {
    for (Map.Entry<MappingKey, ConditionMapping> entry : MAPPING_TABLE.entrySet()) {
      if (entry.getKey().dim() == dim && entry.getValue().conditionId().equals(conditionId)) {
        return entry.getValue().subject();
      }
    }
    return null;
  }

  /**
   * Converts a dotted lowercase {@code reasonCode} (e.g., {@code "worker.starting"}) to
   * the PascalCase form k8s {@link AssertedCondition} requires (e.g., {@code "WorkerStarting"}).
   * Strips dots/underscores; capitalizes each segment.
   */
  static String toPascalReason(String reasonCode) {
    if (reasonCode == null || reasonCode.isBlank()) {
      return "Unknown";
    }
    String[] segments = reasonCode.split("[._]");
    StringBuilder out = new StringBuilder();
    for (String segment : segments) {
      if (segment.isEmpty()) continue;
      out.append(Character.toUpperCase(segment.charAt(0)));
      if (segment.length() > 1) {
        out.append(segment.substring(1));
      }
    }
    return out.length() == 0 ? "Unknown" : out.toString();
  }
}
