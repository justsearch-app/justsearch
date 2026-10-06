/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import java.time.Duration;

/**
 * The actuation primitives of the managed chat server. Only the chat controller calls them, from
 * its own thread (governance/runtime-state.v1.json, {@code RuntimeReconcilerGuardrailsTest}). This
 * is the one seam a later step swaps to launch llama-server through the child-process owner.
 */
public interface ChatActuator {

  /** Generation admission closed and drained; closing it reopens admission. Idempotent. */
  interface DrainHold extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Closes generation admission and waits for in-flight replies, without holding any lifecycle
   * lock.
   *
   * @throws InterruptedException when the controller interrupts the step (shutdown)
   * @throws IllegalStateException when the replies did not finish within {@code bound} or the
   *     admission gate is unavailable
   */
  DrainHold drain(Duration bound) throws InterruptedException;

  /**
   * Closes generation admission and treats in-flight replies as {@code policy} says (Spec A §3.4),
   * without holding any lifecycle lock. The default serves the bounded, refuse-when-busy rows
   * through {@link #drain(Duration)}; an actuator that owns generation admission implements every
   * row.
   *
   * @throws InterruptedException when the controller interrupts the step (shutdown)
   * @throws IllegalStateException when the policy refuses the change as busy, or admission is
   *     unavailable
   */
  default DrainHold drain(ChatDrainPolicy policy) throws InterruptedException {
    if (policy.waiting() == ChatDrainPolicy.Waiting.AFTER_IDLE && policy.bound() != null
        && policy.onExpiry() == ChatDrainPolicy.Expiry.REFUSE_BUSY) {
      return drain(policy.bound());
    }
    throw new UnsupportedOperationException("this actuator drains bounded refusals only: " + policy);
  }

  /**
   * The step that brings the server to {@code activation}: started when active, otherwise stopped
   * (or an adopted external server detached). Activations share one coalescing key, so a waiting
   * activation is replaced by a later one.
   */
  ChatActuation<Void> activation(ChatActuationRouter.Activation activation);

  /** Runs one step's body. */
  <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception;

  /**
   * Stops the server for good and fences every later launch. Callable while another step is still
   * running on the controller thread: the fence makes that step's launch fail.
   */
  void terminalStop(Duration timeout);
}
