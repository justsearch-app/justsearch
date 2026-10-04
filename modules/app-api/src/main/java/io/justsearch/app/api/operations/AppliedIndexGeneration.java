/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.util.Objects;

/** Immutable observation of the committed physical inputs served by one active generation. */
public record AppliedIndexGeneration(String generationId, IndexTargetSnapshot target) {
  public AppliedIndexGeneration {
    if (generationId == null || generationId.isBlank()) {
      throw new IllegalArgumentException("Applied index generation id must be nonblank");
    }
    Objects.requireNonNull(target, "target");
  }
}
