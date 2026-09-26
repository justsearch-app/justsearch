/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.services.runtimestate.RuntimeIntentPreparation;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Compatibility operation spelling for the same accepted chat-intent writer. */
public final class SwitchInferenceModeHandler implements OperationHandler {
  private final Supplier<RuntimeSpecStore> specStoreSupplier;
  private final Supplier<RuntimeReconciler> reconcilerSupplier;

  public SwitchInferenceModeHandler(Supplier<RuntimeSpecStore> specStoreSupplier,
      Supplier<RuntimeReconciler> reconcilerSupplier) {
    this.specStoreSupplier = Objects.requireNonNull(specStoreSupplier, "specStoreSupplier");
    this.reconcilerSupplier = Objects.requireNonNull(reconcilerSupplier, "reconcilerSupplier");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Runtime intent requires an accepted prepared invocation");
  }

  @Override public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance, EngineContext context) {
    enabledOf(argumentsJson);
    return SetChatEnabledHandler.RuntimeIntentWrite.spec(specStoreSupplier).prepareIntent(argumentsJson);
  }

  @Override public void validatePreparation(OperationPreparation prepared) {
    RuntimeIntentPreparation.validate(prepared);
    enabledOf(prepared.argumentsJson());
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    validatePreparation(prepared);
    return new OperationApprovalPreview(enabledOf(prepared.argumentsJson()) ? "Enable chat AI" : "Disable chat AI");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared, InvocationProvenance provenance,
      EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    return SetChatEnabledHandler.RuntimeIntentWrite.apply(specStoreSupplier, reconcilerSupplier, prepared,
        enabledOf(prepared.argumentsJson()), Objects.requireNonNull(record, "accepted record"));
  }

  private static boolean enabledOf(String argumentsJson) {
    try {
      var root = HandlerJson.MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      var node = root == null ? null : root.get("mode");
      if (node != null && node.isTextual()) {
        if ("online".equalsIgnoreCase(node.asString())) return true;
        if ("indexing".equalsIgnoreCase(node.asString())) return false;
      }
      throw new OperationPreparationRefused(OperationResult.failure("Invalid mode. Use 'online' or 'indexing'",
          "INVALID_REQUEST", Map.of(), false));
    } catch (OperationPreparationRefused refusal) { throw refusal; }
    catch (RuntimeException malformed) { throw new OperationPreparationRefused(HandlerJson.invalidArgs(malformed)); }
  }
}
