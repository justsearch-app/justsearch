/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

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
 * Handler for {@code core.bulk-reindex}.
 *
 * <p>Delegates to {@link IndexingService#startMigration(String)} via a lazy supplier —
 * the IndexingService isn't available until after AppFacade construction, which
 * happens later in {@code HeadAssembly} than handler registration. The supplier
 * closure resolves the live service on each {@link #execute(String)} invocation.
 *
 * <p>V1 ignores the {@code corpusIds} arg from the Operation declaration — the
 * underlying {@code startMigration} only takes a reason string. Future expansion can
 * add corpus-scoped migrations when the indexing service supports them.
 *
 * <p>Slice 429 carryover: replaces the prior NOT_IMPLEMENTED stub (slice 1.2's
 * substrate-only ship). Real implementation lands as part of the slice 3a-1-2 +
 * slice 3a-2-c HealthView migration unblock.
 */
public final class BulkReindexHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(BulkReindexHandler.class);

  private final Supplier<IndexingService> indexingSupplier;
  private final OperationLeaseService leaseService;

  public BulkReindexHandler(
      Supplier<IndexingService> indexingSupplier,
      OperationLeaseService leaseService) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
    this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("BulkReindexHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }
    // Tempdoc 542 Phase 3: bulk reindex is MUST_COMPLETE — interruption mid-flight leaves
    // the index in an inconsistent state. Register the lease BEFORE issuing the gRPC call so
    // any concurrent takeover gate read sees the lease (race-window closure per §B.3).
    //
    // Worker persists MIGRATING before acknowledging. The Head lease protects request dispatch;
    // Worker-owned upgrade quiescence protects the asynchronous migration lifetime.
    OperationLeaseHandle handle = leaseService.register(
        "indexing.bulk-reindex",
        OpCriticality.MUST_COMPLETE,
        1800L,
        Map.of("source", "core.bulk-reindex"));
    try {
      var outcome = indexing.startMigration(MigrationSource.USER_REQUESTED_BULK_REINDEX.wire());
      if (!outcome.accepted()) {
        handle.release(OpLeaseOutcome.FAILURE);
        return OperationResult.failure("Bulk reindex could not be started; see worker logs");
      }
      handle.release(OpLeaseOutcome.SUCCESS);
      return OperationResult.success("Bulk reindex (migration) started",
          Map.of("restartRequired", outcome.restartRequired()));
    } catch (RuntimeException e) {
      handle.release(OpLeaseOutcome.FAILURE);
      log.error("BulkReindexHandler: startMigration threw", e);
      return OperationResult.failure("Bulk reindex failed: " + e.getMessage());
    }
  }
}
