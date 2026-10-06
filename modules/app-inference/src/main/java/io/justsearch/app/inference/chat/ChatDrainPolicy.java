/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.runtime.child.StopReason;
import java.time.Duration;
import java.util.Objects;

/**
 * What happens to in-flight replies before the chat server stops for {@code reason} (Spec A §3.4).
 *
 * @param reason why the server stops or relaunches
 * @param waiting whether the drain waits for open leases
 * @param bound how long an {@link Waiting#AFTER_IDLE} drain waits; {@code null} means unbounded
 * @param onExpiry what an {@link Waiting#AFTER_IDLE} drain does when the bound runs out with leases
 *     still open
 * @param onlyWhileServing when true and no server is serving, nothing can be in flight against the
 *     server that stops, so the drain closes admission without waiting
 */
public record ChatDrainPolicy(
    StopReason reason, Waiting waiting, Duration bound, Expiry onExpiry, boolean onlyWhileServing) {

  /** How the drain treats open leases. */
  public enum Waiting {
    /** Close admission and stop at once; open replies end with the server. */
    NOW,
    /** Close admission and wait for every open lease, up to the bound. */
    AFTER_IDLE,
    /** Close admission only if no lease is open, atomically with admission; never wait or kill. */
    IF_IDLE
  }

  /** What an {@link Waiting#AFTER_IDLE} drain does when its bound runs out. */
  public enum Expiry {
    /** Reopen admission and refuse the change as busy; the replies keep running. */
    REFUSE_BUSY,
    /** Keep admission closed and stop anyway. */
    PROCEED_NOW,
    /** Wait for the next idle edge (unbounded drains and pressure unloads). */
    DEFER
  }

  public ChatDrainPolicy {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(waiting, "waiting");
    Objects.requireNonNull(onExpiry, "onExpiry");
    if (bound != null && bound.isNegative()) throw new IllegalArgumentException("negative bound");
  }

  /**
   * The Spec A §3.4 row for {@code reason}.
   *
   * @param afterIdleBound the AFTER_IDLE bound ({@link ChatActuationRouter#DRAIN_BOUND} in the
   *     Engine)
   */
  public static ChatDrainPolicy forReason(StopReason reason, Duration afterIdleBound) {
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(afterIdleBound, "afterIdleBound");
    return switch (reason) {
      case QUIT, UPGRADE, ENGINE_RESTART, DEVICE_LOST, TIMEOUT, ROLLBACK, CRASH_RECOVERY, CANCELLED ->
          new ChatDrainPolicy(reason, Waiting.NOW, Duration.ZERO, Expiry.PROCEED_NOW, false);
      case RECONFIGURE, MODEL_SWITCH, VDU_ENTER, SELF_TEST, RECYCLE ->
          new ChatDrainPolicy(
              reason, Waiting.AFTER_IDLE, afterIdleBound, Expiry.REFUSE_BUSY, false);
      case DEACTIVATE ->
          new ChatDrainPolicy(
              reason, Waiting.AFTER_IDLE, afterIdleBound, Expiry.PROCEED_NOW, false);
      case RESIDENCY_PRESSURE, IDLE_UNLOAD ->
          new ChatDrainPolicy(reason, Waiting.IF_IDLE, Duration.ZERO, Expiry.DEFER, false);
      case VDU_EXIT -> new ChatDrainPolicy(reason, Waiting.AFTER_IDLE, null, Expiry.DEFER, false);
    };
  }

  /** The same row, waiting only while a server is serving (a stop of the serving server). */
  public ChatDrainPolicy whileServing() {
    return new ChatDrainPolicy(reason, waiting, bound, onExpiry, true);
  }
}
