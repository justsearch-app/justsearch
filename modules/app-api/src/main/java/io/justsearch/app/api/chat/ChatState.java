/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * The state of the managed chat server, as one closed set. Generation activity ({@code generating},
 * {@code queued}) is reported beside the state, never as a state. The Engine leaving ({@code QUIT},
 * {@code UPGRADE}, {@code ENGINE_RESTART}, {@code DEVICE_LOST}) is not a chat state: the status
 * surface omits the chat block and lifecycle speaks instead.
 */
public enum ChatState {
  /** Chat is not wanted: disabled, not configured or not installed. */
  OFF,
  /** The server is starting or loading its model. */
  LOADING,
  /** The server is healthy and accepting requests. */
  READY,
  /** It was resident; the controller stopped it and reloads it on demand. */
  UNLOADED,
  /** A load was demanded and declined under the current conditions. */
  REFUSED,
  /** Load or run failed, and the controller does not retry this configuration. */
  FAILED
}
