/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HelperGapSchemaTest {
  @TempDir Path dataDir;

  @Test void rollbackGapConformsToTheLedgerSchema() throws Exception { check(false); }
  @Test void bootBufferGapConformsToTheLedgerSchema() throws Exception { check(true); }

  private void check(boolean overflow) throws Exception {
    var recorder = OwnedHelper.class.getDeclaredField("recorder"); recorder.setAccessible(true);
    var queueField = OwnedHelper.class.getDeclaredField("beforeRecorder"); queueField.setAccessible(true);
    var lost = OwnedHelper.class.getDeclaredField("pendingLost"); lost.setAccessible(true);
    Object savedRecorder = recorder.get(null);
    @SuppressWarnings("unchecked") var queue = (ArrayDeque<Object>) queueField.get(null);
    var savedQueue = new ArrayDeque<>(queue); int savedLost = lost.getInt(null);
    var dir = LedgerFamily.directory(dataDir);
    var writer = ExitLedgerWriter.appendOnly(dir, LedgerFamily.CHILDREN,
        ExitLedgerWriterTest.ENGINE, ExitLedgerWriterTest.CLOCK);
    try {
      recorder.set(null, null); queue.clear(); lost.setInt(null, 0);
      if (overflow) {
        for (int i = 0; i < 2050; i++) try (var ignored = OwnedHelper.window("helper.exe", "probe", java.time.Duration.ZERO)) {}
      } else {
        Process nonexistent = new Process() {
          @Override public long pid() { return Integer.MAX_VALUE; }
          @Override public ProcessHandle toHandle() { return ProcessHandle.current(); }
          @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
          @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
          @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
          @Override public int waitFor() { return 0; }
          @Override public boolean waitFor(long n, TimeUnit unit) { return true; }
          @Override public int exitValue() { return 1; }
          @Override public void destroy() {}
          @Override public Process destroyForcibly() { return this; } // No real process exists.
        };
        assertThrows(IOException.class, () -> OwnedHelper.own(nonexistent, "failed ownership"));
      }
      OwnedHelper.recordTo((type, fields) -> type != LedgerRecordType.LEDGER_GAP || writer.append(type, fields),
          new EngineSelfIntentWriter.EngineIdentity(4242, Optional.empty(), "schema-gap"));
      var lines = Files.readAllLines(LedgerFamily.CHILDREN.current(dir));
      assertEquals(1, lines.size());
      ExitLedgerSchemaTest.load();
      var validate = ExitLedgerSchemaTest.class.getDeclaredMethod("assertConforms", String.class);
      validate.setAccessible(true);
      for (String line : lines) assertDoesNotThrow(() -> validate.invoke(null, line),
          "helper gap lines must include the schema-required since timestamp");
    } finally {
      recorder.set(null, savedRecorder); queue.clear(); queue.addAll(savedQueue); lost.setInt(null, savedLost);
    }
  }
}
