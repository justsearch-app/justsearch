/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.ledger.ActionLedgerProjection;
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.app.services.worker.IndexingJobsSource;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tempdoc 519 §7 / Step 7: Slice 445 — RemoteIndexingJobsBridge + subscription wiring extracted
 * from {@code HeadAssembly}'s main constructor body. The bridge is constructed eagerly
 * with a deferred-stub supplier so the catalog + change registry + controller wire-up happens
 * at bootstrap time, before {@code connectKnowledgeServer()} runs (LocalApiServer is built
 * before the gRPC channel comes up). The bridge resolves the stub at {@code start()} time;
 * {@code connectKnowledgeServer} triggers the start.
 *
 * <p>Subscription bridges the worker's typed Delta events into the
 * {@link IndexingJobsChangeRegistry}'s flat broadcast feed consumed by the SSE controller.
 *
 * <p><b>Tempdoc 550 thesis I (system-operation contributor).</b> The same subscription is the
 * Head-side observation point that feeds the indexing job lifecycle into the ONE action-event log:
 * a job's TERMINAL transition (state ∈ {@code DONE | FAILED}) is projected to an
 * {@link ActionLedgerProjection#projectIndex} event and broadcast to the
 * {@link ActionLedgerChangeRegistry}, so the Activity surface shows indexing happened. In-flight
 * states (PENDING/PROCESSING) are deliberately NOT emitted here — that live state is the rail's
 * Resource projection (the second governed projection of the same job lifecycle), and emitting
 * per-transition would flood the bounded action-event ring and evict agent actions. Snapshot
 * frames are also skipped so a worker reconnect cannot re-flood the (session-scoped) ledger with
 * historical DONE rows.
 */
public final class IndexingJobsBridgeWiring {

  private IndexingJobsBridgeWiring() {}

  /** The bridge + subscription handle. The subscription handle is closed during teardown. */
  public record Output(RemoteIndexingJobsBridge bridge, RemoteIndexingJobsBridge.Subscription subscription) {}

  public static Output wire(
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      IndexingJobsChangeRegistry indexingJobsChangeRegistry,
      ActionLedgerChangeRegistry actionLedgerChangeRegistry) {
    RemoteIndexingJobsBridge bridge =
        new RemoteIndexingJobsBridge(
            () -> {
              KnowledgeClient kc = knowledgeClientSupplier.get();
              return kc == null ? null : (IndexingJobsSource) (onFrame, onError, onCompleted) ->
                  kc.subscribeIndexingJobs(onFrame, onError, onCompleted,
                      io.justsearch.app.services.intent.EngineProvenance.internal(
                          "indexing-jobs-bridge", io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
                          io.justsearch.core.context.EngineContext.Urgency.BACKGROUND));
            });
    RemoteIndexingJobsBridge.Subscription subscription =
        bridge.subscribe(delta -> {
          switch (delta) {
            case RemoteIndexingJobsBridge.Delta.SnapshotReplaced sr ->
                indexingJobsChangeRegistry.broadcast(
                    new IndexingJobsChangeRegistry.Delta.SnapshotReplaced(sr.items()));
            case RemoteIndexingJobsBridge.Delta.Insert ins -> {
              indexingJobsChangeRegistry.broadcast(
                  new IndexingJobsChangeRegistry.Delta.Insert(ins.row()));
              emitTerminalOutcome(actionLedgerChangeRegistry, ins.row());
            }
            case RemoteIndexingJobsBridge.Delta.Update upd -> {
              indexingJobsChangeRegistry.broadcast(
                  new IndexingJobsChangeRegistry.Delta.Update(upd.row()));
              emitTerminalOutcome(actionLedgerChangeRegistry, upd.row());
            }
            case RemoteIndexingJobsBridge.Delta.Delete del ->
                indexingJobsChangeRegistry.broadcast(
                    new IndexingJobsChangeRegistry.Delta.Delete(del.pathHash()));
          }
        });
    return new Output(bridge, subscription);
  }

  private static void emitTerminalOutcome(ActionLedgerChangeRegistry registry, IndexingJobView row) {
    terminalIndexEvent(row).ifPresent(registry::broadcastActionEvent);
  }

  /**
   * The pure terminal-outcome decision (tempdoc 550 thesis I): a {@code kind=index} action-event iff
   * the row reached a TERMINAL state (DONE/FAILED), else empty. In-flight states (PENDING/PROCESSING)
   * return empty — they are the rail's live Resource projection, not a ledger outcome — so the
   * bounded action-event ring is never flooded with per-transition rows. The deterministic id (kind +
   * lastUpdatedMs + collection + pathHash + state) makes a re-delivered terminal transition
   * idempotent in the append-only store. Package-visible for direct unit testing of the decision.
   */
  static Optional<ActionEvent> terminalIndexEvent(IndexingJobView row) {
    if (row == null || row.state() == null) {
      return Optional.empty();
    }
    String state = row.state().toUpperCase(Locale.ROOT);
    // Tempdoc 885 item 21b: RETRY_EXHAUSTED is terminal too. Omitting it would have made a file
    // that spent a week retrying and then gave up produce NO ledger outcome at all — the one case
    // where an operator most needs a durable record.
    if (!state.equals(IndexingJobView.STATE_DONE)
        && !state.equals(IndexingJobView.STATE_FAILED)
        && !state.equals(IndexingJobView.STATE_RETRY_EXHAUSTED)) {
      return Optional.empty();
    }
    return Optional.of(
        ActionLedgerProjection.projectIndex(
            row.pathHash(),
            row.collection(),
            state,
            row.attempts(),
            row.errorMessage(),
            Instant.ofEpochMilli(row.lastUpdatedMs()),
            // Tempdoc 812 D2 — the scan that enqueued this job (empty for non-scan jobs). The
            // rollup aggregator counts these terminal outcomes per scanId; the FE groups the
            // surviving per-doc rows under their scan's rollup by the same key.
            row.scanId()));
  }
}
