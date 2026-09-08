package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.observability.rules.RuleCatalog;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Catalog-vs-emit-reality invariant for tempdoc 430.
 *
 * <p>Per §A.8 + §A.10 Phase 10 + rev 3.15 §B.AB: every catalog ID in §A.2 must have a
 * corresponding emit site (a producer's {@code emittableIds()} set, the rule catalog,
 * or the FE-only allowlist). Failure mode: drift between §A.2 and emit reality.
 *
 * <p>Per CLAUDE.md "audit-driven fixes need a runnable test, not just a passing
 * audit." This is the test.
 *
 * <p>The canonical ID list is sourced from §A.2 plus the visual extraction capability
 * additions. Adding a new catalog ID requires updating this list in
 * this test; the bidirectional pattern catches drift via test failure (per rev 3.15
 * §B.AB.2).
 */
@DisplayName("HealthEventEmitCoverageTest")
final class HealthEventEmitCoverageTest {

  /**
   * §A.2 canonical IDs plus visual extraction capability IDs. Adding a new
   * catalog ID requires updating both §A.2 and this list — failure-on-drift is the
   * safety net (per rev 3.15 §B.AB.2).
   */
  private static final List<String> CANONICAL_IDS =
      List.of(
          "api.unreachable",
          "index.unavailable",
          "index.start-error",
          "index.dense-unavailable",
          "worker.throughput.stalled",
          "worker.throughput.degraded",
          "schema.blocked",
          "schema.reindex-required",
          "embedding.blocked",
          "queue-db.unhealthy",
          "queue-db.check-failed",
          // Tempdoc 821 §3-C3 follow-on — per-stage enrichment completeness (WorkerSnapshotTap).
          "enrichment.incomplete",
          "ai.not-ready",
          "ai.readiness-unknown",
          "ai.not-configured",
          "embedding.not-ready",
          "embedding.readiness-unknown",
          "embedding.not-configured",
          "ocr.unavailable",
          "vdu.unavailable",
          "telemetry.degraded",
          "gpu.saturated",
          "memory.pressure",
          "worker.job.failed",
          "worker.job.retry-scheduled",
          // Tempdoc 627 — supervised-recovery milestones (emitted by CapabilityHealthBridge).
          "worker.restart-attempted",
          "worker.recovered",
          // Tempdoc 627 (N1) — unclean-shutdown recovery narrated on boot (emitted by BootRecoveryEmitter).
          "head.unclean-shutdown-recovered",
          "agent.session.completed",
          "agent.session.cancelled",
          "agent.session.budget-edge-finalize",
          "agent.session.max-iterations",
          "agent.session.errored");

  /**
   * §A.2 + rev 3.19 §B.AF.1 + rev 3.20 §B.AF.6 rec 2: catalog IDs that backend producers
   * MUST NOT emit. Split into two categories per the post-completion verification pass:
   *
   * <ul>
   *   <li>{@code api.unreachable} — genuinely FE-only. The FE detects fetch failure of
   *       {@code /api/status} itself; the backend cannot tell the FE the FE can't reach
   *       it. The slim {@code deriveHealthEvents.ts} emits this from the FE.
   *   <li>{@code ai.not-configured}, {@code embedding.not-configured} — phantom events.
   *       Verified at rev 3.19 §B.AF.1: neither backend nor FE produces them today. The catalog
   *       rows remain in §A.2 for forward-compat (a future enum extension may surface the state
   *       values), but no current code path emits them. Producers MUST NOT emit these either; if
   *       they did, the assertion in {@link #producersDoNotEmitFeOnlyIds} fails.
   *       <p>{@code schema.rebuilding} was a third such id until tempdoc 941: it had no producer
   *       AND no i18n catalog row to render, so it was deleted from
   *       {@code health-events.en.properties} rather than kept as forward-compat for a state
   *       value no emitter maps. Dropped from both lists here so this test stops asserting over
   *       an id the catalog no longer declares.
   * </ul>
   */
  private static final Set<String> FE_ONLY_ALLOWLIST =
      Set.of(
          "api.unreachable",
          // Phantom IDs (forward-compat; not currently fired anywhere):
          "ai.not-configured",
          "embedding.not-configured");

