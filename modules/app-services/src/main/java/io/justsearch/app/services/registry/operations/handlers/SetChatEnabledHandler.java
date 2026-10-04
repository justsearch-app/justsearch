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

/** Records chat intent through its accepted settings row; runtime policy applies at convergence. */
public final class SetChatEnabledHandler implements OperationHandler {
  private final Supplier<RuntimeSpecStore> specStoreSupplier;
  private final Supplier<RuntimeReconciler> reconcilerSupplier;

  public SetChatEnabledHandler(Supplier<RuntimeSpecStore> specStoreSupplier,
      Supplier<RuntimeReconciler> reconcilerSupplier) {
    this.specStoreSupplier = Objects.requireNonNull(specStoreSupplier, "specStoreSupplier");
    this.reconcilerSupplier = Objects.requireNonNull(reconcilerSupplier, "reconcilerSupplier");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Runtime intent requires an accepted prepared invocation");
  }

  @Override public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance, EngineContext context) {
    enabledOf(argumentsJson);
    return RuntimeIntentWrite.spec(specStoreSupplier).prepareIntent(argumentsJson);
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
    return RuntimeIntentWrite.apply(specStoreSupplier, reconcilerSupplier, prepared,
        enabledOf(prepared.argumentsJson()), Objects.requireNonNull(record, "accepted record"));
  }

  private static boolean enabledOf(String argumentsJson) {
    try {
      var root = HandlerJson.MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      var enabled = root == null ? null : root.get("enabled");
      if (enabled == null || !enabled.isBoolean()) {
        throw new OperationPreparationRefused(OperationResult.failure("Missing required arg: enabled (boolean)",
            "INVALID_REQUEST", Map.of(), false));
      }
      return enabled.asBoolean();
    } catch (OperationPreparationRefused refusal) { throw refusal; }
    catch (RuntimeException malformed) { throw new OperationPreparationRefused(HandlerJson.invalidArgs(malformed)); }
  }

  /** Shared recorded intent path for the canonical operation and its mode alias. */
  public static final class RuntimeIntentWrite {
    private RuntimeIntentWrite() {}

    static RuntimeSpecStore spec(Supplier<RuntimeSpecStore> supplier) {
      var spec = supplier.get();
      if (spec == null) throw new OperationPreparationRefused(OperationResult.failure(
          "Runtime authority unavailable (AI runtime not configured)", "SETTINGS_RECOVERY_REQUIRED", Map.of(), false));
      return spec;
    }

    static OperationExecution apply(Supplier<RuntimeSpecStore> specSupplier, Supplier<RuntimeReconciler> reconcilerSupplier,
        OperationPreparation prepared, boolean enabled, OperationRecordHandle record) {
      var spec = spec(specSupplier);
      var reconciler = reconcilerSupplier.get();
      if (reconciler == null) return OperationExecution.finished(OperationResult.failure(
          "Runtime authority unavailable (AI runtime not configured)", "SETTINGS_RECOVERY_REQUIRED", Map.of(), false));
      OperationResult result = spec.applyIntent(prepared, enabled, record);
      if (!result.success()) return OperationExecution.finished(result);
      reconciler.specChanged();
      var status = reconciler.current();
      String engineState = status == null ? "" : status
          .condition(io.justsearch.app.services.runtimestate.RuntimeStatus.Axis.ENGINE)
          .map(io.justsearch.app.services.runtimestate.RuntimeStatus.Condition::status).orElse("");
      return OperationExecution.finished(OperationResult.success(enabled ? "Chat AI enabled" : "Chat AI disabled",
          Map.of("chatEnabled", enabled, "engineState", engineState)));
    }
  }
}
