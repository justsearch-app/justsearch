/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

/** Optional saved user policy. The shared hold evaluates its deadline against the wall clock. */
public record IndexingPausePolicy(boolean paused, Long untilEpochMs, String reason) {
  public IndexingPausePolicy {
    if (paused && !"USER_PAUSED".equals(reason) && !"SETTINGS_RECOVERY".equals(reason)) {
      throw new IllegalArgumentException("Unknown indexing pause reason");
    }
    if (!paused && (untilEpochMs != null || reason != null)) {
      throw new IllegalArgumentException("A resumed policy has no deadline or reason");
    }
  }
}
