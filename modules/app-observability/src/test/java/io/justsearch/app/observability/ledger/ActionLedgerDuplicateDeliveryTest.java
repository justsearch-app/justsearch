/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The ring's id-idempotency is the ledger's ONE dedup point, so every downstream consumer of
 * {@code publish} is gated on it — not only the durable journal.
 *
 * <p>The defect: {@code store.append}'s accepted-return gated the journal write, but the SSE
 * channel publish and the typed listener loop ran unconditionally. A re-delivered event therefore
 * reached the live stream and every listener a second time while the snapshot the same subscriber
 * reads held it exactly once — and {@code ScanRollupLedger}, a listener that AGGREGATES, counted it
 * twice.
 */
@DisplayName("action-ledger duplicate delivery")
final class ActionLedgerDuplicateDeliveryTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private static ActionEvent indexEvent(String pathHash, String state, String scanId) {
    return ActionLedgerProjection.projectIndex(
        pathHash, "scifact", state, 0, "", Instant.parse("2026-08-06T00:00:00Z"), scanId);
  }

  @Test
  @DisplayName("a re-delivered event reaches the channel and the listeners exactly once")
  void duplicateEventFansOutOnce() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> frames = new ArrayList<>();
    List<ActionEvent> observed = new ArrayList<>();
    registry.subscribe(frames::add);
    registry.addEventListener(observed::add);

    ActionEvent event = indexEvent("h1", "DONE", "scan-1");
    registry.broadcastActionEvent(event);
    registry.broadcastActionEvent(event);

    assertEquals(1, frames.size(), "the live stream must not contradict the snapshot");
    assertEquals(1, observed.size(), "a typed listener must see each event once");
    assertEquals(1, registry.store().recent().size(), "positive control: the ring holds one row");
  }

  @Test
  @DisplayName("positive control — a fresh event still reaches the channel and the listeners")
  void freshEventStillDelivered() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    List<SseEnvelope> frames = new ArrayList<>();
    List<ActionEvent> observed = new ArrayList<>();
    registry.subscribe(frames::add);
    registry.addEventListener(observed::add);

    registry.broadcastActionEvent(indexEvent("h1", "DONE", "scan-1"));
    registry.broadcastActionEvent(indexEvent("h2", "DONE", "scan-1"));
    registry.broadcastActionEvent(indexEvent("h3", "FAILED", "scan-1"));

    assertEquals(3, frames.size(), "distinct events must all be delivered — the gate is on id only");
    assertEquals(3, observed.size());
    assertEquals(
        List.of("h1", "h2", "h3"),
        observed.stream().map(e -> ((ActionEvent.Index) e).pathHash()).toList());
  }

  @Test
  @DisplayName("a duplicate terminal cannot inflate a scan's counts or trip completion early")
  void duplicateTerminalDoesNotInflateRollup() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger =
        new ScanRollupLedger(processExecutors, registry, Runnable::run, null, () -> 1_000L, 60_000L);

    ledger.scanStarted("scan-1", "scifact", "C:/corpus");
    ledger.scanEnumerated("scan-1", 3);
    ActionEvent first = indexEvent("h1", "DONE", "scan-1");
    registry.broadcastActionEvent(first);
    registry.broadcastActionEvent(first);
    // A RETRY of the same document mints a different event id, so the ring cannot catch it; the
    // rollup's own per-document guard must.
    registry.broadcastActionEvent(
        ActionLedgerProjection.projectIndex(
            "h1", "scifact", "DONE", 1, "", Instant.parse("2026-08-06T00:00:05Z"), "scan-1"));
    registry.broadcastActionEvent(indexEvent("h2", "DONE", "scan-1"));

    assertEquals(
        1,
        rollups(registry).size(),
        "2 of 3 documents have terminalized — only the STARTED row, no completion yet");

    registry.broadcastActionEvent(indexEvent("h3", "FAILED", "scan-1"));
    List<ActionEvent.ScanRollup> rows = rollups(registry);
    assertEquals(2, rows.size(), "one STARTED + one completion row");
    ActionEvent.ScanRollup done = rows.get(1);
    assertEquals("COMPLETED", done.outcome());
    assertEquals(2, done.docsDone(), "h1 counted once despite three terminal deliveries");
    assertEquals(1, done.docsFailed());
  }

  @Test
  @DisplayName("close() emits a PARTIAL row for every open scan — no dangling STARTED in the journal")
  void closeEmitsPartialForOpenScans() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger =
        new ScanRollupLedger(processExecutors, registry, Runnable::run, null, () -> 4_000L, 60_000L);

    ledger.scanStarted("scan-a", "scifact", "C:/corpus");
    ledger.scanEnumerated("scan-a", 5);
    registry.broadcastActionEvent(indexEvent("h1", "DONE", "scan-a"));
    registry.broadcastActionEvent(indexEvent("h2", "FAILED", "scan-a"));
    ledger.scanStarted("scan-b", "notes", "C:/notes");

    assertEquals(2, rollups(registry).size(), "only the two STARTED rows before close");

    ledger.close();

    List<ActionEvent.ScanRollup> partials =
        rollups(registry).stream().filter(r -> !"STARTED".equals(r.outcome())).toList();
    assertEquals(2, partials.size(), "each open scan is closed out, not silently dropped");
    assertTrue(partials.stream().allMatch(r -> "PARTIAL".equals(r.outcome())));

    ActionEvent.ScanRollup a =
        partials.stream().filter(r -> "scan-a".equals(r.scanId())).findFirst().orElseThrow();
    assertEquals(1, a.docsDone(), "the counts so far are reported, not zeroed");
    assertEquals(1, a.docsFailed());
    assertEquals(5, a.docsAdmitted());

    ActionEvent.ScanRollup b =
        partials.stream().filter(r -> "scan-b".equals(r.scanId())).findFirst().orElseThrow();
    assertEquals(0, b.docsDone());
    assertEquals(ScanRollupLedger.ADMITTED_UNKNOWN, b.docsAdmitted());
  }

  @Test
  @DisplayName("close() is idempotent — a second close emits nothing")
  void closeIsIdempotent() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger =
        new ScanRollupLedger(processExecutors, registry, Runnable::run, null, () -> 1_000L, 60_000L);
    ledger.scanStarted("scan-x", "notes", "C:/notes");

    ledger.close();
    int after = rollups(registry).size();
    ledger.close();

    assertEquals(after, rollups(registry).size(), "the open-scan map was already drained");
    assertFalse(rollups(registry).isEmpty());
  }

  private static List<ActionEvent.ScanRollup> rollups(ActionLedgerChangeRegistry registry) {
    return registry.store().recent().stream()
        .filter(e -> e instanceof ActionEvent.ScanRollup)
        .map(ActionEvent.ScanRollup.class::cast)
        .toList();
  }
}
