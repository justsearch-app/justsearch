/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerRetentionEncodingTest {
  @TempDir Path dir;
  private static final String ROW = """
      {"schemaVersion":1,"type":"engine-start","seq":1,"at":"2026-10-01T10:00:00Z",
       "writer":{"role":"shell","pid":100,"startedAt":"2026-10-01T10:00:00Z"},
       "incarnation":{"session":"old","n":1},"pid":200,"creationFileTime":"134353224000000000"}
      """.replace("\n", "") + "\n";

  /**
   * A write cut off inside a non-ASCII path leaves bytes that are not UTF-8. That line is corrupt;
   * the archive above the bound is still evicted and the readable row keeps its loss receipt.
   */
  @Test void evictionProceedsPastALineCutInsideAMultibyteCharacter() throws Exception {
    Path current = LedgerFamily.ENGINE.current(dir), rotated = LedgerFamily.ENGINE.rotated(dir);
    Path archive = rotated.resolveSibling(rotated.getFileName().toString().replace(".1.jsonl", ".2.jsonl"));
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(ROW.getBytes(StandardCharsets.UTF_8));
    bytes.writeBytes("{\"schemaVersion\":1,\"executable\":\"C:/Users/Z".getBytes(StandardCharsets.UTF_8));
    bytes.write(0xC3);
    Files.write(archive, bytes.toByteArray());
    Files.writeString(current, ROW, StandardCharsets.UTF_8);

    IOException failure = null;
    try {
      LedgerRetention.rotate(current, rotated, 0);
    } catch (IOException e) {
      failure = e;
    }
    assertFalse(Files.exists(archive), "the archive above the retention bound is evicted: " + failure);
    assertNull(failure, "one bad line does not fail rotation");

    var losses = ExitLedgerReader.read(dir, LedgerFamily.ENGINE).ofType(LedgerRecordType.LEDGER_GAP);
    var summaries = losses.stream().filter(r -> r.line().path("pid").isMissingNode()).toList();
    assertEquals(1, summaries.size(), "one eviction summary");
    var summary = summaries.get(0).line();
    assertEquals(1, summary.path("corruptLines").asInt(-1), "the cut-off line is counted as corrupt");
    assertFalse(summary.path("scopeComplete").asBoolean(true), "an unreadable line leaves the scope incomplete");
    assertEquals(1, losses.stream().filter(r -> r.line().path("pid").asLong() == 200).count(),
        "the readable row keeps its loss receipt");
  }

  private static final String EARLIER_LOSS = """
      {"schemaVersion":1,"type":"ledger-gap","seq":1,"at":"2026-10-07T10:00:00Z",
       "writer":{"role":"engine","pid":90,"startedAt":"2026-10-07T10:00:00Z"},"reason":"EVIDENCE_LOST",
       "count":1,"since":"2026-10-07T10:00:00Z","droppedFile":"engine.v1.3.jsonl","droppedBytes":10}
      """.replace("\n", "");
  private static final String CUT_OFF = "{\"schemaVersion\":1,\"type\":\"ledger-gap\",\"detail\":\"cut";

  /** Evicts one readable archive with {@code journal} as the loss journal; returns its lines after. */
  private java.util.List<String> evictWithJournal(String journal) throws Exception {
    Path current = LedgerFamily.ENGINE.current(dir), rotated = LedgerFamily.ENGINE.rotated(dir);
    Path archive = rotated.resolveSibling(rotated.getFileName().toString().replace(".1.jsonl", ".2.jsonl"));
    Files.writeString(archive, ROW, StandardCharsets.UTF_8);
    Files.writeString(current, ROW, StandardCharsets.UTF_8);
    Path loss = LedgerRetention.lossPath(current);
    Files.writeString(loss, journal, StandardCharsets.UTF_8);
    LedgerRetention.rotate(current, rotated, 0);
    assertFalse(Files.exists(archive), "the archive above the retention bound is evicted");
    return Files.readAllLines(loss, StandardCharsets.UTF_8);
  }

  @Test void journalRewriteKeepsAnUnreadableLineVerbatimAndCountsIt() throws Exception {
    var lines = evictWithJournal(EARLIER_LOSS + "\n" + CUT_OFF + "\n");
    assertEquals(1, lines.stream().filter(CUT_OFF::equals).count(), "the unreadable journal line is kept as it was");
    assertEquals(EARLIER_LOSS, lines.get(0), "earlier loss records stay first");
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    var summaries = lines.stream().filter(line -> !line.equals(CUT_OFF)).map(mapper::readTree)
        .filter(r -> "archive dropped at retention bound".equals(r.path("detail").asString(null))).toList();
    assertEquals(1, summaries.size(), "one eviction summary");
    assertEquals(1, summaries.get(0).path("corruptLines").asInt(-1), "the unreadable journal line is counted as corrupt");
  }

  @Test void journalCompactionKeepsAnUnreadableLineVerbatim() throws Exception {
    String padded = EARLIER_LOSS.replace("\"droppedBytes\":10}", "\"droppedBytes\":10,\"detail\":\"" + "x".repeat(300) + "\"}");
    var lines = evictWithJournal((padded + "\n").repeat(2000) + CUT_OFF + "\n");
    assertEquals(1, lines.stream().filter(CUT_OFF::equals).count(), "compaction keeps the unreadable journal line");
    assertEquals(1, lines.stream().filter(line -> !line.equals(CUT_OFF)).count(), "the readable journal compacted to one row");
  }
}
