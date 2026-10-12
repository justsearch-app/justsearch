/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
class KnowledgeServerAvailabilityTest {
  @TempDir Path dir;

  @Test void installedModelIsStartingBeforePublication() throws Exception {
    io.justsearch.indexerworker.embed.EmbeddingFingerprint.setForTesting(null);
    try { check(true, false); }
    finally { io.justsearch.indexerworker.embed.EmbeddingFingerprint.invalidate(); }
  }
  @Test void failedLoadIsPublishedWithOriginalCause() throws Exception { check(true, true); }
  @Test void unconfiguredModelStaysAbsent() throws Exception { check(false, true); }

  private void check(boolean installed, boolean initialize) throws Exception {
    var previous = ConfigStore.globalOrNull();
    var models = dir.resolve("models");
    if (installed) createEmbeddingModel(models);
    var config = embeddingConfiguration("state", models, 1536, 4096);
    ConfigStore.setGlobal(new ConfigStore(config));
    var sessions = mock(SessionHandle.class);
    when(sessions.retirementStatus()).thenReturn(SessionHandle.RetirementStatus.RETIRED);
    var cause = new IllegalArgumentException("tokenizer load failed");
    try (var executors = new TestEngineExecutors();
        var assembler = mockStatic(OrtSessionAssembler.class);
        var embedding = mockStatic(OnnxEmbeddingEncoder.class)) {
      assembler.when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
          .thenReturn(sessions);
      embedding.when(() -> OnnxEmbeddingEncoder.buildAssembly(any(), any(), anyInt(), anyInt(),
          anyBoolean(), any())).thenThrow(cause);
      var server = spy(new KnowledgeServer(executors, WorkerConfig.load(config), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null, config));
      doNothing().when(server).startDeferredModelInitialization(any());
      try {
        server.start();
        if (initialize) invokeDeferredModelInitialization(server);
        try (var view = server.captureServingView()) {
          var provider = (io.justsearch.indexerworker.embed.EmbeddingProvider)
              field(view.services().searchService(), "embeddingProvider");
          assertFalse(provider.isAvailable());
          Object availability = null;
          try { availability = provider.getClass().getMethod("availability").invoke(provider); }
          catch (NoSuchMethodException booleanOnlyBeforeState) { /* old API reports only absence */ }
          String state = availability == null ? "ABSENT"
              : availability.getClass().getMethod("state").invoke(availability).toString();
          assertEquals(!installed ? "ABSENT" : initialize ? "FAILED" : "STARTING", state);
          if (installed && initialize) {
            assertSame(cause, availability.getClass().getMethod("failure").invoke(availability));
          }
        }
      } finally { server.close(); }
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  private ResolvedConfig embeddingConfiguration(
      String name, Path modelsDir, int contextLength, int lateChunkingContextLength) {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), dir.resolve(name).toString());
    values.put("justsearch.index.base_path", dir.resolve(name + "-index").toString());
    values.put(EnvRegistry.MODELS_DIR.configKey(), modelsDir.toString());
    values.put(EnvRegistry.SPARSE_MODEL.configKey(), "splade");
    values.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    values.put(EnvRegistry.EMBED_CONTEXT_LENGTH.configKey(), Integer.toString(contextLength));
    values.put(
        EnvRegistry.EMBED_LATE_CHUNKING_CONTEXT_LENGTH.configKey(),
        Integer.toString(lateChunkingContextLength));
    for (var key :
        new EnvRegistry[] {
          EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED,
          EnvRegistry.RERANK_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED,
          EnvRegistry.BGE_M3_ENABLED
        }) {
      values.put(key.configKey(), "false");
    }
    return TestResolvedConfigHelper.fromEntries(values);
  }

  private static Path createEmbeddingModel(Path modelsDir) throws Exception {
    Path modelDir = modelsDir.resolve("onnx").resolve("gte-multilingual-base");
    Files.createDirectories(modelDir);
    Files.writeString(modelDir.resolve("model.onnx"), "stub");
    Files.writeString(modelDir.resolve("tokenizer.json"), "stub");
    return modelDir;
  }

  private static void invokeDeferredModelInitialization(KnowledgeServer server) throws Exception {
    var method = KnowledgeServer.class.getDeclaredMethod("initDeferredModels");
    method.setAccessible(true);
    method.invoke(server);
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target instanceof KnowledgeServer
        ? KnowledgeServer.class.getDeclaredField(name)
        : target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
