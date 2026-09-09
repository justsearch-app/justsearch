/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.core.execution.EngineExecutorRegistry;
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
import java.util.concurrent.atomic.AtomicReference;
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
 *   <li>{@link #start} — opens the gRPC stream. Returns a future that completes
 *       once the initial snapshot frame has been delivered to listeners.</li>
 *   <li>{@link #subscribe} — registers a listener. New listeners are NOT
 *       replayed the snapshot — they begin receiving deltas from "now". For
 *       initial-state hydration, listeners use {@link #latestSnapshot}.</li>
 *   <li>{@link #stop} — cancels the gRPC stream + clears listeners.</li>
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
  private final EngineExecutorRegistry.Registration reconnectRegistration;
  private final java.util.concurrent.ScheduledExecutorService reconnects;
  private volatile KnowledgeClient.IndexingJobsStream stream;
  private final List<Consumer<Delta>> listeners = new CopyOnWriteArrayList<>();
  /**
   * The cached (seq, items) pair, updated ATOMICALLY together (tempdoc 550 §B.2 fix-pass). A new
   * SSE subscriber reads this ONE record (via {@link #latestSnapshotPair()}), so it can never see a
   * torn snapshot — items at one seq with a {@code seq} from a concurrent delta — which would make
   * the client subscribe past an unseen delta and go permanently stale.
   */
  public record CachedSnapshot(long seq, List<IndexingJobView> items) {}

  private final AtomicReference<CachedSnapshot> cached =
      new AtomicReference<>(new CachedSnapshot(0L, List.of()));
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile boolean stopped = false;

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
    this.sourceSupplier = Objects.requireNonNull(sourceSupplier, "sourceSupplier");
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                "head.indexing-jobs-bridge-reconnect",
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
  }

  /**
   * Opens the gRPC stream. Returns a future completed once the snapshot frame
   * has been delivered to listeners. Callers can register listeners before
   * calling start and will receive the snapshot via the regular delta path.
   *
   * <p>Idempotent on second call — a started bridge returns a future that
   * resolves once the in-flight snapshot has been delivered (or never if the
   * connection failed).
   */
  public CompletableFuture<Void> start() {
    CompletableFuture<Void> snapshotDelivered = new CompletableFuture<>();
    if (!started.compareAndSet(false, true)) {
      // Already started; return an already-completed future to avoid hanging callers.
      // Real "is the current snapshot loaded?" check is via latestSnapshotSeq() > 0.
      snapshotDelivered.complete(null);
      return snapshotDelivered;
    }
    openStream(snapshotDelivered);
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
  public CachedSnapshot latestSnapshotPair() {
    return cached.get();
  }

  /** Most recent snapshot items. Empty list before first connect. */
  public List<IndexingJobView> latestSnapshot() {
    return cached.get().items();
  }

  /** Seq cursor as of the most recent snapshot. {@code 0} before first connect. */
  public long latestSnapshotSeq() {
    return cached.get().seq();
  }

  /**
   * Stops listener fan-out. Subsequent frames arriving on the gRPC stream are
   * dropped without being delivered to listeners or mutating cached state.
   *
   * <p>Lane F stage A item A6: the flow now hands back a
   * {@link KnowledgeClient.IndexingJobsStream}, so stopping the bridge also stops <em>production</em>
   * rather than only muting delivery. Before A6 the async gRPC stub gave the caller no handle on
   * the underlying call, and the stream only ended when the channel was shut down.
   */
  public void stop() {
    stopped = true;
    reconnects.shutdownNow();
    listeners.clear();
    KnowledgeClient.IndexingJobsStream open = stream;
    stream = null;
    if (open != null) {
      try {
        open.close();
      } catch (RuntimeException e) {
        log.warn("RemoteIndexingJobsBridge: closing the indexing-jobs flow failed", e);
      }
    }
    reconnectRegistration.close();
  }

  /**
   * Re-opens the flow after a failure, on a bounded backoff.
   *
   * <p>Resetting {@link #started} is the load-bearing line: {@link #start} is a
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
    long now = System.currentTimeMillis();
    recentAttempts.addLast(now);
    while (!recentAttempts.isEmpty() && now - recentAttempts.peekFirst() > 60_000L) {
      recentAttempts.pollFirst();
    }
    int attemptsThisMinute = recentAttempts.size();
    int failures = consecutiveFailures.incrementAndGet();

    if (attemptsThisMinute > RECONNECT_MAX_PER_MINUTE) {
      log.warn(
          "RemoteIndexingJobsBridge: indexing-jobs flow failed {} times in the last minute"
              + " (cap {}); giving up until something calls start() again. The Library surface will"
              + " stop receiving job updates.",
          attemptsThisMinute,
          RECONNECT_MAX_PER_MINUTE,
          cause);
      started.set(false);
      return;
    }

    long delay = Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * (1L << Math.min(failures - 1, 16)));
    log.warn(
        "RemoteIndexingJobsBridge: indexing-jobs flow failed (attempt {} this minute); re-subscribing"
            + " in {}ms. A fresh snapshot will replace the listeners' state.",
        attemptsThisMinute,
        delay,
        cause);

    KnowledgeClient.IndexingJobsStream dead = stream;
    stream = null;
    if (dead != null) {
      try {
        dead.close();
      } catch (RuntimeException e) {
        log.debug("RemoteIndexingJobsBridge: closing the failed flow threw", e);
      }
    }
    // The reset that makes start() actually re-open. See this method's javadoc.
    started.set(false);

    try {
      reconnects.schedule(
          () -> {
            if (stopped) return;
            start();
          },
          delay,
          java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
      log.debug("RemoteIndexingJobsBridge: reconnect scheduler is shut down; not re-subscribing");
    }
  }

  private void openStream(CompletableFuture<Void> snapshotDelivered) {
    if (stopped) {
      snapshotDelivered.completeExceptionally(new IllegalStateException("bridge stopped"));
      return;
    }
    IndexingJobsSource source = sourceSupplier.get();
    if (source == null) {
      // Allow start() to be retried once the worker is up. Reset the started
      // flag so a follow-up start() will try again.
      started.set(false);
      snapshotDelivered.completeExceptionally(
          new IllegalStateException(
              "Worker not connected yet; retry start() after connectKnowledgeServer."));
      return;
    }
    Consumer<IndexingJobsFrame> onFrame =
        new Consumer<>() {
          @Override
          public void accept(IndexingJobsFrame frame) {
            if (stopped) return;
            try {
              switch (frame.getBodyCase()) {
                case SNAPSHOT -> {
                  var items = new ArrayList<IndexingJobView>(frame.getSnapshot().getItemsCount());
                  for (var view : frame.getSnapshot().getItemsList()) {
                    items.add(toView(view));
                  }
                  List<IndexingJobView> immutable = List.copyOf(items);
                  cached.set(new CachedSnapshot(frame.getSeq(), immutable));
                  emit(new Delta.SnapshotReplaced(frame.getSeq(), immutable));
                  if (!snapshotDelivered.isDone()) {
                    snapshotDelivered.complete(null);
                  }
                }
                case DELTA -> {
                  IndexingJobsDelta d = frame.getDelta();
                  // Tempdoc 550 §B.2 keystone fix: keep `latestSnapshot` a FAITHFUL LIVE
                  // projection of the worker's job table by applying every delta to the
                  // cached snapshot — not only emitting it to live listeners. Previously
                  // only the SNAPSHOT frame mutated `latestSnapshot`, so after the worker
                  // drained jobs (PENDING→DONE via UPDATE deltas) the cache stayed frozen
                  // at the subscribe-time snapshot; every NEW SSE subscriber (browser
                  // reload) then received phantom PENDING rows the live `queueDepth()`
                  // reported as 0 — the count-vs-list drift. Applying deltas here makes a
                  // fresh subscriber's snapshot agree with the live worker state.
                  switch (d.getChangeCase()) {
                    case INSERT -> {
                      IndexingJobView view = toView(d.getInsert());
                      upsertSnapshot(view, frame.getSeq());
                      emit(new Delta.Insert(frame.getSeq(), view));
                    }
                    case UPDATE -> {
                      IndexingJobView view = toView(d.getUpdate());
                      upsertSnapshot(view, frame.getSeq());
                      emit(new Delta.Update(frame.getSeq(), view));
                    }
                    case DELETE_PATH_HASH -> {
                      removeFromSnapshot(d.getDeletePathHash(), frame.getSeq());
                      emit(new Delta.Delete(frame.getSeq(), d.getDeletePathHash()));
                    }
                    case CHANGE_NOT_SET -> log.warn(
                        "RemoteIndexingJobsBridge: empty delta change in frame seq={}",
                        frame.getSeq());
                  }
                }
                case BODY_NOT_SET -> log.warn(
                    "RemoteIndexingJobsBridge: empty body in frame seq={}", frame.getSeq());
              }
            } catch (RuntimeException e) {
              log.error("RemoteIndexingJobsBridge.onNext failed", e);
            }
          }

        };

    Consumer<Throwable> onError =
        t -> {
          if (stopped) return;
          if (!snapshotDelivered.isDone()) {
            snapshotDelivered.completeExceptionally(t);
          }
          scheduleResubscribe(t);
        };

    try {
      stream =
          source.subscribe(
              onFrame,
              onError,
              () -> log.info("RemoteIndexingJobsBridge stream completed by producer"));
    } catch (RuntimeException failure) {
      // Bounded admission/executor refusal happens before a subscription can report onError.
      // Preserve the failed start future and use the same retry budget as asynchronous failures.
      onError.accept(failure);
    }
  }

  /**
   * Apply an Insert/Update to the cached snapshot (tempdoc 550 §B.2). Keyed by
   * {@code pathHash}; preserves insertion order (a re-keyed Update keeps the row's
   * original position). Atomic so concurrent {@link #latestSnapshot()} readers
   * always see a consistent immutable list.
   */
  private void upsertSnapshot(IndexingJobView view, long seq) {
    cached.updateAndGet(
        cur -> {
          var map = new java.util.LinkedHashMap<String, IndexingJobView>(cur.items().size() + 1);
          for (var v : cur.items()) {
            map.put(v.pathHash(), v);
          }
          map.put(view.pathHash(), view);
          return new CachedSnapshot(seq, List.copyOf(map.values()));
        });
  }

  /** Remove a row from the cached snapshot by primary key (tempdoc 550 §B.2). */
  private void removeFromSnapshot(String pathHash, long seq) {
    cached.updateAndGet(
        cur -> {
          boolean present = false;
          for (var v : cur.items()) {
            if (v.pathHash().equals(pathHash)) {
              present = true;
              break;
            }
          }
          // Advance seq even on a no-op remove so the (seq, items) cursor stays current.
          if (!present) {
            return new CachedSnapshot(seq, cur.items());
          }
          var out = new ArrayList<IndexingJobView>(cur.items().size());
          for (var v : cur.items()) {
            if (!v.pathHash().equals(pathHash)) {
              out.add(v);
            }
          }
          return new CachedSnapshot(seq, List.copyOf(out));
        });
  }

  private void emit(Delta delta) {
    for (var listener : listeners) {
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
