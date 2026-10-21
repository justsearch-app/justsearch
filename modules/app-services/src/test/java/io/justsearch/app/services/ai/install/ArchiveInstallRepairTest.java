/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.model.*;
import io.justsearch.ort.OrtCudaHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Planner -> acquisition -> placement, with isolated tiny archives and real filesystem outputs. */
@Tag("cross-platform")
class ArchiveInstallRepairTest {
  @TempDir Path home;
  final AtomicInteger transfers = new AtomicInteger();
  final HardwareProfile hardware = HardwareProfile.cpuOnly();
  final Map<String, byte[]> sources = new LinkedHashMap<>();

  Path models() { return home.resolve("models"); }
  Path runtime() { return models().resolve("cuda12"); }
  Path kept(String name) { return runtime().resolve(name); }
  Path receipt(String name) { return runtime().resolve(".archive-receipts/" + name + ".json"); }

  static byte[] zip(Map<String, String> entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var entry : entries.entrySet()) {
        var item = new ZipEntry(entry.getKey());
        item.setTime(0); // deterministic lengths and identities for changed same-name archives
        zip.putNextEntry(item);
        zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  SupportingFile source(String name, Map<String, String> entries) throws Exception {
    byte[] bytes = zip(entries);
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
    return run(plan, new AcquisitionScheduler.Listener() {});
  }

  AcquisitionScheduler.Summary run(InstallPlan plan, AcquisitionScheduler.Listener listener) throws Exception {
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
        () -> false, System::nanoTime, new PlacementStage(models()), listener, null)
        .scheduler(plan.downloads()).run();
  }

  static Object invoke(Object object, String name, Class<?>[] types, Object... arguments) throws Exception {
    var method = object.getClass().getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(object, arguments);
  }

  AiInstallStatus status(AiInstallService service) throws Exception {
    var field = AiInstallService.class.getDeclaredField("status");
    field.setAccessible(true);
    return (AiInstallStatus) field.get(service);
  }

  @Test void failedPromotedApplySurvivesRestartAndDoesNotSpendTransportBudget() throws Exception {
    var archive = source("pack.zip", Map.of("payload.dll", "payload"));
    var registry = registry(archive);
    Files.createDirectories(runtime().resolve("payload.dll")); // local apply failure, after promotion
    var service = new AiInstallService(null, null, null, null, home);
    var initial = plan(registry);
    invoke(service, "populateStatusPackages", new Class<?>[] {InstallPlan.class, ModelRegistry.class}, initial, registry);
    var listener = (AcquisitionScheduler.Listener) invoke(service, "acquisitionProjection",
        new Class<?>[] {InstallPlan.class, LongSupplier.class}, initial, (LongSupplier) () -> 0L);
    assertEquals(1, run(initial, listener).failed());
    assertEquals("failed", status(service).packages.getFirst().state);
    assertTrue(status(service).packages.getFirst().error.contains("extract"));
    assertTrue(Files.isRegularFile(kept("pack.zip")), "failure is after ZIP promotion");
    assertFalse(Files.exists(receipt("pack.zip")));
    assertNull(InstallAttemptMemory.load(home).get(initial.downloads().getFirst().targetPath()));
    var contract = service.buildContract(initial, registry, hardware);
    assertTrue(contract.getModel("runtime").skipped());
    new PlacementStage(models()).writeContract(contract, home);

    // New service object reads a plan derived solely from persisted disk and contract.
    var cold = spy(new AiInstallService(null, null, null, null, home));
    doReturn(registry).when(cold).getManifest();
    var repair = plan(registry);
    assertEquals(1, repair.downloads().size());
    assertEquals(0, repair.remainingBytes());
    var coldStatus = cold.getStatus();
    assertFalse(coldStatus.installedFully);
    assertTrue(coldStatus.repairNeeded);
    Files.delete(runtime().resolve("payload.dll"));
    assertEquals(0, run(repair).failed());
    assertEquals(1, transfers.get(), "retry must use the kept ZIP");
    assertTrue(plan(registry).downloads().isEmpty());
    var completed = spy(new AiInstallService(null, null, null, null, home));
    doReturn(registry).when(completed).getManifest();
    var completedStatus = completed.getStatus();
    assertTrue(completedStatus.installedFully);
    assertFalse(completedStatus.repairNeeded);
  }

