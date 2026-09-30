/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.app.api.settings.CompositionV2;
import java.util.Set;

/**
 * Durable non-content outcome. Rich handler responses are never serialized into operations.db.
 * Identifiers are compact tokens; the only structured exception is a validated physical
 * composition decision needed for settings replay. Prose and other handler payloads have no slot
 * here. Unit counts come from the committed checkpoint, not the immediate response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationReceipt(String code, String executionId, CompositionV2 composition) {
  private static final Set<String> COMPOSITION_REASONS = Set.of(
      "candidate_fits_free_device_memory",
      "source_holds_no_device_memory",
      "candidate_exceeds_releasable_device_memory",
      "free_device_memory_unknown",
      "source_releasable_device_memory_unknown",
      "candidate_fits_after_source_release");

  public OperationReceipt(String code, String executionId) {
    this(code, executionId, null);
  }

  public OperationReceipt {
    if (!io.justsearch.agent.api.registry.OperationResult.isDurableOutcomeCode(code)) {
      throw new IllegalArgumentException("Outcome code must be a bounded token");
    }
    if (executionId != null && !executionId.matches("[A-Za-z0-9_.:/-]{1,128}")) {
      throw new IllegalArgumentException("Execution id must be a bounded identifier");
    }
    if (composition != null && (composition.reason() == null
        || !COMPOSITION_REASONS.contains(composition.reason()))) {
      throw new IllegalArgumentException("Composition reason must be a durable decision token");
    }
  }
}
