/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExitLedgerCauseWarningTest {
  @Test void distinctWriteFailuresWarnOncePerCauseWithinAnEpisode(@TempDir Path dir) throws Exception {
    var messages = new ArrayList<String>();
    Logger logger = Logger.getLogger(ExitLedgerHealth.class.getName());
    var handler = new Handler() {
      @Override public void publish(LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) messages.add(
            record.getParameters() == null ? record.getMessage()
                : MessageFormat.format(record.getMessage(), record.getParameters()));
      }
      @Override public void flush() {}
      @Override public void close() {}
    };
    logger.addHandler(handler);
    Path blocked = dir.resolve("ledger"); Files.writeString(blocked, "blocked");
    var writer = ExitLedgerWriter.appendOnly(blocked, LedgerFamily.CHILDREN,
        ExitLedgerWriterTest.ENGINE, ExitLedgerWriterTest.CLOCK);
    var fields = Map.of("childId", "c-1", "token", "test");
    try {
      assertFalse(writer.append(LedgerRecordType.CHILD_NOTE, fields));
      assertFalse(writer.append(LedgerRecordType.CHILD_NOTE, fields));
      Files.delete(blocked); Files.createDirectories(LedgerFamily.CHILDREN.current(blocked));
      assertFalse(writer.append(LedgerRecordType.CHILD_NOTE, fields));
      assertFalse(writer.append(LedgerRecordType.CHILD_NOTE, fields));
      assertEquals(2, messages.size(), "a different I/O cause must not be hidden by the episode's first warning");
      assertTrue(messages.stream().allMatch(m -> m.contains("Exception")), messages.toString());
      assertNotEquals(messages.get(0), messages.get(1));
    } finally {
      logger.removeHandler(handler); Files.delete(LedgerFamily.CHILDREN.current(blocked));
      assertTrue(writer.append(LedgerRecordType.CHILD_NOTE, fields));
    }
  }

  @Test void measureDurableAppendLatency(@TempDir Path dir) {
    var writer = ExitLedgerWriter.appendOnly(dir, LedgerFamily.CHILDREN,
        ExitLedgerWriterTest.ENGINE, java.time.Clock.systemUTC());
    long[] times = new long[500];
    for (int i = 0; i < times.length; i++) {
      long start = System.nanoTime();
      assertTrue(writer.append(LedgerRecordType.CHILD_NOTE, Map.of("childId", "c-1", "token", "timing")));
      times[i] = System.nanoTime() - start;
    }
    Arrays.sort(times);
    System.out.println("durable-append-ns p99=" + times[494] + " max=" + times[499] + " samples=500");
  }
}
