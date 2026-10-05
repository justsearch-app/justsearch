/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status.operating;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** The sampled blocks of the {@code operating} status, as named in {@code staleSources}. */
public enum OperatingBlock {
  CHAT,
  INDEXING,
  GPU;

  /** The wire token: the block's JSON key. */
  @JsonValue
  public String token() {
    return name().toLowerCase(Locale.ROOT);
  }
}
