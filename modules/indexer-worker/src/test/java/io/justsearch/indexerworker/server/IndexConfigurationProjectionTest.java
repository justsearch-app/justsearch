/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.ConfigKey;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the published version of the actual opened index and composed service owners. */
@Timeout(120)
class IndexConfigurationProjectionTest {
  @TempDir Path dir;

  @Test
  void effectiveDefaultsAndUnrelatedSettingsPreserveActualOwnerVersion() throws Exception {
    String baseline = bootVersion(Map.of());
    String equivalent = bootVersion(Map.of(
        ConfigKey.INDEX_WRITER_RAM_BUFFER_MB.configKey(), "64",
        ConfigKey.INDEX_NRT_TARGET_MAX_STALE_MS.configKey(), "500",
        EnvRegistry.API_PORT.configKey(), "19431"));
    assertEquals(baseline, equivalent);
    assertNotEquals(baseline, bootVersion(Map.of(
        ConfigKey.INDEX_WRITER_RAM_BUFFER_MB.configKey(), "96")));
    assertNotEquals(baseline, bootVersion(Map.of(
        ConfigKey.INDEX_OCR_MAX_PAGES.configKey(), "17")));
    assertFalse(KnowledgeServer.componentDependencies().contains(EnvRegistry.INDEXER_PORT.configKey()),
        "retired IPC carrier fields are not applied index inputs");
  }

  @Test
  void hotReloadChangesTheActualOwnerVersion() throws Exception {
    assertNotEquals(
        bootVersion(Map.of(EnvRegistry.DEV_HOTRELOAD.configKey(), "false")),
        bootVersion(Map.of(EnvRegistry.DEV_HOTRELOAD.configKey(), "true")));
  }

  @Test
  void retainedHealthDiscoveryChangesTheVersionWithAnExplicitChunkModel() throws Exception {
    Path models = dir.resolve("models");
    var overrides = Map.of(
        EnvRegistry.MODELS_DIR.configKey(), models.toString(),
        EnvRegistry.RERANK_CHUNKS_MODEL_PATH.configKey(), dir.resolve("explicit-chunks").toString());
    String before = bootVersion(overrides);
    Path discovered = Files.createDirectories(models.resolve("onnx/reranker"));
    Files.writeString(discovered.resolve("model.onnx"), "fixture");
    Files.writeString(discovered.resolve("tokenizer.json"), "fixture");
    String after = bootVersion(overrides);
    assertNotEquals(before, after,
        "the independently retained health discovery changes even with a fixed chunk model");
    assertEquals(after, bootVersion(overrides), "unchanged discovery must be stable");
  }

  @Test
  void headOwnedTracingSamplerDoesNotApplyTheRequestedIndexSampler() throws Exception {
    OpenTelemetry previous = GlobalOpenTelemetry.get();
    GlobalOpenTelemetry.resetForTest();
    GlobalOpenTelemetry.set(OpenTelemetry.noop());
    try {
      String sampled = bootVersion(Map.of(EnvRegistry.INDEX_TRACING_LEVEL.configKey(), "sample"));
      String detailed = bootVersion(Map.of(EnvRegistry.INDEX_TRACING_LEVEL.configKey(), "detailed"));
      assertEquals(sampled, detailed,
          "Head already owns the sampler; both settings only enable index span authoring");
      assertNotEquals(sampled,
          bootVersion(Map.of(EnvRegistry.INDEX_TRACING_LEVEL.configKey(), "none")),
          "disabling actual index span authoring still changes the applied version");
    } finally {
      GlobalOpenTelemetry.resetForTest();
      GlobalOpenTelemetry.set(previous);
    }
  }

  @Test
  void acquiredIndexTracingSamplerProjectsItsEffectivePolicy() throws Exception {
    OpenTelemetry previous = GlobalOpenTelemetry.get();
    try {
      GlobalOpenTelemetry.resetForTest();
      String sampled = bootVersion(Map.of(EnvRegistry.INDEX_TRACING_LEVEL.configKey(), "sample"));
      GlobalOpenTelemetry.resetForTest();
      String detailed = bootVersion(Map.of(EnvRegistry.INDEX_TRACING_LEVEL.configKey(), "detailed"));
      GlobalOpenTelemetry.resetForTest();
      String fallback = bootVersion(Map.of(EnvRegistry.INDEX_TRACING_LEVEL.configKey(), "unknown"));
      assertNotEquals(sampled, detailed, "an acquired 1% sampler differs from always-on");
      assertEquals(detailed, fallback, "the tracing owner's fallback also installs always-on");
    } finally {
      GlobalOpenTelemetry.resetForTest();
      GlobalOpenTelemetry.set(previous);
    }
  }

  private String bootVersion(Map<String, String> overrides) throws Exception {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), dir.resolve("data").toString());
    values.put(EnvRegistry.INDEX_BASE_PATH.configKey(), dir.resolve("index").toString());
    values.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    values.put(EnvRegistry.RERANK_CHUNKS_ENABLED.configKey(), "false");
    values.put(EnvRegistry.SPARSE_MODEL.configKey(), "splade");
    for (var key : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
        EnvRegistry.NER_ENABLED, EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED,
        EnvRegistry.CITATION_SCORER_ENABLED}) values.put(key.configKey(), "false");
    values.putAll(overrides);
    var snapshot = TestResolvedConfigHelper.fromEntries(values);
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(snapshot));
    var applied = new AtomicReference<String>();
    var handle = mock(ComponentHandle.class);
    doAnswer(call -> { applied.set(call.getArgument(0)); return null; })
        .when(handle).setAppliedVersion(nullable(String.class));
    try (var executors = new TestEngineExecutors()) {
      var server = spy(new KnowledgeServer(executors, WorkerConfig.load(snapshot), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), handle, null, snapshot));
      // This test exercises index composition; encoder composition has its own real wiring proof.
      doNothing().when(server).startDeferredModelInitialization(any());
      try {
        server.start();
        assertNotNull(applied.get(), "the real physical start must publish its owner version");
        return applied.get();
      } finally {
        server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }
}
