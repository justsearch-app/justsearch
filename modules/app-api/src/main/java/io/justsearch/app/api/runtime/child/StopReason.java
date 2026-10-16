/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

/**
 * Why an owner asked a held process to stop. Written into every {@code stop-intent}, {@code
 * child-exit} and {@code engine-self-intent} record. The wire value is the constant name.
 *
 * <p>The first four ({@link #QUIT}, {@link #UPGRADE}, {@link #ENGINE_RESTART}, {@link
 * #DEVICE_LOST}) end the Engine itself, so they are never shown as a chat state.
 */
public enum StopReason {
  QUIT,
  UPGRADE,
  ENGINE_RESTART,
  DEVICE_LOST,
  RECONFIGURE,
  MODEL_SWITCH,
  DEACTIVATE,
  RESIDENCY_PRESSURE,
  /** Reserved: no producer schedules an idle unload yet. */
  IDLE_UNLOAD,
  VDU_ENTER,
  VDU_EXIT,
  SELF_TEST,
  TIMEOUT,
  ROLLBACK,
  CRASH_RECOVERY,
  RECYCLE,
  CANCELLED;

  /** True for the reasons that end the Engine incarnation instead of continuing it. */
  public boolean endsEngine() {
    return switch (this) {
      case QUIT, UPGRADE, ENGINE_RESTART, DEVICE_LOST -> true;
      case RECONFIGURE, MODEL_SWITCH, DEACTIVATE, RESIDENCY_PRESSURE, IDLE_UNLOAD, VDU_ENTER,
          VDU_EXIT, SELF_TEST, TIMEOUT, ROLLBACK, CRASH_RECOVERY, RECYCLE, CANCELLED -> false;
    };
  }
}
