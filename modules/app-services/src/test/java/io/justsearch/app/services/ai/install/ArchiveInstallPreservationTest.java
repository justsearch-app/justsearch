/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Archive install behavior the repair keeps: healthy installs stay untouched, hostile entries stay rejected. */
@Tag("cross-platform")
class ArchiveInstallPreservationTest {
  @TempDir Path home;
  final AtomicInteger transfers = new AtomicInteger();
  final HardwareProfile hardware = HardwareProfile.cpuOnly();
  final Map<String, byte[]> sources = new LinkedHashMap<>();

  Path models() { return home.resolve("models"); }
  Path runtime() { return models().resolve("cuda12"); }
  Path kept(String name) { return runtime().resolve(name); }

  SupportingFile source(String name, Map<String, String> entries) throws Exception {
    byte[] bytes = ArchiveInstallRepairTest.zip(entries);
    sources.put("https://example.invalid/" + name, bytes);
    String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    return new SupportingFile(name, sha, bytes.length, "https://example.invalid/" + name, true);
  }

  ModelRegistry registry(SupportingFile... archives) {
    return new ModelRegistry(2, "archive repair", List.of(
        new ModelPackage("runtime", "Runtime", "test", "cuda12", List.of(), List.of(archives), 0, null),
        new ModelPackage("healthy", "Healthy", "test", "healthy", List.of(), List.of(), 0, null)));
  }

  InstallPlan plan(ModelRegistry registry) {
    return InstallPlanner.plan(registry, hardware, models());
  }

  void keep(SupportingFile archive) throws IOException {
    Files.createDirectories(runtime());
    Files.write(kept(archive.filename()), sources.get(archive.downloadUrl()));
  }

  AcquisitionScheduler.Summary run(InstallPlan plan) throws Exception {
    ResumableFetch.Transfer transfer = (url, partial, decision, progress, tier) -> {
      transfers.incrementAndGet();
      try {
        byte[] bytes = sources.get(url);
        Files.write(partial, bytes);
        progress.onProgress(bytes.length, bytes.length);
        return true;
      } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    };
    return new AcquisitionStage(models(), transfer,
        TransportRetryPolicy.defaultPolicy().withMaxAttempts(1), InstallAttemptMemory.load(home),
        () -> false, System::nanoTime, new PlacementStage(models()), new AcquisitionScheduler.Listener() {}, null)
        .scheduler(plan.downloads()).run();
  }

  /** A home reached through a symlinked folder above it is the user's choice and is followed. */
  Path linkedHome() throws IOException {
    Path real = Files.createDirectories(home.resolve("real-home"));
    return Files.createSymbolicLink(home.resolve("linked-home"), real);
  }

  @Test @EnabledOnOs(OS.LINUX)
  void symlinkedAncestorFreshInstallSucceeds() throws Exception {
    home = linkedHome();
    var archive = source("pack.zip", Map.of("library.dll", "payload"));
    assertEquals(0, run(plan(registry(archive))).failed());
    assertEquals("payload", Files.readString(runtime().resolve("library.dll")));
    assertEquals(1, transfers.get());
    assertTrue(plan(registry(archive)).downloads().isEmpty());
  }

  @Test @EnabledOnOs(OS.LINUX)
  void symlinkedAncestorHealthyLegacyInstallStaysInstalled() throws Exception {
    home = linkedHome();
    var archive = source("pack.zip", Map.of("library.dll", "payload"));
    keep(archive);
    Files.writeString(runtime().resolve("library.dll"), "payload");
    var installed = plan(registry(archive));
    assertTrue(installed.downloads().isEmpty(), "a healthy install must not become incomplete");
    assertTrue(InstallCompleteness.compute(installed, null).installedFully());
  }

  @Test void healthyLegacyInstallHasNoIncompleteStatusAndDoesNotTouchUnchangedOutputs() throws Exception {
    var archive = source("healthy.zip", Map.of("library.dll", "expected"));
    keep(archive);
    Files.writeString(runtime().resolve("library.dll"), "expected");
    var installed = plan(registry(archive));
    assertTrue(installed.downloads().isEmpty());
    assertTrue(InstallCompleteness.compute(installed, null).installedFully());
    FileTime old = FileTime.fromMillis(1000);
    Files.setLastModifiedTime(runtime().resolve("library.dll"), old);
    AiInstallService.extractZipInPlace(kept("healthy.zip"), runtime());
    AiInstallService.extractZipInPlace(kept("healthy.zip"), runtime());
    assertEquals(old, Files.getLastModifiedTime(runtime().resolve("library.dll")));
    assertEquals(0, transfers.get());
  }

  @ParameterizedTest @ValueSource(strings = {"../outside.dll", "/absolute.dll"})
  void traversalAndAbsolutePathsAreRejected(String entry) throws Exception {
    var archive = source("hostile.zip", Map.of(entry, "bad"));
    keep(archive);
    assertThrows(IOException.class, () -> AiInstallService.extractZipInPlace(kept("hostile.zip"), runtime()));
    assertFalse(Files.exists(models().resolve("outside.dll")));
  }

  @EnabledOnOs(OS.LINUX)
  @ParameterizedTest @ValueSource(strings = {"output", "parent"})
  void replacementRejectsSymlinkRedirection(String kind) throws Exception {
    var archive = source("links.zip", Map.of("nested/library.dll", "new"));
    keep(archive);
    Path outside = home.resolve("outside");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("library.dll"), "old");
    Files.createDirectories(runtime().resolve("nested"));
    switch (kind) {
      case "parent" -> { Files.delete(runtime().resolve("nested")); Files.createSymbolicLink(runtime().resolve("nested"), outside); }
      case "output" -> Files.createSymbolicLink(runtime().resolve("nested/library.dll"), outside.resolve("library.dll"));
    }
    assertThrows(IOException.class, () -> AiInstallService.extractZipInPlace(kept("links.zip"), runtime()));
    assertEquals("old", Files.readString(outside.resolve("library.dll")));
  }
}
