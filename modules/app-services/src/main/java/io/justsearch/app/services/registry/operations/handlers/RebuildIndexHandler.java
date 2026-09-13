/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.status.MigrationSource;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.rebuild-index} — parameterless full-corpus reindex wrapper.
 *
 * <p>Per slice 447-followup-bulk-reindex-recovery.md (Option A) + §X.11.5 follow-up
 * Phase 7: 442 §B.9 row 548 (`index.unavailable + index.not_healthy → core.bulk-reindex`)
 * was deferred at impl-B closure because {@code core.bulk-reindex} requires a
 * {@code corpusIds} argument that's dynamic per-recovery. This wrapper Operation takes
 * no arguments and rebuilds the entire watched-roots corpus via the same
 * {@link IndexingService#startMigration(String)} backend; recovery slots can populate
 * {@code OperationInvocation.of(REBUILD_INDEX)} with no defaults required.
 *
 * <p>Mirrors {@link BulkReindexHandler}: same delegation pattern, same lazy-supplier
 * resolution. Tempdoc 542 Phase 3: registers an op-lease so dev-runner takeovers during
 * the rebuild get the handshake-protocol treatment.
 */
public final class RebuildIndexHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(RebuildIndexHandler.class);

  private final Supplier<IndexingService> indexingSupplier;
  private final OperationLeaseService leaseService;

  public RebuildIndexHandler(
      Supplier<IndexingService> indexingSupplier,
      OperationLeaseService leaseService) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
    this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("RebuildIndexHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    // Tempdoc 542 Phase 3: rebuild is MUST_COMPLETE (same shape as bulk-reindex).
    // Worker-native upgrade quiescence observes the persisted migration state after this RPC.
    OperationLeaseHandle handle = leaseService.register(
        "indexing.rebuild-index",
        OpCriticality.MUST_COMPLETE,
        1800L,
        Map.of("source", "core.rebuild-index"));
    try {
      var outcome = indexing.startMigration(MigrationSource.USER_REQUESTED_REBUILD.wire(), engineContext);
      if (!outcome.accepted()) {
        handle.release(OpLeaseOutcome.FAILURE);
        return OperationResult.failure("Index rebuild could not be started; see worker logs");
      }
      handle.release(OpLeaseOutcome.SUCCESS);
      return OperationResult.success("Index rebuild (migration) started",
          Map.of("restartRequired", outcome.restartRequired()));
    } catch (RuntimeException e) {
      handle.release(OpLeaseOutcome.FAILURE);
      log.error("RebuildIndexHandler: startMigration threw", e);
      return OperationResult.failure("Index rebuild failed: " + e.getMessage());
    }
  }
}
