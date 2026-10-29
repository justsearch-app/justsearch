/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Failed cleanup history stays bounded while survivor identity evidence remains intact. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class LedgerSurvivorRotationTest {
  private static final Map<String, Object> OWNER_A =
      Map.of("pid", 4242L, "creationFileTime", "134044416020000000", "instanceId", "inst-a");

  @TempDir Path dataDir;

  @Test
  void rotationsCarryOnlyFourRecentStopIntentsAndAllIdentityReceipts() throws Exception {
    var fixture = new ChildControlHarness(dataDir);
    Path ledgerDir = fixture.ledgerDir;
    var writer = fixture.writer;
    spawnAndStart(writer, "stuck", 919191, null,
        Instant.parse("2026-10-06T08:00:01Z"), Path.of("java").toAbsolutePath().toString());
    // Conflicting original starts must still prevent ambiguous cleanup after rotation.
    spawnAndStart(writer, "stuck", 919192, null,
        Instant.parse("2026-10-06T08:00:02Z"), Path.of("java").toAbsolutePath().toString());
    var identity = fixture.records();
    for (int boot = 0; boot < 5; boot++) {
      for (int attempt = 0; attempt < 6; attempt++) {
        String id = "s-" + (boot * 6 + attempt);
        assertTrue(writer.append(LedgerRecordType.STOP_INTENT, fields("childId", "stuck",
            "intentId", id, "reason", "CRASH_RECOVERY", "mode", "NOW",
            "requestedAt", Instant.now(), "requestedBy", "predecessor-fallback")));
        assertTrue(writer.append(LedgerRecordType.CHILD_SURVIVED, fields("childId", "stuck",
            "intentId", id, "attempts", 2, "basis", "terminate_timeout")));
      }
      Files.writeString(LedgerFamily.CHILDREN.current(ledgerDir), "\n".repeat(1024 * 1024),
          java.nio.file.StandardOpenOption.APPEND);
      writer.startSession();
      // Read just the new generation: old retries may remain in retention-managed archives.
      var carried = Files.readAllLines(LedgerFamily.CHILDREN.current(ledgerDir)).stream()
          .map(new tools.jackson.databind.ObjectMapper()::readTree).toList();
      assertTrue(carried.containsAll(identity.stream().map(LedgerRecord::line).toList()),
          "all original identity receipts remain intact, including conflicting starts");
      var intents = carried.stream().filter(r -> r.path("type").asString().equals("stop-intent"))
          .map(r -> r.path("intentId").asString()).toList();
      assertEquals(List.of("s-" + (boot * 6 + 2), "s-" + (boot * 6 + 3),
          "s-" + (boot * 6 + 4), "s-" + (boot * 6 + 5)), intents,
          "only the four latest failed cleanup intents carry forward per child");
      assertEquals(1, carried.stream().filter(r -> r.path("type").asString()
          .equals("child-survived")).count(), "only the latest survivor receipt carries");
      for (var line : carried) {
        assertTrue((line.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096);
      }
    }
  }

  private static void spawnAndStart(ExitLedgerWriter writer, String childId, long pid,
      String creationFileTime, Instant startedAt, String executable) {
    writer.append(LedgerRecordType.CHILD_SPAWN, spawn(childId));
    writer.append(LedgerRecordType.CHILD_START, fields("childId", childId, "pid", pid,
        "processStartedAt", startedAt, "creationFileTime", creationFileTime,
        "executable", executable, "argvHash", "a".repeat(64), "contained", null,
        "gpuDevice", null, "processIdentity", startedAt != null && io.justsearch.configuration.PlatformPaths.isLinux()
            ? LinuxProcessIdentity.read(pid) : Optional.empty()));
  }

  private static Map<String, Object> spawn(String childId) {
    return fields("childId", childId, "kind", "EXTRACTION", "label", "fake", "owner", OWNER_A,
        "gpu", false);
  }

  private static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], pairs[i + 1]);
    return fields;
  }
}
