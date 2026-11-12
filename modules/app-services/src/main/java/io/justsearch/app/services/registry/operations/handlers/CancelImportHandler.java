/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;

/** Cancellation latches before the coordinator drains its producer and claims. */
public final class CancelImportHandler implements OperationHandler {
  private final RecordedIngestionService ingestion;
  public CancelImportHandler(RecordedIngestionService ingestion) { this.ingestion = Objects.requireNonNull(ingestion); }
  @Override public OperationResult execute(String arguments, EngineContext context) {
    try {
      var args = HandlerJson.MAPPER.readTree(arguments);
      if (!args.isObject() || args.size() != 1 || !args.path("operationKey").isTextual()) throw new IllegalArgumentException();
      return ingestion.cancelImport(args.path("operationKey").asString(), context);
    } catch (IllegalArgumentException invalid) {
      return OperationResult.failure("Invalid import cancellation", "BAD_REQUEST", Map.of(), false);
    }
  }
}
