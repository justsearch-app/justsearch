/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of Worker-side watch subscriptions (tempdoc 418 Phase A scaffolding,
 * Phase B real-event-delivery wiring).
 *
 * <p>Phase A landed bookkeeping only; Phase B (this revision) injects an optional
 * {@link WorkerMethvinWatcher} so {@code watch}/{@code unwatch} drive real Methvin
 * subscriptions. Constructed without a watcher (legacy / test mode), it falls back to
 * registry-only bookkeeping; constructed with a watcher (production path via
 * {@code DefaultWorkerAppServices}), each {@code watch} also opens a Methvin
 * {@code DirectoryWatcher} feeding {@link io.justsearch.indexerworker.queue.JobQueue}.
 *
 * <p>Publication uses a concurrent map; each immutable registration witness owns a fair event
 * gate. Rewatch/unwatch first remove the published witness, then drain its issued effects before
 * cancelling the physical watcher. A second {@code watch} for one root therefore replaces only
 * that root's incarnation without serializing callbacks for unrelated roots.
 */
public final class RootWatcherRegistry {
  private static final Logger log = LoggerFactory.getLogger(RootWatcherRegistry.class);

  boolean isUserExcluded(Path file) {
    var patterns = io.justsearch.configuration.excludes.ExclusionPatterns.current();
    if (patterns.isEmpty()) return false;
    Path normalized = file.toAbsolutePath().normalize();
    for (Path root = normalized.getParent(); root != null; root = root.getParent()) {
      if ((subscriptions.containsKey(root) || awaitingVolume.containsKey(root))
          && patterns.isExcludedPath(root, normalized)) return true;
    }
    return false;
  }

  private final Map<Path, Subscription> subscriptions = new ConcurrentHashMap<>();
  /**
   * Task t-20261013-ef3c41, round 5 (S8): roots whose watch waits for the volume recorded for them
   * to be back at their path, with the collection each was asked for ({@code ""} for none).
   */
  private final Map<Path, String> awaitingVolume = new ConcurrentHashMap<>();
  /**
   * Round 8 (S8): the removal count at which each root was last unwatched, the person's removal,
   * so work that captured {@link #removalMark()} before it can tell the root was removed since,
   * even when it was added again in between.
   */
  private final Map<Path, Long> removedAt = new ConcurrentHashMap<>();
  private long removals;
  private final WorkerMethvinWatcher watcher;
  /**
   * Set once a reload's replacement registry took over this one's roots. Watch changes that still
   * reach this retired registry go to the successor, so none is lost and no removal is undone.
   */
  private RootWatcherRegistry successor;
  private long handoverMark;

  /** How a successor re-registers one handed-over root (a volume check may hold it instead). */
  @FunctionalInterface
  interface Rewatch {
    WatchResult watch(RootWatcherRegistry registry, Path root, String collection);
  }

  public RootWatcherRegistry() {
    this(null);
  }

  public RootWatcherRegistry(WorkerMethvinWatcher watcher) {
    this.watcher = watcher;
  }

