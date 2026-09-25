/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedGapAcceptancePlan;
import io.justsearch.core.context.EngineContext;

/** Recovers only the exact user-approved gap decision frozen in the durable preparation. */
public final class RecordedGapAcceptancePlanResolver {
  private static final String OPERATION_REF = "core.accept-gaps";

  public RecordedGapAcceptancePlan resolve(OperationRecord row, OperationStore.Preparation stored) {
    if (row.descriptor().kind() != OperationKind.ACCEPT_GAPS
        || !OPERATION_REF.equals(row.descriptor().operationRef())
        || row.context().survival() != EngineContext.Survival.DURABLE
        || row.context().clientKind() != EngineContext.ClientKind.WEBVIEW) {
      throw new IllegalArgumentException("Gap decision requires its durable webview operation");
    }
    OperationPreparation prepared = PreparedInvocationCodec.decodeAcceptedMetadata(row, stored);
    if (prepared.content() != OperationPreparation.Content.METADATA
        || !RecordedGapAcceptancePlan.SCHEMA.equals(prepared.replaySchema())
        || !row.descriptor().hasSameIdentity(OperationDescriptor.invocation(
            OperationKind.ACCEPT_GAPS, OPERATION_REF, prepared.argumentsJson(), false))) {
      throw new IllegalArgumentException("Gap decision preparation identity mismatch");
    }
    var basis = OperationAuthorizationBasis.decode(row.context().grantReference().orElse(null));
    if (!(basis instanceof OperationAuthorizationBasis.PreparedContinuation continuation)
        || !row.key().equals(continuation.operationKey())
        || !stored.nonce().equals(continuation.preparationNonce())) {
      throw new IllegalArgumentException("Gap decision continuation identity mismatch");
    }
    RecordedGapAcceptancePlan arguments = RecordedGapAcceptancePlan.decode(prepared.argumentsJson());
    RecordedGapAcceptancePlan replay = RecordedGapAcceptancePlan.decode(prepared.replayPayloadJson());
    if (!arguments.equals(replay)) {
      throw new IllegalArgumentException("Gap decision replay differs from its invocation");
    }
    return replay;
  }
}
