/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.gpu.UserContextSample;
import io.justsearch.app.api.gpu.UserContextSample.NotificationState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-U4 (section 4.2): the game rule and its debounce. Game Mode is a game on its own; every
 * other branch (exclusive full screen, busy, or a foreground window covering its monitor) needs the
 * foreground process to be someone else's and to hold at least 1 GiB of dedicated graphics memory
 * on a PDH reading. Shell windows, presentation mode and "no user present" are never a game, and an
 * unread part never counts. Raised after two game polls, cleared after five without.
 */
final class GameDetectorTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final long GAME = 4_000L;
  private static final long ENGINE = 1_000L;
  private static final Set<Long> OURS = Set.of(ENGINE);
  private static final GpuDevice DEVICE =
      new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty());
  private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

  // Game Mode alone is a game: no foreground, no memory reading needed
  @Test
  void gameModeAloneIsAGame() {
    var context = new UserContextSample(NotificationState.UNKNOWN, Optional.of(true),
        OptionalLong.empty(), false, false);
    assertTrue(GameDetector.looksLikeGame(context, null, OURS));
    assertTrue(GameDetector.looksLikeGame(context, pdh(GAME, 0L), OURS));
  }

  // each non-Game-Mode branch is a game with 1 GiB of the foreground's own dedicated memory
  @Test
  void eachCandidateBranchWithOneGibIsAGame() {
    var memory = pdh(GAME, GIB);
    assertTrue(GameDetector.looksLikeGame(
        foreground(NotificationState.RUNNING_D3D_FULL_SCREEN, GAME, false), memory, OURS),
        "exclusive full screen");
    assertTrue(GameDetector.looksLikeGame(
        foreground(NotificationState.BUSY, GAME, false), memory, OURS), "busy");
    assertTrue(GameDetector.looksLikeGame(
        foreground(NotificationState.ACCEPTS_NOTIFICATIONS, GAME, true), memory, OURS),
        "borderless window covering its monitor");
  }

  // memory corroboration on every non-Game-Mode branch: just under 1 GiB is not a game
  @Test
  void eachCandidateBranchUnderOneGibIsNoGame() {
    var memory = pdh(GAME, GIB - 1);
    assertFalse(GameDetector.looksLikeGame(
        foreground(NotificationState.RUNNING_D3D_FULL_SCREEN, GAME, false), memory, OURS));
    assertFalse(GameDetector.looksLikeGame(
        foreground(NotificationState.BUSY, GAME, false), memory, OURS));
    assertFalse(GameDetector.looksLikeGame(
        foreground(NotificationState.ACCEPTS_NOTIFICATIONS, GAME, true), memory, OURS));
  }

  // a foreground window that is neither full screen, busy nor covering its monitor is no game
  @Test
  void anOrdinaryWindowIsNoGame() {
    assertFalse(GameDetector.looksLikeGame(
        foreground(NotificationState.ACCEPTS_NOTIFICATIONS, GAME, false), pdh(GAME, 8 * GIB),
        OURS));
  }

  // our own process in the foreground (the app, or our llama-server) is never a game
  @Test
  void ourOwnForegroundIsNoGame() {
    assertFalse(GameDetector.looksLikeGame(
        foreground(NotificationState.RUNNING_D3D_FULL_SCREEN, ENGINE, true), pdh(ENGINE, 8 * GIB),
        OURS));
  }

  // the desktop or taskbar in front, presentation mode and "no user present" are never a game
  @Test
  void shellPresentationAndNotPresentAreNoGame() {
    var memory = pdh(GAME, 8 * GIB);
    assertFalse(GameDetector.looksLikeGame(new UserContextSample(
        NotificationState.ACCEPTS_NOTIFICATIONS, Optional.of(false), OptionalLong.of(GAME), true,
        true), memory, OURS), "shell window");
    assertFalse(GameDetector.looksLikeGame(
        foreground(NotificationState.PRESENTATION_MODE, GAME, true), memory, OURS),
        "presentation mode");
    assertFalse(GameDetector.looksLikeGame(
        foreground(NotificationState.NOT_PRESENT, GAME, true), memory, OURS), "no user present");
  }

  // section 4.3: an unread part never counts toward a game
  @Test
  void unreadPartsAreNoGame() {
    var candidate = foreground(NotificationState.RUNNING_D3D_FULL_SCREEN, GAME, true);
    assertFalse(GameDetector.looksLikeGame(null, pdh(GAME, 8 * GIB), OURS), "no foreground reading");
    assertFalse(GameDetector.looksLikeGame(candidate, null, OURS), "no memory reading");
    assertFalse(GameDetector.looksLikeGame(candidate,
        GpuMemorySample.unknown(GpuMemorySample.UnknownReason.UNSUPPORTED_OS, T0), OURS),
        "unknown memory");
    assertFalse(GameDetector.looksLikeGame(candidate, new GpuMemorySample(GpuMemorySource.NVML,
        Optional.empty(), Optional.of(DEVICE), Optional.of("RTX"), OptionalLong.of(12 * GIB),
        OptionalLong.of(10 * GIB), Map.of(), T0), OURS),
        "NVML has no per-process figures");
    assertFalse(GameDetector.looksLikeGame(candidate, new GpuMemorySample(GpuMemorySource.PDH,
        Optional.empty(), Optional.of(DEVICE), Optional.of("RTX"), OptionalLong.of(12 * GIB),
        OptionalLong.of(10 * GIB), Map.of(), T0), OURS),
        "PDH without per-process figures");
    assertFalse(GameDetector.looksLikeGame(candidate, pdh(5_000L, 8 * GIB), OURS),
        "no figure for the foreground process");
    assertFalse(GameDetector.looksLikeGame(new UserContextSample(
        NotificationState.RUNNING_D3D_FULL_SCREEN, Optional.empty(), OptionalLong.empty(), true,
        false), pdh(GAME, 8 * GIB), OURS), "no foreground process");
    assertFalse(GameDetector.looksLikeGame(UserContextSample.unknown(), pdh(GAME, 8 * GIB), OURS),
        "nothing read");
    assertFalse(GameDetector.looksLikeGame(new UserContextSample(NotificationState.UNKNOWN,
        Optional.of(false), OptionalLong.empty(), false, false), null, OURS),
        "Game Mode read as off");
  }

  // debounce: raised on the second consecutive game poll, cleared on the fifth without
  @Test
  void debounceRaisesAfterTwoAndClearsAfterFive() {
    var detector = new GameDetector();
    List<Boolean> states = new ArrayList<>();
    for (boolean poll : new boolean[] {true, false, true, true, true, false, false, false, false,
        true, false, false, false, false, false}) {
      states.add(detector.advance(poll));
    }
    assertEquals(List.of(false, false, false, true, true, true, true, true, true, true, true, true,
        true, true, false), states);
  }

  // the boot sample decides at once; the debounce runs from there
  @Test
  void seedDecidesAtOnce() {
    var detector = new GameDetector();
    assertTrue(detector.seed(true), "a game at boot is detected on the first poll");
    assertTrue(detector.advance(false));
    var quiet = new GameDetector();
    assertFalse(quiet.seed(false));
    assertFalse(quiet.advance(true), "after a quiet boot sample the debounce applies");
    assertTrue(quiet.advance(true));
    assertFalse(quiet.reset(), "turning detection off clears at once");
  }

  private static UserContextSample foreground(NotificationState state, long pid, boolean covers) {
    return new UserContextSample(state, Optional.of(false), OptionalLong.of(pid), covers, false);
  }

  private static GpuMemorySample pdh(long pid, long dedicated) {
    return new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(), Optional.of(DEVICE),
        Optional.of("NVIDIA GeForce RTX 4070"), OptionalLong.of(12 * GIB),
        OptionalLong.of(dedicated + GIB),
        Map.of(pid, new ProcessMemory(dedicated, 0L), 9_000L, new ProcessMemory(GIB, 0L)), T0);
  }
}
