package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 812 D1 — the durable audit journal. Before this, the whole audit trail lived in a
 * process-lifetime ring: a Head restart emptied it, and a long actor-only session evicted rows with
 * no durable copy. These tests pin the guarantees that replace that.
 */
@DisplayName("ActionEventJournal — durable actor-event audit trail (tempdoc 812 D1)")
class ActionEventJournalTest {

  private static ActionEvent grant(String id, String at) {
    return new ActionEvent.Grant(
        id, Instant.parse(at), "user", "ALLOW_ALWAYS", "g-" + id, "GRANTED_ALWAYS", "core.x");
  }

  private static ActionEvent gate(String id, String at) {
    return new ActionEvent.Gate(
        id, Instant.parse(at), "agent", "AGENT_LOOP", "core.x", "GATED", "REQUIRE_APPROVAL", "UNTRUSTED");
  }

  private static ActionEvent op(String id, String at) {
    return new ActionEvent.Operation(
        id,
        Instant.parse(at),
        "agent",
        "AGENT_LOOP",
        "core.search-index",
        "SUCCESS",
        Optional.of("exec-" + id),
        Optional.of("sess-1"));
  }

  private static ActionEvent nav(String id, String at) {
    return new ActionEvent.Navigation(
        id, Instant.parse(at), "user", "BUTTON", "core.library", "src-" + id);
  }

  private static ActionEvent idx(String id, String at) {
    return new ActionEvent.Index(
        id, Instant.parse(at), "system", "WORKER_INDEXER", "h-" + id, "default", "DONE", 0, "", "");
  }

  private static String at(int second) {
    return String.format("2026-08-06T00:00:%02dZ", second);
  }

  @Test
  @DisplayName("all three durable kinds survive a restart: a fresh journal over the same dir sees them")
  void durableKindsSurviveRestart(@TempDir Path dir) {
    Path audit = dir.resolve("audit");
    ActionEventJournal before = ActionEventJournal.at(audit);
    before.append(grant("g1", at(1)));
    before.append(gate("t1", at(2)));
    before.append(op("o1", at(3)));

    // "Restart": a brand-new journal instance over the same data dir, as a new Head process gets.
    ActionEventJournal after = ActionEventJournal.at(audit);
    List<String> ids = after.tail(ActionEventJournal.TAIL_CAPACITY).stream().map(ActionEvent::id).toList();

    assertEquals(List.of("g1", "t1", "o1"), ids, "every pre-restart actor row is recovered, in order");
  }

  @Test
  @DisplayName("a recovered row round-trips to the same typed event (one schema, not two)")
  void recoveredRowRoundTripsToTheSameEvent(@TempDir Path dir) {
    Path audit = dir.resolve("audit");
    ActionEvent original = op("o1", at(1));
    ActionEventJournal.at(audit).append(original);

    ActionEvent recovered = ActionEventJournal.at(audit).tail(10).get(0);

    assertEquals(original, recovered, "the journal line is the wire row and parses back identically");
    assertEquals(
        ActionLedgerProjection.toWireRow(original),
        ActionLedgerProjection.toWireRow(recovered),
        "and therefore serializes to a byte-identical wire row");
  }

  /**
   * Tempdoc 812 D1×D2 — the scan rollup is durable precisely BECAUSE its {@code kind()} is
   * OPERATION, so it takes the journal path with no wiring of its own. That makes the read path the
   * risk: restoring it as a plain {@code Operation} would compile, journal, recover, and render as
   * "Indexed 0 documents" after every restart — losing exactly the counts the row exists to state.
   */
  @Test
  @DisplayName("a scan rollup is journaled and recovers as a ROLLUP, counts and scan key intact")
  void scanRollupRoundTripsWithItsSummary(@TempDir Path dir) {
    Path audit = dir.resolve("audit");
    ActionEvent rollup =
        new ActionEvent.ScanRollup(
            "historical-scan-1", Instant.parse(at(4)), "system", "WORKER_INDEXER",
            "scan-1", "scifact", "C:/corpus/scifact", "COMPLETED", 5184, 3, 5187, 372_000L);
    ActionEventJournal.at(audit).append(rollup);

    List<ActionEvent> recovered = ActionEventJournal.at(audit).tail(10);
    assertEquals(1, recovered.size(), "an operation-kind rollup IS a durable actor row");
    ActionEvent.ScanRollup restored =
        assertInstanceOf(
            ActionEvent.ScanRollup.class,
            recovered.get(0),
            "recovered as the rollup, not flattened into a bare Operation");
    assertEquals(rollup, restored);
    assertEquals("scan-1", restored.scanId());
    assertEquals(5184, restored.docsDone());
    assertEquals(3, restored.docsFailed());
    assertEquals(5187, restored.docsAdmitted());
    assertEquals(372_000L, restored.durationMs());
    assertEquals("C:/corpus/scifact", restored.root());
  }