  synchronized WatchResult watch(String rootPath, String collection) {
    if (successor != null) return successor.watch(rootPath, collection);
    Path normalized;
    try {
      normalized = Path.of(rootPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return new WatchResult(false, "WatchRootRequest.root_path is not a valid path: " + e.getMessage());
    }
    if (!Files.isDirectory(normalized)) {
      return new WatchResult(false, "WatchRootRequest.root_path is not a directory: " + normalized);
    }
    String coll = collection == null || collection.isBlank() ? null : collection;
    Subscription prior = subscriptions.remove(normalized);
    if (prior != null) retireAndDrain(prior);
    if (watcher != null) {
      // Invalidate the published incarnation before stopping it. A reconciliation holding the
      // prior object must fail closed even when the replacement has the same root and collection.
      watcher.unregisterRoot(normalized);
    }
    Object watcherEpoch = new Object();
    Subscription candidate;
    try (RootIdentityLease identityLease = RootIdentity.hold(normalized)) {
      candidate = new Subscription(normalized, coll, watcherEpoch, identityLease.identity());
      if (watcher != null && !watcher.registerRoot(candidate)) {
        return new WatchResult(false, "Failed to register filesystem watcher for root: " + normalized);
      }
      // Bind the physical watcher and immutable identity to the same observed directory
      // incarnation. The post-registration stable-ID check detects a path rebind; the held handle
      // does not exclude rename/replacement on every filesystem.
      identityLease.requireCurrent();
      subscriptions.put(normalized, candidate);
      if (watcher != null && !watcher.activateRoot(candidate)) {
        throw new IOException("Watcher startup-event drain could not be scheduled");
      }
    } catch (IOException e) {
      subscriptions.computeIfPresent(
          normalized,
          (ignored, current) -> {
            if (current.watcherEpoch() != watcherEpoch) return current;
            retireAndDrain(current);
            return null;
          });
      if (watcher != null) {
        boolean routed = watcher.handleWatcherException(normalized, watcherEpoch, e);
        watcher.unregisterRoot(normalized);
        if (!routed) {
          watcher.reportRegistrationFailure(normalized, e);
        }
      }
      return new WatchResult(
          false,
          "Failed to capture stable identity for watched root " + normalized + ": " + e.getMessage());
    }
    if (prior == null) {
      log.info(
          "Root watcher registered ({}): {}",
          watcher == null ? "registry-only" : "with worker watcher",
          normalized);
    } else {
      log.debug("Root watcher subscription replaced for {}", normalized);
    }
    awaitingVolume.remove(normalized);
    return new WatchResult(true, null);
  }

  /**
   * Holds the watch of {@code root} until the volume recorded for it is back at its path (task
   * t-20261013-ef3c41, round 5, S8): the folder there now is another volume, whose files must not
   * be indexed into the root, so no watcher is registered on it. A watch the root already had is
   * retired. {@link #resumeAwaitingVolume} registers it once a walk or sync finds the volume back.
   */
  synchronized WatchResult watchWhenVolumeReturns(Path root, String collection) {
    if (successor != null) return successor.watchWhenVolumeReturns(root, collection);
    Path normalized = root.toAbsolutePath().normalize();
    Subscription prior = subscriptions.remove(normalized);
    if (prior != null) {
      retireAndDrain(prior);
      if (watcher != null) {
        watcher.unregisterRoot(normalized);
      }
    }
    awaitingVolume.put(normalized, collection == null ? "" : collection);
    log.info("Root watcher waits for the volume first seen at {}; another volume is there now",
        normalized);
    return new WatchResult(false, "ROOT_NOT_DIRECTORY: " + normalized
        + " is not on the volume it was first seen on; watching starts when that volume is back");
  }

  /** Whether the watch of {@code root} is held until its recorded volume is back. */
  boolean awaitsVolume(Path root) {
    return awaitingVolume.containsKey(root.toAbsolutePath().normalize());
  }

  /**
   * Registers the held watch of {@code root}, if there is one, now that the volume recorded for it
   * is back at its path, and queues the writer's pending watcher receipts behind it, so a receipt
   * that waited for this root is applied without a watch call. Returns whether a watch was
   * registered.
   */
  synchronized boolean resumeAwaitingVolume(Path root) {
    if (successor != null) return successor.resumeAwaitingVolume(root);
    Path normalized = root.toAbsolutePath().normalize();
    String collection = awaitingVolume.get(normalized);
    if (collection == null) {
      return false;
    }
    WatchResult result = watch(normalized.toString(), collection);
    if (result.watching()) {
      log.info("Root watcher resumed: the volume first seen at {} is back", normalized);
      if (watcher != null) {
        watcher.applyPendingReceiptsLater();
      }
    }
    return result.watching();
  }

  synchronized boolean unwatch(String rootPath) {
    if (successor != null) return successor.unwatch(rootPath);
    Path normalized;
    try {
      normalized = Path.of(rootPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return false;
    }
    removedAt.put(normalized, ++removals);
    if (watcher != null) {
      watcher.forgetRoot(normalized);
    }
    boolean wasAwaiting = awaitingVolume.remove(normalized) != null;
    Subscription removed = subscriptions.remove(normalized);
    if (removed != null) {
      retireAndDrain(removed);
      if (watcher != null) {
        watcher.unregisterRoot(normalized);
      }
      log.info(
          "Root watcher unregistered ({}): {}",
          watcher == null ? "registry-only" : "with worker watcher",
          normalized);
      return true;
    }
    return wasAwaiting;
  }

  /** The current removal count; {@link #removedSince} compares against it. */
  synchronized long removalMark() {
    return removals;
  }

  /** Whether {@code root} was unwatched (removed by the person) after {@code mark} was taken. */
  boolean removedSince(Path root, long mark) {
    Long at = removedAt.get(root.toAbsolutePath().normalize());
    return at != null && at > mark;
  }

  /**
   * {@link #watch} unless {@code root} was removed after {@code mark}, checked under the same
   * monitor as {@link #unwatch}, so a removed root never gets its watcher back from work that
   * started before the removal (round 8, S8).
   */
  synchronized WatchResult watchUnlessRemovedSince(Path root, String collection, long mark) {
    if (removedSince(root, mark)) {
      return new WatchResult(false, "ROOT_REMOVED: " + root + " was removed");
    }
    if (successor != null) {
      // Any removal since the handover counts: a removed root is never re-watched from here.
      return successor.watchUnlessRemovedSince(root, collection, handoverMark);
    }
    return watch(root.toString(), collection);
  }

  /** The registry that currently owns this one's roots: itself, or its latest successor. */
  synchronized RootWatcherRegistry current() {
    return successor == null ? this : successor.current();
  }

  /**
   * Re-registers every watched root, and every root held for its volume, in {@code next}, with its
   * collection, then retires this registry in its favour. A root removed before the handover is
   * not here and is never revived; one removed after it is removed from {@code next}. A root whose
   * watch cannot be registered now is held in {@code next} until its volume is back, not dropped.
   */
  synchronized void handOverTo(RootWatcherRegistry next, Rewatch rewatch) {
    if (successor != null) {
      successor.handOverTo(next, rewatch);
      return;
    }
    if (next == this) throw new IllegalArgumentException("A registry cannot succeed itself");
    for (Subscription watched : List.copyOf(subscriptions.values())) {
      if (!rewatch.watch(next, watched.root(), watched.collection()).watching()
          && !next.awaitsVolume(watched.root())) {
        next.holdUntilVolumeReturns(watched.root(), watched.collection());
      }
    }
    awaitingVolume.forEach(next::holdUntilVolumeReturns);
    handoverMark = next.removalMark();
    successor = next;
  }

  private synchronized void holdUntilVolumeReturns(Path root, String collection) {
    if (subscriptions.containsKey(root)) return;
    awaitingVolume.putIfAbsent(root, collection == null ? "" : collection);
  }

  @SuppressWarnings("unused") // RootWatcherRegistryTest only; frozen in UnreferencedCodeTest's store.
  Set<Path> watchedRoots() {
    return Collections.unmodifiableSet(subscriptions.keySet());
  }

  /**
   * Round 8 (refined decision 2): whether a watched root, or a root whose watch is held for its
   * volume, is at or above {@code folder}, so the folder is answered for by that declared root.
   */
  boolean declaresRootCovering(Path folder) {
    if (folder == null) return false;
    Path normalized = folder.toAbsolutePath().normalize();
    for (Path root : subscriptions.keySet()) {
      if (normalized.startsWith(root)) return true;
    }
    for (Path root : awaitingVolume.keySet()) {
      if (normalized.startsWith(root)) return true;
    }
    return false;
  }

  /** Immutable registration snapshot used by a root reconciliation admission. */
  Subscription subscription(Path root) {
    if (root == null) return null;
    return subscriptions.get(root.toAbsolutePath().normalize());
  }

  /**
   * Returns whether {@code expected} is still the exact published subscription and its physical
   * watcher incarnation remains healthy. Record value equality is deliberately insufficient: a
   * same-value rewatch is a new registration and cannot certify work admitted by the old one.
   */
  boolean isCurrentAndActive(Subscription expected) throws IOException {
    if (expected == null) return false;
    Subscription current = subscriptions.get(expected.root());
    if (current != expected) return false;
    expected.rootIdentity().requireCurrent(expected.root());
    return watcher == null || watcher.isActive(expected.root(), expected.watcherEpoch());
  }

  /**
   * Acquires the per-incarnation event lease after mutation admission. The map check happens while
   * the subscription's read lease is held, so removal followed by the writer-side drain is a final
   * fence: an old callback either completes before rewatch/unwatch returns or observes itself as
   * stale and performs no effect. No registry monitor is involved in this path.
   */
  EventLease enterEvent(Subscription expected) throws IOException {
    if (expected == null) return null;
    expected.eventGate.readLock().lock();
    boolean retained = false;
    try {
      if (!expected.live || subscriptions.get(expected.root()) != expected) return null;
      expected.rootIdentity().requireCurrent(expected.root());
      retained = true;
      return new EventLease(expected);
    } finally {
      if (!retained) expected.eventGate.readLock().unlock();
    }
  }

  /** Bounded reason code for installed-run diagnostics; never includes the watched path. */
  String currentnessFailure(Subscription expected) {
    if (expected == null) return "MISSING_SUBSCRIPTION";
    if (subscriptions.get(expected.root()) != expected) return "REGISTRATION_CHANGED";
    try {
      expected.rootIdentity().requireCurrent(expected.root());
    } catch (IOException changed) {
      return "ROOT_IDENTITY_UNAVAILABLE_OR_CHANGED";
    }
    if (watcher != null) {
      String watcherStatus = watcher.activityStatus(expected.root(), expected.watcherEpoch());
      if (!"ACTIVE".equals(watcherStatus)) return "WATCHER_" + watcherStatus;
    }
    return "CURRENT";
  }

  private void retireAndDrain(Subscription subscription) {
    subscription.eventGate.writeLock().lock();
    try {
      subscription.live = false;
    } finally {
      subscription.eventGate.writeLock().unlock();
    }
  }

  /**
   * Immutable registration snapshot used by a root reconciliation admission. Both the opaque
   * watcher epoch and stable native root identity belong to this one successful publication.
   */
  public static final class Subscription {
    private final Path root;
    private final String collection;
    private final Object watcherEpoch;
    private final RootIdentity rootIdentity;
    private final ReentrantReadWriteLock eventGate = new ReentrantReadWriteLock(true);
    private volatile boolean live = true;

    Subscription(Path root, String collection, Object watcherEpoch, RootIdentity rootIdentity) {
      this.root = root;
      this.collection = collection;
      this.watcherEpoch = watcherEpoch;
      this.rootIdentity = rootIdentity;
    }

    Path root() { return root; }
    String collection() { return collection; }
    Object watcherEpoch() { return watcherEpoch; }
    RootIdentity rootIdentity() { return rootIdentity; }
  }

  static final class EventLease implements AutoCloseable {
    private final Subscription subscription;
    private final Thread holder = Thread.currentThread();
    private boolean closed;

    private EventLease(Subscription subscription) {
      this.subscription = subscription;
    }

    @Override
    public void close() {
      if (Thread.currentThread() != holder) {
        throw new IllegalStateException("Watcher event lease must close on its owning thread");
      }
      if (closed) return;
      closed = true;
      subscription.eventGate.readLock().unlock();
    }
  }

  record WatchResult(boolean watching, String errorMessage) {}
}
