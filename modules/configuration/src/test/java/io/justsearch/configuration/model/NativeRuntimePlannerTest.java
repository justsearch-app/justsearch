package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeRuntimePlannerTest {
  @TempDir Path root;

  private ModelRegistry registry(String target) throws Exception {
    Path file = root.resolve("registry.json");
    Files.writeString(file, """
        {"schemaVersion":2,"packages":[
          {"id":"native-runtime","label":"Runtime","tier":"runtime","targetDir":"runtime","minVramBytes":0,
           "nativeTarget":"%s","supportingFiles":[
             {"filename":"runtime.zip","sizeBytes":100,"sha256":"abc","downloadUrl":"https://example/runtime.zip","extract":true}]},
          {"id":"portable-model","label":"Model","tier":"retrieval-core","targetDir":"model","minVramBytes":0,
           "supportingFiles":[{"filename":"tokenizer.json","sizeBytes":10,"sha256":"def","downloadUrl":"https://example/tokenizer.json"}]}
        ]}
        """.formatted(target));
    return ModelRegistryLoader.loadFromFile(file);
  }

  @Test void declaredTargetSelectsRuntimeWhileModelsStayPortable() throws Exception {
    String previous = System.getProperty("os.name");
    String arch = System.getProperty("os.arch");
    try {
      System.setProperty("os.arch", "amd64");
      for (String os : List.of("Linux", "Windows 11")) {
        System.setProperty("os.name", os);
        var plan = InstallPlanner.plan(registry("windows-x86_64"), HardwareProfile.gpuFull(12_000_000_000L), root.resolve("models"));
        assertTrue(plan.downloads().stream().anyMatch(d -> d.packageId().equals("portable-model")));
        assertEquals(os.startsWith("Windows"), plan.downloads().stream().anyMatch(d -> d.packageId().equals("native-runtime")));
        assertEquals(os.equals("Linux"), plan.skipped().stream().anyMatch(p -> p.packageId().equals("native-runtime")));
        assertFalse(plan.alreadyInstalled().contains("native-runtime"));
      }
    } finally {
      System.setProperty("os.name", previous);
      System.setProperty("os.arch", arch);
    }
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void shippedLinuxRegistryUsesCpuModelsAndOmitsForeignRuntime() {
    var registry = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    var plan = InstallPlanner.plan(registry, HardwareProfile.gpuFull(12_000_000_000L), root.resolve("models"));
    assertNull(registry.findPackage("cuda-runtime-linux"), "Unpublished Linux packs must not ship");
    assertEquals(DownloadProfile.CPU, plan.profile());
    assertTrue(plan.downloads().stream().anyMatch(d -> d.packageId().equals("embedding") && d.isModelVariant()));
    assertFalse(plan.downloads().stream().anyMatch(d -> d.packageId().equals("cuda-runtime")));
    assertFalse(plan.alreadyInstalled().contains("cuda-runtime"));
    assertFalse(plan.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));
    assertFalse(InstallPlanner.isIncludedByPlan(registry.findPackage("chat"), registry,
        InstallIntent.DEFAULT, java.util.Set.of(), HardwareProfile.gpuFull(12_000_000_000L)));
    assertEquals(ExecutionProvider.CPU, registry.findPackage("embedding").selectVariant(plan.profile()).targetEP());
    var cpu = InstallPlanner.plan(registry, HardwareProfile.cpuOnly(), root.resolve("models"));
    assertEquals(cpu.downloads(), plan.downloads());
    assertTrue(registry.findPackage("embedding").dependsOn().contains("cuda-runtime"));
    assertTrue(plan.skipped().stream().anyMatch(s -> s.packageId().equals("cuda-runtime") && s.cause() == SkipCause.PLATFORM));
  }

  @Test void invalidDeclaredTargetFailsClosed() {
    assertThrows(IllegalArgumentException.class, () -> registry("unknown-x86_64"));
  }
}
