/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Why indexing is slowed or paused. Declaration order is precedence: a {@code reasons} list is
 * sorted by it, and its first entry is the primary reason. Lower snake case on the wire.
 */
public enum IndexingReason {
  SETTINGS_RECOVERY,
  USER_PAUSED,
  SCHEDULE,
  ON_BATTERY,
  GAME_MODE,
  GPU_BUSY_EXTERNAL,
  ENERGY_SAVER,
  CHAT_GENERATING;

  /** The wire token, for example {@code gpu_busy_external}. */
  @JsonValue
  public String token() {
    return name().toLowerCase(Locale.ROOT);
  }
}
