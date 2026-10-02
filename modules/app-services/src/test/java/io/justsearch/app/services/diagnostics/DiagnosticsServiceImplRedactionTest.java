/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.core.component.ComponentState;
import io.justsearch.contract.wire.LifecycleState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiagnosticsServiceImplRedactionTest {

  @TempDir Path tempHome;

  private String previousHome;
  private String previousDataDir;
  private String previousAppVersion;

  @BeforeEach
  void redirectDiagnosticStorage() {
    previousHome = System.getProperty("justsearch.home");
    previousDataDir = System.getProperty("justsearch.data.dir");
    previousAppVersion = System.getProperty("justsearch.app.version");
    System.setProperty("justsearch.home", tempHome.toString());
    System.setProperty("justsearch.data.dir", tempHome.toString());
    System.setProperty("justsearch.app.version", "1.2.3-test");
  }

  @AfterEach
  void restoreProperties() {
    restoreProperty("justsearch.home", previousHome);
    restoreProperty("justsearch.data.dir", previousDataDir);
    restoreProperty("justsearch.app.version", previousAppVersion);
  }

  @Test
  void zipRedactionRemovesWindowsPathsContainingSpaces() throws Exception {
    String input =
        "{\"path\":\"C:\\\\Users\\\\Alice Smith\\\\Private, Folder\\\\secret.txt\","
            + "\"unixPath\":\"/home/Alice Smith/Private, Folder/secret.txt\","
            + "\"other\":\"safe\"}";
    Path settings = tempHome.resolve("ui").resolve("settings.json");
    Files.createDirectories(settings.getParent());
    Files.writeString(settings, input);

    Path zip =
        new DiagnosticsServiceImpl(null, null, () -> null, () -> null)
            .exportDiagnostics(io.justsearch.app.services.TestEngineContexts.internal());
    String redacted;
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipEntry entry = zipFile.getEntry("ui/settings.json");
      assertNotNull(entry);
      redacted =
          new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
    }

    assertEquals(
        "{\"path\":\"[path]\",\"unixPath\":\"[path]\",\"other\":\"safe\"}", redacted);
    assertFalse(redacted.contains("Alice Smith"));
    assertFalse(redacted.contains("Private, Folder"));
    assertFalse(redacted.contains("secret.txt"));
  }

  @Test
  void zipRedactsActiveAndCompressedEngineLogs() throws Exception {
    Path logs = Files.createDirectories(tempHome.resolve("logs"));
    String input =
        "Windows: C:\\Users\\Alice Smith\\Private, Folder\\secret.txt\n"
            + "Unix: /home/Alice Smith/Private, Folder/secret.txt\n"
            + "safe event\n";
    Files.writeString(logs.resolve("engine.log"), input);
    try (var gzip =
        new GZIPOutputStream(Files.newOutputStream(logs.resolve("engine.2026-10-02.0.log.gz")))) {
      gzip.write(input.getBytes(StandardCharsets.UTF_8));
    }

    Path zip = new DiagnosticsServiceImpl(null, null, () -> null, () -> null)
        .exportDiagnostics(io.justsearch.app.services.TestEngineContexts.internal());
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipEntry active = zipFile.getEntry("logs/engine.log");
      ZipEntry rotation = zipFile.getEntry("logs/engine.2026-10-02.0.log.gz");
      assertNotNull(active);
      assertNotNull(rotation);
      String expected = "Windows: [path]\nUnix: [path]\nsafe event\n";
      try (var in = zipFile.getInputStream(active)) {
        assertEquals(expected, new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
      try (var in = new GZIPInputStream(zipFile.getInputStream(rotation))) {
        String redacted = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(expected, redacted);
        assertFalse(redacted.contains("Alice Smith"));
        assertFalse(redacted.contains("secret.txt"));
      }
    }
  }

  @Test
  void zipIncludesDataDirectoryAndDistinctAiHomeLogs() throws Exception {
    Path dataDir = Files.createDirectories(tempHome.resolve("search-data"));
    System.setProperty("justsearch.data.dir", dataDir.toString());
    Files.createDirectories(dataDir.resolve("logs"));
    Files.createDirectories(tempHome.resolve("logs"));
    Files.writeString(dataDir.resolve("logs/engine.log"), "engine event\n");
    // Deliberately collide on the filename to prove both sources survive unambiguously.
    Files.writeString(tempHome.resolve("logs/engine.log"), "AI home event\n");

    Path zip = new DiagnosticsServiceImpl(null, null, () -> null, () -> null)
        .exportDiagnostics(io.justsearch.app.services.TestEngineContexts.internal());
    try (ZipFile zipFile = new ZipFile(zip.toFile())) {
      ZipEntry engine = zipFile.getEntry("logs/engine.log");
      ZipEntry ai = zipFile.getEntry("ai/logs/engine.log");
      assertNotNull(engine);
      assertNotNull(ai);
      try (var in = zipFile.getInputStream(engine)) {
        assertEquals("engine event\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
      try (var in = zipFile.getInputStream(ai)) {
        assertEquals("AI home event\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void summaryUsesTypedLifecycleWithoutDebugStateOrOptionalProcesses() {
    LifecycleSnapshotV2 lifecycle =
        new LifecycleSnapshotV2(2, java.time.Instant.EPOCH.toString(),
            new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_DEGRADED, null, null),
            new LifecycleSnapshotV2.Components(
                component(ComponentState.READY, null),
                component(ComponentState.ABSENT, "index.shut_down"),
                component(ComponentState.ABSENT, null),
                component(ComponentState.ABSENT, "inference.deactivated")));

    String summary = service(() -> () -> lifecycle).buildDiagnosticSummary();

    assertTrue(summary.contains("app.version: 1.2.3-test"));
    assertTrue(summary.contains("lifecycle.index.reason: index.shut_down"));
    assertTrue(summary.contains("lifecycle.generative.reason: inference.deactivated"));
    assertFalse(summary.contains("debug-state"));
    assertTrue(summary.endsWith("note: " + DiagnosticSummaryComposer.LOCAL_ONLY_NOTE + "\n"));
  }

  private DiagnosticsServiceImpl service(
      java.util.function.Supplier<io.justsearch.app.api.StatusSnapshotProvider> statusSupplier) {
    return new DiagnosticsServiceImpl(
        null,
        null,
        () -> {
          throw new AssertionError("debug state must not be read for a diagnostic summary");
        },
        statusSupplier);
  }

  private static void restoreProperty(String name, String previous) {
    if (previous == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, previous);
    }
  }
  private static LifecycleSnapshotV2.Component component(ComponentState state, String reason) {
    return new LifecycleSnapshotV2.Component(state, reason, java.time.Instant.EPOCH.toString());
  }

}
