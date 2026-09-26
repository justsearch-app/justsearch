/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.stream.SseEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ring's id-idempotency is the ledger's ONE dedup point, so every downstream consumer of
 * {@code publish} is gated on it — not only the durable journal.
 *
 * <p>The defect: {@code store.append}'s accepted-return gated the journal write, but the SSE
 * channel publish ran unconditionally. A re-delivered event therefore
 * reached the live stream a second time while the snapshot the same subscriber
 * reads held it exactly once. The same id gate must protect every subscriber.
 */
@DisplayName("action-ledger duplicate delivery")
final class ActionLedgerDuplicateDeliveryTest {

  private static ActionEvent indexEvent(String pathHash, String state, String scanId) {
    return ActionLedgerProjection.projectIndex(
        pathHash, "scifact", state, 0, "", Instant.parse("2026-08-06T00:00:00Z"), scanId);
  }

  @Test
  @DisplayName("a re-delivered event reaches the channel exactly once")
  void duplicateEventFansOutOnce() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> frames = new ArrayList<>();
    registry.subscribe(frames::add);

    ActionEvent event = indexEvent("h1", "DONE", "scan-1");
    registry.broadcastActionEvent(event);
    registry.broadcastActionEvent(event);

    assertEquals(1, frames.size(), "the live stream must not contradict the snapshot");
    assertEquals(1, registry.store().recent().size(), "positive control: the ring holds one row");
  }

  @Test
  @DisplayName("positive control — a fresh event still reaches the channel")
  void freshEventStillDelivered() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> frames = new ArrayList<>();
    registry.subscribe(frames::add);

    registry.broadcastActionEvent(indexEvent("h1", "DONE", "scan-1"));
    registry.broadcastActionEvent(indexEvent("h2", "DONE", "scan-1"));
    registry.broadcastActionEvent(indexEvent("h3", "FAILED", "scan-1"));

    assertEquals(3, frames.size(), "distinct events must all be delivered — the gate is on id only");
    assertEquals(
        List.of("h1", "h2", "h3"),
        frames.stream().map(frame -> ((java.util.Map<?, ?>) frame.payload()).get("pathHash")).toList());
  }

}
