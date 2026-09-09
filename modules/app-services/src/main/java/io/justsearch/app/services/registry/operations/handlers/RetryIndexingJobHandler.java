/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.retry-indexing-job} (slice 445 §A.9).
 *
 * <p>Item Operation on the {@code core.indexing-jobs} TABULAR Resource. Takes a
 * {@code pathHash}, forwards to the worker's {@code RetryIndexingJob} RPC via
 * {@link IndexingService}, and surfaces the worker's typed response.
 *
 * <p>Args JSON shape: {@code {"pathHash": "<sha256-hex>"}}.
 */
public final class RetryIndexingJobHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(RetryIndexingJobHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public RetryIndexingJobHandler(Supplier<IndexingService> indexingSupplier) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String pathHash;
    try {
      JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
      JsonNode hashNode = root.get("pathHash");
      if (hashNode == null || hashNode.isNull() || hashNode.asString().isBlank()) {
        return OperationResult.failure("Missing required argument: pathHash");
      }
      pathHash = hashNode.asString().trim();
    } catch (RuntimeException e) {
      return OperationResult.failure("Invalid arguments JSON: " + e.getMessage());
    }
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("RetryIndexingJobHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    try {
      Map<String, Object> result = indexing.retryIndexingJob(pathHash, engineContext);
      boolean retried = Boolean.TRUE.equals(result.get("retried"));
      String previousState = String.valueOf(result.getOrDefault("previousState", ""));
      return retried
          ? OperationResult.success(
              "Job re-enqueued (previous state: " + previousState + ")", result)
          : OperationResult.failure(
              "Job not retried (state: " + previousState + ")");
    } catch (RuntimeException e) {
      log.error("RetryIndexingJobHandler: retryIndexingJob threw", e);
      return OperationResult.failure("Retry indexing job failed: " + e.getMessage());
    }
  }
}
