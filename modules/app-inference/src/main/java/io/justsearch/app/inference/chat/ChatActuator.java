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

  /**
   * The step that restarts the running server with video-document understanding flags (a VDU
   * hold's entry). It drains first; it fails when the server is not running.
   */
  default ChatActuation<Void> vduEnter() {
    throw new UnsupportedOperationException("this actuator has no VDU mode");
  }

  /** The step that restores the server's configuration from before {@link #vduEnter}. */
  default ChatActuation<Void> vduExit() {
    throw new UnsupportedOperationException("this actuator has no VDU mode");
  }

  /**
   * The step that stops the main server for a self-test session and keeps every later start of it
   * refused until {@link #selfTestEnd} runs. It drains first (Spec A §3.4 SELF_TEST).
   */
  default ChatActuation<Void> selfTestStop() {
    throw new UnsupportedOperationException("this actuator has no self-test");
  }

  /** The step that ends a self-test session: the main server may start again. */
  default ChatActuation<Void> selfTestEnd() {
    throw new UnsupportedOperationException("this actuator has no self-test");
  }

  /** How a residency unload step ended. */
  enum ResidencyUnload {
    /** The server stopped; chat reads UNLOADED with the step's detail. */
    UNLOADED,
    /** A lease was open (or admission fenced), so nothing changed; retry on the next idle edge. */
    BUSY,
    /** The pressure cleared before the stop, so nothing changed. */
    NOT_WANTED,
    /** The serving server is one this Engine did not start; it is never unloaded. */
    EXTERNAL
  }

  /**
   * The step that stops the idle server for another GPU user (Spec A T21) and records why, so chat
   * reads UNLOADED with that detail until its next start. Its drain closes admission only when no
   * lease is open (Spec A §3.4 RESIDENCY_PRESSURE); when one is open, the drain fails and nothing
   * changes. {@code stillWanted} is read after the drain: when it is false (the pressure cleared
   * meanwhile), nothing stops either. A server this Engine did not start is never unloaded. With
   * no server running the step records the detail and settles the stopped runtime, so a start
   * refused under pressure (Spec A CH7) reads UNLOADED too.
   */
  default ChatActuation<ResidencyUnload> residencyUnload(
      io.justsearch.app.api.runtime.child.StopDetail detail,
      java.util.function.BooleanSupplier stillWanted) {
    throw new UnsupportedOperationException("this actuator has no residency control");
  }

  /**
   * The in-flight signal of this server's generations (Spec A §5), which tells the controller when
   * chat goes idle; {@code null} when the actuator has none.
   */
  default io.justsearch.app.api.chat.GenerationActivity generationActivity() {
    return null;
  }

  /** The latched chat failure (Spec A §3.1), or {@code null} while none is latched. */
  default io.justsearch.app.api.chat.FailedCode failureLatch() {
    return null;
  }

  /** True while the server is running or starting (any state but stopped or failed). */
  default boolean serverRunning() {
    return false;
  }

  /** A serving managed chat with open admission, no exchange leases and no queued requests. */
  default boolean idleForAutomaticVdu() { return false; }

  /**
   * True when the next start of the server loads its model onto a CUDA device (GPU layers above
   * zero on a machine with CUDA). Only such a start waits behind the boot-order gate (Spec A §6.2);
   * CPU chat and machines without CUDA skip it.
   */
  default boolean gpuChat() {
    return false;
  }

  /** Runs one step's body. */
  <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception;

  /**
   * Stops the server for good and fences every later launch. Callable while another step is still
   * running on the controller thread: the fence makes that step's launch fail.
   *
   * @param reason the terminal reason the controller recorded first (QUIT, UPGRADE,
   *     ENGINE_RESTART), which the exit ledger records for each server stopped
   */
  void terminalStop(io.justsearch.app.api.runtime.child.StopReason reason, Duration timeout);
}
