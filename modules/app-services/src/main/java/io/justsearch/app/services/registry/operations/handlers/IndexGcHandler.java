/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.IndexingService.IndexGcOutcome;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.index-gc}.
 *
 * <p>Per slice 484 §3.6 / observations.md `core.index-gc` closure: closes the phantom
 * Operation reference. Delegates to {@link IndexingService#runIndexGc(int, boolean)}
 * via a lazy supplier (mirrors {@link ClearFailedJobsHandler}'s init-order
 * accommodation).
 *
 * <p>Args JSON shape: {@code {"keepLatest"?: integer, "pruneMarkedOnly"?: boolean}}.
 * Both optional; defaults are {@code keepLatest=0} (use server-side default policy
 * per the proto contract — matches {@code IndexingController.handleIndexGc:304-305})
 * and {@code pruneMarkedOnly=true} (only prune already-marked segments — bounded
 * destructive).
 *
 * <p>Structured output: {@code {accepted, markedCount, prunedCount}}. The worker's
 * {@code IndexGcOutcome} (slice 484 closure: signature changed from {@code boolean}
 * to {@link IndexGcOutcome} so the counts reach this layer instead of being dropped
 * at {@code MigrationOps}).
 */
public final class IndexGcHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(IndexGcHandler.class);

  /** Default {@code keepLatest=0} — proto convention: "use server-side default policy". */
  private static final int DEFAULT_KEEP_LATEST = 0;

  /** Default {@code pruneMarkedOnly=true} — bounded destructive, recoverable from backups. */
  private static final boolean DEFAULT_PRUNE_MARKED_ONLY = true;

  private final Supplier<IndexingService> indexingSupplier;
  private final OperationLeaseService leaseService;

  public IndexGcHandler(
      Supplier<IndexingService> indexingSupplier,
      OperationLeaseService leaseService) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
    this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    int keepLatest = DEFAULT_KEEP_LATEST;
    boolean pruneMarkedOnly = DEFAULT_PRUNE_MARKED_ONLY;
    if (argumentsJson != null && !argumentsJson.isBlank()) {
      try {
        JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
        JsonNode keepNode = root.get("keepLatest");
        if (keepNode != null && !keepNode.isNull() && keepNode.canConvertToInt()) {
          int parsed = keepNode.asInt();
          if (parsed < 0) {
            return OperationResult.failure(
                "Invalid argument: keepLatest must be non-negative (got " + parsed + ")");
          }
          keepLatest = parsed;
        }
        JsonNode pmoNode = root.get("pruneMarkedOnly");
        if (pmoNode != null && !pmoNode.isNull() && pmoNode.isBoolean()) {
          pruneMarkedOnly = pmoNode.asBoolean();
        }
      } catch (RuntimeException e) {
        return OperationResult.failure("Invalid arguments JSON: " + e.getMessage());
      }
    }
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("IndexGcHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    // Tempdoc 542 Phase 3: index-gc is MUST_COMPLETE — file deletions are irreversible.
    // The Worker RPC performs the deletion synchronously.
    OperationLeaseHandle handle = leaseService.register(
        "indexing.index-gc",
        OpCriticality.MUST_COMPLETE,
        300L,
        Map.of("keepLatest", keepLatest, "pruneMarkedOnly", pruneMarkedOnly));
    try {
      IndexGcOutcome outcome = indexing.runIndexGc(keepLatest, pruneMarkedOnly, engineContext);
      if (!outcome.accepted()) {
        handle.release(OpLeaseOutcome.FAILURE);
        return OperationResult.failure(
            outcome.error().isBlank() ? "Index GC rejected by worker" : outcome.error());
      }
      handle.release(OpLeaseOutcome.SUCCESS);
      String message =
          "Index GC accepted: marked "
              + outcome.markedCount()
              + ", pruned "
              + outcome.prunedCount();
      return OperationResult.success(
          message,
          Map.of(
              "accepted", true,
              "markedCount", outcome.markedCount(),
              "prunedCount", outcome.prunedCount()));
    } catch (RuntimeException e) {
      handle.release(OpLeaseOutcome.FAILURE);
      log.error("IndexGcHandler: runIndexGc threw", e);
      return OperationResult.failure("Index GC failed: " + e.getMessage());
    }
  }
}
