/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
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

  private final Map<Path, Subscription> subscriptions = new ConcurrentHashMap<>();
  private final WorkerMethvinWatcher watcher;

  public RootWatcherRegistry() {
    this(null);
  }

  public RootWatcherRegistry(WorkerMethvinWatcher watcher) {
    this.watcher = watcher;
  }

  synchronized WatchResult watch(String rootPath, String collection) {
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
    return new WatchResult(true, null);
  }

  synchronized boolean unwatch(String rootPath) {
    Path normalized;
    try {
      normalized = Path.of(rootPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return false;
    }
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
    return false;
  }

  @SuppressWarnings("unused") // RootWatcherRegistryTest only — see UnreferencedCodeTest exemption.
  Set<Path> watchedRoots() {
    return Collections.unmodifiableSet(subscriptions.keySet());
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
