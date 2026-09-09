/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.TestEngineExecutors;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 812 D2 — the scan rollup: an N-document scan leaves ONE durable audit record whose
 * counts are the REAL terminal job states, not the enqueue-time admitted count.
 */
@DisplayName("ScanRollupLedger")
final class ScanRollupLedgerTest {

  /** Test clock — the ledger reads it for start/quiescence timing. */
  private final AtomicLong now = new AtomicLong(1_000L);
  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private ScanRollupLedger ledger(ActionLedgerChangeRegistry registry, long quiesceMs) {
    // Synchronous emit + no sweeper thread: the test drives quiescence itself.
    return new ScanRollupLedger(processExecutors, registry, Runnable::run, null, now::get, quiesceMs);
  }

  private static void terminal(
      ActionLedgerChangeRegistry registry, String pathHash, String state, String scanId) {
    registry.broadcastActionEvent(
        ActionLedgerProjection.projectIndex(
            pathHash, "scifact", state, 0, "", Instant.parse("2026-08-06T00:00:00Z"), scanId));
  }

  private static List<ActionEvent.ScanRollup> rollups(ActionLedgerChangeRegistry registry) {
    return registry.store().recent().stream()
        .filter(e -> e instanceof ActionEvent.ScanRollup)
        .map(ActionEvent.ScanRollup.class::cast)
        .toList();
  }

