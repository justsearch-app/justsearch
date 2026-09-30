/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import tools.jackson.databind.ObjectMapper;

@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ_WRITE)
final class EngineResourcePolicyTest {
  @Test
  void everyRetainedKindNamesItsActualFutureProducer() {
    var policy = EngineResourcePolicy.load();
    assertEquals(Set.of("search-cursors", "pinned-readers", "representation-generations",
        "co-resident-encoders", "attempted-configurations"),
        policy.retained().snapshot().stream().map(s -> s.kind()).collect(Collectors.toSet()));
    for (var entry : policy.retained().snapshot()) {
      assertTrue(entry.cap() > 0);
      assertNull(entry.count(), "No retained producer is connected in C1");
      assertEquals(entry.kind().equals("search-cursors") || entry.kind().equals("pinned-readers")
          ? "D2" : "D1", entry.awaitingProducer());
    }
    assertEquals(4, policy.retained().snapshot().stream()
        .filter(s -> s.kind().equals("search-cursors")).findFirst().orElseThrow().perContextCap());
  }

  @Test
  void malformedRetainedRegisterIsRejectedAtLoad() throws IOException {
    String source = Files.readString(repoRoot().resolve("governance/retained-state.v1.json"));
    var mapper = new ObjectMapper();
    for (String invalid : new String[] {
        source.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.5"),
        source.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
        source.replace("\"retained\":", "\"missing\":"),
        source.replace("\"pinned-readers\"", "\"unknown-kind\""),
        source.replace("\"cap\": 34", "\"cap\": 34.5"),
        source.replace("\"perContextCap\": 4", "\"perContextCap\": \"4\"")}) {
      assertThrows(IllegalStateException.class, () -> EngineResourcePolicy.parse(mapper.readTree(invalid)));
    }
  }

  @Test
  void packagedPolicyIsTheRegisterAndMemoryFlagsMatchIt() throws IOException {
    Path root = repoRoot();
    byte[] source = Files.readAllBytes(root.resolve("governance/retained-state.v1.json"));
    try (var input = EngineResourcePolicy.class.getResourceAsStream("/engine/retained-state.v1.json")) {
      assertNotNull(input);
      org.junit.jupiter.api.Assertions.assertArrayEquals(source, input.readAllBytes());
    }
    var json = new ObjectMapper().readTree(source);
    String flag = "-XX:MaxDirectMemorySize=" + json.path("policy").path("directMemoryMiB").asInt() + "m";
    assertTrue(Files.readString(root.resolve("scripts/dev/dev-runner.cjs")).contains(flag));
    assertTrue(Files.readString(root.resolve("modules/shell/src-tauri/src/lib.rs"))
        .contains(".arg(\"" + flag + "\")"));
    assertTrue(Files.readString(root.resolve("scripts/dev/test-dev-runner-head-java-opts.mjs")).contains(flag));
  }

  @Test
  void aggregateOverrideUsesCanonicalEntryAndPreservesOtherPolicy() {
    assertEquals(
        "justsearch.engine.admission.aggregate_limit",
        EnvRegistry.ENGINE_ADMISSION_AGGREGATE_LIMIT.sysProp());
    assertEquals(
        "JUSTSEARCH_ENGINE_ADMISSION_AGGREGATE_LIMIT",
        EnvRegistry.ENGINE_ADMISSION_AGGREGATE_LIMIT.envVar());

    withAggregateOverride("7", () -> {
      var policy = EngineResourcePolicy.load();
      assertEquals(7, policy.execution().get("aggregateLimit"));
      assertEquals(16, policy.execution().get("perContextLimit"));
      assertEquals(1, policy.execution().get("retryAfterSeconds"));
      assertEquals(256, policy.execution().get("timerRegistrations"));
      assertEquals(5, policy.retained().snapshot().size());
    });
  }

  @Test
  void absentAggregateOverrideUsesPackagedDefault() {
    withAggregateOverride(null, () ->
        assertEquals(64, EngineResourcePolicy.load().execution().get("aggregateLimit")));
  }

  @Test
  void aggregateOverrideRejectsMalformedAndOutOfRangeValues() {
    for (String invalid : new String[] {"not-a-number", "0", "-1", "65"}) {
      withAggregateOverride(
          invalid,
          () -> assertThrows(IllegalStateException.class, EngineResourcePolicy::load));
    }
  }

  private static Path repoRoot() {
    for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
      if (Files.isRegularFile(path.resolve("governance/retained-state.v1.json"))) return path;
    }
    throw new IllegalStateException("Repository root not found");
  }

  private static void withAggregateOverride(String value, Runnable assertion) {
    String key = EnvRegistry.ENGINE_ADMISSION_AGGREGATE_LIMIT.sysProp();
    String original = System.getProperty(key);
    try {
      if (value == null) System.clearProperty(key);
      else System.setProperty(key, value);
      assertion.run();
    } finally {
      if (original == null) System.clearProperty(key);
      else System.setProperty(key, original);
    }
  }
}
