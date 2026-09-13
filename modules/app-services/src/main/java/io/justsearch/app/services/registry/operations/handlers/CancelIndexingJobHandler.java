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
 * Handler for {@code core.cancel-indexing-job} (slice 445 §A.9).
 *
 * <p>Item Operation on the {@code core.indexing-jobs} TABULAR Resource. Takes a
 * {@code pathHash} (SHA-256 hex of the absolute normalized path), forwards to
 * the worker's {@code CancelIndexingJob} RPC via {@link IndexingService}, and
 * surfaces the worker's typed response.
 *
 * <p>Args JSON shape: {@code {"pathHash": "<sha256-hex>"}}.
 */
public final class CancelIndexingJobHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(CancelIndexingJobHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public CancelIndexingJobHandler(Supplier<IndexingService> indexingSupplier) {
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
      log.warn("CancelIndexingJobHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    try {
      Map<String, Object> result = indexing.cancelIndexingJob(pathHash, engineContext);
      boolean cancelled = Boolean.TRUE.equals(result.get("cancelled"));
      String previousState = String.valueOf(result.getOrDefault("previousState", ""));
      return cancelled
          ? OperationResult.success("Job cancelled (previous state: " + previousState + ")", result)
          : OperationResult.failure(
              "Job not cancelled (state: " + previousState + ")");
    } catch (RuntimeException e) {
      log.error("CancelIndexingJobHandler: cancelIndexingJob threw", e);
      return OperationResult.failure("Cancel indexing job failed: " + e.getMessage());
    }
  }
}