  /** Returns the union of emittable IDs across all backend producers. */
  private static Set<String> collectProducerSet() {
    Set<String> ids = new LinkedHashSet<>();
    ids.addAll(LifecycleSnapshotTap.emittableIds());
    ids.addAll(WorkerSnapshotTap.emittableIds());
    ids.addAll(HeadHealthEventsEmitter.emittableIds());
    // Tempdoc 627: the supervised-recovery milestone occurrences are emitted by the capability bridge.
    ids.addAll(io.justsearch.app.services.bootstrap.phases.CapabilityHealthBridge.emittableIds());
    // Tempdoc 627 (N1): the unclean-shutdown-recovery occurrence is emitted by BootRecoveryEmitter.
    ids.addAll(BootRecoveryEmitter.emittableIds());
    // Rule catalog: load from classpath (production rules + any test fixtures); extract
    // emit IDs.
    RuleCatalog catalog = RuleCatalog.fromClasspath();
    catalog.rules().forEach(rule -> ids.add(rule.emits().id()));
    return Set.copyOf(ids);
  }

  // ============================================================
  // Invariant tests
  // ============================================================

  @Test
  @DisplayName("canonical IDs equal producer set ∪ FE-only allowlist")
  void everyCatalogIdHasAnEmitSiteOrAllowlistEntry() {
    Set<String> producerSet = collectProducerSet();
    Set<String> coverage = new LinkedHashSet<>();
    coverage.addAll(producerSet);
    coverage.addAll(FE_ONLY_ALLOWLIST);

    Set<String> canonical = new HashSet<>(CANONICAL_IDS);
    Set<String> missingFromCoverage = new TreeSet<>(canonical);
    missingFromCoverage.removeAll(coverage);
    Set<String> unexpectedInCoverage = new TreeSet<>(coverage);
    unexpectedInCoverage.removeAll(canonical);

    assertTrue(
        missingFromCoverage.isEmpty(),
        "Catalog IDs in §A.2 with no emit site (no producer + not in FE-only allowlist): "
            + missingFromCoverage);
    assertTrue(
        unexpectedInCoverage.isEmpty(),
        "Producer / allowlist contains IDs not in §A.2 canonical list: "
            + unexpectedInCoverage
            + " — either §A.2 needs the new ID, or the producer/allowlist drifted.");
  }

  @Test
  @DisplayName("producer set and FE-only allowlist are disjoint")
  void producersDoNotEmitFeOnlyIds() {
    Set<String> producerSet = collectProducerSet();
    Set<String> overlap = new TreeSet<>(producerSet);
    overlap.retainAll(FE_ONLY_ALLOWLIST);
    assertTrue(
        overlap.isEmpty(),
        "Backend producer is emitting an FE-only ID (would double-broadcast with the "
            + "FE's deriveHealthEvents): "
            + overlap);
  }

  @Test
  @DisplayName("canonical list is exactly 33 entries with no duplicates")
  void canonicalListIs33Unique() {
    assertEquals(33, CANONICAL_IDS.size(), "CANONICAL_IDS should hold 33 entries");
    assertEquals(
        33,
        new HashSet<>(CANONICAL_IDS).size(),
        "CANONICAL_IDS contains a duplicate entry");
  }

  @Test
  @DisplayName("each known producer contributes a non-empty emittable set")
  void producersDeclareNonEmptyEmittableSets() {
    assertFalse(
        LifecycleSnapshotTap.emittableIds().isEmpty(),
        "LifecycleSnapshotTap.emittableIds() returned empty — broken accessor or empty"
            + " mapping table");
    assertFalse(
        WorkerSnapshotTap.emittableIds().isEmpty(),
        "WorkerSnapshotTap.emittableIds() returned empty — broken accessor or empty"
            + " mapping tables");
    assertFalse(
        HeadHealthEventsEmitter.emittableIds().isEmpty(),
        "HeadHealthEventsEmitter.emittableIds() returned empty — broken accessor or"
            + " empty AGENT_DISPOSITIONS map");
    assertFalse(
        RuleCatalog.fromClasspath().rules().isEmpty(),
        "RuleCatalog.fromClasspath() returned no rules — production memory-pressure.yaml"
            + " missing from classpath?");
  }
}
