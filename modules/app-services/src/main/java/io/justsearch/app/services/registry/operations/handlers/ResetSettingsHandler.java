/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.reset-settings}.
 *
 * <p>Slice 3a-2-c continuation: SettingsView Reset to Defaults button.
 * Delegates to {@link SettingsService#resetToDefaults()} via lazy supplier.
 *
 * <p>Returns the post-reset SettingsV2 shape in {@code structuredData} so
 * the FE can refresh its store from the response without an extra GET.
 */
public final class ResetSettingsHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ResetSettingsHandler.class);

  private final Supplier<SettingsService> supplier;

  public ResetSettingsHandler(Supplier<SettingsService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    SettingsService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("ResetSettingsHandler: supplier threw", e);
      return OperationResult.failure("Settings service unavailable: " + e.getMessage());
    }
    if (svc == null) {
      return OperationResult.failure("Settings service unavailable");
    }

    try {
      Map<String, Object> result = svc.resetToDefaults();
      return OperationResult.success("Settings reset to defaults", result);
    } catch (IllegalStateException e) {
      // Read-only mode (e.g. in_memory eval). Phase B: typed errorCode lets
      // the FE distinguish "settings persistence disabled" from other failures.
      return OperationResult.failure(
          e.getMessage(), "SETTINGS_READ_ONLY", Map.of(), false);
    } catch (Exception e) {
      log.error("ResetSettingsHandler: resetToDefaults threw", e);
      return OperationResult.failure(
          "Settings reset failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "SETTINGS_RESET_FAILED",
          Map.of(),
          true);
    }
  }
}
