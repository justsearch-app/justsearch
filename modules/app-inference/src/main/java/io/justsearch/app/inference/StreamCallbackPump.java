/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Ordered, bounded, off-lock dispatch of one stream's consumer callbacks. */
final class StreamCallbackPump implements AutoCloseable {
  private final ExecutorService foreground;
  private final ExecutorService background;
  private final int capacity;
  private final int retryAfterSeconds;
  private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CountDownLatch drained = new CountDownLatch(1);
  private final EngineWorkHandle work;
  private final AtomicBoolean workClosed = new AtomicBoolean();
  private boolean active;
  private boolean stopping;

  StreamCallbackPump(ExecutorService executor) {
    this(executor, executor, 64, 1, null);
  }

  StreamCallbackPump(ExecutorService executor, EngineWorkHandle work) {
    this(executor, executor, 64, 1, work);
  }

  StreamCallbackPump(
      ExecutorService foreground,
      ExecutorService background,
      int capacity,
      int retryAfterSeconds,
      EngineWorkHandle work) {
    this.foreground = Objects.requireNonNull(foreground, "foreground");
    this.background = Objects.requireNonNull(background, "background");
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
    if (retryAfterSeconds < 1) {
      throw new IllegalArgumentException("retryAfterSeconds must be positive");
    }
    this.retryAfterSeconds = retryAfterSeconds;
    this.work = work == null ? null : work.retain();
  }

  void dispatch(Runnable callback) {
    Objects.requireNonNull(callback, "callback");
    synchronized (this) {
      if (failure.get() != null || stopping) return;
      if (cancelled()) {
        failure.compareAndSet(null, new CancellationException("Model callback work cancelled"));
        stopping = true;
        queue.clear();
        if (!active) finish();
        return;
      }
      if (queue.size() >= capacity) {
        failure.compareAndSet(
            null,
            new EngineExecutorRejectedException(
                Reason.QUEUE_LIMIT, "inference.callback", retryAfterSeconds));
        queue.clear();
        stopping = true;
        if (!active) finish();
        return;
      }
      queue.addLast(callback);
      if (!active) scheduleNextLocked();
    }
  }

  Throwable failure() {
    return failure.get();
  }

  void rethrowFailure() {
    Throwable problem = failure.get();
    if (problem instanceof RuntimeException runtime) throw runtime;
    if (problem instanceof Error error) throw error;
  }

  void awaitDrain() throws InterruptedException {
    close();
    drained.await();
  }

  @Override
  public synchronized void close() {
    stopping = true;
    if (!active && queue.isEmpty()) finish();
  }

  private void scheduleNextLocked() {
    Runnable callback = queue.pollFirst();
    if (callback == null) {
      active = false;
      if (stopping) finish();
      return;
    }
    active = true;
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              callback.run();
              return null;
            }) {
          @Override
          protected void done() {
            callbackFinished(this);
          }
        };
    try {
      executorNow().execute(task);
    } catch (RuntimeException | Error refused) {
      failure.compareAndSet(null, refused);
      task.cancel(false);
    }
  }

  private void callbackFinished(FutureTask<Void> task) {
    if (Thread.currentThread().isInterrupted()) {
      failure.compareAndSet(null, new CancellationException("Model callback dispatcher interrupted"));
    }
    try {
      task.get();
    } catch (CancellationException cancelled) {
      failure.compareAndSet(null, cancelled);
    } catch (ExecutionException failed) {
      failure.compareAndSet(null, failed.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failure.compareAndSet(null, interrupted);
    }
    synchronized (this) {
      active = false;
      if (failure.get() != null || cancelled()) {
        queue.clear();
        stopping = true;
      }
      scheduleNextLocked();
    }
  }

  private ExecutorService executorNow() {
    if (work == null) return background;
    return work.context().urgency() == EngineContext.Urgency.FOREGROUND
        ? foreground
        : background;
  }

  private boolean cancelled() {
    return work != null && work.cancellationReason().isPresent();
  }

  private void finish() {
    if (work != null && workClosed.compareAndSet(false, true)) work.close();
    drained.countDown();
  }
}