  @Test
  @DisplayName("navigation, effect and index events are NOT journaled (ring-only ephemera)")
  void nonActorKindsAreNotJournaled(@TempDir Path dir) throws IOException {
    Path audit = dir.resolve("audit");
    ActionEventJournal journal = ActionEventJournal.at(audit);
    journal.append(nav("n1", at(1)));
    journal.append(
        new ActionEvent.Effect("e1", Instant.parse(at(2)), "user", "FE_EFFECT", "navigate", "#x"));
    for (int i = 0; i < 50; i++) {
      journal.append(idx("idx-" + i, at(3)));
    }

    assertEquals(List.of(), journal.tail(100), "no non-actor row entered the durable tail");
    Path active = audit.resolve("action-ledger.jsonl");
    assertFalse(Files.exists(active), "a burst of index events creates no journal file at all");

    // ... and the durable kinds still land, so the negative is about the kind filter, not wiring.
    journal.append(grant("g1", at(4)));
    assertEquals(
        List.of("g1"),
        ActionEventJournal.at(audit).tail(100).stream().map(ActionEvent::id).toList());
    assertTrue(Files.exists(active));
  }

  @Test
  @DisplayName("index events are excluded by kind classification, not by accident")
  void kindClassificationIsExplicit() {
    assertTrue(ActionEventJournal.isDurableKind(ActionEvent.ActionEventKind.GRANT));
    assertTrue(ActionEventJournal.isDurableKind(ActionEvent.ActionEventKind.GATE));
    assertTrue(ActionEventJournal.isDurableKind(ActionEvent.ActionEventKind.OPERATION));
    assertFalse(ActionEventJournal.isDurableKind(ActionEvent.ActionEventKind.INDEX));
    assertFalse(ActionEventJournal.isDurableKind(ActionEvent.ActionEventKind.NAVIGATION));
    assertFalse(ActionEventJournal.isDurableKind(ActionEvent.ActionEventKind.EFFECT));
  }

  @Test
  @DisplayName("the size/generation bounds are the documented 4 MB x 8")
  void boundsAreTheDocumentedOnes() {
    assertEquals(4L * 1024 * 1024, ActionEventJournal.MAX_GENERATION_BYTES);
    assertEquals(8, ActionEventJournal.MAX_GENERATIONS);
  }

  @Test
  @DisplayName("rotation caps the generation count, drops the oldest, and always keeps the newest events")
  void rotationDropsOldestGenerationAndKeepsNewest(@TempDir Path dir) throws IOException {
    Path audit = dir.resolve("audit");
    // One event per generation: a threshold below one line's length forces a rotation on every
    // append after the first, exercising the real append -> rotateIfNeeded path (the production
    // 4 MB bound would need ~200 MB of writes to overflow 8 generations).
    ActionEventJournal journal = ActionEventJournal.at(audit, 1L);
    int events = ActionEventJournal.MAX_GENERATIONS + 3;
    for (int i = 0; i < events; i++) {
      journal.append(grant("g" + i, at(i)));
    }

    long files;
    try (var stream = Files.list(audit)) {
      files = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).count();
    }
    assertEquals(
        ActionEventJournal.MAX_GENERATIONS, files, "generation count is capped, oldest dropped");

