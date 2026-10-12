/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExitLedgerRetentionTest {
  @TempDir Path dir;

  private static Map<String, Object> witness(int n, int i) {
    return Map.of("witnessId", "j-" + n + "-" + i, "pid", 1000L + n * 100 + i,
        "creationFileTime", "134359272000000000", "contained", true,
        "incarnation", Map.of("session", "five-incarnations", "n", n));
  }

  @Test void allFiveIncarnationsSurvivePerFileRotation() throws IOException {
    for (int n = 1; n <= 5; n++) {
      var writer = ExitLedgerWriterTest.writer(dir, 400, 900, ExitLedgerWriter.DELETE_AND_RENAME);
      writer.startSession();
      for (int i = 0; i < 8; i++) assertTrue(writer.append(LedgerRecordType.JOB_PROCESS_START, witness(n, i)));
    }
    var read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertEquals(40, read.ofType(LedgerRecordType.JOB_PROCESS_START).size(),
        "the run's previous incarnation evidence cannot be deleted at the per-file bound");
    assertTrue(Files.size(LedgerFamily.CHILDREN.current(dir)) <= 900);
  }

  @Test void boundWritesLostIdentitiesBeforeDroppingEvidence() throws IOException {
    ExitLedgerWriter.Rotation bounded = (current, rotated) -> {
      try {
        Class<?> retention = Class.forName("io.justsearch.app.api.runtime.ledger.LedgerRetention");
        var rotate = retention.getDeclaredMethod("rotate", Path.class, Path.class, long.class);
        rotate.setAccessible(true); rotate.invoke(null, current, rotated, 1800L);
      } catch (ClassNotFoundException before) {
        ExitLedgerWriter.DELETE_AND_RENAME.rotate(current, rotated);
      } catch (ReflectiveOperationException failure) { throw new IOException(failure); }
    };
    var writer = ExitLedgerWriterTest.writer(dir, 400, 900, bounded);
    for (int i = 0; i < 30; i++) assertTrue(writer.append(LedgerRecordType.JOB_PROCESS_START, witness(1, i)));
    var read = ExitLedgerReader.read(dir, LedgerFamily.CHILDREN);
    assertTrue(read.ofType(LedgerRecordType.LEDGER_GAP).stream().anyMatch(r ->
        r.text("reason").orElse("").equals("EVIDENCE_LOST")
        && r.line().get("pid").asLong() == 1100L), "identity-specific loss receipt must survive");
  }
}
