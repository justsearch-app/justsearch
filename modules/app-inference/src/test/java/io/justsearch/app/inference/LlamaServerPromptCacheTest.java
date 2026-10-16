/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The host cache is bounded on every launch, independently of GPU KV-cache flags. */
final class LlamaServerPromptCacheTest {
  private static final String CACHE = "justsearch.llm.prompt_cache_mib";
  private static final String EVAL = "justsearch.eval.mode";
  private String previousCache;
  private String previousEval;

  @BeforeEach
  void captureProperties() {
    previousCache = System.getProperty(CACHE);
    previousEval = System.getProperty(EVAL);
    System.clearProperty(CACHE);
    System.clearProperty(EVAL);
  }

  @AfterEach
  void restoreProperties() {
    restore(CACHE, previousCache);
    restore(EVAL, previousEval);
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }

  private static InferenceConfig config(boolean vdu) {
    return new InferenceConfig(Path.of("llama.exe"), Path.of("model.gguf"), null,
        8082, 32768, 99, vdu);
  }

  private static void assertCache(InferenceConfig cfg, String expected) {
    List<String> argv = LlamaServerOps.buildLaunchCommand(cfg, ResolvedConfig.builder().build(),
        cfg.gpuLayers(), ContextWindowPolicy.auto(true, null));
    assertEquals(1, argv.stream().filter("--cache-ram"::equals).count(),
        "every launch must explicitly bound the host cache: " + argv);
    assertEquals(expected, argv.get(argv.indexOf("--cache-ram") + 1));
  }

  @Test
  void chatDefaultsTo1024MiB() {
    assertCache(config(false), "1024");
  }

  @Test
  void configuredCacheBoundChangesManagedLaunchIdentity() {
    var resolved = ResolvedConfig.builder().build();
    String defaultHash = ManagedLlamaConfigIdentity.declaredHash(config(false), resolved, 99);
    System.setProperty(CACHE, "256");
    assertNotEquals(defaultHash,
        ManagedLlamaConfigIdentity.declaredHash(config(false), resolved, 99),
        "a cache-bound change must invalidate adoption of a child with different launch flags");
  }

  @Test
  void evaluationDisablesCacheEvenWithOverride() {
    System.setProperty(EVAL, "true");
    assertCache(config(false), "0");
    System.setProperty(CACHE, "2048");
    assertCache(config(false), "0");
  }

  @Test
  void vduDisablesCacheEvenWithOverrideAndRestoresChatBoundOnExit() {
    System.setProperty(CACHE, "256");
    InferenceConfig chat = config(false);
    assertCache(chat.withVduMode(true), "0");
    assertCache(chat.withVduMode(true).withVduMode(false), "256");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "256", "2048", "2147483647"})
  void explicitOverrideIsApplied(String value) {
    System.setProperty(CACHE, value);
    assertCache(config(false), value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "", " ", "+1", "1.5", "garbage", "2147483648"})
  void invalidOverrideIsRefusedBeforeLaunch(String value) {
    System.setProperty(CACHE, value);
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> config(false));
    assertTrue(failure.getMessage().contains(CACHE));
    assertTrue(failure.getMessage().contains("non-negative integer"));
  }
}
