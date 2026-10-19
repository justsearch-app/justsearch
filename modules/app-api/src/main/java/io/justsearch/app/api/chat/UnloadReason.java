/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * Why chat is {@link ChatState#UNLOADED}. Entering video-document understanding is not an unload
 * reason: it is a hold that keeps the server stopped, and chat reports it as loading on exit.
 */
public enum UnloadReason {
  /** Unloaded for another GPU user. The detail is {@code gpu_busy} or {@code game_mode}. */
  RESIDENCY_PRESSURE,
  /** The main server is stopped while an activation self-test runs. */
  SELF_TEST,
  /** Reserved: no producer schedules an idle unload yet. */
  IDLE_UNLOAD
}
