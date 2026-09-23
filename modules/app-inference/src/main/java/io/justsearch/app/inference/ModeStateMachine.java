/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.Mode;

/**
 * Tracks the current inference mode and validates transitions.
 *
 * <p>This class does NOT own any locks. All mutation methods must be called under the caller's
 * synchronization (the ILM's {@code lock} object). The {@link #current()} accessor reads the
 * volatile field and is safe for unsynchronized reads.
 *
 * <p>Valid transition flows:
 *
 * <pre>
 *   {OFFLINE, ONLINE, INDEXING} → beginTransition() → TRANSITIONING
 *   TRANSITIONING → complete(target) → {ONLINE, INDEXING, OFFLINE}
 *   TRANSITIONING → rollback() → {previous mode}
 *   {any} → forceOffline() → OFFLINE   (crash recovery, external failure, close)
 * </pre>
 */
final class ModeStateMachine {

  private volatile Mode currentMode = Mode.OFFLINE;
  private Mode previousMode;

  /** Returns the current mode. Safe for unsynchronized reads (volatile). */
  Mode current() {
    return currentMode;
  }

  /**
   * Begin a mode transition. Stores the current mode for potential {@link #rollback()}.
   *
   * @return the mode being left (for listener notification)
   * @throws IllegalStateException if already transitioning
   */
  Mode beginTransition() {
    if (currentMode == Mode.TRANSITIONING) {
      throw new IllegalStateException("Already transitioning");
    }
    previousMode = currentMode;
    currentMode = Mode.TRANSITIONING;
    return previousMode;
  }

  /**
   * Complete the transition to the target mode.
   *
   * @param target the mode to transition to (must not be TRANSITIONING)
   * @throws IllegalStateException if not currently transitioning
   */
  void complete(Mode target) {
    if (currentMode != Mode.TRANSITIONING) {
      throw new IllegalStateException("Not transitioning, current=" + currentMode);
    }
    if (target == Mode.TRANSITIONING) {
      throw new IllegalArgumentException("Cannot complete to TRANSITIONING");
    }
    currentMode = target;
    previousMode = null;
  }

  /**
   * Roll back to the mode that was active before {@link #beginTransition()} was called.
   *
   * @return the restored mode
   * @throws IllegalStateException if not currently transitioning
   */
  Mode rollback() {
    if (currentMode != Mode.TRANSITIONING) {
      throw new IllegalStateException("Not transitioning, current=" + currentMode);
    }
    Mode restored = previousMode;
    currentMode = restored;
    previousMode = null;
    return restored;
  }

  /**
   * Force transition to OFFLINE regardless of current state. Used for emergency shutdown, crash
   * recovery exhaustion, and external server failure.
   *
   * @return the mode being left (for listener notification)
   */
  Mode forceOffline() {
    Mode prev = currentMode;
    currentMode = Mode.OFFLINE;
    previousMode = null;
    return prev;
  }

  /**
   * Installs an already validated stable mode during an outer atomic publication. The caller owns
   * validation and serialization; this method deliberately performs assignments only.
   */
  void installPrepared(Mode target) {
    currentMode = target;
    previousMode = null;
  }
}
