/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.util.Objects;

/** Bounded diagnostic evidence for the most recent composition decision. */
public record ComposeEvidence(
    Mode mode,
    String reason,
    Long freeBytes,
    Long footprintBytes) {

  public ComposeEvidence {
    Objects.requireNonNull(mode, "mode");
    if (reason != null && reason.isBlank()) {
      throw new IllegalArgumentException("reason must be null or non-blank");
    }
    if ((freeBytes != null && freeBytes < 0)
        || (footprintBytes != null && footprintBytes < 0)) {
      throw new IllegalArgumentException("byte evidence must be non-negative");
    }
  }

  public enum Mode {
    BESIDE,
    IN_PLACE
  }
}