  @Test
  @DisplayName("an N-document scan yields ONE completion row; its counts are the terminal states")
  void oneRowPerScanWithRealCounts() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 60_000L);

    ledger.scanStarted("scan-1", "scifact", "C:/corpus/scifact");
    ledger.scanEnumerated("scan-1", 5);
    terminal(registry, "h1", "DONE", "scan-1");
    terminal(registry, "h2", "DONE", "scan-1");
    terminal(registry, "h3", "FAILED", "scan-1");
    terminal(registry, "h4", "DONE", "scan-1");
    now.set(9_000L);
    terminal(registry, "h5", "FAILED", "scan-1");

    List<ActionEvent.ScanRollup> rows = rollups(registry);
    assertEquals(2, rows.size(), "exactly one STARTED + one FINISHED row for the scan");
    assertEquals("STARTED", rows.get(0).outcome());
    ActionEvent.ScanRollup done = rows.get(1);
    assertEquals("COMPLETED", done.outcome());
    // The counts are the OBSERVED terminal states (3 DONE / 2 FAILED), not the admitted 5-as-done.
    assertEquals(3, done.docsDone());
    assertEquals(2, done.docsFailed());
    assertEquals(5, done.docsAdmitted());
    assertEquals("scan-1", done.scanId());
    assertEquals("scifact", done.collection());
    assertEquals("C:/corpus/scifact", done.root());
    assertEquals(8_000L, done.durationMs());
    // 5 per-document index rows stay in the log untouched (ring-only ephemera; D2 keeps them).
    assertEquals(
        5, registry.store().recent().stream().filter(e -> e instanceof ActionEvent.Index).count());
  }

  @Test
  @DisplayName("the rollup is an OPERATION-kind row — the durable tier, never index-first evicted")
  void rollupIsOperationKind() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 60_000L);
    ledger.scanStarted("scan-2", "scifact", "C:/corpus");
    ledger.scanEnumerated("scan-2", 1);
    terminal(registry, "h1", "DONE", "scan-2");

    ActionEvent.ScanRollup done = rollups(registry).get(1);
    assertEquals(ActionEvent.ActionEventKind.OPERATION, done.kind());

    // Index-first eviction: a burst that overflows a small store sacrifices index rows, and the
    // rollup — the audit record the burst is about — survives.
    ActionEventStore store = new ActionEventStore(4);
    store.append(done);
    for (int i = 0; i < 20; i++) {
      store.append(
          ActionLedgerProjection.projectIndex(
              "h" + i, "scifact", "DONE", 0, "", Instant.parse("2026-08-06T00:00:00Z"), "scan-2"));
    }
    assertTrue(store.recent().contains(done), "the scan rollup outlives the per-document burst");
  }

  @Test
  @DisplayName("a scan that goes quiet before every document terminalizes closes out as PARTIAL")
  void quiescenceClosesPartialScans() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 5_000L);
    ledger.scanStarted("scan-3", "notes", "C:/notes");
    ledger.scanEnumerated("scan-3", 10);
    terminal(registry, "h1", "DONE", "scan-3");

    now.set(2_000L);
    assertEquals(List.of(), ledger.sweep(), "not quiet long enough yet");
    assertEquals(1, rollups(registry).size(), "only the STARTED row so far");

    now.set(20_000L);
    assertEquals(List.of("scan-3"), ledger.sweep());
    ActionEvent.ScanRollup partial = rollups(registry).get(1);
    assertEquals("PARTIAL", partial.outcome());
    assertEquals(1, partial.docsDone());
    assertEquals(10, partial.docsAdmitted());
    // Closed out — a second sweep does not emit a duplicate.
    now.set(90_000L);
    assertEquals(List.of(), ledger.sweep());
    assertEquals(2, rollups(registry).size());
  }

  @Test
  @DisplayName("an enumeration that failed (admitted unknown) still closes out on quiescence")
  void unknownAdmittedClosesOnQuiescence() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 5_000L);
    ledger.scanStarted("scan-4", "notes", "C:/notes");
    terminal(registry, "h1", "DONE", "scan-4");
    ledger.scanEnumerated("scan-4", ScanRollupLedger.ADMITTED_UNKNOWN);

    now.set(20_000L);
    assertEquals(List.of("scan-4"), ledger.sweep());
    ActionEvent.ScanRollup partial = rollups(registry).get(1);
    assertEquals("PARTIAL", partial.outcome());
    assertEquals(1, partial.docsDone());
    assertEquals(ScanRollupLedger.ADMITTED_UNKNOWN, partial.docsAdmitted());
  }

  @Test
  @DisplayName("terminal rows from another scan, or with no scan key at all, are never counted in")
  void onlyOwnScanCounts() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 60_000L);
    ledger.scanStarted("scan-a", "scifact", "C:/a");
    ledger.scanStarted("scan-b", "notes", "C:/b");
    ledger.scanEnumerated("scan-a", 2);
    ledger.scanEnumerated("scan-b", 1);

    terminal(registry, "h1", "DONE", "scan-b");
    terminal(registry, "h2", "DONE", ""); // single-file ingest / watcher / pre-812 row
    terminal(registry, "h3", "DONE", "scan-a");
    terminal(registry, "h4", "FAILED", "scan-a");

    List<ActionEvent.ScanRollup> finished =
        rollups(registry).stream().filter(r -> !"STARTED".equals(r.outcome())).toList();
    assertEquals(2, finished.size());
    ActionEvent.ScanRollup b = finished.get(0);
    assertEquals("scan-b", b.scanId());
    assertEquals(1, b.docsDone());
    assertEquals(0, b.docsFailed());
    ActionEvent.ScanRollup a = finished.get(1);
    assertEquals("scan-a", a.scanId());
    assertEquals(1, a.docsDone());
    assertEquals(1, a.docsFailed());
  }

  @Test
  @DisplayName("a scan that admitted nothing completes immediately with zero counts")
  void emptyScanCompletesImmediately() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 60_000L);
    ledger.scanStarted("scan-5", "notes", "C:/empty");
    ledger.scanEnumerated("scan-5", 0);

    ActionEvent.ScanRollup done = rollups(registry).get(1);
    assertEquals("COMPLETED", done.outcome());
    assertEquals(0, done.docsDone());
    assertEquals(0, done.docsFailed());
  }

  @Test
  @DisplayName("the wire row carries the summary under kind=operation")
  void wireRow() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 60_000L);
    ledger.scanStarted("scan-6", "scifact", "C:/corpus");
    ledger.scanEnumerated("scan-6", 1);
    now.set(7_500L);
    terminal(registry, "h1", "DONE", "scan-6");

    var row = ActionLedgerProjection.toWireRow(rollups(registry).get(1));
    assertEquals("operation", row.get("kind"));
    assertEquals(ActionEvent.ScanRollup.OPERATION_ID, row.get("operationId"));
    assertEquals("COMPLETED", row.get("outcome"));
    assertEquals("scan-6", row.get("scanId"));
    assertEquals("scifact", row.get("collection"));
    assertEquals("C:/corpus", row.get("root"));
    assertEquals(1, row.get("docsDone"));
    assertEquals(0, row.get("docsFailed"));
    assertEquals(6_500L, row.get("durationMs"));
    assertEquals("system", row.get("originator"));
  }

  @Test
  @DisplayName("a per-document row without a scan key omits scanId on the wire (keyless legacy)")
  void keylessIndexRowOmitsScanId() {
    ActionEvent keyless =
        ActionLedgerProjection.projectIndex(
            "h1", "default", "DONE", 0, "", Instant.parse("2026-08-06T00:00:00Z"), "");
    assertFalse(ActionLedgerProjection.toWireRow(keyless).containsKey("scanId"));
    ActionEvent keyed =
        ActionLedgerProjection.projectIndex(
            "h1", "default", "DONE", 0, "", Instant.parse("2026-08-06T00:00:00Z"), "scan-7");
    assertEquals("scan-7", ActionLedgerProjection.toWireRow(keyed).get("scanId"));
  }

  @Test
  @DisplayName("a re-emitted completion for the same scan dedups in the id-keyed store")
  void completionIsIdempotent() {
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry();
    ScanRollupLedger ledger = ledger(registry, 5_000L);
    ledger.scanStarted("scan-8", "notes", "C:/n");
    ledger.scanEnumerated("scan-8", 1);
    terminal(registry, "h1", "DONE", "scan-8");
    ActionEvent first = rollups(registry).get(1);

    // A late duplicate terminal for a closed scan must not resurrect or double-count it.
    terminal(registry, "h1", "DONE", "scan-8");
    now.set(90_000L);
    ledger.sweep();
    assertEquals(2, rollups(registry).size());
    assertSame(first, rollups(registry).get(1));
  }
}
