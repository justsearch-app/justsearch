/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** Why chat is {@link ChatState#LOADING}. */
public enum LoadingCause {
  COLD_START,
  /** Waiting for the query encoders before the first load after an Engine start. */
  BOOT_ORDER,
  RECONFIGURE,
  MODEL_SWITCH,
  /** Reloading after video-document understanding released the GPU. */
  VDU,
  RECOVERY,
  /** Reloading on demand after an unload. */
  RELOAD,
  /** Relaunching at a smaller context after running out of memory. */
  STEP_DOWN
}
