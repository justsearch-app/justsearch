/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("exit ledger writer")
final class ExitLedgerWriterTest {

  static final Instant NOW = Instant.parse("2026-10-05T10:15:02.114Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final LedgerWriterId ENGINE =
      new LedgerWriterId(LedgerWriterId.Role.ENGINE, 4242L, Instant.parse("2026-10-05T10:00:00Z"));

  @TempDir Path dataDir;

  @Test
  void persistenceFailureWarnsOnceAndRecoveryIsReported() throws Exception {
    Path dir = LedgerFamily.directory(dataDir);
    Files.createDirectories(dir);
    Path blocked = LedgerFamily.CHILDREN.current(dir);
    Files.createDirectory(blocked);
    var messages = new java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>();
    var logger = java.util.logging.Logger.getLogger("io.justsearch.app.api.runtime.ledger.ExitLedgerHealth");
    var handler = new java.util.logging.Handler() {
      @Override public void publish(java.util.logging.LogRecord record) { messages.add(record); }
      @Override public void flush() {}
      @Override public void close() {}
    };
    logger.addHandler(handler);
    ExitLedgerWriter writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN, ENGINE, CLOCK);
    try {
      assertFalse(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "test", "token", "first")));
      assertFalse(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "test", "token", "second")));
      Files.delete(blocked);
      assertTrue(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "test", "token", "recovered")));
      assertEquals(1, messages.stream().filter(m -> m.getMessage().contains("EXIT_LEDGER_UNWRITABLE")).count(),
          "one visible warning per failure episode");
      assertEquals(1, messages.stream().filter(m -> m.getMessage().contains("Exit ledger recovered")).count());
      assertTrue(ExitLedgerReader.read(dir, LedgerFamily.CHILDREN).records().stream()
          .anyMatch(record -> record.type() == LedgerRecordType.LEDGER_GAP));
    } finally { logger.removeHandler(handler); }
  }

  @Test
  @DisplayName("records round-trip with the v1 envelope, seq from 1, and land under runtime/exit-ledger")
  void roundTrip() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    ExitLedgerWriter writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN, ENGINE, CLOCK);
    writer.startSession();

    assertTrue(writer.append(LedgerRecordType.CHILD_SPAWN, spawn("c-1")));
    assertTrue(writer.append(LedgerRecordType.STOP_INTENT, stopIntent("c-1")));
    assertTrue(writer.healthy());

    assertTrue(Files.isRegularFile(dataDir.resolve("runtime/exit-ledger/children.v1.jsonl")));
    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(0, read.skippedLines());
    assertEquals(2, read.records().size());
    LedgerRecord spawn = read.records().get(0);
    assertEquals(LedgerRecordType.CHILD_SPAWN, spawn.type());
    assertEquals(1L, spawn.seq());
    assertEquals(Optional.of(NOW), spawn.at());
    assertEquals(ENGINE, spawn.writer());
    assertEquals("LLAMA_SERVER", spawn.text("kind").orElseThrow());
    LedgerRecord intent = read.records().get(1);
    assertEquals(2L, intent.seq());
    assertEquals("request_budget", intent.text("detail").orElseThrow());
    assertEquals("2026-10-05T10:15:00Z", intent.text("requestedAt").orElseThrow());
    assertTrue(intent.field("unused").isMissingNode());
  }

  @Test
  @DisplayName("a session closes a torn tail with a newline so the next record starts clean")
  void tornTailIsClosed() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    Files.createDirectories(dir);
    Files.writeString(LedgerFamily.CHILDREN.current(dir),
        "{\"schemaVersion\":1,\"type\":\"child-note\",\"seq\":1,\"at\":\"2026", StandardCharsets.UTF_8);

    ExitLedgerWriter writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN, ENGINE, CLOCK);
    writer.startSession();
    assertTrue(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "c-1", "token", "x")));

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(1, read.records().size(), "the new record is readable");
    assertEquals(1, read.corruptLines(), "the torn fragment is one corrupt line, not a lost record");
    assertFalse(read.tornTail());
  }

  @Test
  @DisplayName("a torn line another writer leaves mid-session is closed before the next record")
  void tornLineMidSessionIsClosed() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    ExitLedgerWriter writer = ExitLedgerWriter.appendOnly(dir, LedgerFamily.ENGINE, ENGINE, CLOCK);
    writer.startSession();
    // ledger-gap belongs to every family, so it stands in for any engine record.
    assertTrue(writer.append(LedgerRecordType.LEDGER_GAP, gapFields()));

    // Another writer of the same family (the supervisor) is cut short after the session started.
    Files.writeString(LedgerFamily.ENGINE.current(dir),
        "{\"schemaVersion\":1,\"type\":\"engine-sp", StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    assertTrue(writer.append(LedgerRecordType.LEDGER_GAP, gapFields()), "reported durable");

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.ENGINE);
    assertEquals(List.of(1L, 2L), read.records().stream().map(LedgerRecord::seq).toList(),
        "the record after the torn line is readable");
    assertEquals(1, read.corruptLines(), "the torn fragment is one corrupt line");
    assertFalse(read.tornTail());
  }

  private static Map<String, Object> gapFields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("reason", "WRITE_FAILED");
    fields.put("count", 1);
    fields.put("since", LedgerTimestamps.format(NOW));
    return fields;
  }

  @Test
  @DisplayName("a write failure returns false, never throws, and leaves a gap on the next write")
  void writeFailureLeavesGap() throws IOException {
    Path dir = dataDir.resolve("blocked");
    Files.writeString(dir, "a file where the ledger directory should be");
    ExitLedgerWriter writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN, ENGINE, CLOCK);

    assertFalse(writer.append(LedgerRecordType.STOP_INTENT, stopIntent("c-1")));
    assertFalse(writer.append(LedgerRecordType.STOP_INTENT, stopIntent("c-2")));
    assertFalse(writer.healthy());

    Files.delete(dir);
    assertTrue(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "c-3", "token", "x")));
    assertTrue(writer.healthy());

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(List.of(LedgerRecordType.LEDGER_GAP, LedgerRecordType.CHILD_NOTE),
        read.records().stream().map(LedgerRecord::type).toList());
    LedgerRecord gap = read.records().get(0);
    assertEquals("WRITE_FAILED", gap.text("reason").orElseThrow());
    assertEquals(2L, gap.field("count").longValue());
    assertEquals(LedgerTimestamps.format(NOW), gap.text("since").orElseThrow());
    assertEquals(List.of(4L, 5L), read.records().stream().map(LedgerRecord::seq).toList(),
        "every attempt spends a seq (1, then 2 and 3 failed), so lost records leave a hole");
  }

  @Test
  void sessionRotation() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    ExitLedgerWriter first = writer(dir, 600, 4096, ExitLedgerWriter.DELETE_AND_RENAME);
    first.startSession();
    for (int i = 0; i < 4; i++) first.append(LedgerRecordType.CHILD_SPAWN, spawn("c-" + i));
    Files.writeString(LedgerFamily.CHILDREN.rotated(dir), "stale older generation\n");

    ExitLedgerWriter second = writer(dir, 600, 4096, ExitLedgerWriter.DELETE_AND_RENAME);
    second.startSession();
    second.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "c-9", "token", "x"));

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(1, read.corruptLines(), "retained corrupt history is reported, never silently erased");
    assertEquals(5, read.records().size(), "older generation first, then current");
    assertEquals(LedgerRecordType.CHILD_NOTE, read.records().get(4).type());
    assertEquals(1, Files.readAllLines(LedgerFamily.CHILDREN.current(dir)).size());
  }

  @Test
  @DisplayName("a rotating writer rotates before an append would pass the hard cap")
  void hardCapRotation() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    ExitLedgerWriter writer = writer(dir, 600, 900, ExitLedgerWriter.DELETE_AND_RENAME);
    writer.startSession();
    for (int i = 0; i < 6; i++) assertTrue(writer.append(LedgerRecordType.CHILD_SPAWN, spawn("c-" + i)));

    assertTrue(Files.size(LedgerFamily.CHILDREN.current(dir)) <= 900);
    assertTrue(Files.exists(LedgerFamily.CHILDREN.rotated(dir)));
  }

  @Test
  @DisplayName("a rotation failure keeps appending and records a ROTATE_FAILED gap")
  void rotationFailureIsAGap() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    ExitLedgerWriter writer = writer(dir, 600, 900, (current, rotated) -> {
      throw new IOException("sharing violation");
    });
    writer.startSession();
    for (int i = 0; i < 6; i++) assertTrue(writer.append(LedgerRecordType.CHILD_SPAWN, spawn("c-" + i)));

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(6, read.ofType(LedgerRecordType.CHILD_SPAWN).size(), "no record was lost");
    List<LedgerRecord> gaps = read.ofType(LedgerRecordType.LEDGER_GAP);
    assertFalse(gaps.isEmpty());
    assertEquals("ROTATE_FAILED", gaps.get(0).text("reason").orElseThrow());
    assertTrue(Files.size(LedgerFamily.CHILDREN.current(dir)) > 900, "the file grew past its cap");
    assertFalse(Files.exists(LedgerFamily.CHILDREN.rotated(dir)));
  }

  @Test
  @DisplayName("an append-only writer never rotates")
  void appendOnlyNeverRotates() throws IOException {
    Path dir = LedgerFamily.directory(dataDir);
    ExitLedgerWriter writer = new ExitLedgerWriter(dir, LedgerFamily.CHILDREN, ENGINE, false, CLOCK,
        (current, rotated) -> {
          throw new AssertionError("rotated");
        }, 600, 900);
    writer.startSession();
    for (int i = 0; i < 6; i++) assertTrue(writer.append(LedgerRecordType.CHILD_SPAWN, spawn("c-" + i)));
    assertFalse(Files.exists(LedgerFamily.CHILDREN.rotated(dir)));
  }

  @Test
  @DisplayName("caller bugs are refused: wrong family, envelope fields, oversized records")
  void callerBugsAreRefused() {
    Path dir = LedgerFamily.directory(dataDir);
    ExitLedgerWriter writer = ExitLedgerWriter.rotating(dir, LedgerFamily.CHILDREN, ENGINE, CLOCK);
    assertThrows(IllegalArgumentException.class,
        () -> writer.append(LedgerRecordType.ENGINE_SELF_INTENT, Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> writer.append(LedgerRecordType.CHILD_NOTE, Map.of("seq", 9)));
    assertThrows(IllegalArgumentException.class,
        () -> writer.append(LedgerRecordType.CHILD_NOTE,
            Map.of("childId", "c", "token", "x".repeat(ExitLedger.MAX_RECORD_BYTES))));
    assertFalse(Files.exists(LedgerFamily.CHILDREN.current(dir)));
  }

  static ExitLedgerWriter writer(Path dir, long sessionBytes, long capBytes,
      ExitLedgerWriter.Rotation rotation) {
    return new ExitLedgerWriter(dir, LedgerFamily.CHILDREN, ENGINE, true, CLOCK, rotation,
        sessionBytes, capBytes);
  }

  static Map<String, Object> spawn(String childId) {
    Map<String, Object> owner = new LinkedHashMap<>();
    owner.put("pid", 4242L);
    owner.put("creationFileTime", "134042121021140000");
    owner.put("instanceId", "inst-1");
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", childId);
    fields.put("kind", ChildKind.LLAMA_SERVER);
    fields.put("label", "llama-main");
    fields.put("owner", owner);
    fields.put("gpu", true);
    return fields;
  }

  static Map<String, Object> stopIntent(String childId) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", childId);
    fields.put("intentId", "i-" + childId);
    fields.put("reason", StopReason.TIMEOUT);
    fields.put("detail", Optional.of(StopDetail.REQUEST_BUDGET));
    fields.put("mode", StopMode.NOW);
    fields.put("requestedAt", Instant.parse("2026-10-05T10:15:00Z"));
    fields.put("requestedBy", "chat-controller");
    return fields;
  }
}