  @ParameterizedTest @ValueSource(strings = {"absent", "skipped", "installed"})
  void legacyZipOnlyAndPartialOutputsRepairRegardlessOfContractKind(String kind) throws Exception {
    var archive = source("legacy.zip", Map.of("one.dll", "one", "two.dll", "two"));
    keep(archive);
    var registry = registry(archive);
    for (boolean partial : List.of(false, true)) {
      if (partial) Files.writeString(runtime().resolve("one.dll"), "o");
      var repair = plan(registry);
      InstallContract contract = kind.equals("absent") ? null : new InstallContract(2, 1L, hardware,
          hardware.downloadProfile(), Map.of("runtime", kind.equals("skipped")
              ? InstallContract.InstalledModel.skipped("runtime", "historical")
              : new InstallContract.InstalledModel("runtime", null, null, null, "cuda12", null,
                  List.of("legacy.zip"), false, null)));
      var completeness = InstallCompleteness.compute(repair, contract);
      assertFalse(completeness.installedFully());
      assertTrue(completeness.repairNeeded());
      assertEquals(0, repair.totalBytes());
      assertEquals(0, run(repair).failed());
      assertEquals("one", Files.readString(runtime().resolve("one.dll")));
      assertEquals("two", Files.readString(runtime().resolve("two.dll")));
      assertEquals(0, transfers.get());
      Files.delete(runtime().resolve("two.dll"));
    }
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void changedArchiveAtSameNameFlowsThroughPlannerAcquisitionAndApply(boolean equalLength) throws Exception {
    var old = source("changed.zip", Map.of("library.dll", "aaaa"));
    assertEquals(0, run(plan(registry(old))).failed());
    var next = source("changed.zip", Map.of("library.dll", equalLength ? "bbbb" : "much longer content"));
    assertEquals(equalLength, old.sizeBytes() == next.sizeBytes());
    var repair = plan(registry(next));
    assertEquals(1, repair.downloads().size(), "new SHA invalidates the receipt even at equal length");
    assertEquals(0, run(repair).failed());
    assertEquals(2, transfers.get());
    assertArrayEquals(sources.get(next.downloadUrl()), Files.readAllBytes(kept("changed.zip")));
    assertEquals(equalLength ? "bbbb" : "much longer content", Files.readString(runtime().resolve("library.dll")));
    assertTrue(plan(registry(next)).downloads().isEmpty());
  }

  @Test void equalLengthKeptZipCorruptionIsRedownloadedBeforeExtraction() throws Exception {
    var archive = source("corrupt.zip", Map.of("library.dll", "expected"));
    keep(archive);
    byte[] bytes = Files.readAllBytes(kept("corrupt.zip"));
    // Change compressed entry bytes without touching the central directory: size probes still pass.
    bytes[45] ^= 1;
    Files.write(kept("corrupt.zip"), bytes);
    var repair = plan(registry(archive));
    assertEquals(0, repair.totalBytes(), "status must not hash the ZIP");
    assertEquals(1, repair.downloads().size());
    assertEquals(0, run(repair).failed());
    assertEquals(1, transfers.get());
    assertEquals("expected", Files.readString(runtime().resolve("library.dll")));
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void receiptCannotHideDeletedOrTruncatedOutput(boolean deleted) throws Exception {
    var archive = source("missing.zip", Map.of("library.dll", "expected"));
    run(plan(registry(archive)));
    if (deleted) Files.delete(runtime().resolve("library.dll"));
    else Files.writeString(runtime().resolve("library.dll"), "short");
    var repair = plan(registry(archive));
    assertEquals(1, repair.downloads().size());
    assertEquals(0, repair.remainingBytes());
    assertEquals(0, run(repair).failed());
    assertEquals(1, transfers.get());
    assertEquals("expected", Files.readString(runtime().resolve("library.dll")));
  }

  @Test void interruptedAtomicReceiptWriteKeepsApplyIncomplete() throws Exception {
    var archive = source("receipt.zip", Map.of("library.dll", "expected"));
    keep(archive);
    // Interrupt the real atomic publisher immediately before its rename. Its temp must disappear.
    Path receiptTarget = receipt("receipt.zip");
    try (var files = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("move") && receiptTarget.equals(invocation.getArgument(1))) {
        throw new java.io.InterruptedIOException("interrupted receipt publication");
      }
      return invocation.callRealMethod();
    })) {
      assertThrows(IOException.class, () -> AiInstallService.extractZipInPlace(kept("receipt.zip"), runtime()));
    }
    assertFalse(Files.exists(receipt("receipt.zip")));
    assertEquals(1, plan(registry(archive)).downloads().size(), "all output sizes alone cannot hide interrupted receipt");
    assertEquals(0, run(plan(registry(archive))).failed());
    assertEquals(0, transfers.get());
    assertTrue(Files.isRegularFile(receipt("receipt.zip")));
  }

  @Test void diskFullDuringPartialWriteIsReportedAndRetryUsesKeptZip() throws Exception {
    var archive = source("diskfull.zip", Map.of("library.dll", "expected"));
    var initial = plan(registry(archive));
    Path partial = runtime().resolve("library.dll.partial");
    try (var files = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("newOutputStream") && partial.equals(invocation.getArgument(0))) {
        return new java.io.FilterOutputStream((OutputStream) invocation.callRealMethod()) {
          @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, Math.min(2, length));
            throw new IOException("No space left on device");
          }
          @Override public void write(int value) throws IOException {
            out.write(value);
            throw new IOException("No space left on device");
          }
        };
      }
      return invocation.callRealMethod();
    })) {
      assertEquals(1, run(initial).failed());
    }
    assertTrue(Files.isRegularFile(kept("diskfull.zip")));
    assertTrue(Files.size(partial) > 0, "disk-full failure leaves only an incomplete sibling");
    assertFalse(Files.exists(runtime().resolve("library.dll")));
    assertNull(InstallAttemptMemory.load(home).get(initial.downloads().getFirst().targetPath()));
    assertEquals(1, plan(registry(archive)).downloads().size());
    assertEquals(0, run(plan(registry(archive))).failed());
    assertEquals(1, transfers.get());
  }

  @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
  void eachOfFiveArchivesCanFailAloneAndRepeatedRepairsConverge(int failing) throws Exception {
    SupportingFile[] archives = new SupportingFile[5];
    for (int i = 0; i < 5; i++) archives[i] = source("pack" + i + ".zip", Map.of("lib" + i + ".dll", "payload" + i));
    var registry = registry(archives);
    Files.createDirectories(runtime().resolve("lib" + failing + ".dll"));
    // One identical shell-bundled output, plus a shell-owned file no archive lists.
    int shell = (failing + 1) % 5;
    Files.writeString(runtime().resolve("lib" + shell + ".dll"), "payload" + shell);
    FileTime original = Files.getLastModifiedTime(runtime().resolve("lib" + shell + ".dll"));
    Files.writeString(runtime().resolve("shell-only.txt"), "keep");
    assertEquals(1, run(plan(registry)).failed());
    for (int i = 0; i < 5; i++) if (i != failing) assertEquals("payload" + i, Files.readString(runtime().resolve("lib" + i + ".dll")));
    assertEquals(original, Files.getLastModifiedTime(runtime().resolve("lib" + shell + ".dll")));
    Files.delete(runtime().resolve("lib" + failing + ".dll"));
    assertEquals(1, plan(registry).downloads().size());
    assertEquals(0, run(plan(registry)).failed());
    assertEquals(5, transfers.get());
    for (int repeat = 0; repeat < 3; repeat++) {
      assertTrue(plan(registry).downloads().isEmpty());
      for (var archive : archives) AiInstallService.extractZipInPlace(kept(archive.filename()), runtime());
    }
    assertEquals("keep", Files.readString(runtime().resolve("shell-only.txt")));
  }

  @Test void sameVersionChangedBytesInvalidateMarkerDuringInterruptedApplyThenRestoreDetection() throws Exception {
    var names = NativeTargets.current();
    var entries = new LinkedHashMap<String, String>();
    entries.put("ort-native-version.txt", OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);
    for (String library : List.of("ort", "ort-jni", "ort-shared", "ort-cuda")) entries.put(names.libraryName(library), "old");
    var archive = source("ort.zip", entries);
    run(plan(registry(archive)));
    assertEquals(OrtCudaHelper.OrtNativePackStatus.SET, OrtCudaHelper.evaluateOrtNativePack(runtime(), null).status());
    entries.replaceAll((key, value) -> key.endsWith(".txt") ? value : "new");
    var changed = source("ort.zip", entries);
    Path blocked = runtime().resolve(names.libraryName("ort-cuda"));
    Files.delete(blocked);
    Files.createDirectory(blocked);
    var observedMidApply = new java.util.concurrent.atomic.AtomicBoolean();
    try (var files = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("newOutputStream")
          && invocation.getArgument(0).toString().endsWith(".partial")
          && !invocation.getArgument(0).toString().endsWith(".zip.partial")) {
        assertFalse(Files.exists(runtime().resolve("ort-native-version.txt")), "ORT must see no marker during payload writes");
        observedMidApply.set(true);
      }
      return invocation.callRealMethod();
    })) {
      assertEquals(1, run(plan(registry(changed))).failed());
    }
    assertTrue(observedMidApply.get());
    assertFalse(Files.exists(runtime().resolve("ort-native-version.txt")), "same-version marker must disappear first");
    assertNotEquals(OrtCudaHelper.OrtNativePackStatus.SET, OrtCudaHelper.evaluateOrtNativePack(runtime(), null).status());
    Files.delete(blocked);
    assertEquals(0, run(plan(registry(changed))).failed());
    assertEquals(2, transfers.get());
    assertEquals(OrtCudaHelper.OrtNativePackStatus.SET, OrtCudaHelper.evaluateOrtNativePack(runtime(), null).status());
    for (var entry : entries.entrySet()) assertEquals(entry.getValue(), Files.readString(runtime().resolve(entry.getKey())));
  }

  @Test void supplementalApplyHidesExistingOrtMarkerUntilItsPayloadIsComplete() throws Exception {
    var archive = source("supplemental.zip", Map.of("cuda.dll", "new-bytes"));
    keep(archive);
    Files.writeString(runtime().resolve("ort-native-version.txt"), OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);
    Files.createDirectory(runtime().resolve("cuda.dll"));
    assertEquals(1, run(plan(registry(archive))).failed());
    assertFalse(Files.exists(runtime().resolve("ort-native-version.txt")));
    Files.delete(runtime().resolve("cuda.dll"));
    assertEquals(0, run(plan(registry(archive))).failed());
    assertEquals("new-bytes", Files.readString(runtime().resolve("cuda.dll")));
    assertEquals(OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION, Files.readString(runtime().resolve("ort-native-version.txt")));
    assertEquals(0, transfers.get());
  }

  @Test void extractionOnlyKeepsRegistrySizeButDoesNotChargeStageOrStatusTransferBytes() throws Exception {
    var archive = source("cost.zip", Map.of("library.dll", "expected"));
    keep(archive);
    var registry = registry(archive);
    var repair = plan(registry);
    var item = repair.downloads().getFirst();
    assertEquals(archive.sizeBytes(), item.sizeBytes());
    assertEquals(0, item.stagedBytes());
    assertEquals(0, item.remainingBytes());
    assertEquals(0, repair.totalBytes());
    assertEquals(0, repair.resumableBytes());
    var slices = InstallStage.partition(repair.downloads(), id -> CapabilityTier.RUNTIME);
    assertEquals(0, slices.getFirst().bytes());
    assertEquals(0, slices.getFirst().remainingBytes());
    var service = new AiInstallService(null, null, null, null, home);
    invoke(service, "populateStatusPackages", new Class<?>[] {InstallPlan.class, ModelRegistry.class}, repair, registry);
    assertEquals(0, status(service).packages.getFirst().bytesTotal);
    var previewService = spy(new AiInstallService(null, null, null, null, home));
    doReturn(registry).when(previewService).getManifest();
    var preview = previewService.previewInstallPlan();
    assertEquals(0, preview.totalDownloadBytes);
    var component = preview.components.stream().filter(c -> c.id.equals("runtime")).findFirst().orElseThrow();
    assertEquals(0, component.downloadBytes);
    assertEquals("to-download", component.state, "a zero-transfer repair is still pending work");
    assertEquals(0, run(repair).acquiredBytes());
    assertEquals(0, transfers.get());
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

  @ParameterizedTest @ValueSource(strings = {"../outside.dll", "/absolute.dll", "C:/absolute.dll", "..\\outside.dll"})
  void traversalAndAbsolutePathsAreRejected(String entry) throws Exception {
    var archive = source("hostile.zip", Map.of(entry, "bad"));
    keep(archive);
    assertThrows(IOException.class, () -> AiInstallService.extractZipInPlace(kept("hostile.zip"), runtime()));
    assertFalse(Files.exists(models().resolve("outside.dll")));
  }

  @Test @EnabledOnOs(OS.LINUX)
  void archiveAcquisitionRejectsRedirectedParentBeforeWritingStagingBytes() throws Exception {
    var archive = source("redirected.zip", Map.of("library.dll", "expected"));
    Path outside = home.resolve("outside");
    Files.createDirectories(outside);
    Files.createDirectories(models());
    Files.createSymbolicLink(runtime(), outside);
    assertEquals(1, run(plan(registry(archive))).failed());
    assertEquals(0, transfers.get());
    try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
  }

  @EnabledOnOs(OS.LINUX)
  @ParameterizedTest @ValueSource(strings = {"output", "parent", "partial", "receipt"})
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
      case "partial" -> Files.createSymbolicLink(runtime().resolve("nested/library.dll.partial"), outside.resolve("library.dll"));
      case "receipt" -> Files.createSymbolicLink(runtime().resolve(".archive-receipts"), outside);
    }
    assertThrows(IOException.class, () -> AiInstallService.extractZipInPlace(kept("links.zip"), runtime()));
    assertEquals("old", Files.readString(outside.resolve("library.dll")));
  }
}
