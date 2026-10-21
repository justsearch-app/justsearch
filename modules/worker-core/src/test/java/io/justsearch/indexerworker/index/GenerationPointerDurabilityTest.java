/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(60)
class GenerationPointerDurabilityTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000121";
  private static final String FINGERPRINT = "b".repeat(64);
  @TempDir Path temporary;

  @Test
  void staleFixedTemporaryPathCannotPreventPublication() throws Exception {
    var manager = new IndexGenerationManager(temporary);
    manager.initializeOrLoad();
    Files.createDirectory(temporary.resolve("state.json.tmp"));
    assertDoesNotThrow(() -> manager.setMigrationPaused(true, "stale temp"));
    assertTrue(new IndexGenerationManager(temporary).readStateBestEffort().migration_paused());
  }

  @Test
  void repairingTornCurrentRetainsValidPreviousPointer() throws Exception {
    var manager = new IndexGenerationManager(temporary);
    manager.initializeOrLoad();
    manager.setMigrationPaused(true, "make backup");
    byte[] valid = Files.readAllBytes(temporary.resolve("state.json.prev"));
    Files.writeString(temporary.resolve("state.json"), "{torn");
    assertDoesNotThrow(() -> new IndexGenerationManager(temporary).initializeOrLoad());
    assertArrayEquals(valid, Files.readAllBytes(temporary.resolve("state.json.prev")));
    assertArrayEquals(valid, Files.readAllBytes(temporary.resolve("state.json")));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void bootRemovesOnlyOwnScratchAfterReadingOrRepairingAuthority(boolean repair) throws Exception {
    var manager = new IndexGenerationManager(temporary);
    manager.initializeOrLoad();
    manager.setMigrationPaused(true, "keep previous");
    if (repair) Files.writeString(temporary.resolve("state.json"), "{torn");
    Path scratch = temporary.resolve("state.json.123456789.tmp");
    Files.writeString(scratch, "{interrupted publication");
    var retained = java.util.List.of("state.json.tmp", "state.json.foreign.tmp",
        "state.json.prev.123.tmp", "other.json.123.tmp");
    for (String name : retained) Files.writeString(temporary.resolve(name), "other writer");
    Path directory = Files.createDirectory(temporary.resolve("state.json.456.tmp"));
    Path link = temporary.resolve("state.json.789.tmp");
    boolean supportsSymlinks = !System.getProperty("os.name").startsWith("Windows");
    if (supportsSymlinks) Files.createSymbolicLink(link, temporary.resolve("other.json.123.tmp"));
    var boot = new IndexGenerationManager(temporary).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), FINGERPRINT);
    assertNotNull(boot.layout().state());
    assertFalse(Files.exists(scratch), "a completed boot must reap this writer's interrupted temp");
    for (String name : retained) assertEquals("other writer", Files.readString(temporary.resolve(name)));
    assertTrue(Files.isDirectory(directory));
    if (supportsSymlinks) assertTrue(Files.isSymbolicLink(link));
  }

  @Test
  void refusedBootKeepsScratchWhenItCouldBeTheOnlyPointerCopy() throws Exception {
    var manager = new IndexGenerationManager(temporary);
    var initial = manager.initializeOrLoad();
    manager.startRecordedMigration(KEY, "recorded-crash-test", FINGERPRINT);
    byte[] pointer = Files.readAllBytes(temporary.resolve("state.json"));
    Path scratch = temporary.resolve("state.json.123.tmp");
    Files.write(scratch, pointer);
    Files.delete(temporary.resolve("state.json"));
    Files.deleteIfExists(temporary.resolve("state.json.prev"));
    var owner = new IndexGenerationManager.BootOwnership.Recorded(
        KEY, initial.activeGenerationId(), "recorded-crash-test", FINGERPRINT, true);
    assertThrows(java.io.IOException.class, () -> new IndexGenerationManager(temporary)
        .initializeForBoot(owner, FINGERPRINT));
    assertArrayEquals(pointer, Files.readAllBytes(scratch));
  }

  @ParameterizedTest
  @CsvSource({"first,before-backup", "first,after-backup", "first,after-publication",
      "replacement,before-backup", "replacement,after-backup", "replacement,after-publication",
      "recovery,before-backup", "recovery,after-backup", "recovery,after-publication"})
  void processKillAtPublicationLeavesBootableAuthority(String mode, String point) throws Exception {
    Path base = temporary.resolve(mode + "-" + point);
    String phase = "IDLE";
    IndexGenerationManager.BootOwnership owner = new IndexGenerationManager.BootOwnership.Native();
    if (!"first".equals(mode)) {
      var manager = new IndexGenerationManager(base);
      var initial = manager.initializeOrLoad();
      if ("replacement".equals(mode)) {
        manager.startRecordedMigration(KEY, "recorded-crash-test", FINGERPRINT);
        owner = new IndexGenerationManager.BootOwnership.Recorded(
            KEY, initial.activeGenerationId(), "recorded-crash-test", FINGERPRINT, true);
        phase = "MIGRATING";
      } else {
        manager.setMigrationPaused(true, "retain backup");
        Files.writeString(base.resolve("state.json"), "{torn");
      }
    }
    Path repository = Path.of("").toAbsolutePath();
    while (repository != null && !Files.exists(repository.resolve("settings.gradle.kts"))) {
      repository = repository.getParent();
    }
    assertNotNull(repository, "publication probe must run inside its checkout");
    Path outputs = Files.createDirectories(repository.resolve("tmp/restart/pointer-unit"));
    Path output = outputs.resolve(mode + "-" + point + "-" + java.util.UUID.randomUUID() + ".log");
    var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx128m", "-cp", testClasspath(), Probe.class.getName(), base.toString(), mode)
        .redirectErrorStream(true).redirectOutput(output.toFile());
    command.environment().put("JUSTSEARCH_SUPERVISOR_HARNESS", "1");
    command.environment().put("JUSTSEARCH_POINTER_PUBLICATION_CUT", point);
    command.environment().put("JUSTSEARCH_POINTER_CUT_PHASE", phase);
    Process child = command.start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "publication probe must terminate");
      assertEquals(73, child.exitValue(), Files.readString(output));
      assertEquals(point, new tools.jackson.databind.ObjectMapper()
          .readTree(Files.readAllBytes(base.resolve("pointer-publication-cut"))).path("point").asText());
      var bootOwner = owner;
      var boot = assertDoesNotThrow(() -> new IndexGenerationManager(base)
          .initializeForBoot(bootOwner, FINGERPRINT));
      assertNotNull(boot.layout().activeGenerationId());
      assertTrue(Files.isRegularFile(base.resolve("state.json")));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  private static String testClasspath() {
    var paths = new LinkedHashSet<String>();
    for (ClassLoader loader = Probe.class.getClassLoader(); loader != null; loader = loader.getParent()) {
      if (loader instanceof java.net.URLClassLoader urls) {
        for (var url : urls.getURLs()) {
          try { paths.add(Path.of(url.toURI()).toString()); }
          catch (java.net.URISyntaxException invalid) { throw new IllegalStateException(invalid); }
        }
      }
    }
    paths.addAll(java.util.List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
    return String.join(File.pathSeparator, paths);
  }

  public static class Probe {
    public static void main(String[] args) throws Exception {
      var manager = new IndexGenerationManager(Path.of(args[0]));
      if ("replacement".equals(args[1])) manager.setMigrationPaused(true, "publication probe");
      else manager.initializeOrLoad();
    }
  }
}
