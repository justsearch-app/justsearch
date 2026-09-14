/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedRootPlan;
import java.util.Objects;

/** Root-composed binding validator for the finite-walk producers; this grants no replay authority. */
public final class RecordedIngestPlanResolver implements OperationAttemptRunner.IngestPlanResolver {
  @Override
  public RecordedRootPlan resolve(OperationRecord parent, OperationStore.Preparation stored) {
    var descriptor = parent.descriptor();
    boolean ingest = descriptor.kind() == OperationKind.INGEST && "core.ingest-files".equals(descriptor.operationRef());
    boolean reindex = descriptor.kind() == OperationKind.REINDEX && "core.reindex".equals(descriptor.operationRef());
    if ((!ingest && !reindex) || stored.payload().sealed()) {
      throw new IllegalArgumentException("Unsupported recorded ingestion producer");
    }
    var envelope = new PreparedInvocationCodec(StoreCipher.disabled()).decode(
        stored.payload(), parent.key(), stored.nonce(), descriptor);
    var preparation = envelope.preparation();
    var provenance = envelope.provenance();
    if (preparation.content() != OperationPreparation.Content.METADATA
        || !RecordedRootPlan.SCHEMA.equals(preparation.replaySchema())
        || !descriptor.hasSameIdentity(io.justsearch.app.api.operations.OperationDescriptor.invocation(
            descriptor.kind(), descriptor.operationRef(), preparation.argumentsJson(), false))
        || !envelope.context().equals(parent.context())
        || !envelope.executor().name().equals(parent.executor())
        || !Objects.equals(provenance.initiator().orElse(null), parent.initiator())
        || !Objects.equals(provenance.correlationId().orElse(null), parent.correlationId())
        || !envelope.occurredAt().equals(parent.provenanceOccurredAt())) {
      throw new IllegalArgumentException("Recorded ingestion preparation binding mismatch");
    }
    return RecordedRootPlan.fromReplayPayload(preparation.replayPayloadJson());
  }
}
