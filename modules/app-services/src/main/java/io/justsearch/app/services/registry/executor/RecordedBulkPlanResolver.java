/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.core.context.EngineContext;

/** Accepted bulk binding validation; current authority and generation still gate every effect. */
public final class RecordedBulkPlanResolver {
  public RecordedBulkPlan resolve(OperationRecord row, OperationStore.Preparation stored) {
    var descriptor = row.descriptor();
    var profile = RecordedBulkPlan.Profile.fromOperationRef(descriptor.operationRef());
    if (descriptor.kind() != OperationKind.REINDEX
        || row.context().survival() != EngineContext.Survival.DURABLE) {
      throw new IllegalArgumentException("Bulk preparation requires a durable reindex row");
    }
    var preparation = PreparedInvocationCodec.decodeAcceptedMetadata(row, stored);
    var basis = OperationAuthorizationBasis.decode(
        row.context().grantReference().orElse(null));
    if (basis instanceof OperationAuthorizationBasis.PreparedContinuation continuation
        && (!continuation.operationKey().equals(row.key())
            || !continuation.preparationNonce().equals(stored.nonce()))) {
      throw new IllegalArgumentException("Bulk continuation identity mismatch");
    }
    if (!RecordedBulkPlan.SCHEMA.equals(preparation.replaySchema())
        || !descriptor.hasSameIdentity(OperationDescriptor.invocation(
            OperationKind.REINDEX, profile.operationRef(), preparation.argumentsJson(), false))) {
      throw new IllegalArgumentException("Bulk preparation identity mismatch");
    }
    var plan = RecordedBulkPlan.fromReplayPayload(preparation.replayPayloadJson());
    if (plan.profile() != profile
        || !plan.source().equals(RecordedBulkPlan.sourceForArguments(profile, preparation.argumentsJson()))) {
      throw new IllegalArgumentException("Bulk profile/source differs from its public invocation");
    }
    return plan;
  }
}
