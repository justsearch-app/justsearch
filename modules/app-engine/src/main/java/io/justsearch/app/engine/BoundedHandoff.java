/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bounded hand-off that replaces a server-streaming RPC (lane F stage A items A7 and A8).
 *
 * <p><b>What the wire was doing, and what has to replace it.</b> Streaming flow control is one of
 * the four operation contracts design §6 requires to survive the channel: "backpressure … a
 * requirement of the work, not of the network". Over gRPC the Worker wrote each frame to a
 * {@code ServerCallStreamObserver}, and Netty's send buffer sat between the producer thread and the
 * consumer. Delete the channel and that buffer goes with it — leaving, if nothing replaces it, a
 * <em>direct call</em> from the producer thread into the consumer. For the indexing-jobs feed the
 * producer thread is the SQLite change-feed's dispatch thread; for a scan it is the walker. Handing
 * either of them the SSE fan-out means a slow HTTP client can pace indexing.
 *
 * <p><b>The bound, and the two policies.</b> {@value #DEFAULT_CAPACITY} elements. What happens on a
 * full queue depends on <em>what the producer's thread is holding</em>, which is why it is a
 * constructor choice and not a global rule — see {@link Backpressure}. Neither policy drops
 * unresolved state. The jobs feed may coalesce pending deltas for the same path into its latest
 * state; distinct paths and snapshot barriers remain ordered and retain the same capacity bound.
 *
 * <p>Never drop-oldest, and the reason is specific to what these streams carry rather than a
 * general preference. Both are <em>ordered state deltas folded into a keyed cache by the consumer</em>
 * ({@code RemoteIndexingJobsBridge} keys job rows by {@code pathHash}; {@code ScanProgressRegistry}
 * keeps the latest per scan). Dropping one delta is not "a lost update" — it is a cache that is
 * permanently wrong and cannot detect that it is: a dropped Delete leaves a phantom row on the
 * Library surface until the next restart, silently. Failing the flow is bounded and visible, and
 * the consumer recovers by re-subscribing, which re-issues a snapshot.
 *
 * <p><b>Close stops production, within one poll.</b> The delivery loop polls with a
 * {@value #POLL_TICK_MS} ms tick, so {@link #close()} both stops delivery and makes
 * {@link #publish} refuse — including a {@code publish} already blocked in a
 * {@link Backpressure#BLOCK} wait, which is why that wait is a loop of short sleeps rather than one
 * long one.
 *
 * @param <T> the frame type
 */
final class BoundedHandoff<T> implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(BoundedHandoff.class);

  /**
   * What a producer does when the consumer has fallen {@value #DEFAULT_CAPACITY} frames behind.
   *
   * <p>The choice is not a tuning preference. It is decided by what the producer's thread is
   * holding while it waits, and getting it wrong is not a slow stream — it is a stalled subsystem
   * (review B4).
   */
  enum Backpressure {
    /**
     * Wait for the consumer, up to {@value #DEFAULT_OFFER_TIMEOUT_MS} ms, then fail the flow.
     *
     * <p>For a producer that owns nothing but its own progress. The scan walker is the case: it
     * holds a directory iterator and nothing else, so pausing it pauses one scan, which is exactly
     * the backpressure the wire used to apply. Waiting is strictly better than failing here,
     * because a scan that fails has to be restarted from the top.
     */
    BLOCK,

    /**
     * Never wait. Fail the flow immediately if the frame does not fit.
     *
     * <p>For a producer that is holding a lock somebody else needs. The indexing-jobs feed is the
     * case, and it is not obvious from its call site: {@code IndexingJobsChangeStream} dispatches
     * deltas after JDBC commit and claim bookkeeping, so the thread inside {@link #publish} is the
     * thread that just mutated the jobs table, and it is holding {@code SqliteJobQueue}'s single
     * write lock. Blocking there for five seconds does not slow the stream — it stops the entire
     * job queue: no enqueue, no dequeue, no {@code markDone}, so the indexing loop stalls and every
     * queue-backed API read stalls with it. One browser tab that stopped reading its SSE stream
     * would halt indexing for the whole machine.
     *
     * <p>So this producer refuses instead, the flow fails and closes, and the consumer re-subscribes
     * and gets a fresh snapshot. The stream is the thing that degrades, which is the correct thing
     * to degrade.
     */
    FAIL_FAST
  }

  /**
   * Frames buffered between the producer and the consumer.
   *
   * <p>256 is sized off the burst these streams actually produce rather than off memory: a scan
   * emits one progress frame per batch and the job feed one per row transition, so a consumer that
   * is merely slow (an SSE writer behind a busy browser) rides out a burst of a few hundred without
   * ever touching the producer, while a consumer that has genuinely stopped hits the bound within a
   * second and is reported instead of quietly accumulating.
   */
  static final int DEFAULT_CAPACITY = 256;

  /** How long a {@link Backpressure#BLOCK} producer waits on a full queue before the flow fails. */
  static final long DEFAULT_OFFER_TIMEOUT_MS = 5_000L;

  /** Delivery-loop poll tick — the bound on how long {@link #close()} takes to stop production. */
  static final long POLL_TICK_MS = 25L;

  /** Upper bound on {@link #drainAndClose}'s wait, however long a caller asks for. */
  static final long MAX_DRAIN_WAIT_MS = 30_000L;

  private final ArrayBlockingQueue<T> queue;
  private final long offerTimeoutMs;
  private final Backpressure backpressure;
  private final java.util.function.Function<T, String> coalescingKey;
  private final java.util.function.BinaryOperator<T> coalesce;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean failed = new AtomicBoolean(false);
  private final AtomicReference<Runnable> onClose = new AtomicReference<>();
  private final Consumer<T> sink;
  private final Consumer<Throwable> onError;
  private final String name;

  /**
   * Accepted and delivered counts, compared by {@link #drainAndClose}. Two counters
   * rather than "is the queue empty and is the consumer idle?": that pair has a window between the
   * delivery loop taking a frame off the queue and marking itself busy, in which a drain would
   * conclude the flow was finished and close over an in-flight frame. Counters have no such window
   * — provided {@code accepted} is incremented BEFORE the frame enters the queue, which it is; the
   * other order has the same window it was meant to remove (offer succeeds, the delivery loop takes
   * and counts the frame, and only then does the producer count it, so a concurrent drain reads
   * {@code delivered >= accepted} over a queue that is not empty and clears it). Coalescing
   * uncounts the removed pending frame only after counting its replacement, so a drain still
   * waits for the latest logical state and never waits for a superseded frame.
   */
  private final AtomicLong accepted = new AtomicLong();

  private final AtomicLong delivered = new AtomicLong();

  BoundedHandoff(
      String name,
      Consumer<T> sink,
      Consumer<Throwable> onError,
      Executor deliveryThread,
      Backpressure backpressure) {
    this(name, sink, onError, deliveryThread, backpressure, DEFAULT_CAPACITY, DEFAULT_OFFER_TIMEOUT_MS);
  }

  BoundedHandoff(
      String name,
      Consumer<T> sink,
      Consumer<Throwable> onError,
      Executor deliveryThread,
      Backpressure backpressure,
      int capacity,
      long offerTimeoutMs) {
    this(name, sink, onError, deliveryThread, backpressure, capacity, offerTimeoutMs, null, null);
  }

  BoundedHandoff(
      String name, Consumer<T> sink, Consumer<Throwable> onError, Executor deliveryThread,
      Backpressure backpressure, java.util.function.Function<T, String> coalescingKey,
      java.util.function.BinaryOperator<T> coalesce) {
    this(name, sink, onError, deliveryThread, backpressure, DEFAULT_CAPACITY,
        DEFAULT_OFFER_TIMEOUT_MS, coalescingKey, coalesce);
  }

  private BoundedHandoff(
      String name, Consumer<T> sink, Consumer<Throwable> onError, Executor deliveryThread,
      Backpressure backpressure, int capacity, long offerTimeoutMs,
      java.util.function.Function<T, String> coalescingKey,
      java.util.function.BinaryOperator<T> coalesce) {
    this.name = name;
    this.sink = sink;
    this.onError = onError;
    this.backpressure = backpressure;
    this.coalescingKey = coalescingKey;
    this.coalesce = coalesce;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.offerTimeoutMs = offerTimeoutMs;
    deliveryThread.execute(this::deliver);
  }

  /**
   * Hands one frame to the consumer, from the producer's thread.
   *
   * @return {@code false} if the flow is closed, or the consumer did not drain in time under this
   *     flow's {@link Backpressure} policy, in which case the producer must stop producing. Never
   *     drops silently: a {@code false} that is not a close has already failed the flow.
   */
  boolean publish(T frame) {
    if (closed.get()) {
      return false;
    }
    // Counted before the offer, not after — see the `accepted` field's note. Uncounted on the
    // paths below where the frame does not enter the queue, so the pair stays exact.
    accepted.incrementAndGet();
    try {
      if (offerUnderPolicy(frame)) {
        return true;
      }
    } catch (InterruptedException e) {
      // The producer's thread was interrupted while waiting for room. Restore the flag and stop
      // the flow: an interrupted producer is a shutting-down producer, and this frame was never
      // queued.
      accepted.decrementAndGet();
      Thread.currentThread().interrupt();
      fail(e);
      return false;
    }
    accepted.decrementAndGet();
    if (closed.get()) {
      // Closed underneath us mid-wait. Not a failure — the caller asked for this.
      return false;
    }
    // The bound was reached: the consumer has stopped draining. Reporting and closing is the
    // visible failure; dropping the frame would corrupt the consumer's keyed cache with no way for
    // it to notice.
    log.warn(
        "{}: consumer did not drain {} buffered frames ({} policy); closing the flow",
        name,
        queue.size(),
        backpressure);
    fail(
        new IllegalStateException(
            name
                + ": flow bound of "
                + (queue.remainingCapacity() + queue.size())
                + " frames reached under "
                + backpressure
                + (backpressure == Backpressure.BLOCK ? " after " + offerTimeoutMs + "ms" : "")));
    return false;
  }

  /**
   * The one difference between the two policies. {@link Backpressure#BLOCK} waits in
   * {@value #POLL_TICK_MS} ms slices rather than one long {@code offer(timeout)} so that a
   * {@link #close()} arriving mid-wait releases the producer within a tick instead of up to
   * {@value #DEFAULT_OFFER_TIMEOUT_MS} ms later — the producer is a scan walker, and leaving it
   * parked for five seconds after the flow it feeds has closed is the shutdown hang that bound
   * would otherwise introduce.
   */
  private boolean offerUnderPolicy(T frame) throws InterruptedException {
    if (backpressure == Backpressure.FAIL_FAST) {
      return offerWithoutWaiting(frame);
    }
    long remainingMs = offerTimeoutMs;
    while (remainingMs > 0) {
      if (closed.get()) {
        return false;
      }
      long slice = Math.min(POLL_TICK_MS, remainingMs);
      if (queue.offer(frame, slice, TimeUnit.MILLISECONDS)) {
        return true;
      }
      remainingMs -= slice;
    }
    return false;
  }

  /**
   * Replace pending keyed state only; a null key is an ordering barrier (the initial snapshot).
   * Removal and appending preserve sequence order across other paths. The consumer may take a
   * candidate concurrently; failed removal then leaves it delivered and enqueues the new frame.
   * No second keyed cache is retained and the producer never waits for delivery or queue space.
   */
  private boolean offerWithoutWaiting(T frame) {
    if (coalescingKey == null) return queue.offer(frame);
    synchronized (queue) {
      String key = coalescingKey.apply(frame);
      if (key != null) {
        var pending = new ArrayList<T>(queue);
        for (int i = pending.size() - 1; i >= 0; i--) {
          T previous = pending.get(i);
          String previousKey = coalescingKey.apply(previous);
          if (previousKey == null) break;
          if (key.equals(previousKey)) {
            T replacement = coalesce.apply(previous, frame);
            if (queue.remove(previous)) {
              accepted.decrementAndGet();
              frame = replacement;
            }
            break;
          }
        }
      }
      return queue.offer(frame);
    }
  }

  /**
   * Waits for every accepted frame to reach the consumer, then closes.
   *
   * <p>The finite counterpart of {@link #close()}, for a flow whose producer ends on its own — a
   * scan that finished walking. Closing immediately would discard the tail of the walk, the
   * terminal event among it, which is the failure mode a bound introduces if the shutdown is not
   * thought about: those frames were <em>accepted</em>, so dropping them here would be exactly the
   * silent loss the policy exists to prevent.
   *
   * <p>The wait is measured on {@link System#nanoTime()}: a wall-clock deadline can be moved by an
   * NTP step or a laptop resume, and this loop is the last thing standing between a finished scan
   * and its terminal event. It is also capped at {@value #MAX_DRAIN_WAIT_MS} ms regardless of what
   * the caller asks for, so a mistaken caller cannot park a shutdown indefinitely.
   *
   * @return true if the flow drained fully; false if the timeout elapsed first, in which case the
   *     caller has lost frames and must say so rather than reporting a clean finish
   */
  boolean drainAndClose(long timeoutMs) {
    long budgetMs = Math.max(0L, Math.min(timeoutMs, MAX_DRAIN_WAIT_MS));
    long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
    boolean drained = false;
    while (true) {
      if (delivered.get() >= accepted.get()) {
        drained = true;
        break;
      }
      if (closed.get()) break;
      if (System.nanoTime() - deadlineNs >= 0) {
        break;
      }
      try {
        Thread.sleep(1L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (!drained) {
      log.warn(
          "{}: {} of {} frames still undelivered after {}ms; closing anyway",
          name,
          accepted.get() - delivered.get(),
          accepted.get(),
          budgetMs);
    }
    close();
    return drained;
  }

  /**
   * Reports a producer-side failure to the consumer and closes the flow.
   *
   * <p>The report happens at most once. Two producers failing the same flow concurrently (or a
   * sink that throws while a publish is timing out) would otherwise deliver two terminal errors to
   * a consumer whose contract is one.
   */
  void fail(Throwable cause) {
    if (!failed.compareAndSet(false, true)) {
      return;
    }
    try {
      onError.accept(cause);
    } catch (RuntimeException e) {
      log.warn("{}: error handler threw", name, e);
    } finally {
      close();
    }
  }

  /**
   * Registers the one handler to run when the flow closes — the producer-side unsubscribe. Runs
   * immediately if the flow is already closed, so a late registration cannot leak a subscription.
   */
  void onClose(Runnable handler) {
    onClose.set(handler);
    if (closed.get()) {
      runOnClose();
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      queue.clear();
      runOnClose();
    }
  }

  private void runOnClose() {
    Runnable handler = onClose.getAndSet(null);
    if (handler != null) {
      try {
        handler.run();
      } catch (RuntimeException e) {
        log.warn("{}: close handler failed", name, e);
      }
    }
  }

  private void deliver() {
    while (!closed.get()) {
      T frame;
      try {
        frame = queue.poll(POLL_TICK_MS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
        return;
      }
      if (frame == null) {
        continue;
      }
      try {
        sink.accept(frame);
        delivered.incrementAndGet();
      } catch (Throwable t) {
        // Throwable, not RuntimeException: this is the flow's only thread, and an Error escaping
        // here (a StackOverflowError in an SSE serializer, an OOM under load) would kill it
        // silently. The producer would then fill the queue, hit the bound and fail the flow with a
        // "consumer stopped draining" message naming nothing — the real cause lost. Rethrowing an
        // Error after reporting keeps the JVM's own handling of it.
        log.warn("{}: consumer threw on delivery; closing the flow", name, t);
        fail(t);
        if (t instanceof Error err) {
          throw err;
        }
        return;
      }
    }
  }
}
