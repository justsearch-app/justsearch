/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata-only history wire row. Committed rows derive from the operations table; operationKey
 * identifies the invocation across snapshots/replay, while operationId identifies its declaration.
 * Legacy and uncommitted persistence-failure observations have no committed key. Actor remains
 * the literal "head" for wire compatibility. The optional executionId is present only for a
 * successful undoable forward invocation. Signed intent tokens are never part of this projection.
 */
public record OperationHistoryEntry(
    OperationRef operationId,
    String actor,
    Instant startTime,
    Instant endTime,
    OperationOutcome outcome,
    Optional<String> diagnosticsLink,
    InvocationProvenance provenance,
    Optional<String> executionId,
    Optional<String> operationKey) {

  /** Legacy live observations have no committed invocation key. */
  public OperationHistoryEntry(OperationRef operationId, String actor, Instant startTime, Instant endTime,
      OperationOutcome outcome, Optional<String> diagnosticsLink, InvocationProvenance provenance,
      Optional<String> executionId) {
    this(operationId, actor, startTime, endTime, outcome, diagnosticsLink, provenance, executionId, Optional.empty());
  }

  public OperationHistoryEntry {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(startTime, "startTime");
    Objects.requireNonNull(endTime, "endTime");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(diagnosticsLink, "diagnosticsLink");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(executionId, "executionId");
    Objects.requireNonNull(operationKey, "operationKey");
    operationKey.ifPresent(key -> {
      if (key.isBlank()) throw new IllegalArgumentException("operationKey must be non-blank when present");
    });
    if (actor.isBlank()) {
      throw new IllegalArgumentException("actor must be non-blank");
    }
  }
}
