/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.BrainRuntimeService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.trigger-offline-processing}.
 *
 * <p>Slice 3a-2-c continuation: the "Process pending enrichment" button. Delegates
 * to {@link BrainRuntimeService#triggerOfflineProcessing()} via lazy supplier.
 * The operation ID and the service method keep their historical names (renaming
 * a wire identifier is not a copy change), but every string this handler hands
 * back is user-facing, so it uses the 813 §6 vocabulary: the work is
 * ENRICHMENT, and "offline" names only the engine being down.
 *
 * <p>Returns immediately after dispatch — the actual processing runs on a
 * virtual thread. Success means dispatch succeeded, not that processing
 * completed.
 */
public final class TriggerOfflineProcessingHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(TriggerOfflineProcessingHandler.class);

  private final Supplier<BrainRuntimeService> brainRuntimeSupplier;

  public TriggerOfflineProcessingHandler(Supplier<BrainRuntimeService> brainRuntimeSupplier) {
    this.brainRuntimeSupplier = Objects.requireNonNull(brainRuntimeSupplier, "brainRuntimeSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    BrainRuntimeService brainRuntime;
    try {
      brainRuntime = brainRuntimeSupplier.get();
    } catch (RuntimeException e) {
      log.warn("TriggerOfflineProcessingHandler: brain-runtime supplier threw", e);
      return OperationResult.failure("Brain runtime service unavailable: " + e.getMessage());
    }
    if (brainRuntime == null) {
      return OperationResult.failure("Brain runtime service unavailable");
    }

    try {
      brainRuntime.triggerOfflineProcessing();
      return OperationResult.success("Enrichment started", Map.of());
    } catch (Exception e) {
      log.error("TriggerOfflineProcessingHandler: triggerOfflineProcessing threw", e);
      return OperationResult.failure(
          "Failed to start enrichment: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }
}
