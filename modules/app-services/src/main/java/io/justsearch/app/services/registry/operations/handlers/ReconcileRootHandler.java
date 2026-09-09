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
 * Handler for {@code core.reconcile-root} (tempdoc 626 §Recency, Move C).
 *
 * <p>Verifies/reconciles a SINGLE watched root identified by its {@code pathHash} — the
 * granularity-matched recovery for the {@code index.drift-unknown} condition, scoped to one folder
 * instead of the corpus-wide {@link ReindexHandler}. Maps to
 * {@link IndexingService#reconcileRoot(String, boolean)} with {@code force=true}; the implementation
 * resolves the hash to the real path Head-side (raw paths never cross the wire — ADR-0028) and runs a
 * forced reconcile (re-prune orphans + re-walk), which re-converges the root and refreshes its per-root
 * verification state (clears {@code deleteDetectionUnverified}, stamps {@code lastVerifiedAt}).
 *
 * <p>Pattern mirrors {@link ReindexHandler} (lazy {@code Supplier<IndexingService>} for the
 * HeadAssembly init-order gap). Args shape: {@code {"pathHash": string}} (required).
 */
public final class ReconcileRootHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ReconcileRootHandler.class);

  private final Supplier<IndexingService> indexingSupplier;

  public ReconcileRootHandler(Supplier<IndexingService> indexingSupplier) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    String pathHash = parsePathHash(argumentsJson);
    if (pathHash == null || pathHash.isBlank()) {
      return OperationResult.failure("pathHash is required");
    }
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("ReconcileRootHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    try {
      // force=true — the scoped recovery default: re-prune + re-walk, fully re-converging the root.
      boolean found = indexing.reconcileRoot(pathHash, true, engineContext);
      if (!found) {
        return OperationResult.failure("No watched root matches the given pathHash");
      }
      indexing.flush(engineContext);
      return OperationResult.success("Folder verification started");
    } catch (RuntimeException e) {
      log.error("ReconcileRootHandler: reconcileRoot threw", e);
      return OperationResult.failure("Folder verification failed: " + e.getMessage());
    }
  }

  /** Parse {@code pathHash} from args JSON. Lenient: missing / wrong shape → null. */
  private static String parsePathHash(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return null;
    }
    try {
      JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
      JsonNode node = root.get("pathHash");
      return node != null && node.isString() ? node.asString() : null;
    } catch (Exception e) {
      return null;
    }
  }
}
