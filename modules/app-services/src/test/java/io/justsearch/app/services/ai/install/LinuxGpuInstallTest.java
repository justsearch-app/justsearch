package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.GpuCapabilityResolver;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.gpu.GpuCapabilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LinuxGpuInstallTest {
  @TempDir Path home;

  @Test void downloadedRuntimeIsRestoredInPlaceAndRepairUsesIt() throws Exception {
    Path exe = executable(home.resolve("native-bin/llama-server/variants/cuda12/llama-server"));
    var previous = ConfigStore.globalOrNull();
    try {
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().build()));
      assertTrue(RuntimeRestoreUtil.ensureRuntimePresent(home));
      assertEquals("#!/bin/sh\nexit 0\n", Files.readString(exe));
      assertFalse(Files.exists(home.resolve("native-bin/llama-server/llama-server")));
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  @Test void postInstallSelectsDownloadedExecutableThroughSettingsOwner() throws Exception {
    Path exe = executable(home.resolve("native-bin/llama-server/variants/cuda12/llama-server"));
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("settings.json"));
    var initial = store.inspect();
    initial.settings().setLlmModelPath(Files.writeString(home.resolve("chat.gguf"), "model").toString());
    store.replacePrepared(store.prepare(initial.settings(), initial.witness()));
    var config = new ConfigStore(ConfigStoreRebuilder.prepare(store.load()));
    var previous = ConfigStore.globalOrNull();
    try (var fixture = new RuntimeIntentTestFixture(home.resolve("intent"), store, config)) {
      ConfigStore.setGlobal(config);
      var service = new AiInstallService(null, store, null, null, home, null,
          new SettingsServiceImpl(store, fixture.runner()));
      var apply = AiInstallService.class.getDeclaredMethod("applyCudaServerExe");
      apply.setAccessible(true);
      assertEquals(true, apply.invoke(service));
      assertEquals(exe.toString(), store.load().getServerExecutablePath());
      assertEquals(exe, config.get().ai().serverExe());
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  @Test void missingDownloadedExecutableDoesNotBlockPlannedPackRepair() throws Exception {
    Path missing = home.resolve("native-bin/llama-server/variants/cuda12/llama-server");
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("settings.json"));
    // Name the Linux dependency explicitly: on the old loader an unresolved Windows
    // dependency excluded chat, so the broken runtime precondition was never reached.
    Path manifest = home.resolve("repair-registry.json");
    try (var resource = getClass().getResourceAsStream("/ai/linux-gpu-registry.fixture.json")) {
      Files.writeString(manifest, new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          .replace("\"dependsOn\": [\n        \"cuda-runtime\"", "\"dependsOn\": [\n        \"cuda-runtime-linux\""));
    }
    var registry = ModelRegistryLoader.loadFromFile(manifest);
    var previous = ConfigStore.globalOrNull();
    try (var restore = mockStatic(RuntimeRestoreUtil.class);
        var stages = mockConstruction(StagedAcquisition.class,
            (mock, context) -> when(mock.run(anyList(), any())).thenReturn(false))) {
      restore.when(() -> RuntimeRestoreUtil.ensureRuntimePresent(home))
          .thenThrow(new IllegalStateException("Saved downloaded executable is missing"));
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
          .putSettings("justsearch.server.exe", missing.toString()).build()));
      var service = spy(new AiInstallService(null, settings, null, null, home));
      doReturn(registry).when(service).getManifest();
      doReturn(HardwareProfile.gpuFull(12_000_000_000L)).when(service).detectHardwareProfile();
      var preview = service.previewInstallPlan();
      assertTrue(preview.components.stream().anyMatch(pkg -> "chat".equals(pkg.id)
          && "to-download".equals(pkg.state)), "Repair must actually plan the chat runtime consumer");
      var run = java.util.Arrays.stream(AiInstallService.class.getDeclaredMethods())
          .filter(m -> m.getName().equals("runInstallInternal")).findFirst().orElseThrow();
      run.setAccessible(true);
      assertDoesNotThrow(() -> run.invoke(service, new Object[] {null}),
          "Repair must not require the deleted executable before acquiring the planned packs");
      assertEquals(1, stages.constructed().size(), "Pack acquisition must run before requiring the deleted executable");
      restore.verifyNoInteractions();
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }


  @Test void coreStageWaitsForChatModelBeforeCommittingDownloadedExecutable() throws Exception {
    try (var seam = new io.justsearch.app.services.chat.ManagedChatSeam(home.resolve("managed"), 0)) {
      Path exe = home.resolve("native-bin/llama-server/variants/cuda12/llama-server");
      Files.createDirectories(exe.getParent());
      Files.copy(seam.base().serverExecutable(), exe);
      assertTrue(exe.toFile().setExecutable(true, true));
      Path model = home.resolve("not-downloaded-yet.gguf");
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("settings.json"));
      var initial = settings.inspect();
      initial.settings().setChatEnabled(true);
      initial.settings().setGpuLayers(0);
      initial.settings().setLlmModelPath(model.toString());
      settings.replacePrepared(settings.prepare(initial.settings(), initial.witness()));
      var config = new ConfigStore(ConfigStoreRebuilder.prepare(settings.load()));
      ConfigStore.setGlobal(config);
      var budget = new io.justsearch.core.context.RetainedStateBudget();
      budget.declare("attempted-configurations", 1, "test");
      try (var components = new io.justsearch.app.engine.DefaultEngineComponentRegistry(budget, config.publicationLock())) {
        var observation = components.register(new io.justsearch.core.component.ComponentSpec("generative", false,
            java.util.Set.of(), io.justsearch.core.component.ComponentSpec.ComposeCapability.IN_PLACE,
            java.time.Duration.ofMinutes(3), 1));
        var owner = new io.justsearch.app.services.GenerativeSettingsComponentOwner(seam.manager(), observation, home, false);
        var composer = new io.justsearch.app.services.settings.FixedSettingsComponentComposer(components);
        composer.register("generative", owner);
        composer.seal();
        try (var fixture = new RuntimeIntentTestFixture(home.resolve("intent-real-owner"), settings, config, composer)) {
          var writer = new SettingsServiceImpl(settings, fixture.runner());
          var service = new AiInstallService(null, settings, null, null, home, null, writer);
          var apply = AiInstallService.class.getDeclaredMethod("applyCudaServerExe");
          apply.setAccessible(true);
          var once = StagedAcquisition.applyOncePerRun(() -> {
            try { return (boolean) apply.invoke(service); }
            catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
          });
          var witness = settings.inspect().witness();
          var core = ConfigurationStage.forInstall(once, () -> false, () -> false,
              () -> false, () -> true, () -> false, null).apply();
          assertTrue(core.notApplied().contains(ConfigurationStage.CUDA_SERVER_EXE));
          assertEquals(witness, settings.inspect().witness(), "Core must not commit an incomplete chat candidate");
          assertTrue(seam.models().isEmpty());
          // The actual owner still rejects an invalid candidate submitted directly.
          var invalid = settings.inspect();
          invalid.settings().setServerExecutablePath(exe.toString());
          var refusal = writer.applyInternal(invalid.settings(), invalid.witness(),
              io.justsearch.app.services.intent.EngineProvenance.internal("invalid-install-candidate",
                  io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
                  io.justsearch.core.context.EngineContext.Urgency.BACKGROUND));
          assertFalse(refusal.response().success());
          assertEquals("INVALID_CONFIG", refusal.response().errorDetails().get("reason"));
          assertEquals(witness, settings.inspect().witness());
          Files.writeString(model, "chat stage acquired model");
          var chat = ConfigurationStage.forInstall(once, () -> false, () -> false,
              () -> false, () -> true, () -> false, null).apply();
          assertTrue(chat.applied().contains(ConfigurationStage.CUDA_SERVER_EXE));
          assertEquals(exe.toString(), settings.load().getServerExecutablePath());
          assertEquals(exe, seam.manager().currentConfig().serverExecutable());
          assertEquals(List.of(model.getFileName().toString()), seam.models());
        }
      }
    }
  }


  @Test void linuxRuntimeAcquisitionDoesNotMutateRestartRequiredNativePath() throws Exception {
    String key = "justsearch.onnxruntime.native_path";
    String before = System.getProperty(key);
    var previous = ConfigStore.globalOrNull();
    try {
      System.clearProperty(key);
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().build()));
      Path pack = Files.createDirectories(home.resolve("native-bin/llama-server/variants/cuda12"));
      for (String name : List.of("cudart", "cublas", "cublasLt")) {
        Files.writeString(pack.resolve(io.justsearch.configuration.NativeTargets.current().libraryName(name)), "library");
      }
      var service = new AiInstallService(null, null, null, null, home);
      var apply = AiInstallService.class.getDeclaredMethod("applyOrtNativePath");
      apply.setAccessible(true);
      assertEquals(false, apply.invoke(service));
      assertNull(System.getProperty(key), "Installing the pack must not drift a restart-required config source");
    } finally {
      if (before == null) System.clearProperty(key); else System.setProperty(key, before);
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test void linuxGpuRequiresDriver570OrNewer() {
    var service = new AiInstallService(null, null, null, null, home);
    for (Integer driver : new Integer[] {null, 569, 570, 580}) {
      var effective = new GpuCapabilities.Effective(true, "fixture", GpuCapabilities.Confidence.HIGH,
          driver == null ? null : driver + ".0", driver, 0, 1, 12_000_000_000L, 10_000_000_000L, 0L,
          new GpuCapabilities.Cuda(true, "fixture", GpuCapabilities.Confidence.HIGH));
      try (var gpu = mockConstruction(GpuCapabilityResolver.class,
          (mock, context) -> when(mock.snapshot()).thenReturn(new GpuCapabilities(null, null, effective)))) {
        assertEquals(driver != null && driver >= 570, service.detectHardwareProfile().cudaFunctional());
      }
    }
  }

  @Test void existingInstallContractAndActivationSettingsRoundTripWithoutMigration() throws Exception {
    var installed = new InstallContract(2, 1700000000000L, HardwareProfile.gpuFull(12_000_000_000L),
        DownloadProfile.GPU_FULL, Map.of("cuda-runtime", new InstallContract.InstalledModel(
            "cuda-runtime", null, null, null, "cuda12", null, List.of("kept.zip"), false, null),
            "chat", new InstallContract.InstalledModel("chat", "chat.gguf", ModelPrecision.GGUF,
                ExecutionProvider.LLAMA_SERVER, "", "abc", List.of("chat.gguf"), false, null)),
        home.resolve("models"), InstallIntent.DEFAULT);
    InstallContractIO.write(installed, home);
    var read = InstallContractIO.read(home);
    assertEquals(installed, read);
    InstallContractIO.write(read, home);
    assertEquals(installed, InstallContractIO.read(home));
    var settingsPath = home.resolve("settings.json");
    Files.writeString(settingsPath, """
        {"chatEnabled":true,"gpuLayers":99,"serverExecutablePath":"/existing/operator/llama-server",
         "llmModelPath":"/existing/models/chat.gguf","declinedAiPackages":["reranker"]}
        """);
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
    var first = settings.inspect();
    settings.replacePrepared(settings.prepare(first.settings(), first.witness()));
    UiSettings reloaded = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath).load();
    assertTrue(reloaded.getChatEnabled());
    assertEquals(99, reloaded.getGpuLayers());
    assertEquals("/existing/operator/llama-server", reloaded.getServerExecutablePath());
    assertEquals(first.settings().getLlmModelPath(), reloaded.getLlmModelPath());
    assertEquals(List.of("reranker"), reloaded.getDeclinedAiPackages());
  }

  private Path executable(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "#!/bin/sh\nexit 0\n");
    assertTrue(path.toFile().setExecutable(true, true));
    return path;
  }
}
