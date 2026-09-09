/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 683 (A5): the diagnostics export embeds an optional frontend-supplied telemetry
 * section verbatim as {@code frontend/fe-telemetry.json}, and omits the entry when none is
 * supplied. Exercises the real ZIP writer against a temp AI home (no mocks).
 */
class DiagnosticsServiceImplFeTelemetryTest {

  @TempDir Path tempHome;

  private String previousHome;

  @BeforeEach
  void redirectAiHome() {
    previousHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", tempHome.toString());
  }

  @AfterEach
  void restoreAiHome() {
    if (previousHome == null) {
      System.clearProperty("justsearch.home");
    } else {
      System.setProperty("justsearch.home", previousHome);
    }
  }

  private DiagnosticsServiceImpl service() {
    return new DiagnosticsServiceImpl(null, null, () -> null, () -> null);
  }

  @Test
  void embedsFeTelemetryEntryVerbatim() throws Exception {
    String feTelemetry = "{\n  \"wireDrift\" : {\n    \"total\" : 2\n  }\n}";

    Path zip =
        service().exportDiagnostics(
            feTelemetry, io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(Files.isRegularFile(zip), "export zip should exist");
    try (ZipFile zf = new ZipFile(zip.toFile())) {
      ZipEntry entry = zf.getEntry("frontend/fe-telemetry.json");
      assertNotNull(entry, "frontend/fe-telemetry.json entry should be present");
      String content = new String(zf.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(feTelemetry, content, "fe telemetry must be embedded verbatim");
    }
  }

  @Test
  void omitsEntryWhenNoFeTelemetrySupplied() throws Exception {
    Path viaNull =
        service().exportDiagnostics(null, io.justsearch.app.services.TestEngineContexts.internal());
    Path viaNoArg =
        service().exportDiagnostics(io.justsearch.app.services.TestEngineContexts.internal());

    try (ZipFile zf = new ZipFile(viaNull.toFile())) {
      assertNull(zf.getEntry("frontend/fe-telemetry.json"), "null telemetry must add no entry");
    }
    try (ZipFile zf = new ZipFile(viaNoArg.toFile())) {
      assertNull(
          zf.getEntry("frontend/fe-telemetry.json"),
          "no-arg overload must behave exactly like the null variant");
    }
  }
}
