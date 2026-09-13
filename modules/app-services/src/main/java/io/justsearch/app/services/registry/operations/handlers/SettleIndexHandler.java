/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.IndexingService.SettleIndexOutcome;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.settle-index} (tempdoc 931 §E item 10).
 *
 * <p>Delegates to {@link IndexingService#settleIndex(boolean, int)} via a lazy supplier (mirrors
 * {@link IndexGcHandler}'s init-order accommodation).
 *
 * <p>Args JSON shape: {@code {"expungeDeletesOnly"?: boolean, "maxSegments"?: integer}}. Both
 * optional; defaults are {@code expungeDeletesOnly=true} (purge tombstones, leave the segment
 * layout alone) and {@code maxSegments=0} ("use the worker default of 1" — only read when
 * {@code expungeDeletesOnly=false}).
 *
 * <p>Structured output: the before/after {@code maxDoc}/{@code numDocs} pair plus segment count and
 * elapsed time, which is what a paired evaluation records to show both arms queried an index with
 * the same merge state.
 */
public final class SettleIndexHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(SettleIndexHandler.class);

  /** Default: purge deletes without reshaping the segment layout. */
  private static final boolean DEFAULT_EXPUNGE_DELETES_ONLY = true;

  /** Default {@code maxSegments=0} — "use the worker default" (1). */
  private static final int DEFAULT_MAX_SEGMENTS = 0;

  private final Supplier<IndexingService> indexingSupplier;
  private final OperationLeaseService leaseService;

  public SettleIndexHandler(
      Supplier<IndexingService> indexingSupplier, OperationLeaseService leaseService) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
    this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    boolean expungeDeletesOnly = DEFAULT_EXPUNGE_DELETES_ONLY;
    int maxSegments = DEFAULT_MAX_SEGMENTS;
    if (argumentsJson != null && !argumentsJson.isBlank()) {
      try {
        JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
        JsonNode edoNode = root.get("expungeDeletesOnly");
        if (edoNode != null && !edoNode.isNull() && edoNode.isBoolean()) {
          expungeDeletesOnly = edoNode.asBoolean();
        }
        JsonNode segNode = root.get("maxSegments");
        if (segNode != null && !segNode.isNull() && segNode.canConvertToInt()) {
          int parsed = segNode.asInt();
          if (parsed < 0) {
            return OperationResult.failure(
                "Invalid argument: maxSegments must be non-negative (got " + parsed + ")");
          }
          maxSegments = parsed;
        }
      } catch (RuntimeException e) {
        return OperationResult.failure("Invalid arguments JSON: " + e.getMessage());
      }
    }
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("SettleIndexHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    // INTERRUPTIBLE_WITH_LOSS, unlike index-gc's MUST_COMPLETE: an interrupted force-merge loses
    // the merge work but destroys nothing — the tombstones simply stay, which is the state the
    // caller started from. The loss is worth reporting, though: the arm did not get equal merge
    // state, so a comparison against it is not the one that was asked for.
    OperationLeaseHandle handle =
        leaseService.register(
            "indexing.settle-index",
            OpCriticality.INTERRUPTIBLE_WITH_LOSS,
            300L,
            Map.of("expungeDeletesOnly", expungeDeletesOnly, "maxSegments", maxSegments));
    try {
      SettleIndexOutcome outcome = indexing.settleIndex(expungeDeletesOnly, maxSegments, engineContext);
      if (!outcome.accepted()) {
        handle.release(OpLeaseOutcome.FAILURE);
        return OperationResult.failure(
            outcome.error().isBlank() ? "Settle rejected by worker" : outcome.error());
      }
      handle.release(OpLeaseOutcome.SUCCESS);
      String message =
          "Index settled: maxDoc "
              + outcome.maxDocBefore()
              + " -> "
              + outcome.maxDocAfter()
              + ", numDocs "
              + outcome.numDocsBefore()
              + " -> "
              + outcome.numDocsAfter();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("accepted", true);
      data.put("maxDocBefore", outcome.maxDocBefore());
      data.put("numDocsBefore", outcome.numDocsBefore());
      data.put("maxDocAfter", outcome.maxDocAfter());
      data.put("numDocsAfter", outcome.numDocsAfter());
      data.put("segmentsAfter", outcome.segmentsAfter());
      data.put("elapsedMs", outcome.elapsedMs());
      return OperationResult.success(message, data);
    } catch (RuntimeException e) {
      handle.release(OpLeaseOutcome.FAILURE);
      log.error("SettleIndexHandler: settleIndex threw", e);
      return OperationResult.failure("Settle failed: " + e.getMessage());
    }
  }
}
