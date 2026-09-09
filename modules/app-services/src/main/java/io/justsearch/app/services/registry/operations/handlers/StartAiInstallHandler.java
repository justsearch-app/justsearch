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
 * Handler for {@code core.start-ai-install}.
 *
 * <p>Slice 3a-2-c continuation: BrainInstallSection Start Install button.
 * Delegates to {@link BrainInstallService#startInstall(boolean)} via lazy
 * supplier.
 *
 * <p>Args shape: {@code {"acceptTerms"?: boolean}}. Returns the post-start
 * install status snapshot in {@code structuredData}.
 */
public final class StartAiInstallHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(StartAiInstallHandler.class);

  private final Supplier<BrainInstallService> supplier;

  public StartAiInstallHandler(Supplier<BrainInstallService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    boolean acceptTerms = BrainInstallHandlerSupport.parseAcceptTerms(argumentsJson);

    BrainInstallService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("StartAiInstallHandler: supplier threw", e);
      return OperationResult.failure("Brain install service unavailable: " + e.getMessage());
    }
    if (svc == null) {
      return OperationResult.failure("Brain install service unavailable");
    }

    try {
      Map<String, Object> status = svc.startInstall(acceptTerms);
      return OperationResult.success("AI install started", status);
    } catch (Exception e) {
      // NOTE: AiInstallException (modules/ui) carries a finer-grained
      // ApiErrorCode + httpStatus; lifted to app-api in Phase D. Until then
      // generic INSTALL_START_FAILED is the substrate-side wire code.
      log.error("StartAiInstallHandler: startInstall threw", e);
      return OperationResult.failure(
          "AI install failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "INSTALL_START_FAILED",
          Map.of("acceptTerms", acceptTerms),
          true);
    }
  }
}
