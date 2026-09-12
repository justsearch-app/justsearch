/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;

/**
 * State/persistence seam for watched roots.
 *
 * <p>Keeps timestamp normalization and persist operations centralized so caller flows
 * can focus on orchestration instead of map/store bookkeeping.
 */
final class WatchedRootsState {

  private final Map<Path, Instant> watchedRoots;
  private final Map<Path, String> walkErrors;
  /**
   * Roots whose filesystem walk has terminated at least once (tempdoc 599 Fix 1). Set for EVERY
   * terminal walk outcome (indexed, walked-empty, failed); cleared at registration. Lets the wire
   * distinguish "walked, nothing to index" (empty) from "walk in progress / never walked" — both of
   * which otherwise have no {@code lastIndexed} and no {@code walkError}.
   */
  private final java.util.Set<Path> walkCompleted;
  /**
   * Roots whose last reconcile could NOT verify index-vs-disk delete correspondence (tempdoc 626
   * §Axis-C — the reconciler's delete-detection scan was skipped because the indexed-path set
   * exceeded the scan cap). Transient (recomputed each periodic sync), NOT persisted: it reflects the
   * latest reconcile outcome. Surfaces a per-root "couldn't verify" state so a large folder never
   * shows a false "✓ indexed".
   */
  private final java.util.Set<Path> deleteDetectionUnverified;
  /**
   * Tempdoc 626 §Axis-C (drift-corrected) — the latest reconcile's orphan-prune outcome per root: a
   * deletion the live watcher missed that the periodic reconcile caught and corrected. Keyed by root,
   * carries {@code (orphanCount, atMs)}; the at-ms advance is the one-shot trigger the
   * {@code IndexDriftHealthTap} dedups on to emit an {@code index.drift-corrected} Occurrence.
   * Transient (recomputed each reconcile), NOT persisted.
   */
  private final Map<Path, OrphanPrune> driftCorrected;
  /**
   * Tempdoc 626 §Recency — per-root wall-clock of the last reconcile that actually CONFIRMED index↔disk
   * correspondence (a clean force=false scan, or any force=true re-converge). Distinct from {@code
   * watchedRoots}'s {@code lastIndexed} (last <em>write</em>): this is the last <em>verification</em>, the
   * heartbeat that lets a calm "✓" prove it is fresh and a cap-skipped root read as visibly stale. Stamped
   * via the injected {@link #clock} when {@link #setDeleteDetectionUnverified} clears the unverified flag.
   * Transient (recomputed each reconcile), NOT persisted.
   */
  private final Map<Path, Instant> lastVerifiedAt;
  /**
   * The collection label each watched root was added under (tempdoc 885 §UD open item 4 / 821
   * §L.3). Persisted: it is the only record of the label, because the two arms that consume it (the
   * Worker-side watcher registration and the scan RPC) are write-only. Without it a labelled root
   * reported {@code collection: "default"} on {@code GET /api/indexing/roots} and every re-walk
   * after a restart re-tagged its documents as the default.
   */
  private final Map<Path, String> collections;
  private final WatchedRootsStore rootsStore;
  private final Clock clock;

  WatchedRootsState(Map<Path, Instant> watchedRoots, WatchedRootsStore rootsStore) {
    this(watchedRoots, rootsStore, Clock.systemUTC());
  }

