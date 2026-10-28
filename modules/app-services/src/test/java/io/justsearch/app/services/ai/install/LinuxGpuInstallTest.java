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
    var registry = ModelRegistryLoader.loadFromClasspath("ai/linux-gpu-registry.fixture.json");
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
      var run = java.util.Arrays.stream(AiInstallService.class.getDeclaredMethods())
          .filter(m -> m.getName().equals("runInstallInternal")).findFirst().orElseThrow();
      run.setAccessible(true);
      run.invoke(service, new Object[] {null});
      assertEquals(1, stages.constructed().size(), "Pack acquisition must run before requiring the deleted executable");
      restore.verifyNoInteractions();
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
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
