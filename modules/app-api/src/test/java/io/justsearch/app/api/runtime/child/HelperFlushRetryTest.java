/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;

/** Task b6d536 b101, verifier round 2: records remain pending when the disk force fails and need another group force. */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
@Tag("cross-platform")
final class HelperFlushRetryTest {
  @Test void failedGroupForceRetainsPendingRecordsForTheNextFlush(@TempDir Path dir) throws Exception {
    var recorder = OwnedHelper.class.getDeclaredField("recorder");
    recorder.setAccessible(true);
    Object saved = recorder.get(null);
    AtomicInteger forces = new AtomicInteger();
    var writer = ExitLedgerWriter.appendOnly(dir, LedgerFamily.CHILDREN,
        LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, Instant.now()), Clock.systemUTC());
    LedgerSink actual = LedgerSink.of(writer);
    try {
      OwnedHelper.recordTo(new LedgerSink() {
        @Override public boolean append(LedgerRecordType type, Map<String, ?> fields) { return actual.append(type, fields); }
        @Override public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) { return actual.appendUnforced(type, fields); }
        @Override public boolean flush() {
          int attempt = forces.incrementAndGet();
          Path file = LedgerFamily.CHILDREN.current(dir);
          java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = null;
          try {
            if (io.justsearch.configuration.PlatformPaths.isLinux()) permissions = Files.getPosixFilePermissions(file);
            if (attempt == 1) {
              if (permissions == null) Files.setAttribute(file, "dos:readonly", true);
              else {
                var readOnly = java.util.EnumSet.copyOf(permissions);
                readOnly.removeAll(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
                Files.setPosixFilePermissions(file, readOnly);
              }
            }
            boolean success = actual.flush();
            System.out.println("actual FileChannel.force attempt=" + attempt + " success=" + success);
            if (attempt == 1) assertTrue(!success, "readonly native ledger caused the intended flush failure");
            return success;
          } catch (java.io.IOException problem) { throw new RuntimeException(problem); }
          finally {
            try {
              if (permissions == null) Files.setAttribute(file, "dos:readonly", false);
              else Files.setPosixFilePermissions(file, permissions);
            } catch (java.io.IOException problem) { throw new RuntimeException(problem); }
          }
        }
      }, new EngineIdentity(ProcessHandle.current().pid(),
          ProcessTerminator.forCurrentOs().creationFileTime(ProcessHandle.current().pid()), "flush-retry"));
      var window = OwnedHelper.sharedWindow("tesseract.exe", "flush retry OCR", Duration.ofMinutes(5));
      window.acquire().close();
      window.close(); // the unforced window end is pending, and this first force fails
      assertTrue(forces.get() >= 1, "closing attempted the group force");
      OwnedHelper.flushPending();
      assertTrue(forces.get() >= 2,
          "a failed force cannot erase pending records; a later flush must retry, observed=" + forces.get());
      // The failed force was a named durability gap, closed only by the later successful force.
      var lines = Files.readAllLines(LedgerFamily.CHILDREN.current(dir));
      assertTrue(lines.stream().anyMatch(line -> line.contains("\"type\":\"ledger-gap\"")
          && line.contains("\"reason\":\"FORCE_FAILED\"") && line.contains("\"clearedAt\"")),
          "the ledger names the durability gap and its clearing: " + lines);
    } finally {
      recorder.set(null, saved);
    }
  }
}
