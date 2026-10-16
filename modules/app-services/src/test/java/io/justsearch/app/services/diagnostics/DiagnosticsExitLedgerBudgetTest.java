/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiagnosticsExitLedgerBudgetTest {
  @Test void exportKeepsNewestRecordsWithinOneEightMiBBudget(@TempDir Path home) throws Exception {
    String previousHome = System.getProperty("justsearch.home"), previousData = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.home", home.toString()); System.setProperty("justsearch.data.dir", home.toString());
    try {
      Path ledger = Files.createDirectories(home.resolve("runtime/exit-ledger"));
      writeRecords(ledger.resolve("engine.v1.1.jsonl"), "old", 3 * 1024, 1);
      writeRecords(ledger.resolve("children.v1.jsonl"), "middle", 3 * 1024, 2);
      writeRecords(ledger.resolve("engine.v1.jsonl"), "newest", 4 * 1024, 3);
      Path zip = new DiagnosticsServiceImpl(null, null, () -> null, () -> null)
          .exportDiagnostics(io.justsearch.app.services.TestEngineContexts.internal());
      try (var z = new ZipFile(zip.toFile())) {
        long bytes = z.stream().filter(e -> e.getName().startsWith("runtime/exit-ledger/") && e.getName().endsWith(".jsonl"))
            .mapToLong(e -> e.getSize()).sum();
        assertTrue(bytes <= 8L * 1024 * 1024, "exit-ledger records have one 8 MiB budget, not an unbounded budget per file: " + bytes);
        String newest = text(z, "engine.v1.jsonl"), oldest = text(z, "engine.v1.1.jsonl");
        assertTrue(newest.contains("newest-0000") && newest.contains("newest-4095"));
        assertFalse(oldest.contains("old-0000"));
        assertTrue(oldest.contains("old-2048") && oldest.contains("old-3071"));
        var note = z.getEntry("runtime/exit-ledger/truncation.txt");
        assertNotNull(note, "truncation must state how much evidence was left out");
        String omitted = new String(z.getInputStream(note).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(omitted.contains("2097152"), omitted);
      }
    } finally {
      restore("justsearch.home", previousHome); restore("justsearch.data.dir", previousData);
    }
  }
  private static void writeRecords(Path file, String label, int count, int age) throws Exception {
    try (var out = Files.newBufferedWriter(file)) {
      for (int i = 0; i < count; i++) {
        String record = String.format(java.util.Locale.ROOT, "{\"record\":\"%s-%04d\",\"padding\":\"", label, i);
        out.write(record + "x".repeat(1024 - record.length() - 3) + "\"}\n");
      }
    }
    Files.setLastModifiedTime(file, FileTime.fromMillis(age * 1000L));
  }
  private static String text(ZipFile zip, String name) throws Exception {
    var entry = zip.getEntry("runtime/exit-ledger/" + name); assertNotNull(entry);
    try (var in = zip.getInputStream(entry)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
  }
  private static void restore(String key, String previous) {
    if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
  }
}
