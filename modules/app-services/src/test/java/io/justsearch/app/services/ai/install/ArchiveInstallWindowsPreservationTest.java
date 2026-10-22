/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** An AI home reached through a junction above it, or a junctioned pack folder, installs and reports status as before. */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class ArchiveInstallWindowsPreservationTest {
  @TempDir Path root;
  Path home;

  @BeforeEach void junctionHome() throws Exception {
    home = root.resolve("linked-home");
    junction(home, Files.createDirectories(root.resolve("real-home")));
  }

  static void junction(Path link, Path target) throws Exception {
    Process maker = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
        .redirectErrorStream(true).start();
    if (!maker.waitFor(15, TimeUnit.SECONDS)) {
      maker.destroyForcibly();
      fail("junction creation timed out");
    }
    assertEquals(0, maker.exitValue(), new String(maker.getInputStream().readAllBytes()));
  }

  @AfterEach void removeJunction() throws Exception {
    Files.deleteIfExists(home);
  }

  Path runtime() { return home.resolve("models/cuda12"); }

  @Test void extractionBelowJunctionAncestorSucceeds() throws Exception {
    Files.createDirectories(runtime());
    Path archive = runtime().resolve("pack.zip");
    Files.write(archive, ArchiveInstallRepairTest.zip(Map.of("library.dll", "payload")));
    ArchiveInstallRepairTest.extract(archive, runtime());
    assertEquals("payload", Files.readString(root.resolve("real-home/models/cuda12/library.dll")));
  }

  @Test void healthyInstallBelowJunctionAncestorStaysInstalled() throws Exception {
    byte[] bytes = ArchiveInstallRepairTest.zip(Map.of("library.dll", "payload"));
    String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var archive = new SupportingFile("pack.zip", sha, bytes.length, "https://example.invalid/pack.zip", true);
    var registry = new ModelRegistry(2, "archive repair", List.of(
        new ModelPackage("runtime", "Runtime", "test", "cuda12", List.of(), List.of(archive), 0, null)));
    Files.createDirectories(runtime());
    Files.write(runtime().resolve("pack.zip"), bytes);
    Files.writeString(runtime().resolve("library.dll"), "payload");
    var installed = InstallPlanner.plan(registry, HardwareProfile.cpuOnly(), home.resolve("models"));
    assertTrue(installed.downloads().isEmpty(), "a healthy install must not become incomplete");
    assertTrue(InstallCompleteness.compute(installed, null).installedFully());
  }

  @Test void junctionedPackFolderInstallsAndStaysInstalled() throws Exception {
    Path real = Files.createDirectories(root.resolve("other-disk/cuda12"));
    Files.createDirectories(home.resolve("models"));
    junction(runtime(), real);
    try {
      byte[] bytes = ArchiveInstallRepairTest.zip(Map.of("library.dll", "payload"));
      String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      var archive = new SupportingFile("pack.zip", sha, bytes.length, "https://example.invalid/pack.zip", true);
      var registry = new ModelRegistry(2, "archive repair", List.of(
          new ModelPackage("runtime", "Runtime", "test", "cuda12", List.of(), List.of(archive), 0, null)));
      Files.write(runtime().resolve("pack.zip"), bytes);
      ArchiveInstallRepairTest.extract(runtime().resolve("pack.zip"), runtime());
      assertEquals("payload", Files.readString(real.resolve("library.dll")));
      var installed = InstallPlanner.plan(registry, HardwareProfile.cpuOnly(), home.resolve("models"));
      assertTrue(installed.downloads().isEmpty(), "a healthy install must not become incomplete");
    } finally {
      Files.deleteIfExists(runtime());
    }
  }
}
