/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.services.ai.preflight.AiPreflightService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.configuration.NativeTargets;
import io.justsearch.app.services.gpu.GpuCapabilityResolver;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.configuration.model.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S7/S12, D13: Linux search installation has no llama-server prerequisite. */
final class LinuxSearchOnlyInstallTest {
  @TempDir Path home;

  @Test
  void cpuInstallDownloadsSearchAndCompletesWithoutChatExecutable() throws Exception {
    byte[] bytes = "search model fixture".getBytes(StandardCharsets.UTF_8);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/model", exchange -> {
      exchange.sendResponseHeaders(200, bytes.length);
      try (var body = exchange.getResponseBody()) { body.write(bytes); }
    });
    server.start();
    NativeTargets linux = NativeTargets.forTarget("linux-x86_64");
    var previousConfig = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    io.justsearch.configuration.resolved.ConfigStore config = null;
    try (var gpu = hardware(-1);
         var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS)) {
      assertNotNull(gpu);
      target.when(NativeTargets::current).thenReturn(linux);
      String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      var search = new ModelPackage("embedding", "Search", "Search fixture", "search", List.of(
          new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
              sha, bytes.length, "http://127.0.0.1:" + server.getAddress().getPort() + "/model")),
          List.of(new SupportingFile("tokenizer.json", sha, bytes.length,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/model")),
          0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
      var shipped = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
      var registry = new ModelRegistry(2, "isolated acquisition", List.of(search,
          shipped.findPackage("chat"), shipped.findPackage("cuda-runtime")));
      var settings = new io.justsearch.app.services.settings.UiSettingsStore(
          io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE,
          home.resolve("settings.json"));
      config = new io.justsearch.configuration.resolved.ConfigStore(
          io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings.load()));
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(config);
      var service = spy(new AiInstallService(null, settings, null, null, home));
      doReturn(registry).when(service).getManifest();
      doReturn(HardwareProfile.cpuOnly()).when(service).detectHardwareProfile();
      var leases = new io.justsearch.app.services.lease.RecordingOperationLeaseService();
      service.setOperationLeaseService(leases);
      var preview = service.previewInstallPlan();
      assertEquals("CPU", preview.downloadProfile);
      String caller = Thread.currentThread().getName();
      var attempt = service.startInstall(true);
      assertEquals(caller, leases.registerThread());
      var result = attempt.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
      assertEquals("completed", result.state, result.errorCode + ": " + result.lastError);
      assertEquals(List.of("register:ai.model-install.core", "release:SUCCESS"), leases.events());
      assertNotEquals(caller, leases.releaseThread());
      assertTrue(result.readyCapabilities.contains("retrieval-core"));
      assertFalse(result.readyCapabilities.contains("llm"));
      var contract = InstallContractIO.read(home);
      assertNotNull(contract);
      Path model = contract.resolveModelPath("embedding", service.modelsDir());
      assertArrayEquals(bytes, Files.readAllBytes(model));
      assertEquals("activation_required", result.phase);
      var candidate = service.prepareInstalledGenerationCandidate().orElseThrow();
      assertEquals(model.getParent().toString(), candidate.candidateSettings().getEmbedOnnxModelPath());
      assertTrue(candidate.generationBoundKeys().contains("justsearch.embed.onnx.model_path"));
      // Restart reconstruction uses the durable contract and retained bytes, not transient status.
      var restarted = spy(new AiInstallService(null, settings, null, null, home));
      doReturn(registry).when(restarted).getManifest();
      assertEquals(candidate.models(), restarted.prepareInstalledGenerationCandidate().orElseThrow().models());
      var chat = preview.components.stream().filter(c -> c.id.equals("chat")).findFirst().orElseThrow();
      assertEquals("unavailable", chat.state);
      assertFalse(chat.unavailableReason.isBlank());
      var runtime = mock(RuntimeActivationService.class);
      when(runtime.getStatus()).thenReturn(new AiRuntimeStatusResponse(null, List.of(), null, List.of()));
      var readiness = new AiPreflightService(service, runtime).getPreflight();
      assertTrue(readiness.packages().stream().noneMatch(AiPreflightService.PackageStatus::blockingIncomplete));
      assertFalse(readiness.canActivateDefault());
      assertFalse(readiness.runtimeInstalled());
      assertFalse(Files.exists(home.resolve("native-bin/llama-server/llama-server")));
    } finally {
      if (config != null) {
        io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(config, previousConfig);
      }
      server.stop(0);
    }
  }

  @Test
  void linuxGpuPreviewAndPreflightExcludeForeignRuntimeAndChat() {
    NativeTargets linux = NativeTargets.forTarget("linux-x86_64");
    var gpu = hardware(12_000_000_000L);
    try (gpu; var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS)) {
      target.when(NativeTargets::current).thenReturn(linux);
      var hardware = HardwareProfile.gpuFull(12_000_000_000L);
      var service = spy(new AiInstallService(null, null, null, null, home));
      // Planner checks do not probe or use a GPU.
      var registry = service.getManifest();
      var preview = service.previewInstallPlan();
      assertEquals("CPU", preview.downloadProfile);
      assertEquals(HardwareProfile.gpuDetectedNoCuda(12_000_000_000L), service.buildHardwareProfile());
      for (String id : List.of("cuda-runtime", "chat")) {
        var component = preview.components.stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow();
        assertEquals("unavailable", component.state, id);
        assertFalse(component.unavailableReason.isBlank());
      }
      var plan = InstallPlanner.plan(registry, hardware, service.modelsDir());
      assertEquals(DownloadProfile.CPU, plan.profile());
      assertTrue(plan.downloads().stream().anyMatch(d -> d.packageId().equals("embedding")));
      var cpu = InstallPlanner.plan(registry, HardwareProfile.cpuOnly(), service.modelsDir());
      assertEquals(cpu.downloads(), plan.downloads(), "Linux ships no CUDA dependency pack");
      assertFalse(plan.downloads().stream().anyMatch(d -> d.packageId().equals("chat")),
          "Linux has no runtime package that can run this chat model");
      assertFalse(InstallPlanner.isIncludedByPlan(registry.findPackage("cuda-runtime"),
          InstallIntent.DEFAULT, hardware), "native-target exclusion must agree with the plan");
      var runtime = mock(RuntimeActivationService.class);
      when(runtime.getStatus()).thenReturn(new AiRuntimeStatusResponse(null, List.of(), null, List.of()));
      var readiness = new AiPreflightService(service, runtime).getPreflight();
      for (String id : List.of("cuda-runtime", "chat")) {
        assertFalse(readiness.packages().stream().filter(p -> p.id().equals(id))
            .findFirst().orElseThrow().blockingIncomplete(), id);
      }
    }
  }

  @Test
  void linuxNativeTargetPredicateMatchesPlanner() {
    NativeTargets linux = NativeTargets.forTarget("linux-x86_64");
    try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS)) {
      target.when(NativeTargets::current).thenReturn(linux);
      var registry = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
      var hardware = HardwareProfile.gpuFull(12_000_000_000L);
      assertFalse(InstallPlanner.isIncludedByPlan(registry.findPackage("cuda-runtime"),
          InstallIntent.DEFAULT, hardware));
    }
  }

  @Test
  void laterLinuxCudaRuntimePackageCanSupplyTheRuntime() {
    NativeTargets linux = NativeTargets.forTarget("linux-x86_64");
    try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS)) {
      target.when(NativeTargets::current).thenReturn(linux);
      var registry = ModelRegistryLoader.loadFromClasspath("ai/linux-gpu-registry.fixture.json");
      var hardware = HardwareProfile.gpuFull(12_000_000_000L);
      var plan = InstallPlanner.plan(registry, hardware, home.resolve("models"), home);
      assertTrue(plan.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));
      assertTrue(AiInstallService.runtimePreconditionMet(false, plan, registry));
      var installed = new InstallPlan(DownloadProfile.GPU_FULL, List.of(), List.of(), 0,
          List.of("cuda-runtime-linux"));
      assertTrue(AiInstallService.runtimePreconditionMet(false, installed, registry));
    }
  }

  @Test
  void windowsInstallStillRestoresAndRefusesAMissingBundledRuntime() throws Exception {
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS);
         var restore = mockStatic(RuntimeRestoreUtil.class)) {
      target.when(NativeTargets::current).thenReturn(windows);
      for (long vram : List.of(-1L, 6_000_000_000L, 12_000_000_000L)) {
        Path installHome = home.resolve("windows-" + vram);
        var gpu = hardware(vram);
        try (gpu) {
          var service = new AiInstallService(null, null, null, null, installHome);
          var run = java.util.Arrays.stream(AiInstallService.class.getDeclaredMethods())
              .filter(m -> m.getName().equals("runInstallInternal")).findFirst().orElseThrow();
          run.setAccessible(true);
          run.invoke(service, new Object[] {null});
          assertEquals("failed", service.getStatus().state);
          assertEquals("RUNTIME_MISSING", service.getStatus().errorCode);
          restore.verify(() -> RuntimeRestoreUtil.ensureRuntimePresent(installHome));
          assertFalse(Files.exists(installHome.resolve(InstallContract.CONTRACT_FILENAME)));
        }
      }
    }
  }

  private static org.mockito.MockedConstruction<GpuCapabilityResolver> hardware(long vram) {
    var effective = new GpuCapabilities.Effective(vram > 0, "fixture", GpuCapabilities.Confidence.HIGH,
        null, null, null, 1, vram, vram, 0L,
        new GpuCapabilities.Cuda(vram > 0, "fixture", GpuCapabilities.Confidence.HIGH));
    return mockConstruction(GpuCapabilityResolver.class,
        (mock, context) -> when(mock.snapshot()).thenReturn(new GpuCapabilities(null, null, effective)));
  }

  @Test
  void windowsPlansRetainTheirPackagesAndRuntimePrecondition() {
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS)) {
      target.when(NativeTargets::current).thenReturn(windows);
      var registry = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
      for (var hardware : List.of(HardwareProfile.cpuOnly(), new HardwareProfile(true, true, 6_000_000_000L),
          HardwareProfile.gpuFull(12_000_000_000L))) {
        var plan = InstallPlanner.plan(registry, hardware, home.resolve("models"));
        var ids = plan.downloads().stream().map(InstallPlan.PlannedDownload::packageId)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> expected = new java.util.HashSet<>(Set.of("embedding", "splade", "reranker", "ner", "citation-scorer"));
        if (hardware.downloadProfile().usesCuda()) expected.add("cuda-runtime");
        if (hardware.downloadProfile().includesGguf()) expected.add("chat");
        assertEquals(expected, ids);
        assertFalse(AiInstallService.runtimePreconditionMet(false, plan, registry));
        assertTrue(AiInstallService.runtimePreconditionMet(true, plan, registry));
      }
    }
  }
}
