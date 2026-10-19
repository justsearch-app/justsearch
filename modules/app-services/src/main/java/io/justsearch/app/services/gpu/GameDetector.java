/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
import java.util.Set;

/**
 * Game detection (Spec E section 4.2, step E4; RV3 F19). Pure: the rule over one poll's readings,
 * and the debounce over consecutive polls; no clock or thread of its own.
 *
 * <pre>
 * game = GAME_MODE
 *     || ((D3D_FULL_SCREEN || QUNS_BUSY || foregroundCoversMonitor)
 *         &amp;&amp; foregroundPid not in ours
 *         &amp;&amp; pdhKnown &amp;&amp; dedicated(foregroundPid) &gt;= gameForegroundVramMiB)
 * </pre>
 *
 * <ul>
 *   <li>Shell windows (the desktop and the taskbar), {@code QUNS_NOT_PRESENT} and {@code
 *       QUNS_PRESENTATION_MODE} are never a candidate. Game Mode is a game on its own.
 *   <li>Every branch except Game Mode needs at least {@link #FOREGROUND_VRAM_BYTES} of dedicated
 *       graphics memory on the foreground process, read from PDH (a false positive pauses bulk work
 *       and, through Spec A, may unload an idle chat).
 *   <li>A part that could not be read never counts toward a game (section 4.3).
 *   <li>Debounce: raised after {@link #RAISE_POLLS} consecutive game polls, cleared after {@link
 *       #CLEAR_POLLS} consecutive polls without one.
 * </ul>
 */
public final class GameDetector {

  /** {@code gameForegroundVramMiB} 1,024 (Spec E section 0), provisional until M7. */
  public static final long FOREGROUND_VRAM_BYTES = 1_024L * 1024L * 1024L;

  /** Consecutive game polls that raise (4 s at the 2 s period). */
  public static final int RAISE_POLLS = 2;

  /** Consecutive polls without a game that clear (10 s). */
  public static final int CLEAR_POLLS = 5;

  private boolean game;
  private int raiseCount;
  private int clearCount;

  /**
   * The rule over one poll.
   *
   * @param context the foreground reading; null when it could not be taken
   * @param memory the same tick's graphics memory reading; null when it could not be taken
   * @param ours our processes: the Engine and its descendants (the same set as section 3)
   */
  public static boolean looksLikeGame(
      UserContextSample context, GpuMemorySample memory, Set<Long> ours) {
    if (context == null) return false;
    if (context.gameMode().orElse(false)) return true;
    NotificationState state = context.notificationState();
    if (state == NotificationState.NOT_PRESENT || state == NotificationState.PRESENTATION_MODE) {
      return false;
    }
    if (context.foregroundIsShell()) return false;
    boolean candidate =
        state == NotificationState.RUNNING_D3D_FULL_SCREEN
            || state == NotificationState.BUSY
            || context.foregroundCoversMonitor();
    if (!candidate || context.foregroundPid().isEmpty()) return false;
    long foreground = context.foregroundPid().getAsLong();
    if (ours.contains(foreground)) return false;
    if (memory == null
        || !memory.known()
        || memory.source() != GpuMemorySource.PDH
        || memory.processes().isEmpty()) {
      return false;
    }
    return memory
        .process(foreground)
        .map(m -> m.dedicatedBytes() >= FOREGROUND_VRAM_BYTES)
        .orElse(false);
  }

  /**
   * Advances the debounce by one poll.
   *
   * @return whether a game is detected after this poll
   */
  public boolean advance(boolean looksLikeGame) {
    if (looksLikeGame) {
      raiseCount++;
      clearCount = 0;
    } else {
      clearCount++;
      raiseCount = 0;
    }
    if (!game && raiseCount >= RAISE_POLLS) {
      game = true;
      raiseCount = 0;
    } else if (game && clearCount >= CLEAR_POLLS) {
      game = false;
      clearCount = 0;
    }
    return game;
  }

  /**
   * The boot sample (section 4.4, reconciliation CH7): before the first poll there is nothing to
   * debounce against, so the first reading decides at once. A game running when the app starts is
   * detected before the chat controller's first start, not two polls later.
   *
   * @return whether a game is detected after this poll
   */
  public boolean seed(boolean looksLikeGame) {
    game = looksLikeGame;
    raiseCount = 0;
    clearCount = 0;
    return game;
  }

  /** Detection turned off: no game, and the debounce starts over. */
  public boolean reset() {
    return seed(false);
  }
}
