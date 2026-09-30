/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/**
 * Durable non-content outcome. Rich handler responses are never serialized into operations.db.
 * Identifiers are compact tokens; prose, diagnostic details and structured handler payloads have
 * no slot here. Unit counts come from the committed checkpoint, not the immediate response.
 */
public record OperationReceipt(String code, String executionId) {
  public OperationReceipt {
    if (!io.justsearch.agent.api.registry.OperationResult.isDurableOutcomeCode(code)) {
      throw new IllegalArgumentException("Outcome code must be a bounded token");
    }
    if (executionId != null && !executionId.matches("[A-Za-z0-9_.:/-]{1,128}")) {
      throw new IllegalArgumentException("Execution id must be a bounded identifier");
    }
  }
}
