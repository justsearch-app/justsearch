/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one ledger family, older generation first, and tolerates everything a crash or a newer
 * writer can leave behind (ADR-0051):
 *
 * <ul>
 *   <li>a last line without a newline is a torn write and is ignored;
 *   <li>a line over {@link ExitLedger#MAX_READ_LINE_BYTES}, or one that is not a valid v1 record,
 *       is skipped and counted as corrupt;
 *   <li>a line of a newer {@code schemaVersion} is skipped and counted;
 *   <li>a type unknown to the family is skipped and counted; unknown fields are kept and ignored;
 *   <li>missing files are an empty ledger, not an error.
 * </ul>
 *
 * Nothing here is fatal except an I/O error reading a file that exists, which the caller must treat
 * as an unreadable ledger.
 */
public final class ExitLedgerReader {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private ExitLedgerReader() {}

  /** Reads {@code family} under {@code ledgerDir} ({@link LedgerFamily#directory}). */
  public static LedgerReadResult read(Path ledgerDir, LedgerFamily family) throws IOException {
    Tally tally = new Tally();
    List<LedgerRecord> records = new ArrayList<>();
    for (Path file : List.of(family.rotated(ledgerDir), family.current(ledgerDir))) {
      byte[] bytes;
      try {
        bytes = Files.readAllBytes(file);
      } catch (NoSuchFileException e) {
        continue;
      }
      readFile(bytes, family, records, tally);
    }
    return new LedgerReadResult(
        records, tally.corrupt, tally.unknownSchema, tally.unknownType, tally.tornTail);
  }

  private static void readFile(
      byte[] bytes, LedgerFamily family, List<LedgerRecord> out, Tally tally) {
    int start = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] != '\n') continue;
      int end = i > start && bytes[i - 1] == '\r' ? i - 1 : i;
      readLine(bytes, start, end, family, out, tally);
      start = i + 1;
    }
    if (start < bytes.length) tally.tornTail = true;
  }

  private static void readLine(
      byte[] bytes, int start, int end, LedgerFamily family, List<LedgerRecord> out, Tally tally) {
    int length = end - start;
    if (length == 0) return; // a newline that closed a torn tail
    if (length > ExitLedger.MAX_READ_LINE_BYTES) {
      tally.corrupt++;
      return;
    }
    String text = new String(bytes, start, length, StandardCharsets.UTF_8);
    if (text.isBlank()) return;
    JsonNode line;
    try {
      line = JSON.readTree(text);
    } catch (JacksonException e) {
      tally.corrupt++;
      return;
    }
    if (line == null || !line.isObject()) {
      tally.corrupt++;
      return;
    }
    JsonNode version = line.get("schemaVersion");
    if (version == null || !version.isIntegralNumber() || version.longValue() < 1) {
      tally.corrupt++;
      return;
    }
    if (version.longValue() > ExitLedger.SCHEMA_VERSION) {
      tally.unknownSchema++;
      return;
    }
    JsonNode typeNode = line.get("type");
    if (typeNode == null || !typeNode.isString()) {
      tally.corrupt++;
      return;
    }
    Optional<LedgerRecordType> type =
        LedgerRecordType.fromToken(typeNode.asString()).filter(t -> t.belongsTo(family));
    if (type.isEmpty()) {
      tally.unknownType++;
      return;
    }
    JsonNode seq = line.get("seq");
    Optional<LedgerWriterId> writer = writer(line.get("writer"));
    if (seq == null || !seq.isIntegralNumber() || seq.longValue() < 1 || writer.isEmpty()) {
      tally.corrupt++;
      return;
    }
    JsonNode at = line.get("at");
    out.add(new LedgerRecord(
        ExitLedger.SCHEMA_VERSION,
        type.get(),
        seq.longValue(),
        at != null && at.isString() ? LedgerTimestamps.known(at.asString()) : Optional.empty(),
        writer.get(),
        line));
  }

  private static Optional<LedgerWriterId> writer(JsonNode node) {
    if (node == null || !node.isObject()) return Optional.empty();
    JsonNode role = node.get("role");
    JsonNode pid = node.get("pid");
    JsonNode startedAt = node.get("startedAt");
    if (role == null || !role.isString() || pid == null || !pid.isIntegralNumber()
        || pid.longValue() <= 0 || startedAt == null || !startedAt.isString()) {
      return Optional.empty();
    }
    Optional<LedgerWriterId.Role> parsedRole = LedgerWriterId.Role.fromToken(role.asString());
    Optional<java.time.Instant> started = LedgerTimestamps.known(startedAt.asString());
    if (parsedRole.isEmpty() || started.isEmpty()) return Optional.empty();
    return Optional.of(new LedgerWriterId(parsedRole.get(), pid.longValue(), started.get()));
  }

  private static final class Tally {
    int corrupt;
    int unknownSchema;
    int unknownType;
    boolean tornTail;
  }
}
