/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.ManagedLlamaConfigIdentity;
import io.justsearch.app.services.bootstrap.phases.InferenceDecision;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.telemetry.Telemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class BootstrapInferenceFactoryCapturedConfigTest {
  @TempDir Path directory;

  @Test
  void actualManagerAndDeclaredIdentityUseCapturedSnapshotAfterGlobalReplacement() throws Exception {
    ConfigStore previous = ConfigStore.globalOrNull();
    String previousLite = System.getProperty("justsearch.lite.mode");
    System.setProperty("justsearch.lite.mode", "false");
    try {
      var captured = snapshot("captured", 18081, 4096, 0);
      var replacement = snapshot("replacement", 18082, 8192, 7);
      ConfigStore.setGlobal(new ConfigStore(captured));
      boolean configured = InferenceDecision.decideInferenceConfigured(captured);
      var declared = InferenceConfig.fromResolvedConfig(captured, directory);
      ConfigStore.setGlobal(new ConfigStore(replacement));

      try (var executors = new TestEngineExecutors();
          var manager = BootstrapInferenceFactory.createInferenceManager(
              executors, configured, captured, directory.toString(), mock(Telemetry.class),
              LoggerFactory.getLogger(getClass()), ManagedChildRegistry.noop())) {
        assertNotNull(manager);
        var applied = manager.currentConfig();
        assertEquals(declared, applied, "factory and declared-launch path must use the same snapshot");
        assertEquals(captured.ai().serverExe(), applied.serverExecutable());
        assertEquals(captured.ai().llmModelPath(), applied.modelPath());
        assertEquals(18081, applied.serverPort());
        assertEquals(4096, applied.contextSize());
        assertEquals(0, applied.gpuLayers());
        assertEquals(
            ManagedLlamaConfigIdentity.declaredHash(declared, captured, 0),
            ManagedLlamaConfigIdentity.declaredHash(applied, captured, 0));
        var other = InferenceConfig.fromResolvedConfig(replacement, directory);
        assertNotEquals(declared, other, "replacement is observably different, not a vacuous fixture");
        assertNotEquals(
            ManagedLlamaConfigIdentity.declaredHash(declared, captured, 0),
            ManagedLlamaConfigIdentity.declaredHash(other, replacement, 7));
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
      if (previousLite == null) System.clearProperty("justsearch.lite.mode");
      else System.setProperty("justsearch.lite.mode", previousLite);
    }
  }

  private ResolvedConfig snapshot(String name, int port, int context, int layers) throws Exception {
    Path root = Files.createDirectories(directory.resolve(name));
    Path server = Files.writeString(root.resolve("llama-server.exe"), "test fixture; never launched");
    Path model = Files.writeString(root.resolve("model.gguf"), "test fixture; never loaded");
    var builder = new ResolvedConfigBuilder();
    builder.putDefault("justsearch.home", root.toString());
    builder.putDefault("justsearch.server.exe", server.toString());
    builder.put("justsearch.llm.model_path", 400, "env_var", "test", model.toString());
    builder.putDefault("justsearch.server.port", Integer.toString(port));
    builder.putDefault("justsearch.context.size", Integer.toString(context));
    builder.putDefault("justsearch.gpu.layers", Integer.toString(layers));
    builder.putDefault("justsearch.ai.disabled", "false");
    builder.putDefault("justsearch.llm.enabled", "true");
    return builder.build();
  }
}
