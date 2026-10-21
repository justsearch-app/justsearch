/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class JvmCrashEvidenceTest {
  @TempDir Path profile;

  @ParameterizedTest
  @CsvSource({
      "internal, InternalError",
      "native-oom, OutOfMemoryError",
      "sigsegv, SIGSEGV",
      "realtime-signal, SIGRTMIN_1"
  })
  void realHotSpotFatalHeadersAppearInThePublicSummary(String fixture, String expectedType)
      throws Exception {
    Path crashes = Files.createDirectories(profile.resolve("crashes"));
    Files.writeString(crashes.resolve("crash-older.json"), """
        {"schema":"crash-report.v1","timestamp":"2026-10-19T00:00:00Z",
         "process":"engine","exception":{"type":"java.lang.RuntimeException"}}
        """);
    Path report = crashes.resolve("hs_err_pid42.log");
    try (var input = getClass().getResourceAsStream("hotspot-fatal-headers/" + fixture + ".txt")) {
      assertNotNull(input, "Missing real HotSpot report fixture");
      Files.copy(input, report);
    }
    Instant timestamp = Instant.parse("2026-10-21T01:02:03Z");
    Files.setLastModifiedTime(report, FileTime.from(timestamp));
    var crash = DiagnosticSummaryComposer.latestCrash(crashes);
    assertNotNull(crash, "Real HotSpot fatal reports must participate in the latest-crash summary");
    assertEquals(expectedType, crash.exceptionType());
    assertEquals(timestamp, crash.timestamp());
    String summary = new DiagnosticSummaryComposer().compose(
        new DiagnosticSummaryComposer.Inputs(null, null, null, null, null, crash));
    assertTrue(summary.contains("latest-crash.process: engine\n"));
    assertTrue(summary.contains("latest-crash.exception-type: " + expectedType + "\n"));
    assertFalse(summary.contains("RuntimeException"));
    assertFalse(summary.contains(".cpp"));
    assertFalse(summary.contains("libjvm"));
    assertFalse(summary.contains("pthread_kill"));
    assertFalse(summary.contains("<path>"));
    assertFalse(summary.contains("pid="));
    assertTrue(summary.getBytes(StandardCharsets.UTF_8).length <= DiagnosticSummaryComposer.MAX_UTF8_BYTES);
  }

  @ParameterizedTest
  @CsvSource({
      "Internal Error, InternalError",
      "Out of Memory Error, OutOfMemoryError",
      "SIG32, SIG32",
      "SIGRTMIN, SIGRTMIN",
      "SIGRTMAX, SIGRTMAX"
  })
  void hotSpotNumericLocationAndOtherPosixSignalFormsAreSafeIdentifiers(
      String headerType, String expectedType) throws Exception {
    Path crashes = Files.createDirectories(profile.resolve("crashes"));
    // VMError::report's no-source-location branch; POSIX also names unknown valid signals SIG<n>.
    Files.writeString(crashes.resolve("hs_err_pid42.log"),
        "#  " + headerType + " (0xe0000000), pid=42, tid=43\n");
    var crash = DiagnosticSummaryComposer.latestCrash(crashes);
    assertNotNull(crash);
    assertEquals(expectedType, crash.exceptionType());
    String summary = new DiagnosticSummaryComposer().compose(
        new DiagnosticSummaryComposer.Inputs(null, null, null, null, null, crash));
    assertTrue(summary.contains("latest-crash.exception-type: " + expectedType + "\n"));
    assertFalse(summary.contains("0xe0000000"));
  }

  @Test
  void newFatalHeaderKindsKeepTheByteAndLineBoundsAndRejectLookalikes() throws Exception {
    Path crashes = Files.createDirectories(profile.resolve("crashes"));
    Path report = crashes.resolve("hs_err_pid42.log");
    for (String header : new String[] {
        "# filler\n".repeat(64) + "#  Internal Error (private.cpp:1)\n",
        "#" + "x".repeat(64 * 1024) + "\n#  Out of Memory Error (private.cpp:1)\n",
        "#  Internal Error private-path\n",
        "#  Out of Memory ErrorDetails (private.cpp:1)\n"
    }) {
      Files.writeString(report, header);
      assertNull(DiagnosticSummaryComposer.latestCrash(crashes));
    }
  }

  @Test
  void fatalJvmReportIsTheLatestCrashWithoutExposingItsPaths() throws Exception {
    Path crashes = Files.createDirectories(profile.resolve("crashes"));
    Path report = crashes.resolve("hs_err_pid42-boot.log");
    Files.writeString(report, "# A fatal error has been detected by the Java Runtime Environment:\n"
        + "#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x123, pid=42\n"
        + "# Problematic frame:\n# C  [C:\\Users\\Private Name\\onnxruntime.dll+0x123]\n");
    Instant timestamp = Instant.parse("2026-10-20T01:02:03Z");
    Files.setLastModifiedTime(report, FileTime.from(timestamp));
    var crash = DiagnosticSummaryComposer.latestCrash(crashes);
    assertNotNull(crash, "JVM fatal logs must participate in the latest-crash summary");
    assertEquals("engine", crash.process());
    assertEquals(timestamp, crash.timestamp());
    assertEquals("EXCEPTION_ACCESS_VIOLATION", crash.exceptionType());
    String summary = new DiagnosticSummaryComposer().compose(
        new DiagnosticSummaryComposer.Inputs(null, null, null, null, null, crash));
    assertFalse(summary.contains("Private Name"));
    assertFalse(summary.contains("onnxruntime"));
  }

  @Test
  void summaryReadsOnlyTheFirst64LinesAndIgnoresHeapDumps() throws Exception {
    Path crashes = Files.createDirectories(profile.resolve("crashes"));
    Files.writeString(crashes.resolve("hs_err_pid1.log"), "# filler\n".repeat(64) + "#  SIGSEGV (0xb)\n");
    Files.writeString(crashes.resolve("heap-private.hprof"), "#  SIGSEGV (0xb)\n");
    assertNull(DiagnosticSummaryComposer.latestCrash(crashes));
  }

  @Test
  void exportIncludesOnlyTheNewestThreeFatalLogsWithBoundedTextAndNoHeapDump() throws Exception {
    String oldHome = System.getProperty("justsearch.home");
    String oldData = System.getProperty("justsearch.data.dir");
    try {
      System.setProperty("justsearch.home", profile.toString());
      System.setProperty("justsearch.data.dir", profile.toString());
      Path crashes = Files.createDirectories(profile.resolve("crashes"));
      for (int i = 1; i <= 4; i++) {
        Path report = crashes.resolve("hs_err_pid" + i + ".log");
        Files.writeString(report, "#  SIGSEGV (0xb)\nC:\\Users\\Private Name\\secret.txt\n" + "x".repeat(1024 * 1024));
        Files.setLastModifiedTime(report, FileTime.fromMillis(i * 1000L));
      }
      Files.writeString(crashes.resolve("heap-private.hprof"), "PRIVATE_HEAP_CONTENT");
      Path zip = new DiagnosticsServiceImpl(null, null, () -> null, () -> null)
          .exportDiagnostics(io.justsearch.app.services.TestEngineContexts.internal());
      try (ZipFile contents = new ZipFile(zip.toFile())) {
        assertNull(contents.getEntry("crashes/hs_err_pid1.log"));
        assertNull(contents.getEntry("crashes/heap-private.hprof"));
        for (int i = 2; i <= 4; i++) {
          var entry = contents.getEntry("crashes/hs_err_pid" + i + ".log");
          assertNotNull(entry, "Fatal JVM report absent from diagnostics export");
          String body = new String(contents.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
          assertTrue(body.startsWith("#  SIGSEGV"));
          assertTrue(body.length() <= 1024 * 1024);
          assertFalse(body.contains("Private Name"));
        }
      }
    } finally {
      restore("justsearch.home", oldHome);
      restore("justsearch.data.dir", oldData);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }
}
