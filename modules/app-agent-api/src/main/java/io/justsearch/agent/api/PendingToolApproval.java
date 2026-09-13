/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import io.justsearch.agent.api.registry.OperationApprovalPreview;
import java.util.Objects;
import java.util.Optional;

/**
 * Read view of an existing live gate. Only detail belongs in events/snapshots; the bounded frozen
 * preview is point-to-point display and never execution input, authority or durable run history.
 */
public record PendingToolApproval(
    AgentEvent.PendingApproval detail, Optional<OperationApprovalPreview> preview) {
  public PendingToolApproval {
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(preview, "preview");
  }
  @Override public String toString() { return "PendingToolApproval[redacted]"; }
}
