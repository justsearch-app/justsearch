/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** External GPU pressure reported to the chat controller. {@link #NONE} clears it. */
public enum PressureSignal {
  NONE,
  EXTERNAL_MEMORY,
  GAME,
  /**
   * A game and another application's memory need at once. The game outranks the memory need
   * wherever a game counts; where a game alone does not (a profile that fits chat beside it), the
   * memory need still does, so a game never hides it.
   */
  GAME_AND_EXTERNAL_MEMORY
}
