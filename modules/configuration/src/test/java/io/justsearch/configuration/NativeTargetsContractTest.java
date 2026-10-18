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
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("contracts/native-targets/spec.v1.json"))) {
      root = root.getParent();
      assertNotNull(root, "Repository contract must be available to this test");
    }
    byte[] source = Files.readAllBytes(root.resolve("contracts/native-targets/spec.v1.json"));
    try (var in = NativeTargets.class.getResourceAsStream("/contracts/native-targets/spec.v1.json")) {
      assertNotNull(in);
      assertArrayEquals(source, in.readAllBytes());
    }
    var json = JsonMapper.builder().build();
    var spec = json.readTree(source);
    Map<String, Object> observed = new LinkedHashMap<>();
    for (var fixture : spec.path("fixtures")) {
      String target = fixture.path("target").asText();
      var reader = NativeTargets.forTarget(target);
      Map<String, Map<String, String>> names = new LinkedHashMap<>();
      for (String group : java.util.List.of("executables", "libraries", "ortResources")) {
        Map<String, String> actual = new LinkedHashMap<>();
        for (var entry : fixture.path(group).properties()) {
          String id = entry.getKey();
          String value = switch (group) {
            case "executables" -> reader.executableName(id);
            case "libraries" -> reader.libraryName(id);
            default -> reader.ortResource(id);
          };
          assertEquals(entry.getValue().asText(), value, target + "/" + id);
          actual.put(id, value);
        }
        names.put(group, actual);
      }
      observed.put(target, names);
    }
    Path report = root.resolve("modules/configuration/build/native-targets-fixtures.json");
    Files.createDirectories(report.getParent());
    Files.writeString(report, json.writeValueAsString(observed));
  }

  @Test void aliasesAndUnsupportedNames() {
    assertEquals("llama-server.exe", NativeTargets.forPlatform("Windows 11", "amd64").executableName("llama-server"));
    assertEquals("libcuda.so.1", NativeTargets.forPlatform("Linux", "amd64").libraryName("cuda"));
    assertThrows(IllegalArgumentException.class, () -> NativeTargets.forPlatform("macos", "aarch64"));
    assertThrows(IllegalArgumentException.class, () -> NativeTargets.current().executableName("unknown"));
  }
}
