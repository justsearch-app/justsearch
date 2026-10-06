/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.api.runtime.child.StopDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec B T15: the Java, Node and Rust readers give the same summary of the same bytes. Each case
 * under {@code governance/fixtures/exit-ledger/cases/} holds ledger files (torn tails, CRLF, corrupt
 * lines, newer schema versions, unknown types, unknown timestamps) and an {@code
 * expected-summary.json}; {@code scripts/dev/test-dev-runner-exit-ledger.mjs} checks the Node reader
 * against the same file, so the two readers agree through it.
 */
@DisplayName("exit ledger cross-language summaries")
final class ExitLedgerSummaryParityTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Record fields that hold an instant; each is read through the 2001 rule. */
  private static final List<String> TIME_FIELDS = List.of(
      "processStartedAt", "requestedAt", "deadlineAt", "exitedAt", "observedAt",
      "terminateCalledAt", "cooperativeAt", "since");

  @Test
  void javaReaderGivesTheSharedSummary() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("governance"))) root = root.getParent();
    Path cases = root.resolve("governance/fixtures/exit-ledger/cases");
    List<Path> dirs;
    try (Stream<Path> listing = Files.list(cases)) {
      dirs = listing.filter(Files::isDirectory).sorted().toList();
    }
    assertFalse(dirs.isEmpty(), "shared ledger cases exist");
    for (Path dir : dirs) {
      ObjectNode actual = JSON.createObjectNode();
      actual.set("children", summary(dir, LedgerFamily.CHILDREN));
      actual.set("engine", summary(dir, LedgerFamily.ENGINE));
      JsonNode expected = JSON.readTree(Files.readString(dir.resolve("expected-summary.json")));
      // Round-trip so number nodes compare by value, not by the int/long node class.
      JsonNode normalized = JSON.readTree(JSON.writeValueAsString(actual));
      assertEquals(expected, normalized, () -> dir.getFileName() + " summary");
    }
  }

  private static ObjectNode summary(Path dir, LedgerFamily family) throws IOException {
    LedgerReadResult read = ExitLedgerReader.read(dir, family);
    ObjectNode out = JSON.createObjectNode();
    out.put("family", family.name().toLowerCase(Locale.ROOT));
    ArrayNode records = out.putArray("records");
    for (LedgerRecord record : read.records()) {
      ObjectNode r = records.addObject();
      r.put("type", record.type().token());
      r.put("seq", record.seq());
      putInstant(r, "at", record.at());
      ObjectNode writer = r.putObject("writer");
      writer.put("role", record.writer().role().token());
      writer.put("pid", record.writer().pid());
      writer.put("startedAt", LedgerTimestamps.format(record.writer().startedAt()));
      r.put("detail", record.detail().map(StopDetail::token).orElse(null));
      r.put("detailRaw", record.detailRaw().orElse(null));
      ObjectNode times = r.putObject("times");
      for (String name : TIME_FIELDS) {
        JsonNode value = record.line().get(name);
        if (value == null) continue;
        putInstant(times, name,
            value.isString() ? LedgerTimestamps.known(value.asString()) : Optional.empty());
      }
    }
    out.put("corruptLines", read.corruptLines());
    out.put("unknownSchemaLines", read.unknownSchemaLines());
    out.put("unknownTypeLines", read.unknownTypeLines());
    out.put("tornTail", read.tornTail());
    return out;
  }

  private static void putInstant(ObjectNode node, String name, Optional<Instant> instant) {
    if (instant.isPresent()) {
      node.put(name, LedgerTimestamps.format(instant.get()));
    } else {
      node.putNull(name);
    }
  }
}
