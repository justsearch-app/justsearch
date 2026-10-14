/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiagnosticsExitLedgerTornLineTest {
  @Test void exportKeepsCompleteRecordsAroundMalformedAndTornLines(@TempDir Path home) throws Exception {
    String previousHome = System.getProperty("justsearch.home"), previousData = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.home", home.toString()); System.setProperty("justsearch.data.dir", home.toString());
    try {
      Path ledger = Files.createDirectories(home.resolve("runtime/exit-ledger")).resolve("engine.v1.jsonl");
      var source = new java.io.ByteArrayOutputStream();
      source.writeBytes("{\"record\":\"first\"}\n".getBytes(StandardCharsets.UTF_8));
      source.writeBytes(new byte[] {(byte) 0xc3, '\n'}); // Malformed complete line.
      source.writeBytes("{\"record\":\"last-complete-§\"}\n".getBytes(StandardCharsets.UTF_8));
      source.writeBytes("{\"record\":\"torn-".getBytes(StandardCharsets.UTF_8));
      source.write(0xe2); source.write(0x82); // Incomplete UTF-8 character, no line terminator.
      Files.write(ledger, source.toByteArray());
      Path exported = new DiagnosticsServiceImpl(null, null, () -> null, () -> null)
          .exportDiagnostics(io.justsearch.app.services.TestEngineContexts.internal());
      try (var zip = new ZipFile(exported.toFile())) {
        var entry = zip.getEntry("runtime/exit-ledger/engine.v1.jsonl");
        assertNotNull(entry, "a torn final character must not lose the complete ledger records");
        String records = text(zip, entry);
        assertEquals("{\"record\":\"first\"}\n{\"record\":\"last-complete-§\"}\n", records);
        var note = zip.getEntry("runtime/exit-ledger/skipped-lines.txt");
        assertNotNull(note, "the export must name the skipped line count");
        assertTrue(text(zip, note).contains("2 malformed or incomplete lines"));
        String truncation = text(zip, zip.getEntry("runtime/exit-ledger/truncation.txt"));
        assertTrue(truncation.contains((source.size() - records.getBytes(StandardCharsets.UTF_8).length) + " source bytes omitted"));
      }
    } finally {
      restore("justsearch.home", previousHome); restore("justsearch.data.dir", previousData);
    }
  }
  private static String text(ZipFile zip, java.util.zip.ZipEntry entry) throws Exception {
    assertNotNull(entry);
    try (var input = zip.getInputStream(entry)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
  }
  private static void restore(String key, String previous) {
    if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
  }
}
