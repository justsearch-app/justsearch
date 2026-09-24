/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.telemetry;

/**
 * Reason for a logical mode transition. Surfaced as the {@code reason} tag on
 * {@code inference.transition.total} / {@code inference.transition.duration_ms}. Bounded set so
 * the tag's cardinality is closed.
 */
public enum TransitionReason {
  /** Operator/UI explicitly requested {@code switchToOnlineMode} / {@code switchToIndexingMode}. */
  USER_SWITCH("user_switch"),
  /** Triggered by {@code applyConfig} (config change requiring a server restart). */
  CONFIG_APPLY("config_apply"),
  /** Crash recovery callback fired after the periodic-health threshold tripped. */
  CRASH_RECOVERY("crash_recovery"),
  /** External-server detached: {@code detachExternalServer} returned to operator-controlled mode. */
  EXTERNAL_DETACH("external_detach"),
  /** Vision/Document-Understanding mode entered: {@code enterVduMode}. */
  VDU_ENTER("vdu_enter"),
  /** Vision/Document-Understanding mode exited: {@code exitVduMode}. */
  VDU_EXIT("vdu_exit"),
  /** Boot-time auto-start of the inference runtime when no operator action was needed. */
  AUTO_START("auto_start"),
  /** An operator requested a runtime refresh through the accepted reconfigure path. */
  ADMIN_TRIGGERED("admin_triggered"),
  /** JVM shutdown / process termination path. */
  SHUTDOWN("shutdown"),
  /** Fallback when the originating call site supplied no reason; should not appear in production. */
  UNKNOWN("unknown");

  private final String wireValue;

  TransitionReason(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
