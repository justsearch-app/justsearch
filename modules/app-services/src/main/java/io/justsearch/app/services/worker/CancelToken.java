/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.grpc.Context;
import java.util.Objects;

/**
 * Caller-controlled cancel signal for long-running gRPC calls (tempdoc 419 / T3).
 *
 * <p>Wraps a {@link Context.CancellableContext} so callers can:
 *
 * <ol>
 *   <li>Pass the token to a streaming RPC like {@link KnowledgeClient#scanRoot}.
 *   <li>Call {@link #cancel()} from a different thread (typically the Javalin handler that
 *       initiated the RPC, in response to an HTTP-client disconnect or an explicit user "Cancel"
 *       click) to propagate gRPC cancellation. The Worker's
 *       {@code ServerCallStreamObserver.isCancelled()} (tempdoc 418 B-H.3) flips to {@code true}
 *       and the scan loop terminates within the next batch.
 * </ol>
 *
 * <p><strong>Interaction with {@code RpcDeadlineCategory}:</strong> orthogonal. gRPC channel-
 * level deadlines and {@link Context.CancellableContext} are independent cancel sources —
 * either firing first cancels the call cleanly. Combining both is safe and intentional: the
 * deadline is the upper bound; the cancel token lets callers terminate earlier.
 *
 * <p>This class is the backing primitive for the validation finding logged 2026-04-26
 * (HTTP-client abort doesn't propagate to gRPC). See ADR / observation in
 * {@code docs/observations.md}.
 */
public final class CancelToken {
  private final Context.CancellableContext context;

  public CancelToken() {
    this.context = Context.current().withCancellation();
  }

  /** Returns the underlying gRPC context. Used by {@link KnowledgeClient} to scope RPC calls. */
  Context.CancellableContext context() {
    return context;
  }

  /** Cancels the associated gRPC call. Idempotent — subsequent calls are no-ops. */
  public void cancel() {
    cancel("client cancelled");
  }

  /** Cancels with a caller-supplied diagnostic message. */
  public void cancel(String reason) {
    context.cancel(new java.util.concurrent.CancellationException(Objects.requireNonNull(reason, "reason")));
  }

  /** Returns {@code true} once {@link #cancel} has fired (or the underlying context cancels). */
  public boolean isCancelled() {
    return context.isCancelled();
  }

  /**
   * Runs {@code handler} once, on whichever thread cancels, when this token fires. Fires
   * immediately if the token is already cancelled.
   *
   * <p>Lane F stage A item A6: this is the transport-neutral half of the token. The gRPC path uses
   * {@link #context()} to scope a call; the in-process path has no context to scope and needs the
   * notification instead. Item A10 replaces the {@code io.grpc.Context} inside with a plain JDK
   * primitive, at which point this method is the whole class's outward surface
   * (stage A §2: "re-home {@code CancelToken} free of {@code io.grpc}").
   */
  public void onCancel(Runnable handler) {
    Objects.requireNonNull(handler, "handler");
    context.addListener(ctx -> handler.run(), Runnable::run);
  }
}
