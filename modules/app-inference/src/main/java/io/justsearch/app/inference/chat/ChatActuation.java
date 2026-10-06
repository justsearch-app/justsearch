/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.runtime.child.StopReason;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * One step that starts, stops or relaunches the managed chat server (Spec A §3.5). The lifecycle
 * manager describes the step; the chat controller decides when it runs, on its own thread, one at a
 * time.
 *
 * <p>A step with a {@linkplain #coalesceKey() coalescing key} may be replaced before it runs: a
 * later step with the same key absorbs it ({@link #absorb}), and the replaced caller is told it
 * was superseded ({@link ChatActuationRouter.SupersededException}). A step that {@linkplain #drainsFirst() drains} has
 * generation admission closed and in-flight replies finished by the controller before its body
 * runs; the body receives the resulting hold and owns it from then on.
 *
 * @param <T> what the body returns to the requesting caller
 */
public final class ChatActuation<T> {

  /** What the step does. Used for coalescing, logging and the controller's state. */
  public enum Kind {
    ACTIVATE,
    DEACTIVATE,
    APPLY,
    PREPARE,
    PREPARED_ABORT,
    PREPARED_RETIRE,
    VDU_ENTER,
    VDU_EXIT,
    RECOVER,
    DETACH
  }

  /** The actuation work, run on the controller thread. */
  @FunctionalInterface
  public interface Body<T> {
    /**
     * @param drained the admission hold the controller's drain produced, or {@code null} when the
     *     step does not drain first. The body owns it: it closes it or hands it on.
     */
    T run(ChatActuator.DrainHold drained) throws Exception;
  }

  /** What the caller receives when the drain before the body failed; the body does not run. */
  @FunctionalInterface
  public interface DrainFailure<T> {
    /**
     * @param failure {@link InterruptedException} when the step was interrupted (the interrupt flag
     *     is already restored), {@link IllegalStateException} when replies did not finish in time
     */
    T onDrainFailure(Exception failure) throws Exception;
  }

  private final Kind kind;
  private final String coalesceKey;
  private final boolean drainsFirst;
  private final DrainFailure<T> drainFailure;
  private final Object coalesceState;
  private final BinaryOperator<ChatActuation<?>> absorbing;
  private final Body<T> body;

  private ChatActuation(
      Kind kind,
      String coalesceKey,
      boolean drainsFirst,
      DrainFailure<T> drainFailure,
      Object coalesceState,
      BinaryOperator<ChatActuation<?>> absorbing,
      Body<T> body) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.coalesceKey = coalesceKey;
    this.drainsFirst = drainsFirst;
    this.drainFailure = drainFailure;
    this.coalesceState = coalesceState;
    this.absorbing = absorbing;
    this.body = Objects.requireNonNull(body, "body");
  }

  /** A step that never coalesces and does not drain. */
  public static <T> ChatActuation<T> of(Kind kind, Body<T> body) {
    return new ChatActuation<>(kind, null, false, null, null, null, body);
  }

  /** A step whose body runs after the controller drained in-flight replies. */
  public static <T> ChatActuation<T> draining(
      Kind kind, DrainFailure<T> onDrainFailure, Body<T> body) {
    Objects.requireNonNull(onDrainFailure, "onDrainFailure");
    return new ChatActuation<>(kind, null, true, onDrainFailure, null, null, body);
  }

  /** A coalescing step that drains first (Spec A §3.5: A→B→C during a drain launches C only). */
  public static <T> ChatActuation<T> coalescingAfterDrain(
      Kind kind,
      String key,
      Object state,
      BinaryOperator<ChatActuation<?>> absorbing,
      DrainFailure<T> onDrainFailure,
      Body<T> body) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(onDrainFailure, "onDrainFailure");
    return new ChatActuation<>(kind, key, true, onDrainFailure, state, absorbing, body);
  }

  /** What the caller gets when the drain failed. */
  public T drainFailed(Exception failure) throws Exception {
    if (drainFailure == null) throw failure;
    return drainFailure.onDrainFailure(failure);
  }

  /**
   * A step that a later step with the same key replaces while it waits; the latest wins.
   *
   * @param state opaque value a later step may read in its absorb function
   * @param absorbing combines {@code (superseded, latest)} into the step that runs; {@code null}
   *     means the latest runs unchanged
   */
  public static <T> ChatActuation<T> coalescing(
      Kind kind,
      String key,
      Object state,
      BinaryOperator<ChatActuation<?>> absorbing,
      Body<T> body) {
    Objects.requireNonNull(key, "key");
    return new ChatActuation<>(kind, key, false, null, state, absorbing, body);
  }

  public Kind kind() {
    return kind;
  }

  /** {@code null} when the step never coalesces. */
  public String coalesceKey() {
    return coalesceKey;
  }

  public boolean drainsFirst() {
    return drainsFirst;
  }

  /**
   * The Spec A §3.4 drain this step asks for, by kind. Stopping or reconfiguring the serving
   * server (deactivate, legacy apply, VDU enter and exit) waits only while a server is serving.
   * Prepare and recovery keep the unconditional 30 s drain they had before the controller.
   *
   * @param afterIdleBound the AFTER_IDLE bound
   */
  public ChatDrainPolicy drainPolicy(java.time.Duration afterIdleBound) {
    return switch (kind) {
      case DEACTIVATE -> ChatDrainPolicy.forReason(StopReason.DEACTIVATE, afterIdleBound)
          .whileServing();
      case APPLY -> ChatDrainPolicy.forReason(StopReason.RECONFIGURE, afterIdleBound).whileServing();
      case VDU_ENTER -> ChatDrainPolicy.forReason(StopReason.VDU_ENTER, afterIdleBound)
          .whileServing();
      case VDU_EXIT -> ChatDrainPolicy.forReason(StopReason.VDU_EXIT, afterIdleBound)
          .whileServing();
      case ACTIVATE, PREPARE, PREPARED_ABORT, PREPARED_RETIRE, RECOVER, DETACH ->
          ChatDrainPolicy.forReason(StopReason.RECONFIGURE, afterIdleBound);
    };
  }

  /** The opaque value given at construction, for an absorb function. */
  public Object coalesceState() {
    return coalesceState;
  }

  /**
   * The step that runs when this (later) step replaces {@code superseded}. Both share a key.
   */
  public ChatActuation<?> absorb(ChatActuation<?> superseded) {
    Objects.requireNonNull(superseded, "superseded");
    if (!Objects.equals(coalesceKey, superseded.coalesceKey) || coalesceKey == null) {
      throw new IllegalArgumentException("only steps with the same coalescing key absorb");
    }
    return absorbing == null ? this : absorbing.apply(superseded, this);
  }

  /** Runs the body. Only the chat controller calls this, on its thread. */
  public T run(ChatActuator.DrainHold drained) throws Exception {
    return body.run(drained);
  }

  @Override
  public String toString() {
    return "ChatActuation[" + kind + (coalesceKey == null ? "" : ", key=" + coalesceKey) + "]";
  }
}
