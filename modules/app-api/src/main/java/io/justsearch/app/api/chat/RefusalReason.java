/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * Why one chat request was not admitted. It travels only on responses ({@link ChatRefusal}, the
 * {@code CHAT_UNAVAILABLE} error) and is never a state.
 */
public enum RefusalReason {
  GPU_BUSY,
  GAME_MODE,
  RELOAD_BACKOFF,
  LOADING,
  BUSY,
  OFF,
  FAILED,
  REFUSED,
  ENGINE_STOPPING,
  DEVICE_RECOVERING,
  SELF_TEST
}
