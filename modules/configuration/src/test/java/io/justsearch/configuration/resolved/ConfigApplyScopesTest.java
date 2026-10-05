/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

final class ConfigApplyScopesTest {

  @Test
  void packagedRegisterIsTheRepositoryRegister() throws IOException {
    byte[] packaged;
    try (InputStream input = getClass().getClassLoader()
        .getResourceAsStream(ConfigApplyScopes.RESOURCE_PATH)) {
      assertNotNull(input);
      packaged = input.readAllBytes();
    }
    assertArrayEquals(
        Files.readAllBytes(findRepositoryRoot().resolve(ConfigApplyScopes.RESOURCE_PATH)), packaged);
    assertEquals(280, ConfigApplyScopes.register().size());
    assertEquals("component:index", ConfigApplyScopes.scopeFor("index.boosts").wireValue());
  }

  @Test
  void classifiesEffectiveValueChangesAndLeavesSourceOnlyChangesAsNoOp() {
    ResolvedConfig before = config(
        "index.boosts", "{\"a\":1}",
        "index.vector.hnsw.m", "16",
        "justsearch.api.port", "8080",
        "justsearch.qu.enabled", "false");
    ResolvedConfig sameValueDifferentSource = config(
        "index.boosts", "{\"a\":1}",
        "index.vector.hnsw.m", "16",
        "justsearch.api.port", "8080",
        "justsearch.qu.enabled", "false");
    assertTrue(ConfigApplyScopes.classify(before, sameValueDifferentSource).isNoOp());

    ResolvedConfig after = config(
        "index.boosts", "{\"b\":1}",
        "index.vector.hnsw.m", "32",
        "justsearch.api.port", "9090",
        "justsearch.qu.enabled", "true");
    ConfigApplyScopes.ChangedKeys changed = ConfigApplyScopes.classify(before, after);
    assertEquals(java.util.Set.of("index.boosts"), changed.component().get("index"));
    assertEquals(java.util.Set.of("index.vector.hnsw.m"), changed.generationBound());
    assertEquals(java.util.Set.of("justsearch.api.port"), changed.restartRequired());
    assertEquals(java.util.Set.of("justsearch.qu.enabled"), changed.hot());
    assertEquals(4, changed.all().size());
    assertFalse(changed.isNoOp());
  }

  @Test
  void changingSnapshotBoundQueryThresholdRequiresRestart() {
    var changed = ConfigApplyScopes.classify(
        config("index.hybrid.vector_skip_min_chars", "4"),
        config("index.hybrid.vector_skip_min_chars", "100"));
    assertEquals(java.util.Set.of("index.hybrid.vector_skip_min_chars"),
        changed.restartRequired());
    assertTrue(changed.hot().isEmpty());
    assertTrue(changed.component().isEmpty());
  }

  @Test
  void sharedGpuPolicyRequiresRestartInsteadOfQueryOwnerComposition() {
    String key = "policy.gpu_acceleration_enabled";
    var changed = ConfigApplyScopes.classify(config(key, "true"), config(key, "false"));
    assertEquals(java.util.Set.of(key), changed.restartRequired());
    assertTrue(changed.component().isEmpty());
    assertTrue(changed.hot().isEmpty());
    assertTrue(changed.generationBound().isEmpty());
  }

  @Test
  void indexPathRetentionKeepsServingValueAndTraceWhilePublishingOtherValues() {
    var serving = config("justsearch.index.base_path", "index-a", "justsearch.api.port", "8080");
    var desired = config("justsearch.index.base_path", "index-b", "justsearch.api.port", "9090");
    var retained = desired.retainingIndexBasePathFrom(serving);
    assertEquals(serving.paths().indexBasePath(), retained.paths().indexBasePath());
    assertEquals(serving.resolution("justsearch.index.base_path"),
        retained.resolution("justsearch.index.base_path"));
    assertEquals(9090, retained.ports().apiPort());
    assertEquals(desired.resolution("justsearch.api.port"), retained.resolution("justsearch.api.port"));
    var bothRetained = retained.retainingApiPortFrom(serving);
    assertEquals(8080, bothRetained.ports().apiPort());
    assertEquals(serving.paths().indexBasePath(), bothRetained.paths().indexBasePath());
  }

  @Test
  void sourceOrdinalDoesNotChangeClassificationWhenEffectiveValueIsEqual() {
    ResolvedConfig before = new ResolvedConfigBuilder()
        .put("index.boosts", ResolvedConfigBuilder.ORDINAL_DEFAULT, "default", null, "{}")
        .build();
    ResolvedConfig after = new ResolvedConfigBuilder()
        .put("index.boosts", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "index.boosts", "{}")
        .build();
    assertTrue(ConfigApplyScopes.classify(before, after).isNoOp());
  }

  private static ResolvedConfig config(String... keyValues) {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    for (int i = 0; i < keyValues.length; i += 2) {
      builder.put(keyValues[i], ResolvedConfigBuilder.ORDINAL_DEFAULT, "test", null, keyValues[i + 1]);
    }
    return builder.build();
  }

  private static Path findRepositoryRoot() {
    Path path = Paths.get("").toAbsolutePath();
    while (path != null) {
      if (Files.isRegularFile(path.resolve("settings.gradle.kts"))
          && Files.isRegularFile(path.resolve(ConfigApplyScopes.RESOURCE_PATH))) {
        return path;
      }
      path = path.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }
}
