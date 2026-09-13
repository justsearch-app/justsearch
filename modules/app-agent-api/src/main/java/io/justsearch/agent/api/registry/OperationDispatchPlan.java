/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side preparation result, never an execution or consent capability. Payload stays in
 * operations.db; a ready reference must pass current authorization again at dispatch. A recorded
 * result is an observation, not permission to replay its effect. Never serialize this as public
 * arguments or broadcast its approval display.
 */
public sealed interface OperationDispatchPlan {
  String operationKey();

  /** The existing row won; no preparation or approval is needed to read its receipt. */
  record Recorded(String operationKey, OperationResult result) implements OperationDispatchPlan {
    public Recorded {
      Objects.requireNonNull(operationKey, "operationKey");
      Objects.requireNonNull(result, "result");
    }
    @Override public String toString() { return "OperationDispatchPlan.Recorded[redacted]"; }
  }

  /** A null nonce denotes a passthrough handler; display is optional and never execution input. */
  record Ready(String operationKey, UUID preparationNonce,
      Optional<OperationApprovalPreview> approvalPreview) implements OperationDispatchPlan {
    public Ready {
      Objects.requireNonNull(operationKey, "operationKey");
      Objects.requireNonNull(approvalPreview, "approvalPreview");
      if (operationKey.isBlank() || (approvalPreview.isPresent() && preparationNonce == null)) {
        throw new IllegalArgumentException("Preparation reference is incomplete");
      }
    }
    @Override public String toString() { return "OperationDispatchPlan.Ready[redacted]"; }
  }
}
