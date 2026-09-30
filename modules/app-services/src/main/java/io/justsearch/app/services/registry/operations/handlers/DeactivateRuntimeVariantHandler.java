/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.InvocationProvenance;

import static io.justsearch.agent.api.registry.OperationExecution.finished;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.RuntimeVariantService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.deactivate-runtime-variant}.
 *
 * <p>Slice 3a-2-c continuation: BrainRuntimeSection Deactivate Runtime
 * Variant button. Delegates to {@link RuntimeVariantService#deactivate()}
 * via lazy supplier.
 *
 * <p>No args. Returns the post-start activation status snapshot in
 * {@code structuredData}.
 */
public final class DeactivateRuntimeVariantHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(DeactivateRuntimeVariantHandler.class);

  private final Supplier<RuntimeVariantService> supplier;

  public DeactivateRuntimeVariantHandler(Supplier<RuntimeVariantService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    return executeRecorded(argumentsJson, null, engineContext, null).response();
  }

  @Override
  public OperationExecution executeRecorded(String argumentsJson, InvocationProvenance provenance,
      EngineContext engineContext, OperationRecordHandle record) {
    RuntimeVariantService svc;
    try {
      svc = supplier.get();
    } catch (RuntimeException e) {
      log.warn("DeactivateRuntimeVariantHandler: supplier threw", e);
      return finished(OperationResult.failure("Runtime variant service unavailable: " + e.getMessage()));
    }
    if (svc == null) {
      return finished(OperationResult.failure("Runtime variant service unavailable"));
    }

    try {
      var attempt = svc.deactivate();
      return RuntimeActivationOutcome.execution("Runtime variant deactivation started", attempt);
    } catch (IllegalStateException e) {
      // RuntimeActivationService.startDeactivate throws ISE when an
      // activation/deactivation is already running.
      return finished(OperationResult.failure(
          e.getMessage(), "Runtime activation already running".equals(e.getMessage())
              ? "RUNTIME_ACTIVATION_RUNNING" : "RUNTIME_DEACTIVATION_START_FAILED", Map.of(), true));
    } catch (Exception e) {
      log.error("DeactivateRuntimeVariantHandler: deactivate threw", e);
      return finished(OperationResult.failure(
          "Runtime variant deactivation failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "RUNTIME_DEACTIVATION_START_FAILED",
          Map.of(),
          true));
    }
  }
}
