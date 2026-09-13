/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV1;
import io.justsearch.contract.wire.LifecycleState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  void summaryUsesTypedLifecycleWithoutDebugStateOrOptionalProcesses() {
    LifecycleSnapshotV1 lifecycle =
        LifecycleSnapshotV1.now(
            new LifecycleSnapshotV1.Lifecycle(LifecycleState.LIFECYCLE_STATE_DEGRADED),
            new LifecycleSnapshotV1.Components(
                new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY),
                new LifecycleSnapshotV1.Component(
                    LifecycleState.LIFECYCLE_STATE_STOPPED, "worker.shut_down"),
                new LifecycleSnapshotV1.Component(
                    LifecycleState.LIFECYCLE_STATE_STOPPED, "inference.deactivated")));

    String summary = service(() -> () -> lifecycle).buildDiagnosticSummary();

    assertTrue(summary.contains("app.version: 1.2.3-test"));
    assertTrue(summary.contains("lifecycle.worker.reason: worker.shut_down"));
    assertTrue(summary.contains("lifecycle.inference.reason: inference.deactivated"));
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
}
