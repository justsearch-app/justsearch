/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.inference.LlamaServerExitClassifier.Classification;
import io.justsearch.app.inference.LlamaServerExitClassifier.CrashClass;
import java.time.Duration;
import java.util.Objects;

/**
 * What the chat controller does after llama-server exits unexpectedly (Spec A §3.2 T6-T11 and
 * T27-T30, §7.2). One table for both phases, and the only place a relaunch is decided:
 *
 * <ul>
 *   <li>There is no same-configuration retry loop. A start that fails is never retried with the
 *       same configuration; the one exception is a lost port bind, relaunched once on a new port at
 *       the same rung (ruling CH3).
 *   <li>Only {@link CrashClass#RESOURCE} steps the context window down, one rung, and never below
 *       an operator's explicit window (P4: an override fails loud).
 *   <li>A server that crashes with class OTHER after {@link
 *       BrainSupervisionPolicy#DEFAULT_STABILITY_WINDOW_MS} of READY earns one same-configuration
 *       restart; an earlier crash, or a second one, is {@code crash_loop}. A hang counts as OTHER.
 * </ul>
 */
public final class ChatCrashPolicy {

  /** Spec A §3.3: how long READY must have lasted for a crash to earn one restart. */
  public static final Duration STABILITY_WINDOW =
      Duration.ofMillis(BrainSupervisionPolicy.DEFAULT_STABILITY_WINDOW_MS);

  /** What to do next. */
  public enum Action {
    /** Relaunch one context rung lower (T6, T27). The rung is sticky for the Engine's life. */
    STEP_DOWN,
    /** Relaunch with the same configuration: once on a lost bind (CH3), once after T28. */
    RESTART_SAME,
    /** Stay FAILED with {@link Decision#failedCode()} until an operator retry or a change (T34). */
    FAIL
  }

  /**
   * One decision.
   *
   * @param action what to do
   * @param failedCode the FAILED code when {@code action} is FAIL, otherwise null
   * @param rule the Spec A row that decided it, for the log
   */
  public record Decision(Action action, FailedCode failedCode, String rule) {
    public Decision {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(rule, "rule");
      if ((action == Action.FAIL) != (failedCode != null)) {
        throw new IllegalArgumentException("a failed code goes with FAIL and only with FAIL");
      }
    }

    static Decision fail(FailedCode code, String rule) {
      return new Decision(Action.FAIL, code, rule);
    }

    static Decision act(Action action, String rule) {
      return new Decision(action, null, rule);
    }
  }

  private ChatCrashPolicy() {}

  /**
   * The server exited before it became healthy (STARTING). A rejected {@code --reasoning-budget}
   * is decided before this (T8: relaunch once without the flag).
   *
   * @param exit the classified exit
   * @param lowerRungAvailable a lower rung exists and the window is not an operator override
   * @param portRetryUsed this start already relaunched once after a lost bind
   */
  public static Decision afterStartExit(
      Classification exit, boolean lowerRungAvailable, boolean portRetryUsed) {
    Objects.requireNonNull(exit, "exit");
    return switch (exit.crashClass()) {
      case RESOURCE -> lowerRungAvailable
          ? Decision.act(Action.STEP_DOWN, "T6")
          : Decision.fail(FailedCode.RESOURCE_EXHAUSTED, "T7");
      case PORT_LOST -> portRetryUsed
          ? Decision.fail(FailedCode.START_FAILED, "CH3")
          : Decision.act(Action.RESTART_SAME, "CH3");
      case CONFIG -> Decision.fail(exit.failedCode(), "T9");
      case DEVICE_SUSPECT -> Decision.fail(FailedCode.START_FAILED, "7.1 DEVICE_SUSPECT");
      case OTHER -> Decision.fail(FailedCode.START_FAILED, "T10");
    };
  }

  /**
   * The server failed after it was READY: it exited, or it stopped answering health (T30, a hang,
   * which counts as OTHER).
   *
   * @param exit the classified exit; for a hang, class OTHER
   * @param readyFor how long it had been READY, on the monotonic clock
   * @param sameConfigRestartUsed this configuration already had its one T28 restart
   * @param lowerRungAvailable a lower rung exists and the window is not an operator override
   */
  public static Decision afterReadyFailure(
      Classification exit,
      Duration readyFor,
      boolean sameConfigRestartUsed,
      boolean lowerRungAvailable) {
    Objects.requireNonNull(exit, "exit");
    Objects.requireNonNull(readyFor, "readyFor");
    return switch (exit.crashClass()) {
      case RESOURCE -> lowerRungAvailable
          ? Decision.act(Action.STEP_DOWN, "T27")
          : Decision.fail(FailedCode.RESOURCE_EXHAUSTED, "T27");
      case CONFIG -> Decision.fail(exit.failedCode(), "T9");
      case DEVICE_SUSPECT -> Decision.fail(FailedCode.CRASH_LOOP, "7.1 DEVICE_SUSPECT");
      case PORT_LOST, OTHER -> readyFor.compareTo(STABILITY_WINDOW) >= 0 && !sameConfigRestartUsed
          ? Decision.act(Action.RESTART_SAME, "T28")
          : Decision.fail(FailedCode.CRASH_LOOP, "T29");
    };
  }
}
