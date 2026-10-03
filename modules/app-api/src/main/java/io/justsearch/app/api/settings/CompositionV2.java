/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.settings;

import java.util.Set;

/** Response-only evidence for the physical composition decision made by a settings write. */
public record CompositionV2(
    String mode,
    String reason,
    Long freeBytes,
    Long footprintBytes) {
  private static final Set<String> MODES = Set.of("BESIDE", "IN_PLACE", "REFUSED");

  public CompositionV2 {
    if (!MODES.contains(mode)) throw new IllegalArgumentException("Invalid composition mode");
    if (reason != null && reason.isBlank()) {
      throw new IllegalArgumentException("Composition reason must be null or non-blank");
    }
    if ((freeBytes != null && freeBytes < 0) || (footprintBytes != null && footprintBytes < 0)) {
      throw new IllegalArgumentException("Composition byte evidence must be non-negative");
    }
  }
}
