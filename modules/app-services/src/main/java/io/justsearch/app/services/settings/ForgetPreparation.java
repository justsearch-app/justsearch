/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.ForgetPlan;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.registry.executor.PreparedInvocationCodec;
import io.justsearch.core.context.EngineContext;

/** Shared strict accepted-plan read for the fixed settings collaborator and forget recovery owner. */
public final class ForgetPreparation {
  private ForgetPreparation() {}

  public static ForgetPlan decode(OperationRecord row, OperationStore.Preparation accepted) {
    try {
      if (row.context().clientKind() != EngineContext.ClientKind.WEBVIEW
          || row.descriptor().kind() != OperationKind.FORGET
          || !ForgetPlan.OPERATION_ID.equals(row.descriptor().operationRef())) {
        throw new IllegalArgumentException("Forget requires a UI-owned accepted preparation");
      }
      var preparation = PreparedInvocationCodec.decodeAcceptedForgetMetadata(row, accepted);
      if (!row.descriptor().hasSameIdentity(OperationDescriptor.invocation(OperationKind.FORGET,
          ForgetPlan.OPERATION_ID, preparation.argumentsJson(), false))) {
        throw new IllegalArgumentException("Forget preparation public identity mismatch");
      }
      return ForgetPlan.validate(preparation);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid accepted forget preparation");
    }
  }
}
