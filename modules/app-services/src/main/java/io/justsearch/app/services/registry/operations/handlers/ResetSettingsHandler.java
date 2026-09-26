/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.services.settings.SettingsResetPreparation;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Fixed reset intent is accepted before the settings owner performs any write. */
public final class ResetSettingsHandler implements OperationHandler {
  private final Supplier<SettingsService> supplier;

  public ResetSettingsHandler(Supplier<SettingsService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    throw new IllegalStateException("Settings reset requires an accepted prepared invocation");
  }

  @Override
  public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance, EngineContext context) {
    return service().prepareReset(argumentsJson);
  }

  @Override
  public void validatePreparation(OperationPreparation prepared) {
    SettingsResetPreparation.validate(prepared);
  }

  @Override
  public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    var intent = SettingsResetPreparation.validate(prepared);
    return new OperationApprovalPreview(intent.recovery()
        ? "Reset unavailable settings history to defaults and restart the Engine. Preserved corruption evidence remains."
        : "Reset user-facing settings to defaults while preserving administrator paths and window settings.");
  }

  @Override
  public OperationExecution executePrepared(OperationPreparation prepared, InvocationProvenance provenance,
      EngineContext context, OperationRecordHandle record) {
    SettingsResetPreparation.validate(prepared);
    return OperationExecution.finished(service().resetToDefaults(Objects.requireNonNull(record, "accepted record")));
  }

  private SettingsService service() {
    var service = supplier.get();
    if (service == null) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Settings service unavailable", "SETTINGS_RESET_FAILED", Map.of(), false));
    }
    return service;
  }
}
