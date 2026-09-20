/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.navigation.NavigationHistoryEntry;
import io.justsearch.app.observability.operations.AuthorizationOutcomeEntry;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

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
 * fan-in relay. Its bounded live ring and retained audit journal are projections of those sources.
 */
public final class ActionLedgerChangeRegistry {

  /** Stable StreamId for the unified action-ledger stream. */
  public static final StreamId STREAM_ID = StreamId.surface("action-ledger");

  private final SseStreamChannel channel;
  // Tempdoc 550 thesis I — the ONE action-event log. Every broadcast event is appended here, so the
  // snapshot endpoint and the live stream read one store rather than re-projecting per-kind stores.
  private final ActionEventStore store = new ActionEventStore();
  // Operations split immediate live publication from forced journal acceptance in their drainer.
  // Other producers retain their existing synchronous publish path.
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

  /** Operations source delivery acknowledges only this forced, idempotent journal acceptance. */
  public boolean persistOperation(OperationHistoryEntry entry) {
    return journal.append(ActionLedgerProjection.projectOperation(Objects.requireNonNull(entry, "entry")));
  }

  /** Immediate committed-row visibility without moving journal I/O onto the producer thread. */
  public void publishLiveOperation(OperationHistoryEntry entry) {
    publishLive(ActionLedgerProjection.projectOperation(Objects.requireNonNull(entry, "entry")));
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

  private void publish(ActionEvent event) {
    // Retry the durable sink even when a prior failed write already reached the live ring.
    // The journal owns retained-id deduplication; the ring only owns duplicate live delivery.
    journal.append(event);
    publishLive(event);
  }

  private void publishLive(ActionEvent event) {
    // Append to the one log FIRST (so the snapshot a new subscriber reads already includes it),
    // then broadcast the live UPDATE.
    //
    // The ring's id-idempotency gates live downstream consumers after the journal attempt. A re-delivered event that reached the SSE
    // channel would contradict the snapshot the same subscriber
    // reads (the row is there exactly once).
    if (!store.append(event)) {
      return;
    }
    channel.publish(SseFrameKind.UPDATE, ActionLedgerProjection.toWireRow(event));

  }
}
