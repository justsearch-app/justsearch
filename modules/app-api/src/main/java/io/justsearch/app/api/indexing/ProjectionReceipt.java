/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import java.util.Objects;

/** Exact applied generation and visibility of one source-owned projection mutation. */
public record ProjectionReceipt(
    String sourceId, String documentId, long sourceRevision,
    String generationId, Visibility visibility) {
  public enum Visibility { NRT, DURABLE, TEXT_ONLY_SEMANTIC_AT_ACTIVATION }

  public ProjectionReceipt {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(documentId, "documentId");
    if (sourceRevision < 0) throw new IllegalArgumentException("Negative source revision");
    if (generationId == null || generationId.isBlank()) {
      throw new IllegalArgumentException("Projection receipt requires generation");
    }
    Objects.requireNonNull(visibility, "visibility");
  }
}
