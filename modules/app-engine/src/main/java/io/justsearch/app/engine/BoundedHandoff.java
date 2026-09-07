/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

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
 * <p><b>The bound, and the policy.</b> {@value #DEFAULT_CAPACITY} elements, and on a full queue the
 * producer <b>blocks</b> for up to {@value #DEFAULT_OFFER_TIMEOUT_MS} ms; if the queue is still full
 * after that, the flow <b>fails and closes</b> rather than dropping.
 *
 * <p>Block, not drop-oldest, and the reason is specific to what these streams carry rather than a
 * general preference. Both are <em>ordered state deltas folded into a keyed cache by the consumer</em>
 * ({@code RemoteIndexingJobsBridge} keys job rows by {@code pathHash}; {@code ScanProgressRegistry}
 * keeps the latest per scan). Dropping one delta is not "a lost update" — it is a cache that is
 * permanently wrong and cannot detect that it is: a dropped Delete leaves a phantom row on the
 * Library surface until the next restart, silently. Blocking is bounded and visible; dropping is
 * unbounded in consequence and invisible, which is the trade the design's "a collector bounds
 * neither queues nor CPU nor disk contention" sentence is about.
 *
 * <p>The timeout is what keeps blocking from being unbounded in the other direction: a consumer
 * that has stopped draining (a wedged SSE writer) must not be able to pace the indexing loop
 * forever. When it fires, the flow closes and the consumer is told — a visible failure it can
 * recover from by re-subscribing, which re-issues a snapshot.
 *
 * <p><b>Close stops production, within one poll.</b> The delivery loop polls with a
 * {@value #POLL_TICK_MS} ms tick, so {@link #close()} both stops delivery and makes
 * {@link #publish} refuse immediately — which is what the caller wires into the worker call's
 * {@code CallContext.CancelSignal}, so cancelling the flow closes the producer's subscription too.
 *
 * @param <T> the frame type
 */
final class BoundedHandoff<T> implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(BoundedHandoff.class);

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

  /** How long a full queue blocks the producer before the flow is failed. */
  static final long DEFAULT_OFFER_TIMEOUT_MS = 5_000L;

  /** Delivery-loop poll tick — the bound on how long {@link #close()} takes to stop production. */
  static final long POLL_TICK_MS = 25L;

  private final ArrayBlockingQueue<T> queue;
  private final long offerTimeoutMs;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicReference<Runnable> onClose = new AtomicReference<>();
  private final Consumer<T> sink;
  private final Consumer<Throwable> onError;
  private final String name;

  /**
   * Accepted and delivered counts, compared by {@link #drainAndClose}. Two monotonic counters
   * rather than "is the queue empty and is the consumer idle?": that pair has a window between the
   * delivery loop taking a frame off the queue and marking itself busy, in which a drain would
   * conclude the flow was finished and close over an in-flight frame. Counters have no such window.
   */
  private final AtomicLong accepted = new AtomicLong();

  private final AtomicLong delivered = new AtomicLong();

  BoundedHandoff(String name, Consumer<T> sink, Consumer<Throwable> onError, Executor deliveryThread) {
    this(name, sink, onError, deliveryThread, DEFAULT_CAPACITY, DEFAULT_OFFER_TIMEOUT_MS);
  }

  BoundedHandoff(
      String name,
      Consumer<T> sink,
      Consumer<Throwable> onError,
      Executor deliveryThread,
      int capacity,
      long offerTimeoutMs) {
    this.name = name;
    this.sink = sink;
    this.onError = onError;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.offerTimeoutMs = offerTimeoutMs;
    deliveryThread.execute(this::deliver);
  }

  /**
   * Hands one frame to the consumer, from the producer's thread.
   *
   * @return {@code false} if the flow is closed or the consumer did not drain within the timeout,
   *     in which case the producer must stop producing. Never drops silently.
   */
  boolean publish(T frame) {
    if (closed.get()) {
      return false;
    }
    try {
      if (queue.offer(frame, offerTimeoutMs, TimeUnit.MILLISECONDS)) {
        accepted.incrementAndGet();
        return true;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      close();
      return false;
    }
    // The bound was reached and held for the whole timeout: the consumer has stopped draining.
    // Reporting and closing is the visible failure; dropping the frame would corrupt the
    // consumer's keyed cache with no way for it to notice.
    log.warn(
        "{}: consumer did not drain {} buffered frames within {}ms; closing the flow",
        name,
        queue.size(),
        offerTimeoutMs);
    fail(
        new IllegalStateException(
            name + ": flow bound of " + queue.remainingCapacity() + "+" + queue.size()
                + " frames held for " + offerTimeoutMs + "ms"));
    return false;
  }

  /**
   * Waits for every accepted frame to reach the consumer, then closes.
   *
   * <p>The finite counterpart of {@link #close()}, for a flow whose producer ends on its own — a
   * scan that finished walking. Closing immediately would discard the tail of the walk, the
   * terminal event among it, which is the failure mode a bound introduces if the shutdown is not
   * thought about: those frames were <em>accepted</em>, so dropping them here would be exactly the
   * silent loss the block policy exists to prevent.
   *
   * @return true if the flow drained fully; false if the timeout elapsed first
   */
  boolean drainAndClose(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    boolean drained = false;
    while (System.currentTimeMillis() < deadline) {
      if (closed.get() || delivered.get() >= accepted.get()) {
        drained = true;
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
          timeoutMs);
    }
    close();
    return drained;
  }

  /** Reports a producer-side failure to the consumer and closes the flow. */
  void fail(Throwable cause) {
    if (closed.get()) {
      return;
    }
    try {
      onError.accept(cause);
    } finally {
      close();
    }
  }

  /** Whether the flow has been closed. Wired into the worker call's cancellation signal. */
  boolean isClosed() {
    return closed.get();
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
        return;
      }
      if (frame == null) {
        continue;
      }
      try {
        sink.accept(frame);
        delivered.incrementAndGet();
      } catch (RuntimeException e) {
        log.warn("{}: consumer threw on delivery; closing the flow", name, e);
        fail(e);
        return;
      }
    }
  }
}
