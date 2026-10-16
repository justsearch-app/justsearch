/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** What currently keeps an unloaded or refused chat server from reloading. */
public enum BlockedBy {
  /** Another application is using the graphics card's memory. */
  GPU_BUSY,
  /** A game is running. */
  GAME_MODE,
  /** The minimum dwell after a pressure unload is still running. */
  RELOAD_BACKOFF
}
