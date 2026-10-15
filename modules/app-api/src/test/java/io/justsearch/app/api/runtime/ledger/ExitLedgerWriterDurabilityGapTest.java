/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task b6d536 b101: a failed force of unforced records is a named durability gap. The writer is
 * unhealthy and the ledger records a {@code FORCE_FAILED} gap, open until a later force succeeds and
 * a forced record of the same gap carries {@code clearedAt}.
 */
@EnabledOnOs(OS.WINDOWS) // a DOS read-only ledger file makes the real force fail
final class ExitLedgerWriterDurabilityGapTest {
  @TempDir Path dir;

  @Test void aFailedForceIsAnOpenDurabilityGapUntilAForceSucceeds() throws Exception {
    ExitLedgerWriter writer = ExitLedgerWriter.appendOnly(dir, LedgerFamily.CHILDREN,
        ExitLedgerWriterTest.ENGINE, ExitLedgerWriterTest.CLOCK);
    Path current = LedgerFamily.CHILDREN.current(dir);
    assertTrue(writer.appendUnforced(LedgerRecordType.HELPER_WINDOW_END, window()));
    Files.setAttribute(current, "dos:readonly", true);
    try {
      assertFalse(writer.flush(), "a read-only ledger file cannot be forced");
    } finally {
      Files.setAttribute(current, "dos:readonly", false);
    }
    assertFalse(writer.healthy(), "an unforced record may not be durable: the writer reports the gap");

    // The next record carries the open gap the failed write could not record at once.
    assertTrue(writer.append(LedgerRecordType.HELPER_WINDOW_START, window()));
    assertFalse(writer.healthy(), "only a successful force clears the gap");
    List<JsonNode> open = gaps(current);
    assertEquals(1, open.size(), open.toString());
    assertFalse(open.getFirst().has("clearedAt"), open.toString());

    assertTrue(writer.flush());
    assertTrue(writer.healthy());
    List<JsonNode> gaps = gaps(current);
    assertEquals(2, gaps.size(), gaps.toString());
    assertEquals(gaps.get(0).get("since"), gaps.get(1).get("since"), "the clearing names the gap it closes");
    assertTrue(gaps.get(1).has("clearedAt"), gaps.toString());

    ExitLedgerSchemaTest.load();
    var validate = ExitLedgerSchemaTest.class.getDeclaredMethod("assertConforms", String.class);
    validate.setAccessible(true);
    for (String line : Files.readAllLines(current)) {
      if (line.contains("FORCE_FAILED")) assertDoesNotThrow(() -> validate.invoke(null, line), line);
    }
  }

  private static List<JsonNode> gaps(Path current) throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    return Files.readAllLines(current).stream().filter(line -> !line.isBlank()).map(json::readTree)
        .filter(node -> "ledger-gap".equals(node.path("type").asString())
            && "FORCE_FAILED".equals(node.path("reason").asString()))
        .toList();
  }

  private static Map<String, Object> window() {
    return Map.of("intentId", "i-durability", "image", "tesseract.exe", "reason", "durability gap",
        "requestedAt", "2026-10-15T10:00:00Z", "deadlineAt", "2026-10-15T10:01:00Z");
  }
}
