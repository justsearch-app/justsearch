/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.EngineProcessResources;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.component.EngineComponentRegistry.ApplyAttempt;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The whole embedded fixture owns its process substrate as well as its restartable index. */
@Timeout(90)
final class EngineTestHarnessProcessCleanupTest {
  @Test
  void closeRetiresTheFixturesOwnProcessResources(@TempDir Path directory) throws Exception {
    ConfigStore previousConfig = ConfigStore.globalOrNull();
    Map<String, String> previousProperties = new HashMap<>();
    EngineTestHarness harness = null;
    EngineProcessResources resources = null;
    try {
      property(previousProperties, "justsearch.eval.frozen", "false");
      property(previousProperties, "justsearch.ai.disabled", "true");
      property(previousProperties, "justsearch.llm.enabled", "false");
      property(previousProperties, "justsearch.models.dir",
          Files.createDirectories(directory.resolve("empty-models")).toString());
      property(previousProperties, "ai.djl.offline", "true");
      for (var role : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED, EnvRegistry.RERANK_ENABLED, EnvRegistry.RERANK_CHUNKS_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED, EnvRegistry.BGE_M3_ENABLED}) {
        property(previousProperties, role.sysProp(), "false");
      }
      harness = EngineTestHarness.start(directory.resolve("data"));
      var rootField = EngineTestHarness.class.getDeclaredField("root");
      rootField.setAccessible(true);
      resources = ((EngineRoot) rootField.get(harness)).processResources();
      var executors = resources.executors();
      try (var registration = executors.register(EngineExecutorSpec.virtual(
          "harness-owned-cleanup-probe", EngineExecutorSpec.Kind.FOREGROUND, 1))) {
        var executor = registration.openVirtual();
        assertEquals(42, executor.submit(() -> 42).get(5, TimeUnit.SECONDS),
            "the real process executor must be usable before fixture teardown");
        harness.close();
        assertTrue(executor.isShutdown(), "fixture close must shut down its owned executor");
        assertEquals(EngineExecutorRejectedException.Reason.CLOSED,
            assertThrows(EngineExecutorRejectedException.class, () -> executors.register(
                EngineExecutorSpec.virtual("after-fixture-close",
                    EngineExecutorSpec.Kind.FOREGROUND, 1))).reason());
        assertEquals(ApplyAttempt.Reason.CLOSED,
            assertInstanceOf(ApplyAttempt.Refused.class, resources.components().tryApply()).reason());
      }
    } finally {
      try {
        if (harness != null) harness.close();
      } finally {
        try {
          // Also retire the uncorrected before-state fixture when its assertion fails.
          if (resources != null) resources.close();
        } finally {
          previousProperties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
          });
          ConfigStore.restoreGlobal(ConfigStore.global(), previousConfig);
        }
      }
    }
  }

  private static void property(Map<String, String> previous, String key, String value) {
    if (!previous.containsKey(key)) previous.put(key, System.getProperty(key));
    System.setProperty(key, value);
  }
}
