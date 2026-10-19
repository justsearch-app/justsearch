package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NativeTargetsContractTest {
  @Test void packagedBytesAndEveryNameMatchSharedFixtures() throws Exception {
    Path root = repositoryRoot();
    byte[] source = Files.readAllBytes(root.resolve("contracts/native-targets/spec.v1.json"));
    try (var in = NativeTargets.class.getResourceAsStream("/contracts/native-targets/spec.v1.json")) {
      assertNotNull(in);
      assertArrayEquals(source, in.readAllBytes());
    }
    var json = JsonMapper.builder().build();
    var spec = json.readTree(source);
    Map<String, Object> observed = new LinkedHashMap<>();
    for (var targetEntry : spec.path("targets").properties()) {
      String target = targetEntry.getKey();
      var fixture = targetEntry.getValue();
      var reader = NativeTargets.forTarget(target);
      Map<String, Object> names = new LinkedHashMap<>();
      for (String group : java.util.List.of("executables", "libraries", "ortResources", "paths")) {
        Map<String, String> actual = new LinkedHashMap<>();
        for (var entry : fixture.path(group).properties()) {
          String id = entry.getKey();
          String value = switch (group) {
            case "executables" -> reader.executableName(id);
            case "libraries" -> reader.libraryName(id);
            case "ortResources" -> reader.ortResource(id);
            default -> reader.relativePath(id);
          };
          assertEquals(entry.getValue().asText(), value, target + "/" + id);
          actual.put(id, value);
        }
        names.put(group, actual);
      }
      assertEquals(fixture.path("capabilities").path("runtimePacks").asBoolean(), reader.supportsRuntimePacks());
      assertEquals(fixture.path("capabilities").path("cpuBaselineRuntime").asBoolean(), reader.hasCpuBaselineRuntime());
      names.put("capabilities", Map.of("runtimePacks", reader.supportsRuntimePacks(), "cpuBaselineRuntime", reader.hasCpuBaselineRuntime()));
      observed.put(target, names);
    }
    Path report = root.resolve("modules/configuration/build/native-targets-fixtures.json");
    Files.createDirectories(report.getParent());
    Files.writeString(report, json.writeValueAsString(observed));
  }

  @Test void windowsNamesMatchIndependentPrePortGolden() throws Exception {
    Path root = repositoryRoot();
    var json = JsonMapper.builder().build();
    var golden = json.readTree(Files.readAllBytes(root.resolve("contracts/native-targets/windows.pre-port.golden.json")));
    var descriptor = json.readTree(Files.readAllBytes(root.resolve("contracts/native-targets/spec.v1.json")));
    assertEquals(golden, descriptor.path("targets").path("windows-x86_64"),
        "Windows literals from 37e0957c9 must stay unchanged independently of the descriptor");
  }

  private static Path repositoryRoot() {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("contracts/native-targets/spec.v1.json"))) {
      root = root.getParent();
      assertNotNull(root, "Repository contract must be available to this test");
    }
    return root;
  }

  @Test void aliasesAndUnsupportedNames() {
    assertEquals("llama-server.exe", NativeTargets.forPlatform("Windows 11", "amd64").executableName("llama-server"));
    assertEquals("libcuda.so.1", NativeTargets.forPlatform("Linux", "amd64").libraryName("cuda"));
    assertThrows(IllegalArgumentException.class, () -> NativeTargets.forPlatform("macos", "aarch64"));
    assertThrows(IllegalArgumentException.class, () -> NativeTargets.current().executableName("unknown"));
  }
}
