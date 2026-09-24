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
    assertEquals(278, ConfigApplyScopes.register().size());
    assertEquals("component:index", ConfigApplyScopes.scopeFor("index.boosts").wireValue());
  }

  @Test
  void classifiesEffectiveValueChangesAndLeavesSourceOnlyChangesAsNoOp() {
    ResolvedConfig before = config(
        "index.boosts", "{\"a\":1}",
        "index.vector.hnsw.m", "16",
        "justsearch.api.port", "8080",
        "index.hybrid.adaptive_weights_enabled", "false");
    ResolvedConfig sameValueDifferentSource = config(
        "index.boosts", "{\"a\":1}",
        "index.vector.hnsw.m", "16",
        "justsearch.api.port", "8080",
        "index.hybrid.adaptive_weights_enabled", "false");
    assertTrue(ConfigApplyScopes.classify(before, sameValueDifferentSource).isNoOp());

    ResolvedConfig after = config(
        "index.boosts", "{\"b\":1}",
        "index.vector.hnsw.m", "32",
        "justsearch.api.port", "9090",
        "index.hybrid.adaptive_weights_enabled", "true");
    ConfigApplyScopes.ChangedKeys changed = ConfigApplyScopes.classify(before, after);
    assertEquals(java.util.Set.of("index.boosts"), changed.component().get("index"));
    assertEquals(java.util.Set.of("index.vector.hnsw.m"), changed.generationBound());
    assertEquals(java.util.Set.of("justsearch.api.port"), changed.restartRequired());
    assertEquals(java.util.Set.of("index.hybrid.adaptive_weights_enabled"), changed.hot());
    assertEquals(4, changed.all().size());
    assertFalse(changed.isNoOp());
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
