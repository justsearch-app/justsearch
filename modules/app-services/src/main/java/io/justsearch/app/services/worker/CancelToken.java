/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caller-controlled cancel signal for long-running calls (tempdoc 419 / T3).
 *
 * <p>The contract is unchanged since 419: a caller passes the token to a long-running call such as
 * {@link KnowledgeClient#scanRoot}, and calls {@link #cancel()} from a different thread — typically
 * the Javalin handler that started the call, in response to an HTTP-client disconnect or an
 * explicit user "Cancel" click — to stop it. The producer stops within the next batch.
 *
 * <p><b>Lane F stage A item A10 re-homed the implementation off {@code io.grpc.Context}.</b> The
 * token was a wrapper over a {@code Context.CancellableContext} because cancelling a gRPC call
 * meant cancelling the context the call was bound to; the {@code context()} accessor existed for
 * exactly one caller, {@code RemoteKnowledgeClient}, which item A10 deleted with the rest of the
 * wire client stack. What remains is {@link #onCancel}, which is what the in-process path
 * always needed: there
 * is no call context to scope, so the producer registers a notification and flips its own cancel
 * signal. Keeping the gRPC type after its only user was gone would have made a transport
 * dependency out of a plain observable boolean — and dragged the gRPC runtime into every module
 * that cancels a scan.
 *
 * <p><b>Interaction with {@code RpcDeadlineCategory}:</b> orthogonal, as before. The deadline is
 * the upper bound the call is given; the token lets the caller terminate earlier. Either firing
 * first ends the call cleanly.
 *
 * <p>Thread-safe. {@link #cancel} is idempotent and may race with {@link #onCancel} from another
 * thread without dropping or double-running a handler.
 */
public final class CancelToken {

  /** Set exactly once, by whichever thread wins the cancel. */
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  /**
   * Handlers registered before the cancel fired. Drained to {@code null} by the cancelling thread,
   * which is what makes "run each handler exactly once" hold across the register/cancel race: a
   * handler registered after the drain sees a null queue and runs inline instead of being queued
   * onto a list nobody will read again.
   */
  private final AtomicReference<Queue<Runnable>> handlers =
      new AtomicReference<>(new ConcurrentLinkedQueue<>());

  /** The reason passed to {@link #cancel(String)}, for diagnostics. */
  private volatile String reason;

  /** Cancels the associated call. Idempotent — subsequent calls are no-ops. */
  public void cancel() {
    cancel("client cancelled");
  }

  /** Cancels with a caller-supplied diagnostic message. */
  public void cancel(String reason) {
    Objects.requireNonNull(reason, "reason");
    if (!cancelled.compareAndSet(false, true)) {
      return;
    }
    this.reason = reason;
    Queue<Runnable> pending = handlers.getAndSet(null);
    if (pending == null) {
      return;
    }
    for (Runnable handler = pending.poll(); handler != null; handler = pending.poll()) {
      handler.run();
    }
  }

  /** Returns {@code true} once {@link #cancel} has fired. */
  public boolean isCancelled() {
    return cancelled.get();
  }

  /** The reason this token was cancelled, or {@code null} if it has not been. */
  public String reason() {
    return reason;
  }

  /**
   * Runs {@code handler} once, on whichever thread cancels, when this token fires. Fires
   * immediately, on the calling thread, if the token is already cancelled.
   *
   * <p>This is the whole outward surface the producers use: the in-process path has no call context
   * to scope, so it wires this to its own cancel signal.
   */
  public void onCancel(Runnable handler) {
    Objects.requireNonNull(handler, "handler");
    Queue<Runnable> pending = handlers.get();
    if (pending != null) {
      pending.add(handler);
      // Re-check: a cancel that drained the queue between get() and add() would otherwise leave
      // this handler queued and never run.
      if (handlers.get() != null || !pending.remove(handler)) {
        return;
      }
    }
    handler.run();
  }
}
