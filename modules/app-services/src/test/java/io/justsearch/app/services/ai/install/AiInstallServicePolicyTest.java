/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.services.gpu.GpuCapabilityResolver;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.model.*;
import io.justsearch.gpu.GpuCapabilities;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AiInstallServicePolicyTest {
  @TempDir Path home;

  private static final String POLICY_REASON = "GPU acceleration is disabled by administrator policy";

  private static EffectivePolicy policy(boolean downloads, boolean gpu) {
    return new EffectivePolicy(true, downloads, true, gpu, false, List.of(), List.of(),
        "test", false, null, null, false);
  }

  private AiInstallService service(EnterprisePolicyService policy) {
    return new AiInstallService(null, null, null, policy, home);
  }

  private static Object invoke(AiInstallService service, String name) throws Exception {
    Method method = AiInstallService.class.getDeclaredMethod(name);
    method.setAccessible(true);
    return method.invoke(service);
  }

  @Test
  void startAndRepairRejectSnapshotExceptionBeforeWrites() throws Exception {
    EnterprisePolicyService policy = () -> { throw new IllegalStateException("snapshot failed"); };
    assertAdmissionRefused(policy);
  }

  @Test
  void startAndRepairRejectNullSnapshotBeforeWrites() throws Exception {
    assertAdmissionRefused(() -> null);
  }

  private void assertAdmissionRefused(EnterprisePolicyService policy) throws Exception {
    UiSettingsStore settings = mock(UiSettingsStore.class);
    SettingsService owner = mock(SettingsService.class);
    Thread caller = Thread.currentThread();
    // If the before-state wrongly admits the request, its background owner still cannot acquire.
    EnterprisePolicyService admissionPolicy = () -> Thread.currentThread() == caller
        ? policy.snapshot() : policy(false, false);
    var service = new AiInstallService(null, settings, null, admissionPolicy, home, null, owner);
    for (boolean repair : List.of(false, true)) {
      var failure = assertThrows(AiInstallException.class, () -> {
        if (repair) service.repair(true); else service.startInstall(true);
      });
      assertEquals(403, failure.httpStatus());
      assertEquals(ApiErrorCode.DOWNLOADS_DISABLED, failure.errorCode());
      assertEquals("Downloads disabled by administrator policy.", failure.getMessage());
    }
    verifyNoInteractions(settings, owner);
    try (var files = Files.list(home)) { assertEquals(0, files.count()); }
  }

  @Test
  void backgroundSnapshotExceptionFailsBeforeAcquisition() throws Exception {
    EnterprisePolicyService policy = mock(EnterprisePolicyService.class);
    when(policy.snapshot()).thenReturn(policy(true, true))
        .thenThrow(new IllegalStateException("background snapshot failed"));
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    byte[] bytes = "runtime fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    server.createContext("/runtime", exchange -> {
      requests.incrementAndGet();
      exchange.sendResponseHeaders(200, bytes.length);
      try (var body = exchange.getResponseBody()) { body.write(bytes); }
    });
    server.start();
    try {
      String sha = java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
      var runtime = new ModelPackage("runtime-fixture", "Runtime", "fixture", "runtime", List.of(),
          List.of(new SupportingFile("runtime.bin", sha, bytes.length,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/runtime")),
          0, null, null, null, CapabilityTier.RUNTIME);
      var service = spy(service(policy));
      doReturn(new ModelRegistry(2, "local transport fixture", List.of(runtime)))
          .when(service).getManifest();
      var attempt = service.startInstall(true);
      var result = attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
      assertEquals("failed", result.state);
      assertEquals("DOWNLOADS_DISABLED", result.errorCode);
      assertEquals("Downloads disabled by administrator policy.", result.lastError);
      assertEquals(0, requests.get(), "background denial must precede every transport request");
      assertFalse(Files.exists(home.resolve(InstallContract.CONTRACT_FILENAME)));
      try (var files = Files.walk(home)) {
        assertTrue(files.noneMatch(Files::isRegularFile), "no acquisition or settings writes");
      }
    } finally { server.stop(0); }
  }

  @Test
  void configuredSnapshotFailureMakesCapableHardwareCpuOnly() throws Exception {
    for (EnterprisePolicyService policy : List.<EnterprisePolicyService>of(
        () -> null, () -> { throw new IllegalStateException("GPU policy read failed"); })) {
      try (var _ = capableGpu(12_000_000_000L)) {
        assertEquals(DownloadProfile.CPU, service(policy).buildHardwareProfile().downloadProfile());
      }
    }
  }

  @Test
  void gpuOffPreviewAndExecutionExcludeCudaAndRecordCpuContract() throws Exception {
    assertCpuPlan(12_000_000_000L, true, true);
  }

  @Test
  void gpuOffLitePreviewKeepsTheOriginalVramReasonForChat() throws Exception {
    assertCpuPlan(6_000_000_000L, false, true);
  }

  @Test
  void gpuOffExecutionRecordsCpuContractWithoutCudaAcquisition() throws Exception {
    assertCpuPlan(12_000_000_000L, true, false);
  }

  private void assertCpuPlan(long vram, boolean chatWasEligible, boolean inspectPreview) throws Exception {
    var service = service(() -> policy(true, false));
    Path retained = home.resolve("native-bin/llama-server/variants/cuda12/retained.dll");
    Files.createDirectories(retained.getParent());
    Files.writeString(retained, "installed CUDA bytes");
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS);
         var _ = capableGpu(vram);
         var restore = mockStatic(RuntimeRestoreUtil.class);
         var stages = mockConstruction(StagedAcquisition.class,
             (mock, context) -> when(mock.run(anyList(), any())).thenReturn(true))) {
      target.when(NativeTargets::current).thenReturn(windows);
      restore.when(() -> RuntimeRestoreUtil.ensureRuntimePresent(home)).thenReturn(true);
      if (inspectPreview) {
        var preview = service.previewInstallPlan();
        assertEquals("CPU", preview.downloadProfile);
        var cuda = preview.components.stream().filter(c -> c.id.equals("cuda-runtime"))
            .findFirst().orElseThrow();
        assertEquals(POLICY_REASON, cuda.unavailableReason);
        var chat = preview.components.stream().filter(c -> c.id.equals("chat"))
            .findFirst().orElseThrow();
        if (chatWasEligible) assertEquals(POLICY_REASON, chat.unavailableReason);
        else assertTrue(chat.unavailableReason.startsWith("Insufficient VRAM"));
      }

      Method run = java.util.Arrays.stream(AiInstallService.class.getDeclaredMethods())
          .filter(m -> m.getName().equals("runInstallInternal")).findFirst().orElseThrow();
      run.setAccessible(true);
      assertDoesNotThrow(() -> run.invoke(service, new Object[] {null}));
      assertEquals(1, stages.constructed().size());
      var slices = org.mockito.ArgumentCaptor.forClass(List.class);
      verify(stages.constructed().getFirst()).run(slices.capture(), any());
      for (Object value : slices.getValue()) {
        var slice = (InstallStage.Slice) value;
        assertTrue(slice.downloads().stream().noneMatch(d ->
            d.packageId().equals("cuda-runtime") || d.packageId().equals("chat")));
        assertTrue(slice.downloads().stream().filter(InstallPlan.PlannedDownload::isModelVariant)
            .noneMatch(d -> d.targetPath().contains("fp16")));
      }
      var contract = InstallContractIO.read(home);
      assertNotNull(contract);
      assertEquals(DownloadProfile.CPU, contract.downloadProfile());
      assertEquals(HardwareProfile.cpuOnly(), contract.hardwareProfile());
      assertEquals(POLICY_REASON, contract.getModel("cuda-runtime").skipReason());
      assertTrue(contract.getModel("cuda-runtime").installedFiles().isEmpty());
      assertEquals("installed CUDA bytes", Files.readString(retained));
    }
  }

  @Test
  void gpuOffRetainsCudaResumeJobsAndPartialBytesOutsideThePlan() throws Exception {
    var service = service(() -> policy(true, false));
    Path partial = home.resolve("native-bin/llama-server/variants/cuda12/runtime.zip.partial");
    Files.createDirectories(partial.getParent());
    Files.writeString(partial, "partial CUDA bytes");
    DownloadResume.write(partial, new DownloadResume.State("https://example.invalid/cuda", 100,
        "abc", "retained-job"));
    var plan = new InstallPlan(DownloadProfile.CPU, List.of(), List.of(), 0, List.of());
    Method sweep = AiInstallService.class.getDeclaredMethod("sweepOrphanedBitsJobsBestEffort", InstallPlan.class);
    sweep.setAccessible(true);
    try (var executor = mockStatic(DownloadExecutor.class)) {
      sweep.invoke(service, plan);
      assertDoesNotThrow(executor::verifyNoInteractions);
    }
    assertEquals("partial CUDA bytes", Files.readString(partial));
    assertEquals("retained-job", DownloadResume.read(partial).bitsJobId());
  }

  @Test
  void absentServiceAndPermissiveServiceKeepAllThreePlans() throws Exception {
    // This preservation check describes the Windows runtime plans. Linux has no CUDA pack yet.
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS)) {
      target.when(NativeTargets::current).thenReturn(windows);
      for (EnterprisePolicyService policy : new EnterprisePolicyService[] {null, () -> policy(true, true)}) {
        for (long vram : new long[] {12_000_000_000L, 6_000_000_000L, -1}) {
          try (var _ = capableGpu(vram)) {
            var hardware = service(policy).buildHardwareProfile();
            assertEquals(vram < 0 ? DownloadProfile.CPU
                : vram > 7_500_000_000L ? DownloadProfile.GPU_FULL : DownloadProfile.GPU_LITE,
                hardware.downloadProfile());
            assertEquals(false, invoke(service(policy), "policyBlocksDownloads"));
          }
        }
      }
    }
  }

  private static org.mockito.MockedConstruction<GpuCapabilityResolver> capableGpu(long vram) {
    var effective = new GpuCapabilities.Effective(vram > 0, "fixture", GpuCapabilities.Confidence.HIGH,
        null, null, null, 1, vram, vram, 0L,
        new GpuCapabilities.Cuda(vram > 0, "fixture", GpuCapabilities.Confidence.HIGH));
    return mockConstruction(GpuCapabilityResolver.class,
        (mock, context) -> when(mock.snapshot()).thenReturn(new GpuCapabilities(null, null, effective)));
  }
}
