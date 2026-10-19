/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

/** What background indexing is doing right now. */
public enum IndexingState {
  /** Nothing is pending. Pause conditions may still be listed in {@code reasons}. */
  IDLE,
  RUNNING,
  /** Running at the duty floor while chat is generating. */
  SLOWED,
  PAUSED
}
