/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.indexerworker.services.CallContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The cancellation signal a bounded in-process flow hands to the worker call (items A7 and A8).
 *
 * <p>Over the wire this was {@code ServerCallStreamObserver}: the worker polled
 * {@code isCancelled()} between batches and registered a handler that closed its subscription. Both
 * halves have to survive, because both are what stops a producer whose consumer has gone away — the
 * poll bounds how long a scan keeps walking, and the handler is what unsubscribes the change feed.
 *
 * <p>{@link #cancel()} is idempotent and runs each registered handler exactly once; a handler
 * registered after cancellation runs immediately. Native child groups and a Worker producer may
 * register independently without replacing one another's cancellation callback.
 */
final class FlowCancelSignal implements CallContext.CancelSignal {

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final Object handlersLock = new Object();
  private final List<Runnable> handlers = new ArrayList<>();

  @Override
  public boolean isCancelled() {
    return cancelled.get();
  }

  @Override
  public void onCancel(Runnable onCancel) {
    Objects.requireNonNull(onCancel, "onCancel");
    synchronized (handlersLock) {
      if (!cancelled.get()) {
        handlers.add(onCancel);
        return;
      }
    }
    onCancel.run();
  }

  /** Flips the signal and runs the producer's cancel handler once. */
  void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    List<Runnable> toRun;
    synchronized (handlersLock) {
      toRun = List.copyOf(handlers);
      handlers.clear();
    }
    Throwable failure = null;
    for (Runnable handler : toRun) {
      try { handler.run(); }
      catch (RuntimeException | Error cause) {
        if (failure == null) failure = cause;
        else if (failure != cause) failure.addSuppressed(cause);
      }
    }
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
  }
}
