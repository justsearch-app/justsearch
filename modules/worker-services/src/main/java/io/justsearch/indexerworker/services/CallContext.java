/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

/**
 * Per-call context a caller supplies to a worker service method.
 *
 * <p>Lane F stage A item A3 ("converted, not deleted"): the three worker services no longer
 * extend a generated gRPC {@code ImplBase}, so the call-scoped facts they used to read out of the
 * transport's own thread-local context statics are now passed in explicitly. Design §6: "cancellation,
 * deadlines, bounded work, per-batch memory limits and backpressure are requirements of the
 * work, not of the network ... none may vanish with the channel."
 *
 * <p>What the services actually read today, and therefore what this carries:
 *
 * <ul>
 *   <li>{@code traceId} — the W3C trace id the caller propagated, used to open the logging MDC
 *       scope (previously read from the tracing server interceptor, deleted at item A9).
 *   <li>{@code requestId} — the caller's request id, same MDC scope (previously
 *       read from the request-metadata server interceptor, deleted at item A9).
 *   <li>{@code cancel} — the call's cancellation signal, read by the two streaming methods
 *       (previously {@code ServerCallStreamObserver#isCancelled()} and
 *       {@code #setOnCancelHandler(Runnable)}).
 *   <li>{@code engineContext} — caller attribution and independent work axes from the port.
 *   <li>{@code provenance} — the application-owned originator projection for durable ingestion.
 * </ul>
 *
 * <p><b>No deadline field, deliberately.</b> No worker-side code reads a call deadline today —
 * the deadline is a caller-side property (the Head's {@code RpcDeadlineCategory}) that the
 * transport enforces. Stage A item A6 re-homes it onto the port call, which is where it has a
 * reader; seeding an unread field here would be residue, not re-homing.
 *
 * <p>Between A3 and A9 the {@code Delegating*Service} adapters built this from the two worker-core
 * interceptors, so production behaviour stayed byte-identical across the change. Item A9 deleted
 * adapters and interceptors alike; {@code EngineKnowledgeClient} now fills the same two fields by
 * reading the current span and MDC in place, on the caller's own thread.
 */
public record CallContext(String traceId, String requestId, CancelSignal cancel,
    io.justsearch.core.context.EngineContext engineContext,
    io.justsearch.indexerworker.queue.JobQueue.EnqueueProvenance provenance) {

  /** Normalises a null cancellation signal to {@link CancelSignal#NEVER}. */
  public CallContext {
    java.util.Objects.requireNonNull(engineContext, "engineContext");
    java.util.Objects.requireNonNull(provenance, "provenance");
    if (!engineContext.transport().equals(provenance.transport())) {
      throw new IllegalArgumentException("Queue attribution disagrees with Engine transport");
    }
    if (cancel == null) {
      cancel = CancelSignal.NEVER;
    }
  }

  private static final CallContext NONE = new CallContext(null, null, CancelSignal.NEVER,
      new io.justsearch.core.context.EngineContext(
          io.justsearch.core.context.EngineContext.ClientKind.INTERNAL, "index-maintenance",
          java.util.Optional.empty(), java.util.Optional.empty(), "TRUSTED", "SYSTEM_INTERNAL",
          io.justsearch.core.context.EngineContext.Survival.DURABLE,
          io.justsearch.core.context.EngineContext.Urgency.BACKGROUND),
      new io.justsearch.indexerworker.queue.JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL"));

  /**
   * Explicit internal maintenance: no tracing ids, never cancelled, durable background work.
   * Request paths receive their caller's context instead of using this producer context.
   */
  public static CallContext none() {
    return NONE;
  }

  /** Whether the caller has abandoned this call. */
  public boolean cancelled() {
    return cancel.isCancelled();
  }

  /** Registers a handler to run if the caller abandons this call. */
  public void onCancel(Runnable handler) {
    cancel.onCancel(handler);
  }

  /**
   * The call's cancellation signal. Two shapes are used: a poll ({@link #isCancelled()}) and a
   * one-shot registration ({@link #onCancel(Runnable)}) — exactly the pair
   * {@code ServerCallStreamObserver} exposed.
   */
  @FunctionalInterface
  public interface CancelSignal {

    /** A signal that never fires. */
    CancelSignal NEVER = () -> false;

    /** Whether the call has been cancelled by its caller. */
    boolean isCancelled();

    /**
     * Registers a cancellation handler. The default is a no-op, which is correct for
     * {@link #NEVER} and for any caller that cannot be cancelled.
     */
    default void onCancel(Runnable handler) {
      // No cancellation is possible for the default signal; nothing to register.
    }
  }
}
