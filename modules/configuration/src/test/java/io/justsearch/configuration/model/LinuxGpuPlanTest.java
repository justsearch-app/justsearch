package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.NativeTargets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxGpuPlanTest {
  @TempDir Path root;

  private ModelRegistry registry(boolean published) throws Exception {
    if (published) return ModelRegistryLoader.loadFromClasspath("ai/linux-gpu-registry.fixture.json");
    Path json = root.resolve("registry.json");
    try (var input = getClass().getResourceAsStream("/ai/linux-gpu-registry.fixture.json")) {
      assertNotNull(input);
      Files.writeString(json, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          .replace("\"sizeBytes\": 100", "\"sizeBytes\": 0")
          .replace("https://example/gpu.zip", "https://not-yet-published.invalid/gpu.zip"));
    }
    return ModelRegistryLoader.loadFromFile(json);
  }

  @Test void publishedLinuxRuntimeEnablesGpuAndPreviewMatchesEveryPlanGate() throws Exception {
    String previous = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Linux");
      assertTrue(NativeTargets.current().supportsRuntimePacks());
      assertFalse(NativeTargets.current().hasCpuBaselineRuntime());
      var registry = registry(true);
      assertNull(registry.findPackage("cuda-runtime"));
      assertEquals(List.of("cuda-runtime-linux"), registry.findPackage("chat").dependsOn());
      for (var hardware : List.of(HardwareProfile.cpuOnly(), HardwareProfile.gpuFull(6_000_000_000L),
          HardwareProfile.gpuFull(12_000_000_000L))) {
        for (var intent : InstallIntent.values()) {
          var plan = InstallPlanner.plan(registry, hardware, intent, Set.of(), root.resolve("models"), root);
          for (var pkg : registry.packages()) {
            boolean selected = plan.downloads().stream().anyMatch(d -> d.packageId().equals(pkg.id()))
                || plan.alreadyInstalled().contains(pkg.id());
            assertEquals(selected, InstallPlanner.isIncludedByPlan(pkg, registry, intent, Set.of(), hardware),
                pkg.id() + ": " + hardware + "/" + intent);
          }
        }
      }
      var full = InstallPlanner.plan(registry, HardwareProfile.gpuFull(12_000_000_000L), root.resolve("models"));
      assertEquals(DownloadProfile.GPU_FULL, full.profile());
      assertTrue(full.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));
    } finally { System.setProperty("os.name", previous); }
  }

  @Test void unpublishedLinuxRuntimeKeepsCpuSearchAndChatUnavailable() throws Exception {
    String previous = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Linux");
      var registry = registry(false);
      var hardware = HardwareProfile.gpuFull(12_000_000_000L);
      var plan = InstallPlanner.plan(registry, hardware, root.resolve("models"));
      assertEquals(DownloadProfile.CPU, plan.profile());
      assertEquals(List.of("search"), plan.downloads().stream().map(InstallPlan.PlannedDownload::packageId).toList());
      for (String id : List.of("chat", "cuda-runtime-linux")) {
        assertFalse(InstallPlanner.isIncludedByPlan(registry.findPackage(id), registry, InstallIntent.DEFAULT, Set.of(), hardware));
        assertTrue(plan.skipped().stream().anyMatch(s -> s.packageId().equals(id) && s.cause() == SkipCause.PLATFORM));
      }
    } finally { System.setProperty("os.name", previous); }
  }
}
