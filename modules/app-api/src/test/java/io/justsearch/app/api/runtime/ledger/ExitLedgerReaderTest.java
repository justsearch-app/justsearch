/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

@DisplayName("exit ledger reader tolerates what a crash or a newer writer leaves")
final class ExitLedgerReaderTest {

  private static final String WRITER =
      "\"writer\":{\"role\":\"engine\",\"pid\":7,\"startedAt\":\"2026-10-05T10:00:00Z\"}";

  @TempDir Path dir;

  @Test
  @DisplayName("missing files are an empty ledger, not an error")
  void missingFilesAreEmpty() throws IOException {
    LedgerReadResult read = ExitLedgerReader.read(dir.resolve("absent"), LedgerFamily.CHILDREN);
    assertEquals(List.of(), read.records());
    assertEquals(0, read.skippedLines());
  }

  @Test
  @DisplayName("a newer schemaVersion is refused line by line and counted, never fatal")
  void futureVersionIsRefused() throws IOException {
    write(LedgerFamily.CHILDREN.current(dir),
        line(1, "child-note", 1, "\"childId\":\"c\",\"token\":\"a\""),
        line(2, "child-note", 2, "\"childId\":\"c\",\"token\":\"b\""),
        line(1, "child-note", 3, "\"childId\":\"c\",\"token\":\"c\""));

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(List.of(1L, 3L), read.records().stream().map(LedgerRecord::seq).toList());
    assertEquals(1, read.unknownSchemaLines());
    assertEquals(0, read.corruptLines());
  }

  @Test
  @DisplayName("a torn last line is ignored and reported")
  void tornTailIgnored() throws IOException {
    Files.writeString(LedgerFamily.CHILDREN.current(dir),
        line(1, "child-note", 1, "\"childId\":\"c\",\"token\":\"a\"")
            + "{\"schemaVersion\":1,\"type\":\"child-",
        StandardCharsets.UTF_8);

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(1, read.records().size());
    assertTrue(read.tornTail());
    assertEquals(0, read.skippedLines(), "a torn tail is not a corrupt line");
  }

  @Test
  @DisplayName("malformed, oversized and envelope-less lines are corrupt; unknown and foreign types counted")
  void corruptAndUnknownLines() throws IOException {
    write(LedgerFamily.CHILDREN.current(dir),
        "not json",
        "[1,2]",
        "{\"schemaVersion\":1,\"type\":\"child-note\",\"seq\":0,\"at\":\"2026-10-05T10:00:00Z\"," + WRITER + "}",
        "{\"schemaVersion\":1,\"type\":\"child-note\",\"seq\":1,\"at\":\"2026-10-05T10:00:00Z\"}",
        line(1, "child-note", 2, "\"token\":\"" + "x".repeat(ExitLedger.MAX_READ_LINE_BYTES) + "\""),
        line(1, "child-teleported", 3, ""),
        line(1, "engine-self-intent", 4, ""),
        line(1, "child-note", 5, "\"childId\":\"c\",\"token\":\"ok\",\"futureField\":true"));

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(5, read.corruptLines());
    assertEquals(2, read.unknownTypeLines(), "an unknown type and a type of the other family");
    assertEquals(1, read.records().size());
    assertTrue(read.records().get(0).field("futureField").asBoolean(), "unknown fields are kept");
  }

  @Test
  @DisplayName("an unknown timestamp reads as unknown without dropping the record")
  void unknownTimestamps() throws IOException {
    write(LedgerFamily.ENGINE.current(dir),
        "{\"schemaVersion\":1,\"type\":\"ledger-gap\",\"seq\":1,\"at\":\"1601-01-01T00:00:00Z\","
            + WRITER + ",\"reason\":\"WRITE_FAILED\",\"count\":1,\"since\":\"2026-10-05T10:00:00Z\"}");

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.ENGINE);
    assertEquals(1, read.records().size());
    assertEquals(Optional.empty(), read.records().get(0).at());
    assertEquals(Optional.empty(), LedgerTimestamps.known("0"));
    assertEquals(Optional.empty(), LedgerTimestamps.known("2000-12-31T23:59:59.999Z"));
    assertEquals(Optional.empty(), LedgerTimestamps.known("2026-10-05T10:00:00+02:00"));
    assertEquals(Optional.of(Instant.parse("2001-01-01T00:00:00Z")),
        LedgerTimestamps.known("2001-01-01T00:00:00Z"));
  }

  @Test
  @DisplayName("the older generation is read before the current one")
  void rotatedFirst() throws IOException {
    write(LedgerFamily.CHILDREN.rotated(dir), line(1, "child-note", 7, "\"childId\":\"old\",\"token\":\"a\""));
    write(LedgerFamily.CHILDREN.current(dir), line(1, "child-note", 1, "\"childId\":\"new\",\"token\":\"a\""));

    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(List.of("old", "new"),
        read.records().stream().map(r -> r.text("childId").orElseThrow()).toList());
  }

  private static String line(int version, String type, long seq, String fields) {
    return "{\"schemaVersion\":" + version + ",\"type\":\"" + type + "\",\"seq\":" + seq
        + ",\"at\":\"2026-10-05T10:00:00Z\"," + WRITER + (fields.isEmpty() ? "" : "," + fields)
        + "}\n";
  }

  private static void write(Path file, String... lines) throws IOException {
    StringBuilder text = new StringBuilder();
    for (String l : lines) text.append(l.endsWith("\n") ? l : l + "\n");
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }
}
