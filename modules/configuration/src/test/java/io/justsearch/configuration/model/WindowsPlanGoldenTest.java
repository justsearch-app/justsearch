package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

class WindowsPlanGoldenTest {
  @Test void everyWindowsPlanRetainsBaselineBytes() throws Exception {
    String previous = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Windows 11");
      var registry = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
      var outputs = new ArrayList<InstallPlan>();
      Path home = Path.of("/justsearch-plan-golden");
      for (var hardware : List.of(HardwareProfile.cpuOnly(), HardwareProfile.gpuFull(6_000_000_000L),
          HardwareProfile.gpuFull(12_000_000_000L))) {
        for (var intent : InstallIntent.values()) {
          for (var declined : List.of(Set.<String>of(), Set.of("chat", "reranker", "splade"))) {
            outputs.add(InstallPlanner.plan(registry, hardware, intent, declined, home.resolve("models"), home));
          }
        }
      }
      var json = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
      String homeJson = json.writeValueAsString(home.toAbsolutePath().toString());
      // The fixture pins plan bytes, independent of the filesystem running this Windows-input test.
      String actual = json.writeValueAsString(outputs)
          .replace(homeJson.substring(1, homeJson.length() - 1), "/justsearch-plan-golden")
          .replace("\\\\", "/") + "\n";
      if (Boolean.getBoolean("captureWindowsPlans")) {
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/windows-install-plans.json"), actual);
      } else {
        try (var expected = getClass().getResourceAsStream("/ai/windows-install-plans-9dd16cf565.json")) {
          assertNotNull(expected);
          assertEquals(new String(expected.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), actual);
        }
      }
    } finally { System.setProperty("os.name", previous); }
  }
}
