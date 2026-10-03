/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Point-to-point approval display, derived only from frozen target/root/scope metadata.
 * Never include document bodies, prompts or credentials. This is not execution authority,
 * and must not be included in logs, action history or routing broadcasts.
 */
public record OperationApprovalPreview(String summary) {
  public OperationApprovalPreview {
    Objects.requireNonNull(summary, "summary");
    if (summary.isBlank() || summary.getBytes(StandardCharsets.UTF_8).length > 8192) {
      throw new IllegalArgumentException("Approval preview must contain 1 to 8192 UTF-8 bytes");
    }
  }

  @Override public String toString() { return "OperationApprovalPreview[redacted]"; }
}
