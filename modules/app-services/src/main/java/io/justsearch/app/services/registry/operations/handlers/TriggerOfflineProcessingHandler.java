/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineFutures;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Records one captured enrichment pass through actual procedure and ownership cleanup. */
public final class TriggerOfflineProcessingHandler implements OperationHandler {
  private final Supplier<BrainRuntimeService> brainRuntimeSupplier;

  public TriggerOfflineProcessingHandler(Supplier<BrainRuntimeService> brainRuntimeSupplier) {
    this.brainRuntimeSupplier = Objects.requireNonNull(brainRuntimeSupplier, "brainRuntimeSupplier");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    return executeRecorded(argumentsJson, null, context, null).response();
  }

  @Override public OperationExecution executeRecorded(String argumentsJson,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    try {
      BrainRuntimeService service = brainRuntimeSupplier.get();
      if (service == null) return OperationExecution.finished(
          OperationResult.failure("Brain runtime service unavailable"));
      var completion = service.triggerOfflineProcessing(context, outcome -> {
        if (record != null) record.checkpoint(HandlerJson.MAPPER.writeValueAsString(fields(outcome)),
            outcome.processed(), outcome.failed());
      });
      return new OperationExecution(OperationResult.success("Enrichment pass started", Map.of()),
          completion.thenApply(TriggerOfflineProcessingHandler::result));
    } catch (RuntimeException failure) {
      EngineFutures.rethrowCancellation(failure);
      EngineFutures.rethrowExecutorRefusal(failure);
      if (failure instanceof EngineAdmissionException) throw failure;
      return OperationExecution.finished(OperationResult.failure(
          "Unable to start enrichment: " + failure.getMessage(),
          "ENRICHMENT_START_FAILED", Map.of(), true));
    }
  }

  private static OperationResult result(OfflineProcessingOutcome outcome) {
    return outcome.complete()
        ? OperationResult.success("Enrichment pass completed", fields(outcome))
        : OperationResult.failure("Enrichment pass incomplete", "ENRICHMENT_INCOMPLETE", fields(outcome), true);
  }

  private static Map<String, Object> fields(OfflineProcessingOutcome outcome) {
    return Map.of("selected", outcome.selected(), "processed", outcome.processed(),
        "failed", outcome.failed(), "remaining", outcome.remaining(), "blocked", outcome.blocked(),
        "blockedReason", outcome.blockedReason().name().toLowerCase(Locale.ROOT),
        "embeddingHandoff", outcome.embeddingHandoff().name().toLowerCase(Locale.ROOT));
  }
}
