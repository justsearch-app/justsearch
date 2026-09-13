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

/**
 * Handler for {@code core.clear-failed-jobs}.
 *
 * <p>Delegates to {@link IndexingService#clearFailedJobs()} via a lazy supplier
 * (mirrors {@link BulkReindexHandler}'s init-order accommodation).
 *
 * <p>Slice 3a-2-c (admin actions) precondition: this handler is the substrate-side
 * counterpart to the React HealthView "Clear All Failures" button. The FE migration
 * uses {@code OperationClient.invoke('core.clear-failed-jobs', ...)} via
 * {@code useOperation}.
 */
public final class ClearFailedJobsHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ClearFailedJobsHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public ClearFailedJobsHandler(Supplier<IndexingService> indexingSupplier) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("ClearFailedJobsHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    try {
      int cleared = indexing.clearFailedJobs(engineContext);
      return OperationResult.success(
          "Cleared " + cleared + " failed job" + (cleared == 1 ? "" : "s"),
          Map.of("clearedCount", cleared));
    } catch (RuntimeException e) {
      log.error("ClearFailedJobsHandler: clearFailedJobs threw", e);
      return OperationResult.failure("Clear failed jobs failed: " + e.getMessage());
    }
  }
}
