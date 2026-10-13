package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 626 §Axis-C — the per-root index-drift legibility tap. A root whose reconcile could not
 * verify deletions is surfaced as a persistent {@code index.drift-unknown} condition with a
 * {@code core.reindex} recovery; a reconcile that prunes stale entries fires a one-shot
 * {@code index.drift-corrected} Occurrence.
 */
final class IndexDriftHealthTapTest {

  private static final Source HEAD = Source.forProcess("head", "i", "1.0");

  private IndexDriftHealthTap tap(ConditionStore store) {
    return tap(store, new OccurrenceLog(200), new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()));
  }

  private IndexDriftHealthTap tap(
      ConditionStore store, OccurrenceLog occ, HealthEventChangeRegistry changes) {
    return new IndexDriftHealthTap(
        store,
        occ,
        changes,
        HEAD,
        Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC),
        List::of); // unused — tests drive reconcile(...) directly
  }

  /** WatchedRoot with the delete-detection-unverified flag (no drift-corrected prune). */
  private static IndexingService.WatchedRoot root(String path, boolean unverified) {
    return new IndexingService.WatchedRoot(null, Path.of(path), null, null, true, unverified);
  }

  /** WatchedRoot carrying a drift-corrected orphan-prune (count + at-ms). */
  private static IndexingService.WatchedRoot prunedRoot(String path, int orphanCount, long atMs) {
    return new IndexingService.WatchedRoot(
        null, Path.of(path), null, null, true, false, orphanCount, atMs);
  }

  @Test
  void unverifiedRootAssertsConditionWithScopedReconcileRecovery() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(List.of(root("/docs", true)));

    List<HealthEvent> snap = store.currentSnapshot();
    assertEquals(1, snap.size(), "an unverified root must assert one condition");
    HealthEvent e = snap.get(0);
    assertEquals("index.drift-unknown", e.id());
    AssertedCondition c = (AssertedCondition) e.body();
    assertEquals(ConditionStatus.TRUE, c.status(), "presence of the condition = the can't-verify state");
    assertTrue(c.subject().startsWith("index.drift-unknown/"), "subject is per-root (hashed)");
    assertTrue(c.recovery().isPresent(), "must carry a recovery operation");
    // Tempdoc 626 §Recency (Move C) — granularity-matched scoped recovery: verify THIS folder, not a
    // corpus-wide reindex. The recovery carries the same pathHash as the condition subject.
    assertEquals("core.reconcile-root", c.recovery().get().target().value());
    String pathHash = c.subject().substring("index.drift-unknown/".length());
    assertEquals(
        "{\"pathHash\":\"" + pathHash + "\"}", c.recovery().get().defaultArgsJson());
  }

  @Test
  void verifiedRootAssertsNoCondition() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(List.of(root("/docs", false)));
    assertTrue(store.currentSnapshot().isEmpty(), "a verified root must assert nothing (healthy = absent)");
  }

  @Test
  void becomingVerifiedClearsTheCondition() {
    ConditionStore store = new ConditionStore();
    IndexDriftHealthTap t = tap(store);
    t.reconcile(List.of(root("/docs", true)));
    assertEquals(1, store.currentSnapshot().size());
    t.reconcile(List.of(root("/docs", false))); // next reconcile: now verified
    assertTrue(store.currentSnapshot().isEmpty(), "a re-verified root must clear its drift condition");
  }

  @Test
  void rootRemovedClearsTheCondition() {
    ConditionStore store = new ConditionStore();
    IndexDriftHealthTap t = tap(store);
    t.reconcile(List.of(root("/docs", true)));
    assertEquals(1, store.currentSnapshot().size());
    t.reconcile(List.of()); // root no longer watched
    assertTrue(store.currentSnapshot().isEmpty(), "a removed root must clear its drift condition");
  }

  @Test
  void multipleUnverifiedRootsEachGetOwnSubject() {
    ConditionStore store = new ConditionStore();
    tap(store).reconcile(List.of(root("/a", true), root("/b", true), root("/c", false)));
    // Two unverified roots → two distinct per-root conditions; the verified one asserts nothing.
    assertEquals(2, store.currentSnapshot().size());
    long distinctSubjects =
        store.currentSnapshot().stream()
            .map(e -> ((AssertedCondition) e.body()).subject())
            .distinct()
            .count();
    assertEquals(2, distinctSubjects, "per-root subjects must be distinct (ConditionStore keys on id+subject)");
  }

  // ===== drift-corrected Occurrence (tempdoc 626 §Axis-C) =====

  /** A registry with a captured event list (HealthEventChangeRegistry is final — subscribe, don't subclass). */
  private static HealthEventChangeRegistry recording(
      CopyOnWriteArrayList<HealthEventChangeRegistry.HealthChangeEvent> sink) {
    HealthEventChangeRegistry reg = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    reg.subscribeTyped(sink::add);
    return reg;
  }

  private static long occurrencesOf(
      CopyOnWriteArrayList<HealthEventChangeRegistry.HealthChangeEvent> events, String id) {
    return events.stream()
        .filter(
            e ->
                e.kind() == HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED
                    && id.equals(e.event().id()))
        .count();
  }

  @Test
  void orphanPruneEmitsDriftCorrectedOccurrence() {
    OccurrenceLog occ = new OccurrenceLog(200);
    var events = new CopyOnWriteArrayList<HealthEventChangeRegistry.HealthChangeEvent>();
    tap(new ConditionStore(), occ, recording(events))
        .reconcile(List.of(prunedRoot("/docs", 7, 1000L)));

    assertEquals(1, occ.recent().size(), "an orphan-prune must append one occurrence");
    HealthEvent e = occ.recent().get(0);
    assertEquals("index.drift-corrected", e.id());
    LifecycleEvent body = (LifecycleEvent) e.body();
    assertEquals(7, body.attributes().get("orphanCount"));
    assertEquals(1L, occurrencesOf(events, "index.drift-corrected"), "must broadcast OCCURRENCE_APPENDED once");
  }

  @Test
  void samePruneAtMsDoesNotReEmit() {
    OccurrenceLog occ = new OccurrenceLog(200);
    IndexDriftHealthTap t = tap(new ConditionStore(), occ, new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()));
    t.reconcile(List.of(prunedRoot("/docs", 7, 1000L)));
    t.reconcile(List.of(prunedRoot("/docs", 7, 1000L))); // same prune event, next snapshot
    assertEquals(1, occ.recent().size(), "the same prune event must fire exactly once (dedup on at-ms)");
  }

  @Test
  void laterPruneEmitsAgain() {
    OccurrenceLog occ = new OccurrenceLog(200);
    IndexDriftHealthTap t = tap(new ConditionStore(), occ, new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()));
    t.reconcile(List.of(prunedRoot("/docs", 7, 1000L)));
    t.reconcile(List.of(prunedRoot("/docs", 3, 2000L))); // a NEW prune (later at-ms)
    assertEquals(2, occ.recent().size(), "a new orphan-prune event must fire a second occurrence");
  }

  @Test
  void zeroOrphanCountNeverEmits() {
    OccurrenceLog occ = new OccurrenceLog(200);
    tap(new ConditionStore(), occ, new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()))
        .reconcile(List.of(prunedRoot("/docs", 0, 1000L)));
    assertTrue(occ.recent().isEmpty(), "no orphans pruned → no drift-corrected occurrence");
  }
}
