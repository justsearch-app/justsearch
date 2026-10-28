/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.inference.LlamaDevicePlan;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.gpu.GpuCapabilityResolver;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.model.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.gpu.GpuCapabilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class AiInstallServicePolicyPreservationTest {
  @TempDir Path home;

  @Test
  void noPolicyPreviewAndNormalizedContractKeepAllThreeProfiles() throws Exception {
    // This preservation check describes the Windows runtime plans. Linux has no CUDA pack yet.
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS)) {
      target.when(NativeTargets::current).thenReturn(windows);
      var mapper = JsonMapper.builder().build();
      for (long vram : new long[] {12_000_000_000L, 6_000_000_000L, -1}) {
        var facts = new GpuCapabilities.Effective(vram > 0, "fixture", GpuCapabilities.Confidence.HIGH,
            null, null, null, 1, vram, vram, 0L,
            new GpuCapabilities.Cuda(vram > 0, "fixture", GpuCapabilities.Confidence.HIGH));
        try (var _ = mockConstruction(GpuCapabilityResolver.class,
            (mock, context) -> when(mock.snapshot()).thenReturn(new GpuCapabilities(null, null, facts)))) {
          var service = new AiInstallService(null, null, null, null, home);
          var profile = service.buildHardwareProfile();
          var preview = service.previewInstallPlan();
          assertEquals(vram < 0 ? "CPU" : vram > 7_500_000_000L ? "GPU_FULL" : "GPU_LITE",
              preview.downloadProfile);
          assertTrue(preview.components.stream().noneMatch(c -> c.unavailableReason != null
              && c.unavailableReason.contains("administrator policy")));
          var registry = service.getManifest();
          var plan = InstallPlanner.plan(registry, profile, InstallIntent.FULL_DESKTOP,
              java.util.Set.of(), home.resolve("models"), home);
          var contract = service.buildContract(plan, registry, profile);
          assertEquals(profile.downloadProfile(), contract.downloadProfile());
          // Compare these outputs between the base and candidate, excluding only clock and fixture root.
          var normalized = new InstallContract(contract.schemaVersion(), 0, contract.hardwareProfile(),
              contract.downloadProfile(), contract.models(), null, contract.installIntent());
          Path output = Path.of("build/policy-preservation/" + preview.downloadProfile);
          Files.createDirectories(output);
          Files.writeString(output.resolve("preview.json"),
              mapper.writeValueAsString(preview).replace(home.toString().replace("\\", "\\\\"), "<home>"));
          InstallContractIO.write(normalized, output);
        }
      }
    }
  }

  @Test
  void savedCudaPreferencesSurviveInstallSelectionAndReloadWithCpuDevicePlan() throws Exception {
    Path saved = home.resolve("ui/settings.json");
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, saved);
    Path cudaExe = home.resolve("native-bin/llama-server/variants/cuda12/llama-server.exe");
    Files.createDirectories(cudaExe.getParent());
    Files.writeString(cudaExe, "retained executable");
    var settings = new UiSettings();
    settings.setServerExecutablePath(cudaExe.toString());
    settings.setGpuLayers(99);
    store.replacePrepared(store.prepareExact(settings, new SettingsWitness(0, null)));
    byte[] original = Files.readAllBytes(saved);
    var previous = ConfigStore.globalOrNull();
    NativeTargets windows = NativeTargets.forTarget("windows-x86_64");
    try (var targets = mockStatic(NativeTargets.class, CALLS_REAL_METHODS);
         var platform = mockStatic(PlatformPaths.class, CALLS_REAL_METHODS)) {
      targets.when(NativeTargets::current).thenReturn(windows);
      platform.when(PlatformPaths::isWindows).thenReturn(true);
      for (int restart = 0; restart < 3; restart++) {
        var loaded = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, saved).load();
        var builder = ResolvedConfig.builder();
        ConfigStoreRebuilder.contributeUiSettings(builder, loaded);
        builder.putDefault("policy.gpu_acceleration_enabled", Boolean.toString(restart == 0));
        var resolved = builder.build();
        ConfigStore.setGlobal(new ConfigStore(resolved));
        var service = new AiInstallService(null, store, null,
            restart == 0 ? null : () -> new io.justsearch.app.api.EffectivePolicy(true, true, true, false, false,
                List.of(), List.of(), "test", false, null, null, false), home);
        var select = AiInstallService.class.getDeclaredMethod("applyCudaServerExe");
        select.setAccessible(true);
        assertEquals(false, select.invoke(service));
        assertEquals(cudaExe.toString(), loaded.getServerExecutablePath());
        assertEquals(99, loaded.getGpuLayers());
        assertArrayEquals(original, Files.readAllBytes(saved));
        if (restart == 0) continue; // P7: no policy preserves the same saved settings bytes.
        var device = LlamaDevicePlan.resolve(resolved, loaded.getGpuLayers(), Optional.empty());
        assertEquals(List.of("-ngl", "0"), device.layerFlags());
        assertEquals(List.of("--device", "none"), device.deviceFlags());
        assertEquals(List.of("--no-mmproj-offload"), device.projectorFlags());
        assertEquals(Map.of("CUDA_VISIBLE_DEVICES", "-1"), device.environment());
        assertArrayEquals(original, Files.readAllBytes(saved));
      }
    } finally {
      ConfigStore.restoreGlobal(ConfigStore.globalOrNull(), previous);
    }
    Path output = Path.of("build/policy-preservation");
    Files.createDirectories(output);
    Files.writeString(output.resolve("settings.json"), Files.readString(saved)
        .replace(home.toString().replace("\\", "\\\\"), "<home>"));
    assertEquals("retained executable", Files.readString(cudaExe));
  }
}
