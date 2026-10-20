/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slice 445 head-side bridge for the {@code core.indexing-jobs} TABULAR
 * Resource. Owns the long-lived {@code SubscribeIndexingJobs} streaming RPC,
 * translates incoming proto frames into typed {@link Delta} events, and
 * fans them out to subscribed listeners.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start} — opens the in-process subscription. Returns a future that completes
 *       once the initial snapshot frame has been delivered to listeners.</li>
 *   <li>{@link #subscribe} — registers a listener. New listeners are NOT
 *       replayed the snapshot — they begin receiving deltas from "now". For
 *       initial-state hydration, listeners use {@link #latestSnapshot}.</li>
 *   <li>{@link #stop} — cancels the in-process subscription + clears listeners.</li>
 * </ul>
 *
 * <p>Reconnect handling: if the flow fails, the bridge re-issues the subscribe call on a bounded
 * backoff; the new stream begins with a fresh snapshot which replaces {@link #latestSnapshot} and
 * emits a {@link Delta.SnapshotReplaced} event so listeners can rebuild their keyed state.
 *
 * <p><b>This paragraph used to describe behaviour the class did not have</b> (lane F review,
 * blocker 2). The error path logged "will rely on caller-driven reconnect" and returned; the
 * backoff was deferred to a "Phase 4" and the reconnect to "higher layers". Two later changes made
 * that lethal. There is exactly ONE caller of {@link #start} — {@code HeadAssembly}, once at boot —
 * so no higher layer ever re-called it. And review item B4 gave the indexing-jobs flow a FAIL_FAST
 * policy, because its producer runs inside the SQLite commit hook holding the job-queue write lock:
 * a consumer that stops draining now FAILS the flow rather than blocking it, and closing the flow
 * cancels the worker's change-feed subscription. So one wedged SSE consumer took the indexing-jobs
 * feed down process-wide, for every client, until restart. The bounded reconnect is what makes
 * FAIL_FAST a degradation instead of an outage.
 *
 * <p>Per slice 445 lean scope (verification commit {@code 044b21ab3}): concrete
 * to {@link IndexingJobView}; not parameterized. A second TABULAR Resource
 * would motivate a generic extraction.
 */
public final class RemoteIndexingJobsBridge {

  private static final Logger log = LoggerFactory.getLogger(RemoteIndexingJobsBridge.class);

  /** Per-row delta event delivered to subscribers. */
  public sealed interface Delta {
    /** Initial snapshot OR replacement after a stream reconnect. */
    record SnapshotReplaced(long snapshotSeq, List<IndexingJobView> items) implements Delta {}

    /** New row inserted into the worker's job queue. */
    record Insert(long seq, IndexingJobView row) implements Delta {}

    /** Existing row mutated. */
    record Update(long seq, IndexingJobView row) implements Delta {}

    /**
     * Row removed from the worker's job queue. Only the {@code pathHash}
     * (primary key) is carried since the row is gone.
     */
    record Delete(long seq, String pathHash) implements Delta {}
  }

  /**
   * Listener registration handle. {@link #close()} unregisters and is safe to
   * call from listener callbacks.
   */
  public interface Subscription extends java.io.Closeable {
    @Override
    void close();
  }

  private final Supplier<IndexingJobsSource> sourceSupplier;
  private final LongSupplier reconnectClock;
  private final EngineExecutorRegistry.Registration reconnectRegistration;
  private final java.util.concurrent.ScheduledExecutorService reconnects;
  private final EngineExecutorRegistry.Registration reconnectRetryRegistration;
  private final java.util.concurrent.ExecutorService reconnectRetries;
  private final AtomicBoolean reconnectPending = new AtomicBoolean();
  private final Object subscriptionLock = new Object();
  private volatile StreamAttempt activeSubscription;

  private static final class StreamAttempt {
    KnowledgeClient.IndexingJobsStream stream; // Owned under subscriptionLock.
    boolean receivedFrame; // Cursor translation is owned under the bridge monitor.
    long sequenceOffset;
  }
  private final List<Consumer<Delta>> listeners = new CopyOnWriteArrayList<>();
  /**
   * The cached (seq, items) pair, updated ATOMICALLY together (tempdoc 550 §B.2 fix-pass). A new
   * SSE subscriber reads this ONE record (via {@link #latestSnapshotPair()}), so it can never see a
   * torn snapshot — items at one seq with a {@code seq} from a concurrent delta — which would make
   * the client subscribe past an unseen delta and go permanently stale.
   */
  public record CachedSnapshot(long seq, List<IndexingJobView> items) {}

  private final LinkedHashMap<String, IndexingJobView> rows = new LinkedHashMap<>();
  private final LinkedHashMap<String, IndexingJobView> failures = new LinkedHashMap<>();
  private long snapshotSeq;
  private Set<String> presentStates = Set.of();
  private static final int FAILURE_WINDOW_SIZE = 200;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile boolean stopped = false;
  private Long nextOverflowReconnectAt;

  /**
   * Re-subscribe backoff. The first retry is immediate-ish and they lengthen to
   * {@value #RECONNECT_MAX_DELAY_MS}; {@value #RECONNECT_MAX_PER_MINUTE} attempts per rolling
   * minute is the cap. The cap exists because the failure this recovers from can be permanent — a
   * consumer that never drains fails the flow again the moment it is re-created — and an
   * unbounded retry against a permanently-failing producer is a busy loop that also re-issues a
   * full snapshot each time.
   */
  static final long RECONNECT_BASE_DELAY_MS = 250L;

  static final long RECONNECT_MAX_DELAY_MS = 30_000L;

  static final int RECONNECT_MAX_PER_MINUTE = 6;

  static final long RECONNECT_WINDOW_MS = 60_000L;

  private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
      new java.util.concurrent.atomic.AtomicInteger();

  /** Attempt timestamps within the current rolling minute, for the cap. */
  private final java.util.Deque<Long> recentAttempts = new java.util.concurrent.ConcurrentLinkedDeque<>();

  /**
   * Constructs the bridge against a supplier of the knowledge client.
   *
   * <p>The supplier is resolved at {@link #start()} time (not construction
   * time), which lets the bridge be wired into the bootstrap eagerly even
   * though the client only becomes usable after async Worker startup.
   * {@link #start()} returns a future that fails fast when the supplier
   * returns {@code null}; callers retry once the worker is up.
   *
   * <p>Lane F stage A item A6: the bridge used to take the async gRPC stub directly. It now takes
   * the client and asks it for a {@link KnowledgeClient.IndexingJobsStream}, so the same fan-out
   * works whether the frames arrive over a socket or from the worker's change stream in this JVM.
   */
  public RemoteIndexingJobsBridge(
      EngineExecutorRegistry processExecutors, Supplier<IndexingJobsSource> sourceSupplier) {
    this(processExecutors, sourceSupplier,
        () -> java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
  }

  RemoteIndexingJobsBridge(
      EngineExecutorRegistry processExecutors, Supplier<IndexingJobsSource> sourceSupplier,
      LongSupplier reconnectClock) {
    this(processExecutors, sourceSupplier, reconnectClock, "head.indexing-jobs-bridge");
  }

  /** Independent sources need independently owned reconnect executors. */
  public RemoteIndexingJobsBridge(EngineExecutorRegistry processExecutors,
      Supplier<IndexingJobsSource> sourceSupplier, String ownerName) {
    this(processExecutors, sourceSupplier,
        () -> java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), ownerName);
  }

  private RemoteIndexingJobsBridge(EngineExecutorRegistry processExecutors,
      Supplier<IndexingJobsSource> sourceSupplier, LongSupplier reconnectClock, String ownerName) {
    this.sourceSupplier = Objects.requireNonNull(sourceSupplier, "sourceSupplier");
    this.reconnectClock = Objects.requireNonNull(reconnectClock, "reconnectClock");
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                ownerName + "-reconnect",
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    try {
      this.reconnectRegistration = registration;
      this.reconnects =
          registration.openScheduled(
              runnable -> {
                Thread thread = new Thread(runnable, "indexing-jobs-bridge-reconnect");
                thread.setDaemon(true);
                return thread;
              });
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
    EngineExecutorRegistry.Registration retries = null;
    try {
      retries = processExecutors.register(new EngineExecutorSpec(
          ownerName + "-reconnect-retry", EngineExecutorSpec.Kind.BACKGROUND,
          EngineExecutorSpec.Mode.PLATFORM, 1, Math.min(1, background.maxQueue()), 1));
      this.reconnectRetryRegistration = retries;
      this.reconnectRetries = retries.open(runnable -> {
        Thread thread = new Thread(runnable, "indexing-jobs-bridge-reconnect-retry");
        thread.setDaemon(true);
        return thread;
      });
    } catch (RuntimeException | Error failure) {
      if (retries != null) retries.close();
      registration.close();
      throw failure;
    }
  }

  /**
   * Opens the in-process subscription. Returns a future completed once the snapshot frame
   * has been delivered to listeners. Callers can register listeners before
   * calling start and will receive the snapshot via the regular delta path.
   *
   * <p>Idempotent on second call — a started bridge returns a future that
   * resolves once the in-flight snapshot has been delivered (or never if the
   * connection failed).
   */
  public CompletableFuture<Void> start() {
    CompletableFuture<Void> snapshotDelivered = new CompletableFuture<>();
    StreamAttempt attempt;
    synchronized (subscriptionLock) {
      if (stopped) {
        snapshotDelivered.completeExceptionally(new IllegalStateException("bridge stopped"));
        return snapshotDelivered;
      }
      if (!started.compareAndSet(false, true)) {
        // Already started; return an already-completed future to avoid hanging callers.
        snapshotDelivered.complete(null);
        return snapshotDelivered;
      }
      attempt = new StreamAttempt();
      activeSubscription = attempt;
    }
    openStream(attempt, snapshotDelivered);
    return snapshotDelivered;
  }

  /**
   * Subscribes a listener. New listeners do NOT receive a synthetic snapshot
   * — they start receiving deltas from "now". For initial-state hydration use
   * {@link #latestSnapshot}.
   */
  public Subscription subscribe(Consumer<Delta> listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  /**
   * The current (seq, items) pair as ONE atomic read — the correct way for a new SSE subscriber to
   * hydrate, so the seq and items can never be torn apart (tempdoc 550 §B.2 fix-pass).
   */
  public synchronized CachedSnapshot latestSnapshotPair() {
    return new CachedSnapshot(snapshotSeq, List.copyOf(rows.values()));
  }

  /** Most recent snapshot items. Empty list before first connect. */
  public List<IndexingJobView> latestSnapshot() {
    return latestSnapshotPair().items();
  }

  /** Seq cursor as of the most recent snapshot. {@code 0} before first connect. */
  public synchronized long latestSnapshotSeq() {
    return snapshotSeq;
  }

  /**
   * Stops listener fan-out. Subsequent frames arriving on the in-process subscription are
   * dropped without being delivered to listeners or mutating cached state.
   *
   * <p>Lane F stage A item A6: the flow now hands back a
   * {@link KnowledgeClient.IndexingJobsStream}, so stopping the bridge also stops <em>production</em>
   * rather than only muting delivery. Before A6 the async gRPC stub gave the caller no handle on
   * the underlying call, and the stream only ended when the channel was shut down.
   */
  public void stop() {
    KnowledgeClient.IndexingJobsStream open;
    synchronized (subscriptionLock) {
      stopped = true;
      var attempt = activeSubscription;
      activeSubscription = null;
      open = attempt == null ? null : attempt.stream;
      if (attempt != null) attempt.stream = null;
    }
    reconnectPending.set(false);
    reconnectRetries.shutdownNow();
    reconnects.shutdownNow();
    listeners.clear();
    if (open != null) {
      try {
        open.close();
      } catch (RuntimeException e) {
        log.warn("RemoteIndexingJobsBridge: closing the indexing-jobs flow failed", e);
      }
    }
    try {
      reconnectRetryRegistration.close();
    } finally {
      reconnectRegistration.close();
    }
  }

  /**
   * Re-opens the flow after a failure, on a bounded backoff.
   *
   * <p>The failed attempt resets {@link #started} before scheduling: {@link #start} is a
   * compare-and-set on it, so without the reset a re-subscribe would take the
   * "already started" branch and complete immediately without opening anything —
   * a reconnect that reports success and reconnects nothing.
   *
   * <p>The re-subscribe issues a FRESH SNAPSHOT by construction: the worker's
   * {@code subscribeIndexingJobs} always emits a snapshot frame first, and the frame handler
   * publishes {@link Delta.SnapshotReplaced} for it, so listeners rebuild their keyed state rather
   * than carrying rows from the dead stream. That is why a dropped delta cannot leave a permanently
   * wrong cache here, which is the property the flow's never-drop policy exists to protect.
   */
  private void scheduleResubscribe(Throwable cause) {
    long now = reconnectClock.getAsLong();
    recentAttempts.addLast(now);
    while (!recentAttempts.isEmpty() && now - recentAttempts.peekFirst() > RECONNECT_WINDOW_MS) {
      recentAttempts.pollFirst();
    }
    int attemptsThisMinute = recentAttempts.size();
    int failures = consecutiveFailures.incrementAndGet();

    long delay = Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * (1L << Math.min(failures - 1, 16)));
    if (cause instanceof StreamOverflowException) {
      // Snapshot success resets ordinary failures, but never this overload pacing.
      // First recovery is immediate; a sustained snapshot/overflow cycle gets at
      // most four starts per second, with no minute-long stale-cache cooldown.
      synchronized (subscriptionLock) {
        delay = nextOverflowReconnectAt == null ? 0L : Math.max(0L, nextOverflowReconnectAt - now);
        nextOverflowReconnectAt = now + delay + RECONNECT_BASE_DELAY_MS;
      }
      log.warn("RemoteIndexingJobsBridge: indexing-jobs overflow (attempt {} since snapshot); re-subscribing in {}ms",
          failures, delay);
    } else if (attemptsThisMinute > RECONNECT_MAX_PER_MINUTE) {
      // Exhaustion throttles the one pending reconnect; it must not discard that obligation.
      // Leave a slot for the next attempt, including the failures beyond the cap. A timestamp
      // remains in the rolling window at its exact expiry, hence the +1.
      int failuresToExpire = attemptsThisMinute - RECONNECT_MAX_PER_MINUTE + 1;
      for (long attempt : recentAttempts) {
        if (--failuresToExpire == 0) {
          delay = Math.max(delay, RECONNECT_WINDOW_MS - (now - attempt) + 1);
          break;
        }
      }
      log.warn(
          "RemoteIndexingJobsBridge: indexing-jobs flow failed {} times in the last minute"
              + " (cap {}); retaining reconnect after {}ms cooldown.",
          attemptsThisMinute,
          RECONNECT_MAX_PER_MINUTE,
          delay,
          cause);
    } else {
      log.warn(
          "RemoteIndexingJobsBridge: indexing-jobs flow failed (attempt {} since snapshot); re-subscribing"
              + " in {}ms. A fresh snapshot will replace the listeners' state.",
          failures,
          delay,
          cause);
    }

    if (!stopped && reconnectPending.compareAndSet(false, true)) {
      long reconnectDelay = delay;
      try {
        // This worker needs no timer credit. One retained obligation survives timer saturation.
        reconnectRetries.execute(() -> retryReconnectSchedule(reconnectDelay));
      } catch (java.util.concurrent.RejectedExecutionException closed) {
        reconnectPending.set(false);
        log.debug("RemoteIndexingJobsBridge: reconnect retry owner is closed", closed);
      }
    }
  }

  private void retryReconnectSchedule(long delay) {
    long backoff = RECONNECT_BASE_DELAY_MS;
    while (!stopped) {
      try {
        reconnects.schedule(() -> {
          reconnectPending.set(false);
          if (!stopped) start();
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        return;
      } catch (EngineExecutorRejectedException refused) {
        if (refused.reason() != EngineExecutorRejectedException.Reason.TIMER_LIMIT
            && refused.reason() != EngineExecutorRejectedException.Reason.QUEUE_LIMIT) {
          reconnectPending.set(false);
          log.debug("RemoteIndexingJobsBridge: reconnect scheduler is closed", refused);
          return;
        }
        backoff = Math.max(backoff, java.util.concurrent.TimeUnit.SECONDS.toMillis(refused.retryAfterSeconds()));
      } catch (java.util.concurrent.RejectedExecutionException closed) {
        reconnectPending.set(false);
        log.debug("RemoteIndexingJobsBridge: reconnect scheduler is shut down", closed);
        return;
      }
      try {
        Thread.sleep(Math.min(backoff, RECONNECT_MAX_DELAY_MS));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        reconnectPending.set(false);
        return;
      }
      backoff = Math.min(RECONNECT_MAX_DELAY_MS, backoff * 2);
    }
    reconnectPending.set(false);
  }

  private void openStream(StreamAttempt attempt, CompletableFuture<Void> snapshotDelivered) {
    if (stopped) {
      snapshotDelivered.completeExceptionally(new IllegalStateException("bridge stopped"));
      return;
    }
    IndexingJobsSource source = sourceSupplier.get();
    if (source == null) {
      // Allow start() to be retried once the worker is up. Reset the started
      // flag so a follow-up start() will try again.
      synchronized (subscriptionLock) {
        if (activeSubscription == attempt) {
          activeSubscription = null;
          started.set(false);
        }
      }
      snapshotDelivered.completeExceptionally(
          new IllegalStateException(
              "Worker not connected yet; retry start() after prepareKnowledgeServerBinding."));
      return;
    }
    Consumer<IndexingJobsFrame> onFrame = frame -> {
      // Keep publications ordered across attempts, without making retirement wait for listeners.
      synchronized (RemoteIndexingJobsBridge.this) {
        try {
          Delta delivery;
          synchronized (subscriptionLock) {
            if (stopped || activeSubscription != attempt) return;
            if (!attempt.receivedFrame) {
              // Worker cursors restart per subscription. Rebase the fresh snapshot, retaining
              // relative ordering while the head cursor stays monotonic across reconnects.
              attempt.sequenceOffset = frame.hasSnapshot() ? Math.max(0L, snapshotSeq - frame.getSeq()) : 0L;
              attempt.receivedFrame = true;
            }
            long sequence = frame.getSeq() + attempt.sequenceOffset;
            if (sequence < snapshotSeq) return;
            switch (frame.getBodyCase()) {
              case SNAPSHOT -> {
                var items = new ArrayList<IndexingJobView>(frame.getSnapshot().getItemsCount());
                for (var view : frame.getSnapshot().getItemsList()) items.add(toView(view));
                replaceSnapshot(sequence, items);
                applyStatePresence(frame);
                consecutiveFailures.set(0);
                recentAttempts.clear();
                delivery = new Delta.SnapshotReplaced(sequence, latestSnapshot());
              }
              case DELTA -> {
                var delta = frame.getDelta();
                applyStatePresence(frame);
                delivery = switch (delta.getChangeCase()) {
                  case INSERT -> {
                    var view = toView(delta.getInsert());
                    upsertSnapshot(view, sequence);
                    yield new Delta.Insert(sequence, view);
                  }
                  case UPDATE -> {
                    var view = toView(delta.getUpdate());
                    upsertSnapshot(view, sequence);
                    yield new Delta.Update(sequence, view);
                  }
                  case DELETE_PATH_HASH -> {
                    removeFromSnapshot(delta.getDeletePathHash(), sequence);
                    yield new Delta.Delete(sequence, delta.getDeletePathHash());
                  }
                  case CHANGE_NOT_SET -> {
                    log.warn("RemoteIndexingJobsBridge: empty delta change in frame seq={}", frame.getSeq());
                    yield null;
                  }
                };
              }
              case BODY_NOT_SET -> {
                log.warn("RemoteIndexingJobsBridge: empty body in frame seq={}", frame.getSeq());
                delivery = null;
              }
              default -> throw new IllegalStateException("Unknown indexing-jobs frame body");
            }
          }
          if (delivery != null) emit(attempt, delivery);
          if (frame.hasSnapshot()) snapshotDelivered.complete(null);
        } catch (RuntimeException failure) {
          log.error("RemoteIndexingJobsBridge.onNext failed", failure);
        }
      }
    };

    Consumer<Throwable> onError = t -> {
      KnowledgeClient.IndexingJobsStream dead;
      synchronized (subscriptionLock) {
        if (stopped || activeSubscription != attempt) return;
        activeSubscription = null;
        dead = attempt.stream;
        attempt.stream = null;
        started.set(false);
        scheduleResubscribe(t);
      }
      snapshotDelivered.completeExceptionally(t);
      closeRetiredStream(dead);
    };

    Runnable onCompleted = () -> {
      KnowledgeClient.IndexingJobsStream dead;
      synchronized (subscriptionLock) {
        if (stopped || activeSubscription != attempt) return;
        activeSubscription = null;
        dead = attempt.stream;
        attempt.stream = null;
        started.set(false);
      }
      closeRetiredStream(dead);
      log.info("RemoteIndexingJobsBridge stream completed by producer");
    };
    try {
      var opened = source.subscribe(onFrame, onError, onCompleted);
      synchronized (subscriptionLock) {
        if (!stopped && activeSubscription == attempt) {
          attempt.stream = opened;
          return;
        }
      }
      // A synchronous failure or completion may retire this attempt before subscribe returns.
      closeRetiredStream(opened);
    } catch (RuntimeException failure) {
      onError.accept(failure);
    }
  }

  private void closeRetiredStream(KnowledgeClient.IndexingJobsStream retired) {
    if (retired == null) return;
    try { retired.close(); }
    catch (RuntimeException failure) {
      log.debug("RemoteIndexingJobsBridge: closing the retired flow threw", failure);
    }
  }

  /** Mutate the bounded view in place; terminal deltas still reach every live listener. */
  private synchronized void upsertSnapshot(IndexingJobView view, long seq) {
    String key = view.pathHash();
    failures.remove(key);
    if ("PROCESSING".equals(view.state())) {
      rows.put(key, view);
    } else if (isFailure(view)) {
      rows.put(key, view);
      failures.putLast(key, view);
      evictFailures();
    } else {
      rows.remove(key);
    }
    snapshotSeq = seq;
  }

  private synchronized void removeFromSnapshot(String pathHash, long seq) {
    rows.remove(pathHash);
    failures.remove(pathHash);
    snapshotSeq = seq;
  }

  private synchronized void replaceSnapshot(long seq, List<IndexingJobView> items) {
    rows.clear();
    failures.clear();
    // Worker snapshots arrive newest first. Keep a separate oldest-first eviction order.
    for (IndexingJobView view : items) {
      if ("PROCESSING".equals(view.state()) || isFailure(view)) rows.put(view.pathHash(), view);
      if (isFailure(view)) failures.putFirst(view.pathHash(), view);
    }
    evictFailures();
    snapshotSeq = seq;
  }

  private void evictFailures() {
    while (failures.size() > FAILURE_WINDOW_SIZE) rows.remove(failures.pollFirstEntry().getKey());
  }

  private static boolean isFailure(IndexingJobView view) {
    return "FAILED".equals(view.state()) || "RETRY_EXHAUSTED".equals(view.state());
  }

  private synchronized void applyStatePresence(IndexingJobsFrame frame) {
    if (!frame.hasStatePresence()) return;
    Set<String> next = Set.copyOf(frame.getStatePresence().getStatesList());
    io.justsearch.core.status.StatusChangeCounter.process().changed(presentStates, next);
    presentStates = next;
  }

  private void emit(StreamAttempt attempt, Delta delta) {
    for (var listener : listeners) {
      if (stopped || activeSubscription != attempt) return;
      try {
        listener.accept(delta);
      } catch (RuntimeException e) {
        log.warn("RemoteIndexingJobsBridge listener threw on delta delivery; continuing", e);
      }
    }
  }

  private static IndexingJobView toView(io.justsearch.ipc.IndexingJobView proto) {
    return new IndexingJobView(
        proto.getPathHash(),
        proto.getState(),
        proto.getAttempts(),
        proto.getLastUpdatedMs(),
        proto.getErrorMessage(),
        proto.getRetryAfterMs(),
        proto.getCollection(),
        // Tempdoc 812 D2 — the rollup key travels with the row (empty for non-scan jobs).
        proto.getScanId());
  }
}
