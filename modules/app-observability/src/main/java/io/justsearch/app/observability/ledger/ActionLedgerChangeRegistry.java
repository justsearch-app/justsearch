/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.navigation.NavigationHistoryEntry;
import io.justsearch.app.observability.operations.AuthorizationOutcomeEntry;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified live change-stream for the action ledger — tempdoc 550 Outcome face (G3/G4/G5).
 *
 * <p>550 says the receipt, activity timeline, undo, and trust-audit are all read-views over one
 * ledger. The snapshot ({@code GET /api/action-ledger}) gives the current rows; this registry is
 * the LIVE channel: each of the three federated sources (operation history, navigation history,
 * trust-gate firings) calls the matching {@code broadcast*} method at its emit site, and this
 * registry projects the entry to the unified row shape ({@link ActionLedgerProjection}) and
 * publishes an UPDATE frame on one {@link SseStreamChannel}. The FE subscribes once and renders
 * every kind through the same projection it uses for the snapshot — so the live views update
 * without a poll, and the wire shape can never drift between snapshot and stream.
 *
 * <p>Federated-ledger discipline (D1): the per-kind stores stay authoritative; this registry is a
 * fan-in relay, not another store. It holds no entries.
 */
public final class ActionLedgerChangeRegistry {

  private static final Logger log = LoggerFactory.getLogger(ActionLedgerChangeRegistry.class);

  /** Stable StreamId for the unified action-ledger stream. */
  public static final StreamId STREAM_ID = StreamId.surface("action-ledger");

  /** Typed observers of every event entering the log (tempdoc 812 D2). */
  private final List<Consumer<ActionEvent>> eventListeners = new CopyOnWriteArrayList<>();

  private final SseStreamChannel channel;
  // Tempdoc 550 thesis I — the ONE action-event log. Every broadcast event is appended here, so the
  // snapshot endpoint and the live stream read one store rather than re-projecting per-kind stores.
  private final ActionEventStore store = new ActionEventStore();
  // Tempdoc 812 D1 — the durable write-behind copy of the actor kinds. Every producer already fans
  // in through publish(), so this is one sink addition rather than a change at four emit sites.
  private final ActionEventJournal journal;

  public ActionLedgerChangeRegistry() {
    this(ActionEventJournal.disabled());
  }

  public ActionLedgerChangeRegistry(ActionEventJournal journal) {
    this.channel = new SseStreamChannel(STREAM_ID);
    this.journal = Objects.requireNonNull(journal, "journal");
  }

  /** The one action-event log this registry fans every broadcast into (tempdoc 550 thesis I). */
  public ActionEventStore store() {
    return store;
  }

  /** The durable audit journal the actor kinds are also written to (tempdoc 812 D1). */
  public ActionEventJournal journal() {
    return journal;
  }

  /** The current monotonic seq cursor (for snapshot-then-resume). */
  public long currentSeq() {
    return channel.currentSeq();
  }

  /** The underlying channel for controller-side per-connection writer wiring. */
  public SseStreamChannel channel() {
    return channel;
  }

  public SseStreamChannel.Subscription subscribe(Consumer<SseEnvelope> listener) {
    return channel.subscribe(listener);
  }

  /** Relay a completed operation as a unified UPDATE row. */
  public void broadcastOperation(OperationHistoryEntry entry) {
    Objects.requireNonNull(entry, "entry");
    publish(ActionLedgerProjection.projectOperation(entry));
  }

  /** Relay a navigation as a unified UPDATE row. */
  public void broadcastNavigation(NavigationHistoryEntry entry) {
    Objects.requireNonNull(entry, "entry");
    publish(ActionLedgerProjection.projectNavigation(entry));
  }

  /** Relay a trust-gate firing as a unified UPDATE row. */
  public void broadcastGate(AuthorizationOutcomeEntry entry) {
    Objects.requireNonNull(entry, "entry");
    publish(ActionLedgerProjection.projectGate(entry));
  }

  /**
   * Relay an already-typed {@link ActionEvent} into the one log (tempdoc 550 thesis IV: grant
   * lifecycle events are emitted directly as ActionEvents, no per-kind store).
   */
  public void broadcastActionEvent(ActionEvent event) {
    Objects.requireNonNull(event, "event");
    publish(event);
  }

  /**
   * Tempdoc 812 D2 — observe every event entering the one log, TYPED (the SSE channel carries the
   * flattened wire row; a listener that needs the record must not re-parse a Map). Used by the
   * scan-rollup aggregator to count terminal indexing outcomes per scan. Listeners run on the
   * publishing thread AFTER the store append + channel publish, so an event is already readable
   * from the snapshot when a listener sees it; a listener that throws is logged and skipped so one
   * bad observer cannot break the ledger. A listener must not publish synchronously (re-entrancy);
   * the rollup aggregator defers its own emission to its scheduler for exactly that reason.
   */
  public void addEventListener(Consumer<ActionEvent> listener) {
    Objects.requireNonNull(listener, "listener");
    eventListeners.add(listener);
  }

  private void publish(ActionEvent event) {
    // Retry the durable sink even when a prior failed write already reached the live ring.
    // The journal owns retained-id deduplication; the ring only owns duplicate live delivery.
    journal.append(event);
    // Append to the one log FIRST (so the snapshot a new subscriber reads already includes it),
    // then broadcast the live UPDATE.
    //
    // The ring's id-idempotency gates live downstream consumers after the journal attempt. A re-delivered event that reached the SSE
    // channel or the typed listeners anyway would contradict the snapshot the same subscriber
    // reads (the row is there exactly once) and would double-count in listeners that aggregate,
    // e.g. ScanRollupLedger's per-scan terminal counts.
    if (!store.append(event)) {
      return;
    }
    channel.publish(SseFrameKind.UPDATE, ActionLedgerProjection.toWireRow(event));
    for (Consumer<ActionEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (RuntimeException e) {
        log.warn("action-ledger event listener threw; continuing", e);
      }
    }
  }
}
