/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LedgerRetentionScopeTest {
  @TempDir Path dir;
  private static final String ROW = """
      {"schemaVersion":1,"type":"engine-start","seq":1,"at":"2026-10-01T10:00:00Z",
       "writer":{"role":"shell","pid":100,"startedAt":"2026-10-01T10:00:00Z"},
       "incarnation":{"session":"old","n":1},"pid":200,"creationFileTime":"134353224000000000"}
      """.replace("\n", "") + "\n";

  private void evict(boolean compacted) throws Exception {
    Path current = LedgerFamily.ENGINE.current(dir), rotated = LedgerFamily.ENGINE.rotated(dir);
    if (compacted) {
      // Earlier receipts for another session: compaction must cover them AND the new eviction.
      var old = JsonMapper.builder().build().readTree(ROW).deepCopy();
      var loss = (tools.jackson.databind.node.ObjectNode) old;
      loss.put("type", "ledger-gap"); loss.put("reason", "EVIDENCE_LOST");
      loss.put("at", "2026-09-30T10:00:00Z"); loss.putObject("incarnation").put("session", "older").put("n", 1);
      loss.put("count", 1); loss.put("since", "2026-09-30T10:00:00Z");
      loss.put("scopeComplete", true); loss.putArray("affectedSessions").add("older");
      loss.put("evidenceFrom", "2026-09-30T10:00:00Z");
      loss.put("evidenceThrough", "2026-09-30T10:00:00Z");
      Files.writeString(LedgerRetention.lossPath(current), (loss.toString() + "\n").repeat(2000), StandardCharsets.UTF_8);
    }
    Files.writeString(rotated, ROW, StandardCharsets.UTF_8);
    Files.writeString(current, ROW, StandardCharsets.UTF_8);
    LedgerRetention.rotate(current, rotated, 0);
    var losses = ExitLedgerReader.read(dir, LedgerFamily.ENGINE).ofType(LedgerRecordType.LEDGER_GAP);
    assertFalse(losses.isEmpty());
    if (compacted) assertEquals(1, losses.size(), "the journal compacted to one row");
    String from = compacted ? "2026-09-30T10:00:00Z" : "2026-10-01T10:00:00Z";
    var sessions = compacted ? java.util.List.of("old", "older") : java.util.List.of("old");
    for (var loss : losses) {
      assertEquals(from, loss.text("evidenceFrom").orElse(null),
          "retain the original evidence range, not the eviction timestamp");
      assertEquals("2026-10-01T10:00:00Z", loss.text("evidenceThrough").orElse(null),
          "the range covers every lost record, including the newest eviction");
      assertTrue(loss.line().path("scopeComplete").asBoolean(), "every lost record has a known session");
      var affected = new java.util.ArrayList<String>();
      loss.line().path("affectedSessions").forEach(s -> affected.add(s.asString()));
      assertEquals(sessions, affected, "affected sessions cover every lost record");
    }
  }

  @Test void unreadableEvictedLineLeavesLossScopeIncomplete() throws Exception {
    Path current = LedgerFamily.ENGINE.current(dir), rotated = LedgerFamily.ENGINE.rotated(dir);
    Files.writeString(rotated, ROW + "{torn\n", StandardCharsets.UTF_8);
    Files.writeString(current, ROW, StandardCharsets.UTF_8);
    LedgerRetention.rotate(current, rotated, 0);
    var summaries = ExitLedgerReader.read(dir, LedgerFamily.ENGINE).ofType(LedgerRecordType.LEDGER_GAP)
        .stream().filter(r -> r.line().path("pid").isMissingNode()).toList();
    assertEquals(1, summaries.size());
    assertFalse(summaries.getFirst().line().path("scopeComplete").asBoolean(true),
        "an unreadable evicted line has unknown scope");
  }

  @Test void compactedUnknownRangeKeepsNewestEvictionTime() throws Exception {
    // Summaries written before scope fields existed leave the merged range unknown; the compacted
    // row's `at` must then be the newest eviction time, the bound a reader uses instead.
    Path current = LedgerFamily.ENGINE.current(dir), rotated = LedgerFamily.ENGINE.rotated(dir);
    String unscoped = """
        {"schemaVersion":1,"type":"ledger-gap","seq":1,"at":"2026-09-30T10:00:00Z",
         "writer":{"role":"engine","pid":100,"startedAt":"2026-09-30T09:00:00Z"},
         "since":"2026-09-30T10:00:00Z","reason":"EVIDENCE_LOST","count":1,
         "droppedFile":"engine.v1.9.jsonl","droppedBytes":512,"detail":"archive dropped at retention bound"}
        """.replace("\n", "") + "\n";
    Files.writeString(LedgerRetention.lossPath(current), unscoped.repeat(3000), StandardCharsets.UTF_8);
    Files.writeString(rotated, ROW, StandardCharsets.UTF_8);
    Files.writeString(current, ROW, StandardCharsets.UTF_8);
    var started = java.time.Instant.now().minusSeconds(1);
    LedgerRetention.rotate(current, rotated, 0);
    var losses = ExitLedgerReader.read(dir, LedgerFamily.ENGINE).ofType(LedgerRecordType.LEDGER_GAP);
    assertEquals(1, losses.size(), "the journal compacted to one row");
    var compacted = losses.getFirst();
    assertFalse(compacted.line().path("evidenceRangeComplete").asBoolean(true), "the merged range is unknown");
    assertTrue(compacted.text("evidenceFrom").isEmpty() && compacted.text("evidenceThrough").isEmpty(),
        "an unknown merged range keeps no bound");
    var at = java.time.Instant.parse(compacted.text("at").orElseThrow());
    assertFalse(at.isBefore(started), "the compacted row keeps the newest eviction time, not " + at);
  }

  @Test void retentionLossKeepsOriginalSessionAndEvidenceRange() throws Exception { evict(false); }
  @Test void compactedLossKeepsOriginalSessionAndEvidenceRange() throws Exception { evict(true); }
}
