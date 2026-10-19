/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpu;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One reading of what the user is doing in the foreground, the inputs of game detection (Spec E
 * section 4.1). Every part is read separately; a part that could not be read is unknown on its own
 * ({@link NotificationState#UNKNOWN}, an empty {@code gameMode}, an empty {@code foregroundPid}), and
 * an unknown part never counts toward a game (section 4.3).
 *
 * @param notificationState the shell's user notification state ({@code
 *     SHQueryUserNotificationState})
 * @param gameMode whether the effective power mode is Game Mode; empty until the power notification
 *     has reported, or when it could not be registered
 * @param foregroundPid the process that owns the foreground window; empty without one
 * @param foregroundCoversMonitor the foreground window's rectangle covers its whole monitor
 * @param foregroundIsShell the foreground window is the desktop or the taskbar ({@code Progman},
 *     {@code WorkerW}, {@code Shell_TrayWnd})
 */
public record UserContextSample(
    NotificationState notificationState,
    Optional<Boolean> gameMode,
    OptionalLong foregroundPid,
    boolean foregroundCoversMonitor,
    boolean foregroundIsShell) {

  /** {@code QUERY_USER_NOTIFICATION_STATE}, plus {@link #UNKNOWN} when it could not be read. */
  public enum NotificationState {
    UNKNOWN,
    NOT_PRESENT,
    BUSY,
    RUNNING_D3D_FULL_SCREEN,
    PRESENTATION_MODE,
    ACCEPTS_NOTIFICATIONS,
    QUIET_TIME,
    APP;

    /** The state for a {@code QUNS_*} value; {@link #UNKNOWN} for any other value. */
    public static NotificationState fromQuns(int quns) {
      return switch (quns) {
        case 1 -> NOT_PRESENT;
        case 2 -> BUSY;
        case 3 -> RUNNING_D3D_FULL_SCREEN;
        case 4 -> PRESENTATION_MODE;
        case 5 -> ACCEPTS_NOTIFICATIONS;
        case 6 -> QUIET_TIME;
        case 7 -> APP;
        default -> UNKNOWN;
      };
    }
  }

  public UserContextSample {
    Objects.requireNonNull(notificationState, "notificationState");
    Objects.requireNonNull(gameMode, "gameMode");
    Objects.requireNonNull(foregroundPid, "foregroundPid");
  }

  /** Nothing could be read. */
  public static UserContextSample unknown() {
    return new UserContextSample(
        NotificationState.UNKNOWN, Optional.empty(), OptionalLong.empty(), false, false);
  }
}
