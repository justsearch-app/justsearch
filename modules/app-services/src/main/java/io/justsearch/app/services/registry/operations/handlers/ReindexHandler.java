/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.reindex}.
 *
 * <p>Lightweight incremental reindex of all watched roots. Distinct from
 * {@code core.bulk-reindex} (blue/green migration). Maps to
 * {@link IndexingService#reindexWatchedRoots(boolean)}; the {@code force} arg
 * bypasses the file-mtime unchanged-detection optimization.
 *
 * <p>Slice 3a-1-2 closure: real handler for HealthView's "Reindex Now" button.
 * Pattern mirrors {@link ClearFailedJobsHandler} + {@link BulkReindexHandler}
 * (lazy {@code Supplier<IndexingService>} for the HeadAssembly init-order
 * gap).
 *
 * <p>Args shape: {@code {"force": boolean}} (optional; defaults false).
 */
public final class ReindexHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ReindexHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public ReindexHandler(Supplier<IndexingService> indexingSupplier) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    boolean force = parseForce(argumentsJson);
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("ReindexHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    try {
      indexing.reindexWatchedRoots(force, engineContext);
      indexing.flush(engineContext);
      // Tempdoc 821 §3-C3 — this sentence became TRUE here. The scan now goes out as
      // SCAN_MODE_FORCE_REINDEX, the Worker marks the admitted paths forced, and JobBatchExtractor
      // skips its unchanged-check for them. Until then the force flag never left the Head and the
      // message was a claim the pipeline did not honour.
      return OperationResult.success(
          force ? "Reindex started (force=true; bypasses mtime unchanged-check)" : "Reindex started");
    } catch (RuntimeException e) {
      log.error("ReindexHandler: reindexWatchedRoots threw", e);
      return OperationResult.failure("Reindex failed: " + e.getMessage());
    }
  }

  /** Parse {@code force} from args JSON. Lenient: missing / wrong shape → false. */
  private static boolean parseForce(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return false;
    }
    try {
      JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
      JsonNode forceNode = root.get("force");
      return forceNode != null && forceNode.isBoolean() && forceNode.asBoolean();
    } catch (Exception e) {
      // Treat parse failure as force=false rather than fail the dispatch — matches
      // existing handler-pattern leniency (PingBackendHandler ignores args entirely).
      return false;
    }
  }
}
