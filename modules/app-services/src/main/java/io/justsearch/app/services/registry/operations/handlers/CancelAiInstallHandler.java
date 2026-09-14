/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.BrainInstallService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.cancel-ai-install}.
 *
 * <p>Slice 3a-2-c continuation: BrainInstallSection Cancel Install button.
 * Delegates to {@link BrainInstallService#cancelInstall()} via lazy supplier.
 * Idempotent if no install is running.
 */
public final class CancelAiInstallHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(CancelAiInstallHandler.class);

  private final Supplier<BrainInstallService> supplier;

  public CancelAiInstallHandler(Supplier<BrainInstallService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    BrainInstallService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("CancelAiInstallHandler: supplier threw", e);
      return OperationResult.failure("Brain install service unavailable: " + e.getMessage());
    }
    if (svc == null) {
      return OperationResult.failure("Brain install service unavailable");
    }

    try {
      Map<String, Object> status = svc.cancelInstall();
      return OperationResult.success("AI install cancel requested", status);
    } catch (Exception e) {
      log.error("CancelAiInstallHandler: cancelInstall threw", e);
      return OperationResult.failure(
          "AI install cancel failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "INSTALL_CANCEL_FAILED",
          Map.of(),
          true);
    }
  }
}
