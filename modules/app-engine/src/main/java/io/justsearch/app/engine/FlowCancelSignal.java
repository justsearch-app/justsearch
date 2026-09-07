/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.indexerworker.services.CallContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The cancellation signal a bounded in-process flow hands to the worker call (items A7 and A8).
 *
 * <p>Over the wire this was {@code ServerCallStreamObserver}: the worker polled
 * {@code isCancelled()} between batches and registered a handler that closed its subscription. Both
 * halves have to survive, because both are what stops a producer whose consumer has gone away — the
 * poll bounds how long a scan keeps walking, and the handler is what unsubscribes the change feed.
 *
 * <p>{@link #cancel()} is idempotent and runs the registered handler exactly once; a handler
 * registered after cancellation runs immediately, so a race between the caller closing the flow and
 * the worker registering its unsubscribe cannot leak a subscription.
 */
final class FlowCancelSignal implements CallContext.CancelSignal {

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final AtomicReference<Runnable> handler = new AtomicReference<>();

  @Override
  public boolean isCancelled() {
    return cancelled.get();
  }

  @Override
  public void onCancel(Runnable onCancel) {
    handler.set(onCancel);
    if (cancelled.get()) {
      fire();
    }
  }

  /** Flips the signal and runs the producer's cancel handler once. */
  void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      fire();
    }
  }

  private void fire() {
    Runnable h = handler.getAndSet(null);
    if (h != null) {
      h.run();
    }
  }
}
