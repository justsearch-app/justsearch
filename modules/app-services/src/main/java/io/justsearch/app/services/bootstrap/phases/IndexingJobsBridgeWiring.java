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
 * at bootstrap time, before {@code prepareKnowledgeServerBinding()} runs (LocalApiServer is built
 * before the index component is ready). The bridge resolves the client at {@code start()} time;
 * {@code activateKnowledgeServerBinding} triggers the start after publication.
 *
 * <p>Subscription bridges the worker's typed Delta events into the
 * {@link IndexingJobsChangeRegistry}'s flat broadcast feed consumed by the SSE controller.
 *
 * <p>Terminal activity is accepted through an independent committed-outcomes subscription,
 * before the row view's recoverable handoff. A view overflow can replace rows from a snapshot;
 * it must not lose activity. Both subscriptions use the existing bridge lifecycle and fences,
 * with independently owned executors. The outcome source starts with an empty registration
 * barrier, never historical rows. SSE delivery remains asynchronous, and snapshots never replay
 * terminal activity. Current ingestion rollups read the worker's persisted outcome ledger;
 * the historical ScanRollup event has no live producer.
 */
public final class IndexingJobsBridgeWiring {

  private IndexingJobsBridgeWiring() {}

  /** The bridge + subscription handle. The subscription handle is closed during teardown. */
  public record Output(RemoteIndexingJobsBridge bridge, RemoteIndexingJobsBridge.Subscription subscription) {}

  public static Output wire(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      IndexingJobsChangeRegistry indexingJobsChangeRegistry,
      ActionLedgerChangeRegistry actionLedgerChangeRegistry) {
    // A view can recover from a snapshot; terminal activity cannot. Keep its subscription
    // independent and accept outcomes synchronously before the lossy view handoff.
    var outcomes = new RemoteIndexingJobsBridge(executors, () -> {
      KnowledgeClient kc = knowledgeClientSupplier.get();
      return kc == null ? null : (onFrame, onError, onCompleted) -> kc.subscribeIndexingJobOutcomes(
          onFrame, onError, onCompleted, io.justsearch.app.services.intent.EngineProvenance.internal(
              "indexing-job-outcomes", io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
              io.justsearch.core.context.EngineContext.Urgency.BACKGROUND));
    }, "head.indexing-job-outcomes");
    var outcomeSubscription = outcomes.subscribe(delta -> {
      switch (delta) {
        case RemoteIndexingJobsBridge.Delta.Insert insert -> emitTerminalOutcome(actionLedgerChangeRegistry, insert.row());
        case RemoteIndexingJobsBridge.Delta.Update update -> emitTerminalOutcome(actionLedgerChangeRegistry, update.row());
        default -> {} // Snapshots, including reconnects, never replay activity.
      }
    });
    try {
    RemoteIndexingJobsBridge bridge =
        new RemoteIndexingJobsBridge(
            executors,
            () -> {
              KnowledgeClient kc = knowledgeClientSupplier.get();
              return kc == null ? null : (IndexingJobsSource) (onFrame, onError, onCompleted) -> {
                // Establish terminal registration before opening the view to callers.
                outcomes.start().join();
                return kc.subscribeIndexingJobs(onFrame, onError, onCompleted,
                    io.justsearch.app.services.intent.EngineProvenance.internal(
                        "indexing-jobs-bridge", io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
                        io.justsearch.core.context.EngineContext.Urgency.BACKGROUND));
              };
            });
    RemoteIndexingJobsBridge.Subscription subscription =
        bridge.subscribe(delta -> {
          switch (delta) {
            case RemoteIndexingJobsBridge.Delta.SnapshotReplaced sr ->
                indexingJobsChangeRegistry.broadcastAsync(
                    new IndexingJobsChangeRegistry.Delta.SnapshotReplaced(sr.items()));
            case RemoteIndexingJobsBridge.Delta.Insert ins -> {
              indexingJobsChangeRegistry.broadcastAsync(
                  new IndexingJobsChangeRegistry.Delta.Insert(ins.row()));
            }
            case RemoteIndexingJobsBridge.Delta.Update upd -> {
              indexingJobsChangeRegistry.broadcastAsync(
                  new IndexingJobsChangeRegistry.Delta.Update(upd.row()));
            }
            case RemoteIndexingJobsBridge.Delta.Delete del ->
                indexingJobsChangeRegistry.broadcastAsync(
                    new IndexingJobsChangeRegistry.Delta.Delete(del.pathHash()));
          }
        });
    return new Output(bridge, () -> {
      try { subscription.close(); }
      finally {
        try { outcomeSubscription.close(); }
        finally { outcomes.stop(); }
      }
    });
    } catch (RuntimeException | Error failure) {
      outcomeSubscription.close();
      try { outcomes.stop(); }
      catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private static void emitTerminalOutcome(ActionLedgerChangeRegistry registry, IndexingJobView row) {
    terminalIndexEvent(row).ifPresent(registry::broadcastActionEventAsync);
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
            // Retain the scan that enqueued this job (empty for non-scan jobs), including
            // outcomes that never belonged to the live row window.
            row.scanId()));
  }
}
