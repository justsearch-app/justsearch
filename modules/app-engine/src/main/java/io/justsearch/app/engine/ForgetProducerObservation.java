/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.ForgetPlan;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedIngestChild;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.registry.executor.RecordedBulkPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedInstallerGenerationPlanResolver;
import java.nio.file.Path;

/** Read-only settlement evidence for the out-of-root FILE exception. It never cancels producers. */
final class ForgetProducerObservation {
  enum Status { CLEAR, OLD_PRODUCER, UNKNOWN_SCOPE, SNAPSHOT_INCOMPLETE }
  record Observation(Status status, long acceptanceSequence) {}

  static Observation inspect(ForgetPlan forget, long forgetRecordId,
      OperationStore.ForgetAuthoritySnapshot snapshot, OperationStore store) {
    if (forget.keepOutRequired() || forget.scope()
        != io.justsearch.configuration.excludes.LiteralExclusion.Scope.FILE) {
      throw new IllegalArgumentException("Only out-of-root files require producer settlement");
    }
    if (!snapshot.complete()) return new Observation(Status.SNAPSHOT_INCOMPLETE, snapshot.acceptanceSequence());
    Path target = Path.of(forget.path());
    for (OperationRecord row : snapshot.records()) {
      if (row.id() == forgetRecordId || row.state().terminal()) continue;
      // Both invoke and undo identities are digest-only. No public digest proves disjoint paths.
      if ("core.file-operations".equals(row.descriptor().operationRef())) {
        return new Observation(Status.UNKNOWN_SCOPE, snapshot.acceptanceSequence());
      }
      if (row.descriptor().kind() != OperationKind.INGEST && row.descriptor().kind() != OperationKind.REINDEX) continue;
      final RecordedRootPlan plan;
      try {
        var prepared = store.acceptedPreparation(row.id()).orElseThrow();
        if (row.descriptor().kind() == OperationKind.INGEST && row.descriptor().operationRef() == null) {
          plan = RecordedIngestChild.from(row.descriptor(), prepared.payload()).plan();
        } else if ("core.ingest-files".equals(row.descriptor().operationRef())
            || "core.reindex".equals(row.descriptor().operationRef())) {
          plan = new RecordedIngestPlanResolver().resolve(row, prepared);
        } else if (RecordedInstallerGenerationPlan.OPERATION_ID.equals(row.descriptor().operationRef())) {
          plan = new RecordedInstallerGenerationPlanResolver().resolve(row, prepared).scope();
        } else {
          plan = new RecordedBulkPlanResolver().resolve(row, prepared).scope();
        }
      } catch (RuntimeException unavailable) {
        return new Observation(Status.UNKNOWN_SCOPE, snapshot.acceptanceSequence());
      }
      // Parents include roots whose children have not been accepted yet. Inspect the complete plan,
      // not the current live child map or queue, and preserve every unrelated root's continuation.
      for (RecordedRootPlan.Root root : plan.roots()) {
        boolean covers = root.singleFile() ? target.equals(root.path())
            : target.startsWith(root.path()) && root.excludedSubtrees().stream().noneMatch(target::startsWith);
        if (covers) return new Observation(Status.OLD_PRODUCER, snapshot.acceptanceSequence());
      }
    }
    return new Observation(Status.CLEAR, snapshot.acceptanceSequence());
  }

  private ForgetProducerObservation() {}
}
