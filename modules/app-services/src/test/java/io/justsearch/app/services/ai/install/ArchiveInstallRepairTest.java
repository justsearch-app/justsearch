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

  /** Applies a kept archive as the only one its folder lists, after hashing it. */
  static void extract(Path archive, Path target) throws IOException {
    String sha;
    try {
      sha = DownloadExecutor.sha256(archive);
    } catch (Exception failure) {
      throw new IOException("Cannot hash archive", failure);
    }
    ArchiveExtractor.apply(archive, target, sha, null);
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
    // Apply works on the destination's real path, which may spell the temp folder differently.
    Path receiptTarget = Path.of("cuda12", ".archive-receipts", "receipt.zip.json");
    try (var _ = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("move")
          && invocation.getArgument(1) instanceof Path to && to.endsWith(receiptTarget)) {
        throw new java.io.InterruptedIOException("interrupted receipt publication");
      }
      return invocation.callRealMethod();
    })) {
      assertThrows(IOException.class, () -> extract(kept("receipt.zip"), runtime()));
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
    Path staging = runtime().resolve(".archive-staging");
    try (var _ = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("newOutputStream")
          && invocation.getArgument(0) instanceof Path to && to.getParent().endsWith(Path.of("cuda12", ".archive-staging"))) {
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
    try (var staged = Files.list(staging)) {
      assertTrue(staged.allMatch(file -> file.toFile().length() > 0), "disk-full failure leaves only an incomplete staged file");
    }
    assertFalse(Files.exists(runtime().resolve("library.dll")));
    assertNull(InstallAttemptMemory.load(home).get(initial.downloads().getFirst().targetPath()));
    assertEquals(1, plan(registry(archive)).downloads().size());
    assertEquals(0, run(plan(registry(archive))).failed());
    assertEquals(1, transfers.get());
    assertFalse(Files.exists(staging), "the next apply cleans the interrupted apply's staged file");
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
      for (var archive : archives) extract(kept(archive.filename()), runtime());
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
    try (var _ = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("newOutputStream")
          && invocation.getArgument(0) instanceof Path to && to.getParent().endsWith(".archive-staging")) {
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
    assertEquals(1, repair.downloads().size(), "a kept ZIP without its outputs must be planned as extraction-only repair");
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

  @ParameterizedTest @ValueSource(strings = {"C:/absolute.dll", "..\\outside.dll"})
  void traversalAndAbsolutePathsAreRejected(String entry) throws Exception {
    var archive = source("hostile.zip", Map.of(entry, "bad"));
    keep(archive);
    assertThrows(IOException.class, () -> extract(kept("hostile.zip"), runtime()));
    assertFalse(Files.exists(models().resolve("outside.dll")));
  }

  @Test @EnabledOnOs(OS.LINUX)
  void archiveAcquisitionRefusesLinkBelowDestinationBeforeWritingStagingBytes() throws Exception {
    var archive = source("redirected.zip", Map.of("library.dll", "expected"));
    Path outside = home.resolve("outside");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("victim"), "old");
    Files.createDirectories(runtime());
    Files.createSymbolicLink(runtime().resolve("redirected.zip.partial"), outside.resolve("victim"));
    assertEquals(1, run(plan(registry(archive))).failed());
    assertEquals(0, transfers.get());
    assertEquals("old", Files.readString(outside.resolve("victim")));
    try (var files = Files.list(outside)) { assertEquals(1, files.count()); }
  }

  /** A link above the pack folder, or the pack folder itself moved to another disk, is followed. */
  @EnabledOnOs(OS.LINUX)
  @ParameterizedTest @ValueSource(strings = {"ancestor", "destination"})
  void repairThroughSymlinkedAncestorOrDestinationUsesKeptZip(String kind) throws Exception {
    Path real = Files.createDirectories(home.resolve("real-home"));
    Path receipts = real.resolve("models/cuda12/.archive-receipts");
    if (kind.equals("ancestor")) {
      home = Files.createSymbolicLink(home.resolve("linked-home"), real);
    } else {
      Files.createDirectories(models());
      Files.createSymbolicLink(runtime(), Files.createDirectories(real.resolve("models/cuda12")));
    }
    var archive = source("pack.zip", Map.of("library.dll", "payload"));
    assertEquals(0, run(plan(registry(archive))).failed());
    Files.delete(runtime().resolve("library.dll"));
    var repair = plan(registry(archive));
    assertEquals(1, repair.downloads().size());
    assertEquals(0, run(repair).failed());
    assertEquals(1, transfers.get());
    assertEquals("payload", Files.readString(runtime().resolve("library.dll")));
    assertTrue(Files.isRegularFile(receipts.resolve("pack.zip.json")));
    assertTrue(plan(registry(archive)).downloads().isEmpty());
  }

  /** Install runs plan through their own entry point; reflection keeps this class compiling on the before-state. */
  InstallPlan installRunPlan(ModelRegistry registry) throws Exception {
    var entry = java.util.Arrays.stream(InstallPlanner.class.getMethods())
        .filter(method -> method.getName().equals("planInstallRun")).findFirst();
    assertTrue(entry.isPresent(), "install runs must verify archives installed before receipts");
    return (InstallPlan) entry.get().invoke(null, registry, hardware, InstallIntent.DEFAULT,
        java.util.Set.of(), models(), home, null);
  }

  @Test void legacyInstallWithChangedEqualLengthArchiveIsUpdatedByTheNextInstallRun() throws Exception {
    var old = source("changed.zip", Map.of("library.dll", "aaaa"));
    keep(old);
    Files.writeString(runtime().resolve("library.dll"), "aaaa");
    var next = source("changed.zip", Map.of("library.dll", "bbbb"));
    assertEquals(old.sizeBytes(), next.sizeBytes());
    assertTrue(plan(registry(next)).downloads().isEmpty(), "status keeps the legacy install and never hashes ZIPs");
    var install = installRunPlan(registry(next));
    assertEquals(1, install.downloads().size(), "a new SHA must not be hidden by a legacy install");
    assertEquals(0, run(install).failed());
    assertEquals(1, transfers.get());
    assertArrayEquals(sources.get(next.downloadUrl()), Files.readAllBytes(kept("changed.zip")));
    assertEquals("bbbb", Files.readString(runtime().resolve("library.dll")));
    assertTrue(installRunPlan(registry(next)).downloads().isEmpty());
  }

  @Test void installRunHashesHealthyLegacyArchiveOnceAndRecordsReceiptWithoutRewriting() throws Exception {
    var archive = source("legacy.zip", Map.of("library.dll", "expected"));
    keep(archive);
    Files.writeString(runtime().resolve("library.dll"), "expected");
    FileTime old = FileTime.fromMillis(1_000_000_000_000L);
    Files.setLastModifiedTime(runtime().resolve("library.dll"), old);
    var status = plan(registry(archive));
    assertTrue(status.downloads().isEmpty());
    assertTrue(InstallCompleteness.compute(status, null).installedFully());
    var install = installRunPlan(registry(archive));
    assertEquals(1, install.downloads().size());
    assertEquals(0, install.remainingBytes());
    var zipReads = new AtomicInteger();
    try (var _ = mockStatic(Files.class, invocation -> {
      if (invocation.getMethod().getName().equals("newInputStream")
          && invocation.getArgument(0) instanceof Path read && read.endsWith("legacy.zip")) {
        zipReads.incrementAndGet();
      }
      return invocation.callRealMethod();
    })) {
      assertEquals(0, run(install).failed());
    }
    assertEquals(1, zipReads.get(), "the kept ZIP is hashed once per install run");
    assertEquals(0, transfers.get());
    assertEquals(old, Files.getLastModifiedTime(runtime().resolve("library.dll")));
    assertTrue(Files.isRegularFile(receipt("legacy.zip")));
    assertTrue(installRunPlan(registry(archive)).downloads().isEmpty());
  }

  @Test void overlappingArchiveWithDifferentContentFailsVisiblyInsteadOfAlternating() throws Exception {
    var first = source("first.zip", Map.of("shared.dll", "short", "first.dll", "1"));
    var second = source("second.zip", Map.of("shared.dll", "much longer", "second.dll", "2"));
    var registry = registry(first, second);
    for (int pass = 0; pass < 3; pass++) {
      var repair = plan(registry);
      assertEquals(pass == 0 ? 2 : 1, repair.downloads().size());
      assertEquals(1, run(repair).failed(), "the conflicting archive fails on every run");
      assertEquals("short", Files.readString(runtime().resolve("shared.dll")), "outputs never alternate");
      assertTrue(Files.isRegularFile(receipt("first.zip")));
      assertFalse(Files.exists(receipt("second.zip")));
    }
    var failure = assertThrows(IOException.class,
        () -> extract(kept("second.zip"), runtime()));
    assertTrue(failure.getMessage().contains("first.zip") && failure.getMessage().contains("second.zip"),
        failure.getMessage());
    assertFalse(Files.exists(runtime().resolve("second.dll")), "the refused archive writes nothing");
    assertEquals(2, transfers.get());
  }

  @Test void registryRenamedArchiveReplacesTheOneItSupersedesWithoutOverlapFailure() throws Exception {
    var cudnn = source("cudnn-9-runtime-12.4.zip", Map.of("cudnn.dll", "cudnn"));
    var old = source("ort-native-cuda12-v1.24.3.zip",
        Map.of("onnxruntime.dll", "ort 1.24.3", "ort-native-version.txt", "1.24.3"));
    assertEquals(0, run(plan(registry(cudnn, old))).failed());
    assertTrue(Files.isRegularFile(receipt(old.filename())));
    var next = source("ort-native-cuda12-v1.25.0.zip",
        Map.of("onnxruntime.dll", "ort 1.25.0 build", "ort-native-version.txt", "1.25.0"));
    var update = installRunPlan(registry(cudnn, next));
    assertEquals(1, update.downloads().size());
    assertEquals(0, run(update).failed(), "a renamed pack is an update, not an overlap");
    assertEquals("ort 1.25.0 build", Files.readString(runtime().resolve("onnxruntime.dll")));
    assertEquals("1.25.0", Files.readString(runtime().resolve("ort-native-version.txt")));
    assertTrue(Files.isRegularFile(receipt(next.filename())));
    assertFalse(Files.exists(receipt(old.filename())), "the superseded archive's receipt is retired");
    assertTrue(Files.isRegularFile(receipt(cudnn.filename())), "receipts the registry still lists stay");
    assertTrue(installRunPlan(registry(cudnn, next)).downloads().isEmpty());
    assertEquals(3, transfers.get());
  }

  @Test void receiptOfArchiveNoLongerListedTakesNoPartAndOnlyItIsDeleted() throws Exception {
    var archive = source("pack.zip", Map.of("payload.dll", "new"));
    Path receipts = runtime().resolve(".archive-receipts");
    Files.createDirectories(receipts);
    // Claims payload.dll with other content: taking part in the overlap rule would fail the apply.
    Files.writeString(receipt("retired.zip"),
        "{\"sha256\":\"00\",\"entries\":[{\"name\":\"payload.dll\",\"size\":3,\"crc\":1}]}");
    Files.writeString(receipts.resolve("retired.zip.json.pending"), "pending");
    Files.writeString(kept("retired.zip"), "old zip");
    Files.writeString(runtime().resolve("retired.dll"), "retired output");
    Files.writeString(runtime().resolve("retired.zip.json"), "not a receipt");
    assertEquals(0, run(plan(registry(archive))).failed());
    assertEquals("new", Files.readString(runtime().resolve("payload.dll")));
    assertFalse(Files.exists(receipt("retired.zip")));
    assertTrue(Files.isRegularFile(receipt("pack.zip")));
    assertEquals("pending", Files.readString(receipts.resolve("retired.zip.json.pending")));
    assertEquals("old zip", Files.readString(kept("retired.zip")));
    assertEquals("retired output", Files.readString(runtime().resolve("retired.dll")));
    assertEquals("not a receipt", Files.readString(runtime().resolve("retired.zip.json")));
  }

  @EnabledOnOs(OS.LINUX)
  @ParameterizedTest @ValueSource(strings = {".archive-staging", ".archive-old", ".archive-receipts"})
  void replacementRejectsSymlinkedStateFolder(String folder) throws Exception {
    var archive = source("links.zip", Map.of("nested/library.dll", "new"));
    keep(archive);
    Path outside = home.resolve("outside");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("library.dll"), "old");
    Files.createDirectories(runtime().resolve("nested"));
    Files.writeString(runtime().resolve("nested/library.dll"), "old output");
    Files.createSymbolicLink(runtime().resolve(folder), outside);
    assertThrows(IOException.class, () -> extract(kept("links.zip"), runtime()));
    assertEquals("old", Files.readString(outside.resolve("library.dll")));
    try (var files = Files.list(outside)) { assertEquals(1, files.count(), "nothing written or removed outside"); }
  }

  @Test void interruptedApplyLeftoversInTheExtractorFoldersAreCleanedByTheNextApply() throws Exception {
    var archive = source("pack.zip", Map.of("library.dll", "payload"));
    keep(archive);
    // Leftovers of an apply interrupted after staging and after moving an old file aside.
    Files.createDirectories(runtime().resolve(".archive-staging"));
    Files.writeString(runtime().resolve(".archive-staging/0d6f1c4e-interrupted"), "half");
    Files.createDirectories(runtime().resolve(".archive-old"));
    Files.writeString(runtime().resolve(".archive-old/5b2a9e70-interrupted"), "old dll");
    extract(kept("pack.zip"), runtime());
    assertFalse(Files.exists(runtime().resolve(".archive-staging")), "stale staged file cleaned");
    assertFalse(Files.exists(runtime().resolve(".archive-old")), "stale moved-aside file cleaned");
    assertEquals("payload", Files.readString(runtime().resolve("library.dll")));
  }

  @Test void unlistedFileNamedLikeStagingOfANewOutputSurvives() throws Exception {
    var archive = source("pack.zip", Map.of("library.dll", "payload"));
    keep(archive);
    Path unlisted = runtime().resolve("library.dll.partial");
    Files.writeString(unlisted, "user-owned");
    extract(kept("pack.zip"), runtime());
    assertTrue(Files.exists(unlisted), "a file no archive lists was deleted");
    assertEquals("user-owned", Files.readString(unlisted));
    assertEquals("payload", Files.readString(runtime().resolve("library.dll")));
  }

  @Test @EnabledOnOs(OS.LINUX)
  void heldOpenChangedFileIsMovedAsideInsideDestinationWithoutTruncation() throws Exception {
    var archive = source("held.zip", Map.of("library.dll", "new-bytes-longer"));
    keep(archive);
    Path target = runtime().resolve("library.dll");
    Files.writeString(target, "old-bytes");
    var moves = new java.util.ArrayList<Path>();
    try (var held = Files.newInputStream(target);
        var _ = mockStatic(Files.class, invocation -> {
          if (invocation.getMethod().getName().equals("move") && invocation.getArgument(0) instanceof Path from
              && from.endsWith(Path.of("cuda12", "library.dll"))) {
            moves.add(invocation.getArgument(1));
          }
          return invocation.callRealMethod();
        })) {
      extract(kept("held.zip"), runtime());
      assertEquals("new-bytes-longer", Files.readString(target));
      assertEquals("old-bytes", new String(held.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
          "the old file is moved aside, never truncated or rewritten in place");
    }
    assertEquals(1, moves.size());
    assertTrue(moves.getFirst().getParent().endsWith(Path.of("cuda12", ".archive-old")),
        "move-aside stays inside the destination, on its volume: " + moves.getFirst());
    try (var files = Files.list(runtime())) {
      assertEquals(java.util.Set.of("held.zip", "library.dll", ".archive-receipts"),
          files.map(file -> file.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"second.zip", ".archive-staging/x.dll", ".archive-old/x.dll",
      ".archive-receipts/first.zip.json", "a.dll.partial", "second.zip/x.dll", "second.zip.partial",
      "second.zip.partial.resume", "first.zip.partial"})
  void entryNamedLikeReservedOrKeptArchivePathFailsItsArchiveWithoutWriting(String name) throws Exception {
    var second = source("second.zip", Map.of("two.dll", "2"));
    var entries = new LinkedHashMap<String, String>();
    entries.put("a.dll", "a");
    entries.put(name, "{\"sha256\":\"x\",\"entries\":[]}");
    var first = source("first.zip", entries);
    var registry = registry(second, first);
    assertEquals(1, run(plan(registry)).failed(), "the colliding archive fails visibly");
    assertArrayEquals(sources.get(second.downloadUrl()), Files.readAllBytes(kept("second.zip")));
    assertEquals("2", Files.readString(runtime().resolve("two.dll")));
    assertTrue(Files.isRegularFile(receipt("second.zip")));
    assertFalse(Files.exists(runtime().resolve("a.dll")), "the refused archive writes nothing");
    assertFalse(Files.exists(receipt("first.zip")), "and publishes no receipt");
    assertEquals(1, plan(registry).downloads().size());
  }

  @Test void entryNameTheFilesystemCannotRepresentFailsItsArchiveNotTheRun() throws Exception {
    var bad = source("nul.zip", Map.of("bad\u0000name.dll", "x"));
    var good = source("good.zip", Map.of("good.dll", "g"));
    var summary = assertDoesNotThrow(() -> run(plan(registry(bad, good))));
    assertEquals(1, summary.failed());
    assertEquals("g", Files.readString(runtime().resolve("good.dll")));
    assertFalse(Files.exists(receipt("nul.zip")));
  }

  static Map<String, String> ortPack() {
    var entries = new LinkedHashMap<String, String>();
    entries.put("ort-native-version.txt", OrtCudaHelper.EXPECTED_ORT_NATIVE_VERSION);
    for (String library : List.of("ort", "ort-jni", "ort-shared", "ort-cuda")) {
      entries.put(NativeTargets.current().libraryName(library), "lib");
    }
    return entries;
  }

  OrtCudaHelper.OrtNativePackStatus ortStatus() {
    return OrtCudaHelper.evaluateOrtNativePack(runtime(), null).status();
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void ortMarkerStaysHiddenWhileAnotherListedArchiveFailsAndShowsOnceItIsRepaired(boolean failingFirst) throws Exception {
    var ort = source("ort-native.zip", ortPack());
    var cudnn = source("cudnn.zip", Map.of("cudnn.dll", "cudnn"));
    var registry = failingFirst ? registry(cudnn, ort) : registry(ort, cudnn);
    Files.createDirectories(runtime().resolve("cudnn.dll")); // cudnn's apply fails
    assertEquals(1, run(plan(registry)).failed(), "cudnn's failure is reported");
    assertFalse(Files.exists(runtime().resolve("ort-native-version.txt")), "no marker while cudnn has no receipt");
    assertNotEquals(OrtCudaHelper.OrtNativePackStatus.SET, ortStatus());
    Files.delete(runtime().resolve("cudnn.dll"));
    assertEquals(0, run(plan(registry)).failed());
    assertEquals(OrtCudaHelper.OrtNativePackStatus.SET, ortStatus(), "the repaired pack shows its marker");
    assertTrue(plan(registry).downloads().isEmpty());
    assertEquals(2, transfers.get());
  }

  @Test void repairingOneOfTwoFailedSupplementalArchivesKeepsTheOrtMarkerHidden() throws Exception {
    var ort = source("ort-native.zip", ortPack());
    assertEquals(0, run(plan(registry(source("a.zip", Map.of("a.dll", "old")),
        source("b.zip", Map.of("b.dll", "old")), ort))).failed());
    assertEquals(OrtCudaHelper.OrtNativePackStatus.SET, ortStatus());
    var registry = registry(source("a.zip", Map.of("a.dll", "changed-a")),
        source("b.zip", Map.of("b.dll", "changed-b")), ort);
    for (String output : List.of("a.dll", "b.dll")) {
      Files.delete(runtime().resolve(output));
      Files.createDirectory(runtime().resolve(output));
    }
    assertEquals(2, run(plan(registry)).failed());
    assertFalse(Files.exists(runtime().resolve("ort-native-version.txt")));
    Files.delete(runtime().resolve("a.dll"));
    assertEquals(1, run(plan(registry)).failed());
    assertEquals("changed-a", Files.readString(runtime().resolve("a.dll")));
    assertFalse(Files.exists(runtime().resolve("ort-native-version.txt")), "b.zip is still incomplete");
    assertNotEquals(OrtCudaHelper.OrtNativePackStatus.SET, ortStatus());
    Files.delete(runtime().resolve("b.dll"));
    assertEquals(0, run(plan(registry)).failed());
    assertEquals(OrtCudaHelper.OrtNativePackStatus.SET, ortStatus());
    assertEquals(5, transfers.get());
  }

  @ParameterizedTest @ValueSource(strings = {"./cuda12", "cuda12/", "cuda12/."})
  void destinationsSpelledDifferentlyShareReceiptsAndTheOverlapRule(String spelling) throws Exception {
    var first = source("first.zip", Map.of("shared.dll", "short", "first.dll", "1"));
    var second = source("second.zip", Map.of("shared.dll", "much longer", "second.dll", "2"));
    var registry = new ModelRegistry(2, "spellings", List.of(
        new ModelPackage("first", "First", "test", "cuda12", List.of(), List.of(first), 0, null),
        new ModelPackage("second", "Second", "test", spelling, List.of(),
            List.of(second), 0, null)));
    for (int pass = 0; pass < 3; pass++) {
      assertEquals(1, run(plan(registry)).failed(), "the conflicting archive fails on every run");
      assertEquals("short", Files.readString(runtime().resolve("shared.dll")), "outputs never alternate");
      assertTrue(Files.isRegularFile(receipt("first.zip")), "one spelling never retires the other's receipt");
      assertFalse(Files.exists(receipt("second.zip")));
    }
    assertEquals(2, transfers.get());
  }
}
