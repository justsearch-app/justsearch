/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chat host prompt-cache override is registered where the other inference launch keys are:
 * declared in {@link EnvRegistry}, classified in the apply register, and resolved with the
 * registry's precedence, rather than read as a one-off system property.
 */
final class PromptCacheKeyRegistrationTest {

  private static final String KEY = "justsearch.llm.prompt_cache_mib";
  private static final String ENV = "JUSTSEARCH_LLM_PROMPT_CACHE_MIB";

  private static List<EnvRegistry> declarations() {
    return Arrays.stream(EnvRegistry.values()).filter(e -> e.sysProp().equals(KEY)).toList();
  }

  @Test
  void theOverrideIsDeclaredOnceInTheRegistryWithItsEnvironmentVariable() {
    List<EnvRegistry> declared = declarations();
    assertEquals(1, declared.size(), "exactly one registry declaration for " + KEY);
    EnvRegistry entry = declared.get(0);
    assertEquals(ENV, entry.envVar());
    assertEquals(EnvRegistry.LifecycleStage.PERMANENT, entry.lifecycleStage());
    assertNull(entry.defaultValue(),
        "no registered default: chat uses 1024 but evaluation mode uses 0, so the launch owns it");
  }

  @Test
  void theOverrideHasAGovernedApplyScopeOfRestartRequired() {
    var scope = ConfigApplyScopes.register().scopesByKey().get(KEY);
    assertNotNull(scope, "the apply register must classify " + KEY);
    assertEquals("restart-required", scope.wireValue(),
        "the bound is captured when the inference configuration is created");
  }

  @Test
  void theOverrideResolvesFromTheSystemPropertyLikeOtherRegisteredKeys() {
    String previous = System.getProperty(KEY);
    try {
      System.setProperty(KEY, "256");
      ConfigResolution resolution =
          ResolvedConfig.builder().contributeEnvRegistry().build().resolution(KEY);
      assertNotNull(resolution, KEY + " must be tracked in the resolved configuration");
      assertTrue(resolution.isResolved());
      assertEquals("256", resolution.value());
      assertEquals("jvm_arg", resolution.sourceName());
    } finally {
      if (previous == null) System.clearProperty(KEY);
      else System.setProperty(KEY, previous);
    }
  }
}
