/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.policy;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AppUpdatePolicyTest {
  @TempDir Path root;
  private String previousGpu;
  private String previousExternal;

  @org.junit.jupiter.api.BeforeEach
  void savePolicyProperties() {
    previousGpu = System.getProperty("policy.gpu_acceleration_enabled");
    previousExternal = System.getProperty("justsearch.policy.disallowExternalInferenceServers");
  }

  @org.junit.jupiter.api.AfterEach
  void restorePolicyProperties() {
    restore("policy.gpu_acceleration_enabled", previousGpu);
    restore("justsearch.policy.disallowExternalInferenceServers", previousExternal);
  }
  private static final ObjectMapper JSON = new ObjectMapper();

  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  @Test
  void sharedPolicyFixturesAgreeWithShell() throws Exception {
    try (var input = getClass().getResourceAsStream("/policy/app-update-policy-cases.json")) {
      for (JsonNode fixture : JSON.readTree(input)) {
        Path dir = Files.createDirectory(root.resolve(Integer.toString(fixture.hashCode())));
        Path machine = materialize(dir.resolve("machine"), fixture.get("machine"), fixture.get("machineKind").asText());
        Path user = materialize(dir.resolve("user"), fixture.get("user"), fixture.get("userKind").asText());
        try {
          var effective = new EnterprisePolicyServiceImpl().snapshot(machine, user);
          JsonNode wire = JSON.valueToTree(effective);
          assertTrue(wire.has("appUpdatesEnabled"), fixture.get("name").asText() + ": effective API field absent");
          assertEquals(fixture.get("allowed").asBoolean(), wire.get("appUpdatesEnabled").asBoolean(), fixture.get("name").asText());
        } finally {
          // Restore fixture permissions so the test's temporary directory can be cleaned.
          for (Path path : new Path[] {dir.resolve("machine"), dir.resolve("user")}) {
            if (Files.exists(path)) path.toFile().setReadable(true, true);
          }
        }
      }
    }
  }

  @Test
  void machineNonregularPathsFailClosed() throws Exception {
    Path directory = Files.createDirectory(root.resolve("machine"));
    var service = new EnterprisePolicyServiceImpl();
    var effective = service.snapshot(directory, root.resolve("user"));
    assertTrue(effective.machine().present());
    assertFalse(effective.machine().loaded());
    assertFalse(effective.downloadsEnabled());
  }

  @Test
  void policyReloadAndAllowlistAppendPreserveRestriction() throws Exception {
    Path user = root.resolve("policy.v1.json");
    Path machine = root.resolve("absent-machine");
    var service = new EnterprisePolicyServiceImpl();
    Files.writeString(user, "{\"schemaVersion\":1,\"appUpdatesEnabled\":false}");
    var denied = service.snapshot(machine, user);
    assertFalse(JSON.valueToTree(denied).path("appUpdatesEnabled").asBoolean(true));
    UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(denied, "a".repeat(64));
    assertFalse(JSON.readTree(Files.readString(user)).path("appUpdatesEnabled").asBoolean(true));
    assertFalse(JSON.valueToTree(service.snapshot(machine, user)).path("appUpdatesEnabled").asBoolean(true));
    Files.writeString(user, "{\"schemaVersion\":1,\"appUpdatesEnabled\":true}");
    assertTrue(JSON.valueToTree(service.snapshot(machine, user)).path("appUpdatesEnabled").asBoolean());
  }

  @Test
  void aiDisabledOverrideDoesNotDisableAppUpdates() throws Exception {
    var previous = ConfigStore.globalOrNull();
    String previousGpu = System.getProperty("policy.gpu_acceleration_enabled");
    String previousExternal = System.getProperty("justsearch.policy.disallowExternalInferenceServers");
    try {
      ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of("justsearch.ai.disabled", "true"))));
      var effective = new EnterprisePolicyServiceImpl().snapshot(null, root.resolve("absent"));
      assertFalse(effective.downloadsEnabled());
      assertTrue(JSON.valueToTree(effective).path("appUpdatesEnabled").asBoolean());
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
      restore("policy.gpu_acceleration_enabled", previousGpu);
      restore("justsearch.policy.disallowExternalInferenceServers", previousExternal);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }

  private static Path materialize(Path path, JsonNode contents, String kind) throws Exception {
    switch (kind) {
      case "utf16le" -> Files.write(path, contents.asText().getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
      case "utf16be" -> Files.write(path, contents.asText().getBytes(java.nio.charset.StandardCharsets.UTF_16BE));
      case "utf32le" -> Files.write(path, contents.asText().getBytes(java.nio.charset.Charset.forName("UTF-32LE")));
      case "utf32be" -> Files.write(path, contents.asText().getBytes(java.nio.charset.Charset.forName("UTF-32BE")));
      case "directory" -> Files.createDirectory(path);
      case "dangling" -> Files.createSymbolicLink(path, path.resolveSibling("missing"));
      case "dangling-parent" -> {
        Files.createSymbolicLink(path, path.resolveSibling("missing"));
        return path.resolve("policy.v1.json");
      }
      case "inaccessible" -> {
        // A regular file as parent guarantees an attribute-lookup error, including under root.
        Files.writeString(path, "not a directory");
        return path.resolve("policy.v1.json");
      }
      default -> {
        if (!contents.isNull()) Files.writeString(path, contents.asText());
        if (kind.equals("unreadable")) {
          Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
          assertFalse(Files.isReadable(path), "fixture requires an unprivileged POSIX test process");
        }
      }
    }
    return path;
  }
}
