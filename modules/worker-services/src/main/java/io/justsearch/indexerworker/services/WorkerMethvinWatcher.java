/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHasher;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 418 Phase B — Worker-side Methvin file watcher.
 *
 * <p>Replaces the Head-side {@code MethvinWatcherStrategy} (modules/app-indexing) with a watcher
 * that lives in the same process as {@link JobQueue} — events feed straight into the queue with
 * no IPC hop and no per-event in-process port submitBatch. The watcher is registered per root via an immutable
 * {@link RootWatcherRegistry.Subscription}; deregistration via {@link #unregisterRoot(Path)} closes
 * the underlying Methvin {@code DirectoryWatcher} for that root.
 *
 * <p>Event handling:
 * <ul>
 *   <li>{@code CREATE} / {@code MODIFY} → {@link JobQueue#enqueue(List, String)} with the
 *       collection from the watch subscription. {@code WorkerIngestionAuthority} applies its
 *       admission rules when the loop later picks the path up.
 *   <li>{@code DELETE} → forwarded to the admission-aware delete route only while the captured
 *       registration and stable root identity remain current.
 *   <li>{@code OVERFLOW} → schedules an immediate forced reconciliation under the same witness.
 * </ul>
 * Every event carries its immutable registration witness through mutation admission and a
 * per-incarnation drainable event lease. Stale epochs are ignored; routing or identity failures
 * invalidate migration replay certainty.
 *
 * <p>Telemetry: per-event-kind counters under {@code index.watcher.events_total} with a
 * {@code component=worker_watcher} tag, paralleling the Head-side metric so dashboards can
 * compare event volumes during the cutover soak window.
 *
 * <p>Threading: each root gets its own {@code DirectoryWatcher} which manages its own thread
 * via {@code watchAsync()}. {@link #close()} cancels all futures and closes all watchers.
 *
 * <p>Linux note: inotify limit exhaustion is treated as a soft failure — the registration
 * returns without throwing, the periodic sync eventually catches changes for that root.
 */
public final class WorkerMethvinWatcher implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(WorkerMethvinWatcher.class);

  /** Events/second crossing this per-root rate schedules a reconcile (heuristic missed-event net). */
  static final int BURST_THRESHOLD = 100;

  /** Delay before a burst-triggered reconcile, so a spike coalesces into one walk. */
  static final int BURST_RECONCILE_DELAY_SECONDS = 5;
  static final int STARTUP_EVENT_BUFFER_LIMIT = 4_096;

  private final Consumer<RuntimeException> routingFailureSink;
  private final EventRouter eventRouter;
  private final BiConsumer<RootWatcherRegistry.Subscription, String> witnessedDeletePathSink;
  private final WitnessedUpsertSink witnessedUpsertPathSink;
  private final WorkerWatcherMetricCatalog watcherCatalog;
  // Tempdoc 626 §Axis-A — overflow/burst recovery relocated onto the Worker watcher so the Head
  // watcher could be retired. reconcileSink runs the in-process reconciler (force flag); it is
  // dispatched off the watcher's event thread via reconcileExecutor so a long walk never blocks
  // event delivery (mirrors the Head's dedicated sync-scheduler thread).
  private final BiConsumer<Path, Boolean> reconcileSink;
  private final WorkerBurstDetector burstDetector = new WorkerBurstDetector();
  private final ScheduledExecutorService reconcileExecutor;
  private final Map<Path, Boolean> pendingReconciliations = new ConcurrentHashMap<>();
  private final Map<Path, RootSubscription> watchers = new ConcurrentHashMap<>();
  private final Map<Path, CompletableFuture<Void>> watchFutures = new ConcurrentHashMap<>();
  private final Map<Path, Object> failedEpochs = new ConcurrentHashMap<>();

  public enum Kind {
    CREATE,
    MODIFY,
    DELETE,
    OVERFLOW
  }

  @FunctionalInterface
  public interface EventRouter {
    void route(RootWatcherRegistry.Subscription witness, Runnable effect);
  }

  @FunctionalInterface
  public interface WitnessedUpsertSink {
    void accept(RootWatcherRegistry.Subscription witness, String collection, Path path);
  }

  /**
   * Constructs the Worker-side watcher. Tempdoc 418 B-H.4 introduced the {@code deletePathSink};
   * tempdoc 418 Phase C sub-commit A (Slice D, 2026-04-25) deleted the legacy 2-arg
   * back-compat constructor so production callers cannot accidentally drop DELETE events.
   * Tests pass an explicit sink (no-op for create/modify-focused cases, recording sink for
   * delete-focused cases).
   *
   * <p>Tempdoc 417 → 410 merge: telemetry now flows through {@link WorkerWatcherMetricCatalog}
   * (typed) instead of the legacy {@code Telemetry.Counter} per-kind map.
   */
  public WorkerMethvinWatcher(
      EngineExecutorRegistry.Registration reconcileRegistration,
      JobQueue jobQueue,
      WorkerWatcherMetricCatalog watcherCatalog,
      Consumer<String> deletePathSink,
      BiConsumer<Path, Boolean> reconcileSink) {
    this(reconcileRegistration, jobQueue, watcherCatalog, deletePathSink, reconcileSink,
        (collection, path) -> jobQueue.enqueueEntries(List.of(entryForLiveEvent(path)), collection),
        ignored -> {}, (ignored, effect) -> effect.run(),
        (ignored, path) -> deletePathSink.accept(path),
        (witness, collection, path) -> jobQueue.enqueueEntries(
            List.of(entryForLiveEvent(path).withinRoot(witness.root())), collection));
  }

  /** The composed Worker supplies an admission-aware sink across generation cutover. */
  public WorkerMethvinWatcher(
      EngineExecutorRegistry.Registration reconcileRegistration,
      JobQueue jobQueue,
      WorkerWatcherMetricCatalog watcherCatalog,
      Consumer<String> deletePathSink,
      BiConsumer<Path, Boolean> reconcileSink,
      BiConsumer<String, Path> upsertPathSink) {
    this(reconcileRegistration, jobQueue, watcherCatalog, deletePathSink, reconcileSink,
        upsertPathSink, ignored -> {});
  }

  /** Production cutover refuses promotion after any watcher event failed to reach its route. */
  public WorkerMethvinWatcher(
      EngineExecutorRegistry.Registration reconcileRegistration,
      JobQueue jobQueue,
      WorkerWatcherMetricCatalog watcherCatalog,
      Consumer<String> deletePathSink,
      BiConsumer<Path, Boolean> reconcileSink,
      BiConsumer<String, Path> upsertPathSink,
      Consumer<RuntimeException> routingFailureSink) {
    this(reconcileRegistration, jobQueue, watcherCatalog, deletePathSink, reconcileSink,
        upsertPathSink, routingFailureSink, (ignored, effect) -> effect.run());
  }

  /** Production wiring supplies the mutation-first registration-witness router. */
  public WorkerMethvinWatcher(
      EngineExecutorRegistry.Registration reconcileRegistration,
      JobQueue jobQueue,
      WorkerWatcherMetricCatalog watcherCatalog,
      Consumer<String> deletePathSink,
      BiConsumer<Path, Boolean> reconcileSink,
      BiConsumer<String, Path> upsertPathSink,
      Consumer<RuntimeException> routingFailureSink,
      EventRouter eventRouter) {
    this(reconcileRegistration, jobQueue, watcherCatalog, deletePathSink, reconcileSink,
        upsertPathSink, routingFailureSink, eventRouter,
        (ignored, path) -> deletePathSink.accept(path),
        (ignored, collection, path) -> upsertPathSink.accept(collection, path));
  }

  /** Production delete routing retains the immutable registration through the mutation fence. */
  public WorkerMethvinWatcher(
      EngineExecutorRegistry.Registration reconcileRegistration,
      JobQueue jobQueue,
      WorkerWatcherMetricCatalog watcherCatalog,
      Consumer<String> deletePathSink,
      BiConsumer<Path, Boolean> reconcileSink,
      BiConsumer<String, Path> upsertPathSink,
      Consumer<RuntimeException> routingFailureSink,
      EventRouter eventRouter,
      BiConsumer<RootWatcherRegistry.Subscription, String> witnessedDeletePathSink,
      WitnessedUpsertSink witnessedUpsertPathSink) {
    Objects.requireNonNull(jobQueue, "jobQueue");
    Objects.requireNonNull(upsertPathSink, "upsertPathSink");
    Objects.requireNonNull(deletePathSink, "deletePathSink");
    this.routingFailureSink = Objects.requireNonNull(routingFailureSink, "routingFailureSink");
    this.eventRouter = Objects.requireNonNull(eventRouter, "eventRouter");
    this.witnessedDeletePathSink =
        Objects.requireNonNull(witnessedDeletePathSink, "witnessedDeletePathSink");
    this.witnessedUpsertPathSink =
        Objects.requireNonNull(witnessedUpsertPathSink, "witnessedUpsertPathSink");
    this.watcherCatalog = watcherCatalog == null ? WorkerWatcherMetricCatalog.noop() : watcherCatalog;
    this.reconcileSink = reconcileSink == null ? (root, force) -> {} : reconcileSink;
    this.reconcileExecutor =
        Objects.requireNonNull(reconcileRegistration, "reconcileRegistration")
            .openScheduled(
                r -> {
                  Thread t = new Thread(r, "worker-watcher-reconcile");
                  t.setDaemon(true);
                  return t;
                });
  }

  /**
   * Test-only convenience: no reconcile sink (overflow/burst recovery becomes a no-op). Production
   * wiring uses the 4-arg constructor so OVERFLOW/burst recovery is never silently dropped.
   */
  WorkerMethvinWatcher(
      EngineExecutorRegistry.Registration reconcileRegistration,
      JobQueue jobQueue,
      WorkerWatcherMetricCatalog watcherCatalog,
      Consumer<String> deletePathSink) {
    this(reconcileRegistration, jobQueue, watcherCatalog, deletePathSink, null);
  }

  /**
   * Registers a root using the registry-owned immutable witness. Idempotent: if the root is
   * already being watched, the prior subscription is closed and replaced. Callers must publish
   * the registry subscription and then invoke {@link #activateRoot} after registration succeeds.
   */
  synchronized boolean registerRoot(RootWatcherRegistry.Subscription registration) {
    Objects.requireNonNull(registration, "registration");
    Path normalized = registration.root();
    Object watcherEpoch = registration.watcherEpoch();
    closeWatcherFor(normalized);
    log.info("Worker watcher registering root: {} (collection={})", normalized,
        registration.collection());
    try {
      AtomicReference<RootSubscription> subscriptionRef = new AtomicReference<>();
      DirectoryChangeListener listener =
          new DirectoryChangeListener() {
            @Override
            public void onEvent(DirectoryChangeEvent event) {
              RootSubscription current = subscriptionRef.get();
              if (current != null) current.dispatchOrBuffer(event);
            }

            @Override
            public void onException(Exception failure) {
              if (!routeMethvinNullOverflow(normalized, watcherEpoch, failure)) {
                handleWatcherException(normalized, watcherEpoch, failure);
              }
            }
          };
      DirectoryWatcher watcher =
          DirectoryWatcher.builder()
              .path(normalized)
              .fileHasher(FileHasher.LAST_MODIFIED_TIME)
              .listener(listener)
              .build();
      RootSubscription subscription =
          new RootSubscription(registration, watcher);
      subscriptionRef.set(subscription);
      watchers.put(normalized, subscription);
      CompletableFuture<Void> future = watcher.watchAsync();
      watchFutures.put(normalized, future);
      CompletableFuture<Void> completionObserver = future.whenComplete(
          (ignored, failure) -> {
            RuntimeException routed =
                failure == null
                    ? new IllegalStateException("Worker watcher stopped unexpectedly for " + normalized)
                    : new IllegalStateException(
                        "Worker watcher failed unexpectedly for " + normalized, failure);
            handleWatcherException(normalized, watcherEpoch, routed);
          });
      // Retaining a named stage documents that completion is observed by the lifecycle callback.
      Objects.requireNonNull(completionObserver);
      boolean active = isActive(normalized, watcherEpoch);
      if (!active) {
        closeWatcherFor(normalized);
      }
      return active;
    } catch (IOException e) {
      closeWatcherFor(normalized);
      routingFailureSink.accept(
          new IllegalStateException("Failed to register worker watcher for " + normalized, e));
      if (isInotifyExhausted(e)) {
        log.warn(
            "Inotify limit reached for {}: file changes may not be detected; periodic sync will catch up",
            normalized);
        return false;
      }
      log.warn("Failed to register worker watcher for {}: {}", normalized, e.getMessage());
      return false;
    } catch (RuntimeException failure) {
      closeWatcherFor(normalized);
      routingFailureSink.accept(
          new IllegalStateException("Failed to start worker watcher for " + normalized, failure));
      log.warn(
          "Worker watcher startup failed (failureType={})",
          failure.getClass().getSimpleName());
      return false;
    }
  }

  /** Publishes the registration boundary and schedules startup-event drain without routing inline. */
  synchronized boolean activateRoot(RootWatcherRegistry.Subscription registration) {
    RootSubscription current = watchers.get(registration.root());
    if (current == null || current.registration() != registration) {
      throw new IllegalStateException("Cannot activate a non-current watcher registration");
    }
    if (!current.publish()) return false;
    try {
      reconcileExecutor.execute(current::drainBufferedEvents);
      return true;
    } catch (RuntimeException rejected) {
      current.discardBufferedEvents();
      handleWatcherException(registration.root(), registration.watcherEpoch(), rejected);
      return false;
    }
  }

  /** Unregisters a watcher by root path. Returns true if a subscription was found and closed. */
  synchronized boolean unregisterRoot(Path root) {
    Objects.requireNonNull(root, "root");
    Path normalized = root.toAbsolutePath().normalize();
    return closeWatcherFor(normalized);
  }

  private boolean closeWatcherFor(Path normalized) {
    RootSubscription prior = watchers.remove(normalized);
    CompletableFuture<Void> priorFuture = watchFutures.remove(normalized);
    failedEpochs.remove(normalized);
    burstDetector.removeRoot(normalized);
    if (priorFuture != null) {
      priorFuture.cancel(true);
    }
    if (prior != null) {
      prior.discardBufferedEvents();
      try {
        prior.watcher().close();
      } catch (IOException e) {
        log.debug("Failed to close prior worker watcher for {}: {}", normalized, e.getMessage());
      }
      return true;
    }
    return false;
  }

  /** True only for the current, non-failed incarnation with a live future and open watcher. */
  boolean isActive(Path root, Object watcherEpoch) {
    return "ACTIVE".equals(activityStatus(root, watcherEpoch));
  }

  /** Bounded lifecycle status for diagnostics; contains no root or exception message. */
  String activityStatus(Path root, Object watcherEpoch) {
    if (root == null || watcherEpoch == null) return "INVALID_WITNESS";
    Path normalized = root.toAbsolutePath().normalize();
    RootSubscription subscription = watchers.get(normalized);
    CompletableFuture<Void> future = watchFutures.get(normalized);
    if (subscription == null) return "MISSING_WATCHER";
    if (subscription.registration().watcherEpoch() != watcherEpoch) return "EPOCH_CHANGED";
    if (failedEpochs.get(normalized) == watcherEpoch) return "FAILED_EPOCH";
    if (future == null) return "MISSING_FUTURE";
    if (future.isCancelled()) return "FUTURE_CANCELLED";
    if (future.isDone()) return "FUTURE_COMPLETED";
    if (subscription.watcher().isClosed()) return "WATCHER_CLOSED";
    return "ACTIVE";
  }

  /**
   * Invalidates only the matching current incarnation. This callback deliberately never acquires
   * the registry or watcher monitor; replacement removes map entries before cancellation, so an
   * intentional stale callback cannot poison its successor.
   */
  boolean handleWatcherException(Path root, Object watcherEpoch, Exception failure) {
    Path normalized = root.toAbsolutePath().normalize();
    AtomicBoolean currentEpoch = new AtomicBoolean();
    AtomicBoolean firstFailure = new AtomicBoolean();
    watchers.computeIfPresent(
        normalized,
        (ignored, current) -> {
          if (current.registration().watcherEpoch() == watcherEpoch) {
            currentEpoch.set(true);
            firstFailure.set(failedEpochs.put(normalized, watcherEpoch) != watcherEpoch);
          }
          return current;
        });
    if (!currentEpoch.get()) return false;
    if (firstFailure.get()) {
      log.warn(
          "Worker watcher incarnation ended unexpectedly (failureType={})",
          failure.getClass().getSimpleName(),
          failure);
      routingFailureSink.accept(
          failure instanceof RuntimeException runtime
              ? runtime
              : new IllegalStateException(
                  "Worker watcher failed unexpectedly for " + normalized, failure));
    }
    return true;
  }

  /**
   * Methvin 0.19.1 passes a null native OVERFLOW context to onEvent, which calls
   * ConcurrentSkipListMap.get(null) before notifying its listener. Its event loop catches that
   * exception and continues watching. Restore the lost OVERFLOW notification for this exact
   * dependency failure; any other watcher exception still invalidates the incarnation.
   */
  boolean routeMethvinNullOverflow(Path root, Object watcherEpoch, Exception failure) {
    if (!isMethvinNullOverflow(failure)) return false;
    RootSubscription current = watchers.get(root.toAbsolutePath().normalize());
    if (current == null || current.registration().watcherEpoch() != watcherEpoch) return true;
    try {
      current.dispatchOrBuffer(new DirectoryChangeEvent(
          DirectoryChangeEvent.EventType.OVERFLOW, false, root, null, 1, root));
    } catch (RuntimeException routingFailure) {
      handleWatcherException(root, watcherEpoch, routingFailure);
    }
    return true;
  }

  private static boolean isMethvinNullOverflow(Exception failure) {
    if (!(failure instanceof NullPointerException)) return false;
    boolean nullMapRead = false;
    boolean methvinEvent = false;
    boolean methvinLoop = false;
    for (StackTraceElement frame : failure.getStackTrace()) {
      if ("java.util.concurrent.ConcurrentSkipListMap".equals(frame.getClassName())
          && "get".equals(frame.getMethodName())) nullMapRead = true;
      if ("io.methvin.watcher.DirectoryWatcher".equals(frame.getClassName())) {
        if ("onEvent".equals(frame.getMethodName()) && frame.getLineNumber() == 424) {
          methvinEvent = true;
        }
        if ("runEventLoop".equals(frame.getMethodName())) methvinLoop = true;
      }
    }
    return nullMapRead && methvinEvent && methvinLoop;
  }

  /** Records a failed registration step that occurred outside Methvin after startup. */
  void reportRegistrationFailure(Path root, Exception failure) {
    routingFailureSink.accept(
        failure instanceof RuntimeException runtime
            ? runtime
            : new IllegalStateException("Worker watcher registration failed for " + root, failure));
  }

  private void handleEvent(RootWatcherRegistry.Subscription witness, DirectoryChangeEvent event) {
    Kind kind = mapEventKind(event.eventType());
    if (kind == null) return;
    dispatchEvent(witness, kind, event.path());
  }

  /** Routes one witnessed event through the production event path. */
  void dispatchEvent(RootWatcherRegistry.Subscription witness, Kind kind, Path path) {
    eventRouter.route(
        witness,
        () -> {
          watcherCatalog.eventsTotal.increment(WorkerWatcherEventTags.of(kind));
          log.trace("Worker watcher event: {} {}", kind, path);
          switch (kind) {
            case CREATE, MODIFY -> handleUpsert(witness, path);
            case DELETE -> {
              handleDelete(witness, path);
              maybeScheduleBurstReconcile(witness.root());
            }
            case OVERFLOW -> handleOverflow(witness.root(), path);
          }
        });
  }

  private void handleUpsert(RootWatcherRegistry.Subscription witness, Path path) {
    if (io.justsearch.indexerworker.ingest.IngestionSkipPolicy.shouldSkipWithinRoot(
        path, witness.root())) return;
    try {
      witnessedUpsertPathSink.accept(witness, witness.collection(), path);
    } catch (RuntimeException failure) {
      routingFailureSink.accept(failure);
      log.warn("Worker watcher enqueue failed for {}: {}", path, failure.getMessage());
    }
    maybeScheduleBurstReconcile(witness.root());
  }

  /**
   * Builds the sized queue entry for a single-file watcher event, recording a size of 0 as
   * <em>unknown</em> rather than as a known zero.
   *
   * <p>A live CREATE/MODIFY notification races the writer: the OS reports the entry the moment it
   * appears, which can be before the first byte is flushed, so {@code Files.size} legitimately
   * returns 0 for a file that is about to be large. Recording that 0 as a KNOWN size is the defect
   * — {@code JobQueue.pendingBytes()} then sums it into {@code knownBytes} as "0 bytes of work"
   * instead of counting it in {@code unknownSizeJobs}, which is the tri-state 813 Slice B built so a
   * consumer can tell "no work left" from "work left whose weight is unknown". A mid-write 4 GB file
   * understates the backlog by 4 GB with nothing marking the estimate as incomplete.
   *
   * <p>A 0 observed on a live event is therefore not knowledge, it is "looked too early".
   *
   * <p>The two encodings are NOT interchangeable, and the difference is deliberate. {@code
   * unknownSizeJobs} is a hard suppression input, not a footnote: {@code indexingProgress.ts}
   * withdraws the byte estimate entirely when {@code unknownSizeJobs * 2 > jobsPending}. So a
   * copy-in whose in-flight CREATEs come to outnumber half the pending backlog shows
   * "N files remaining" with no byte figure; a sequential copy against a deep existing backlog
   * stays under the ratio and keeps its estimate. The suppression is conditional on that ratio,
   * not on "a copy is happening".
   *
   * <p>That is the intended 813 behaviour ("an absent estimate is an absent segment"): withholding
   * a number beats showing one that understates a mid-write backlog by gigabytes with nothing
   * marking it incomplete.
   *
   * <p>How long the unknown lasts, stated honestly. Nothing re-stats at processing time — {@code
   * size_bytes} is written only by the enqueue path — so the row is healed by the next watcher
   * event for that path ({@code INSERT OR REPLACE} restates it), not by a later read. Two cases
   * make that window longer than "a moment":
   * <ul>
   *   <li>{@link FileHasher#LAST_MODIFIED_TIME} (see {@code registerRoot}) suppresses a MODIFY
   *       whose mtime matches the recorded one, and Windows updates an open file's mtime lazily —
   *       typically at close. For a large copy the healing MODIFY therefore tends to arrive when
   *       the copy FINISHES, so the unknown window is roughly the file's whole copy duration, not
   *       a few seconds.
   *   <li>If no further event ever arrives — a file created empty and left empty, or a create and
   *       write that land inside one mtime tick — the row keeps {@code UNKNOWN} for as long as it
   *       stays PENDING/PROCESSING, i.e. until it is indexed and leaves the aggregate entirely.
   * </ul>
   * Both are accepted: an unknown that persists is still a true statement about what this
   * producer observed, whereas the known {@code 0} it replaces was false for the entire window.
   *
   * <p>Scoped to live events on purpose: the bulk-walk producers ({@code WorkerScanOps},
   * {@code SyncDirectoryOps}) construct from {@code attrs.size()} on a settled file, where a 0 is a
   * true fact and stays a known 0. One empty file can therefore be encoded two ways depending on
   * which producer found it — that asymmetry tracks how trustworthy the observation was, which is
   * the distinction worth keeping.
   *
   * <p>Deliberately not a re-stat/settle loop: that would block the watcher's event-delivery thread
   * on wall-clock time (the reason reconciles are already dispatched off-thread here) and would
   * still be timing-dependent, trading a wrong value for a flaky one.
   */
  static JobQueue.EnqueueEntry entryForLiveEvent(Path path) {
    JobQueue.EnqueueEntry stated = JobQueue.EnqueueEntry.stat(path);
    return new JobQueue.EnqueueEntry(path,
        stated.sizeBytes() == 0L ? JobQueue.UNKNOWN_SIZE_BYTES : stated.sizeBytes(),
        CallContext.none().provenance());
  }

  /**
   * OVERFLOW is a deterministic OS signal that events were dropped — the index is known stale, so
   * reconcile immediately with force=true (tempdoc 626 §Axis-A; mirrors the retired Head-side
   * {@code WatcherEventOps.handleOverflow}). The reconcile runs off the event thread so a long walk
   * never blocks event delivery. Package-private for deterministic unit-testing.
   */
  void handleOverflow(Path root, Path path) {
    log.warn("Worker watcher buffer overflow for {} — triggering reconcile (force=true)", path);
    submitReconcile(root, /* force= */ true, /* delaySeconds= */ 0);
  }

  /**
   * Heuristic missed-event net: when a per-root event spike crosses {@link #BURST_THRESHOLD}/sec,
   * schedule a (non-forced) reconcile after a short coalescing delay. force=false so the reconciler
   * yields to active user search; this catches OS-dropped events that arrive without an OVERFLOW
   * (e.g. Windows DELETE misses during bulk operations).
   */
  private void maybeScheduleBurstReconcile(Path root) {
    if (root != null && burstDetector.recordEvent(root, BURST_THRESHOLD)) {
      log.info(
          "Worker watcher burst (>{} events/s) for {} — scheduling reconcile in {}s",
          BURST_THRESHOLD,
          root,
          BURST_RECONCILE_DELAY_SECONDS);
      submitReconcile(root, /* force= */ false, BURST_RECONCILE_DELAY_SECONDS);
    }
  }

  private void submitReconcile(Path root, boolean force, int delaySeconds) {
    if (root == null) return;
    try {
      var _ =
          reconcileExecutor.schedule(
              () -> {
                try {
                  reconcileSink.accept(root, force);
                } catch (RuntimeException e) {
                  routingFailureSink.accept(e);
                  log.warn(
                      "Worker watcher reconcile failed for {} (force={}): {}",
                      root,
                      force,
                      e.getMessage());
                } finally {
                  retryPendingReconciliations();
                }
              },
              delaySeconds,
              TimeUnit.SECONDS);
    } catch (EngineExecutorRejectedException e) {
      if (e.reason() == Reason.CLOSED) {
        log.debug("Worker watcher reconcile rejected during close for {}", root);
        return;
      }
      pendingReconciliations.merge(root, force, (left, right) -> left || right);
      log.warn(
          "Worker watcher reconcile retained after executor capacity refusal for {} "
              + "(force={}, reason={})",
          root,
          force,
          e.reason());
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // A non-registry rejection can only be the concrete executor closing.
      log.debug("Worker watcher reconcile rejected (shutting down) for {}", root);
    }
  }

  private void retryPendingReconciliations() {
    for (Map.Entry<Path, Boolean> pending : List.copyOf(pendingReconciliations.entrySet())) {
      if (pendingReconciliations.remove(pending.getKey(), pending.getValue())) {
        submitReconcile(pending.getKey(), pending.getValue(), 0);
      }
    }
  }

  private void handleDelete(RootWatcherRegistry.Subscription witness, Path path) {
    try {
      witness.rootIdentity().requireCurrent(witness.root());
    } catch (IOException unavailableOrRebound) {
      routingFailureSink.accept(
          new IllegalStateException(
              "Watched root identity became unavailable or changed during delete routing",
              unavailableOrRebound));
      log.warn("Worker watcher skipped delete because the registered root identity changed");
      return;
    }
    try {
      String normalizedPath = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
      witnessedDeletePathSink.accept(witness, normalizedPath);
    } catch (RuntimeException failure) {
      routingFailureSink.accept(failure);
      log.debug("Worker watcher witnessed delete route failed: {}", failure.getMessage());
    }
  }

  private static Kind mapEventKind(DirectoryChangeEvent.EventType eventType) {
    return switch (eventType) {
      case CREATE -> Kind.CREATE;
      case MODIFY -> Kind.MODIFY;
      case DELETE -> Kind.DELETE;
      case OVERFLOW -> Kind.OVERFLOW;
    };
  }

  private static boolean isInotifyExhausted(IOException e) {
    String msg = e.getMessage();
    if (msg == null) return false;
    String lower = msg.toLowerCase(Locale.ROOT);
    return lower.contains("no space left")
        || lower.contains("inotify")
        || lower.contains("user limit");
  }

  @Override
  public synchronized void close() {
    for (Path root : List.copyOf(watchers.keySet())) {
      closeWatcherFor(root);
    }
    // Defensive cleanup for a partially-started registration. Clear before cancellation so its
    // completion callback is classified as intentional.
    List<CompletableFuture<Void>> orphanedFutures = List.copyOf(watchFutures.values());
    watchFutures.clear();
    for (CompletableFuture<Void> future : orphanedFutures) {
      future.cancel(true);
    }
    failedEpochs.clear();
    pendingReconciliations.clear();
    for (Runnable queued : reconcileExecutor.shutdownNow()) {
      if (queued instanceof Future<?> future) {
        future.cancel(false);
      }
    }
  }

  private final class RootSubscription {
    private enum DeliveryState { STARTING, DRAINING, ACTIVE, DISCARDED }

    private final RootWatcherRegistry.Subscription registration;
    private final DirectoryWatcher watcher;
    private final List<DirectoryChangeEvent> bufferedEvents = new ArrayList<>();
    private DeliveryState deliveryState = DeliveryState.STARTING;

    private RootSubscription(
        RootWatcherRegistry.Subscription registration, DirectoryWatcher watcher) {
      this.registration = registration;
      this.watcher = watcher;
    }

    RootWatcherRegistry.Subscription registration() { return registration; }
    DirectoryWatcher watcher() { return watcher; }

    void dispatchOrBuffer(DirectoryChangeEvent event) {
      boolean overflowed = false;
      synchronized (this) {
        if (deliveryState == DeliveryState.DISCARDED) return;
        if (deliveryState != DeliveryState.ACTIVE) {
          if (bufferedEvents.size() >= STARTUP_EVENT_BUFFER_LIMIT) {
            deliveryState = DeliveryState.DISCARDED;
            bufferedEvents.clear();
            overflowed = true;
          } else {
            bufferedEvents.add(event);
            return;
          }
        }
      }
      if (overflowed) {
        handleWatcherException(
            registration.root(),
            registration.watcherEpoch(),
            new IllegalStateException("Worker watcher startup event buffer limit exceeded"));
        return;
      }
      handleEvent(registration, event);
    }

    synchronized boolean publish() {
      if (deliveryState == DeliveryState.DISCARDED) return false;
      if (deliveryState != DeliveryState.STARTING) {
        throw new IllegalStateException("Watcher registration is not awaiting publication");
      }
      deliveryState = DeliveryState.DRAINING;
      return true;
    }

    void drainBufferedEvents() {
      synchronized (this) {
        if (deliveryState == DeliveryState.STARTING) {
          throw new IllegalStateException("Watcher registration was not published before drain");
        }
      }
      while (true) {
        List<DirectoryChangeEvent> pending;
        synchronized (this) {
          if (deliveryState == DeliveryState.DISCARDED) return;
          if (bufferedEvents.isEmpty()) {
            deliveryState = DeliveryState.ACTIVE;
            return;
          }
          pending = List.copyOf(bufferedEvents);
          bufferedEvents.clear();
        }
        for (DirectoryChangeEvent event : pending) {
          synchronized (this) {
            if (deliveryState == DeliveryState.DISCARDED) return;
          }
          handleEvent(registration, event);
        }
      }
    }

    synchronized void discardBufferedEvents() {
      deliveryState = DeliveryState.DISCARDED;
      bufferedEvents.clear();
    }
  }
}
