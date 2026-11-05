/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** Private saved authority and revocation latch. Absence is legacy, never implied approval. */
public record ImportApprovalState(ImportApprovalBinding binding, long interruptionRevision,
    String interruptionReason) {
  public ImportApprovalState {
    if (interruptionRevision < 0) throw new IllegalArgumentException("Negative interruption revision");
  }
}