  WatchedRootsState(Map<Path, Instant> watchedRoots, WatchedRootsStore rootsStore, Clock clock) {
    this.watchedRoots = Objects.requireNonNull(watchedRoots, "watchedRoots");
    this.walkErrors = new java.util.concurrent.ConcurrentHashMap<>();
    this.walkCompleted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    this.deleteDetectionUnverified = java.util.concurrent.ConcurrentHashMap.newKeySet();
    this.driftCorrected = new java.util.concurrent.ConcurrentHashMap<>();
    this.lastVerifiedAt = new java.util.concurrent.ConcurrentHashMap<>();
    this.collections = new java.util.concurrent.ConcurrentHashMap<>();
    this.rootsStore = Objects.requireNonNull(rootsStore, "rootsStore");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  synchronized void loadPersistedRoots() {
    var result = rootsStore.loadPersistedRootsWithErrors();
    for (var entry : result.roots().entrySet()) {
      watchedRoots.put(entry.getKey(), normalizeTimestamp(entry.getValue()));
    }
    walkErrors.putAll(result.walkErrors());
    walkCompleted.addAll(result.walkCompleted());
    collections.putAll(result.collections());
  }

  /**
   * Records the collection label a root is watched under. Blank/null clears it, so the callers'
   * "no label" case never persists an empty string that would later read as a real label.
   */
  synchronized void setCollection(Path root, String collection) {
    if (collection == null || collection.isBlank()) {
      collections.remove(root);
    } else {
      collections.put(root, collection);
    }
  }

  /** The collection label {@code root} was added under, or {@code null} if none was recorded. */
  String getCollection(Path root) {
    return collections.get(root);
  }

  /** Membership and labels share one observation; no filesystem availability work is scheduled. */
  synchronized java.util.List<io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding>
      snapshotBindings() {
    return watchedRoots.keySet().stream()
        .map(root -> new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(
            root, collections.get(root)))
        .toList();
  }

  /** Register membership and its label together, preserving the caller's duplicate policy. */
  synchronized boolean register(Path root, String collection, boolean onlyIfAbsent) {
    if (onlyIfAbsent && watchedRoots.containsKey(root)) return false;
    markNeverIndexed(root);
    setCollection(root, collection);
    return true;
  }

  synchronized void markIndexed(Path root) {
    watchedRoots.put(root, Instant.now(clock));
    walkErrors.remove(root);
    walkCompleted.add(root);
  }

  /** Registration / pre-walk: tracked but the walk has NOT completed yet. */
  synchronized void markNeverIndexed(Path root) {
    watchedRoots.put(root, WatchedRootsStore.NEVER_INDEXED);
    walkCompleted.remove(root);
  }

  /** Terminal walk outcome with zero admitted files: no timestamp, but the walk DID complete. */
  synchronized void markWalkedEmpty(Path root) {
    watchedRoots.put(root, WatchedRootsStore.NEVER_INDEXED);
    walkErrors.remove(root);
    walkCompleted.add(root);
  }

  synchronized void markWalkFailed(Path root, String error) {
    watchedRoots.put(root, WatchedRootsStore.NEVER_INDEXED);
    walkErrors.put(root, error != null ? error : "unknown");
    walkCompleted.add(root);
  }

  String getWalkError(Path root) {
    return walkErrors.get(root);
  }

  boolean isWalkCompleted(Path root) {
    return walkCompleted.contains(root);
  }

  /**
   * Tempdoc 626 §Axis-C — record the latest reconcile's delete-detection verification outcome for a
   * root. {@code true} marks the root UNVERIFIED (the cap-skipped scan); {@code false} clears it (a
   * later reconcile verified deletes). Transient, not persisted.
   */
  void setDeleteDetectionUnverified(Path root, boolean unverified) {
    if (unverified) {
      deleteDetectionUnverified.add(root);
    } else {
      deleteDetectionUnverified.remove(root);
      // Tempdoc 626 §Recency — clearing the unverified flag IS the verification event: a reconcile just
      // confirmed index↔disk correspondence for this root. Stamp the heartbeat via the injected clock.
      lastVerifiedAt.put(root, Instant.now(clock));
    }
  }

  boolean isDeleteDetectionUnverified(Path root) {
    return deleteDetectionUnverified.contains(root);
  }

  /** Tempdoc 626 §Recency — the last time a reconcile verified this root, or {@code null} if never. */
  Instant getLastVerifiedAt(Path root) {
    return lastVerifiedAt.get(root);
  }

  /** Orphan-prune outcome of a reconcile: {@code count} stale entries corrected at {@code atMs}. */
  record OrphanPrune(int count, long atMs) {}

  /**
   * Tempdoc 626 §Axis-C (drift-corrected) — record that a reconcile pruned {@code orphanCount} stale
   * index entries for {@code root} (a deletion the live watcher missed). The {@code atMs} advance is the
   * one-shot trigger the {@code IndexDriftHealthTap} dedups on.
   */
  void recordDriftCorrected(Path root, int orphanCount, long atMs) {
    if (orphanCount > 0) {
      driftCorrected.put(root, new OrphanPrune(orphanCount, atMs));
    }
  }

  /** The latest orphan-prune outcome for a root, or {@code null} if none recorded. */
  OrphanPrune getDriftCorrected(Path root) {
    return driftCorrected.get(root);
  }

  synchronized void removeRootAndNested(Path normalizedRoot) {
    watchedRoots.remove(normalizedRoot);
    walkCompleted.remove(normalizedRoot);
    deleteDetectionUnverified.remove(normalizedRoot);
    driftCorrected.remove(normalizedRoot);
    lastVerifiedAt.remove(normalizedRoot);
    collections.remove(normalizedRoot);
    collections.keySet().removeIf(p -> p.startsWith(normalizedRoot) && !p.equals(normalizedRoot));
    watchedRoots.keySet().removeIf(p -> p.startsWith(normalizedRoot) && !p.equals(normalizedRoot));
    walkCompleted.removeIf(p -> p.startsWith(normalizedRoot) && !p.equals(normalizedRoot));
    deleteDetectionUnverified.removeIf(p -> p.startsWith(normalizedRoot) && !p.equals(normalizedRoot));
    driftCorrected.keySet().removeIf(p -> p.startsWith(normalizedRoot) && !p.equals(normalizedRoot));
    lastVerifiedAt.keySet().removeIf(p -> p.startsWith(normalizedRoot) && !p.equals(normalizedRoot));
  }

  synchronized void persist() {
    rootsStore.persistRoots(watchedRoots, walkErrors, walkCompleted, collections);
  }

  /** Clears all watched roots and walk errors, then persists the empty state. */
  synchronized void clearAll() {
    watchedRoots.clear();
    walkErrors.clear();
    walkCompleted.clear();
    deleteDetectionUnverified.clear();
    driftCorrected.clear();
    lastVerifiedAt.clear();
    collections.clear();
    persist();
  }

  private static Instant normalizeTimestamp(Instant timestamp) {
    return timestamp == null ? WatchedRootsStore.NEVER_INDEXED : timestamp;
  }
}