    // Re-read from disk only (a fresh instance has no in-memory mirror to fall back on).
    List<String> recovered =
        ActionEventJournal.at(audit).tail(ActionEventJournal.TAIL_CAPACITY).stream()
            .map(ActionEvent::id)
            .toList();
    assertEquals(
        ActionEventJournal.MAX_GENERATIONS,
        recovered.size(),
        "exactly one event survives per retained generation");
    assertTrue(recovered.contains("g" + (events - 1)), "the newest event is always present");
    assertFalse(recovered.contains("g0"), "the oldest generation was dropped");
  }

  @Test
  @DisplayName("the in-memory tail is bounded, and tail(n) returns the NEWEST n oldest-first")
  void tailIsBoundedAndNewest(@TempDir Path dir) {
    ActionEventJournal journal = ActionEventJournal.at(dir.resolve("audit"));
    for (int i = 0; i < ActionEventJournal.TAIL_CAPACITY + 25; i++) {
      journal.append(
          new ActionEvent.Grant(
              "g" + i, Instant.parse(at(0)).plusSeconds(i), "user", "ALLOW_ALWAYS", "g", "ISSUED", "s"));
    }

    List<ActionEvent> all = journal.tail(Integer.MAX_VALUE);
    assertEquals(ActionEventJournal.TAIL_CAPACITY, all.size(), "the mirror is bounded");
    assertEquals("g25", all.get(0).id(), "the oldest retained is the newest-500 boundary");

    List<ActionEvent> three = journal.tail(3);
    assertEquals(
        List.of("g" + (ActionEventJournal.TAIL_CAPACITY + 22),
            "g" + (ActionEventJournal.TAIL_CAPACITY + 23),
            "g" + (ActionEventJournal.TAIL_CAPACITY + 24)),
        three.stream().map(ActionEvent::id).toList(),
        "tail(n) is the newest n, still oldest-first");
  }

  @Test
  @DisplayName("a line from an unknown/future schema is refused, and the readable rows still load")
  void futureOrTornLinesAreRefusedNotFatal(@TempDir Path dir) throws IOException {
    Path audit = dir.resolve("audit");
    ActionEventJournal.at(audit).append(grant("g1", at(1)));
    Path active = audit.resolve("action-ledger.jsonl");
    Files.writeString(
        active,
        // A row whose `kind` this build does not know (a newer build wrote it) + a torn tail line.
        "{\"id\":\"future-1\",\"kind\":\"quantum\",\"occurredAt\":\"2026-08-06T00:00:05Z\"}\n"
            + "{\"id\":\"torn-1\",\"kind\":\"gra",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    List<String> ids =
        ActionEventJournal.at(audit).tail(100).stream().map(ActionEvent::id).toList();

    assertEquals(
        List.of("g1"),
        ids,
        "unknown-kind and torn lines are skipped; the rest of the audit trail still loads");
  }

  @Test
  @DisplayName("the actor cliff: 600 actor events past a 500-row ring lose none from the durable record")
  void actorCliffLosesNothingDurable(@TempDir Path dir) throws IOException {
    Path audit = dir.resolve("audit");
    // Drive the REAL production fan-in point, not the journal directly.
    ActionLedgerChangeRegistry registry = new ActionLedgerChangeRegistry(ActionEventJournal.at(audit));
    int total = 600;
    for (int i = 0; i < total; i++) {
      registry.broadcastActionEvent(
          new ActionEvent.Grant(
              "g" + i,
              Instant.parse(at(0)).plusSeconds(i),
              "user",
              "ALLOW_ALWAYS",
              "g" + i,
              "GRANTED_ALWAYS",
              "core.x"));
    }

    // The ring is bounded and HAS evicted actor rows — that is the cliff.
    assertEquals(500, registry.store().recent().size(), "the ring is still bounded at 500");
    assertEquals(
        100, registry.store().actorEvictions(), "100 actor rows fell off the ring");
    assertFalse(
        registry.store().recent().stream().anyMatch(e -> "g0".equals(e.id())),
        "the oldest actor rows are gone from the ring");

    // ... and not one of them is lost from the durable record.
    List<String> journaled =
        Files.readAllLines(audit.resolve("action-ledger.jsonl"), StandardCharsets.UTF_8);
    assertEquals(total, journaled.size(), "every actor event is on disk, including the evicted ones");
    assertTrue(journaled.get(0).contains("\"g0\""), "the row the ring dropped first is still journaled");
    assertTrue(
        journaled.get(total - 1).contains("\"g" + (total - 1) + "\""), "and so is the newest");
  }

  @Test
  @DisplayName("a disabled journal writes nothing and serves an empty tail")
  void disabledJournalIsInert(@TempDir Path dir) throws IOException {
    ActionEventJournal journal = ActionEventJournal.disabled();
    journal.append(grant("g1", at(1)));

    assertFalse(journal.isEnabled());
    assertEquals(List.of(), journal.tail(10));
    try (var stream = Files.list(dir)) {
      assertEquals(0, stream.count(), "no file was created anywhere");
    }
  }
}
