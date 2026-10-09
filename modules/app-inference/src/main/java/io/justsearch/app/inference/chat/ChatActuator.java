/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import java.time.Duration;
import java.util.function.BooleanSupplier;

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
      BooleanSupplier stillWanted) {
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

  /**
   * True when the next start of the server loads its model onto a CUDA device (GPU layers above
   * zero on a machine with CUDA). Only such a start waits behind the boot-order gate (Spec A §6.2);
   * CPU chat and machines without CUDA skip it.
   */
  default boolean gpuChat() {
    return false;
  }

  /**
   * The step for a lost device (Spec A §7.3): it drains first (NOW), stops the server at once with
   * reason {@code DEVICE_LOST}, and only then asks {@code restartEngine} whether the Engine
   * restarts for it (the ledger is read after the stop). When it does not (over the limit, or the
   * limit could not be read), the step latches chat FAILED with {@code device_lost_limit}; an
   * operator retry, a change or a resume clears the latch (T34).
   *
   * @return whether the Engine restarts for the lost device
   */
  default ChatActuation<Boolean> deviceLostStop(BooleanSupplier restartEngine) {
    throw new UnsupportedOperationException("this actuator has no device-lost stop");
  }

  /**
   * Resets the server's timers after the machine resumed (Spec A §7.4), at once and from any
   * thread, without waiting for a step in progress: the health streak, the fixed-delay periodic
   * health schedule and a start's health deadline restart in full, so suspended time is not
   * counted. Also clears a latched failure (T34).
   */
  default void noteResume() {}

  /** How a resume probe ended (Spec A §7.4). */
  enum ResumeOutcome {
    /** No server this Engine started was serving: there was nothing to probe. */
    NOT_SERVING,
    /** The server answered, or a reply in progress finished. */
    PASSED,
    /** The process had exited: its crash path handles it. */
    EXITED,
    /** The probe failed and the server's output names a lost device ({@code deviceLostCode}). */
    DEVICE_LOST,
    /** The probe failed otherwise: the server was stopped (TIMEOUT) and relaunched once. */
    RELAUNCHED
  }

  /**
   * A resume probe's result.
   *
   * @param deviceLostCode the device-lost status code, present only for {@link
   *     ResumeOutcome#DEVICE_LOST}
   */
  record ResumeProbe(ResumeOutcome outcome, java.util.Optional<String> deviceLostCode) {
    public ResumeProbe {
      java.util.Objects.requireNonNull(outcome, "outcome");
      java.util.Objects.requireNonNull(deviceLostCode, "deviceLostCode");
      if ((outcome == ResumeOutcome.DEVICE_LOST) != deviceLostCode.isPresent()) {
        throw new IllegalArgumentException("a device-lost code goes with DEVICE_LOST only");
      }
    }

    public static ResumeProbe of(ResumeOutcome outcome) {
      return new ResumeProbe(outcome, java.util.Optional.empty());
    }

    public static ResumeProbe deviceLost(String code) {
      return new ResumeProbe(ResumeOutcome.DEVICE_LOST, java.util.Optional.of(code));
    }
  }

  /**
   * The step that probes a serving server after a resume (Spec A §7.4, T33): it waits for a reply
   * in progress (one that finishes counts as a pass), then asks {@code /health} (5 s) and for one
   * token (10 s). A dead process is left to its crash path; device-lost text is reported in the
   * result; any other failure stops the server (TIMEOUT) and relaunches it once, whatever the
   * stability window says. It does not drain.
   */
  default ChatActuation<ResumeProbe> resumeProbe() {
    return ChatActuation.of(ChatActuation.Kind.RESUME_PROBE,
        drained -> ResumeProbe.of(ResumeOutcome.NOT_SERVING));
  }

  /** Runs one step's body. */
  <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception;

  /**
   * Stops the server for good and fences every later launch. Callable while another step is still
   * running on the controller thread: the fence makes that step's launch fail.
   *
   * @param reason the terminal reason the controller recorded first (QUIT, UPGRADE,
   *     ENGINE_RESTART, DEVICE_LOST), which the exit ledger records for each server stopped
   */
  void terminalStop(io.justsearch.app.api.runtime.child.StopReason reason, Duration timeout);
}
