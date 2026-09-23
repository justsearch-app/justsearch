/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.adapters.lucene.runtime.DeferredRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.ModelCapabilities.PoolingMode;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.apache.lucene.search.MatchAllDocsQuery;

@Timeout(120)
class KnowledgeServerStartupConfigurationTest {
  @TempDir Path dir;

  @Test
  void physicalBootUsesCapturedConfigurationAfterGlobalReplacement() throws Exception {
    var previous = ConfigStore.globalOrNull();
    var captured = configuration("captured", "bge-m3", "2");
    var replacement = configuration("replacement", "splade", "9");
    ConfigStore.setGlobal(new ConfigStore(captured));
    try (var executors = new TestEngineExecutors()) {
      var worker = WorkerConfig.load(captured);
      var server =
          new KnowledgeServer(
              executors,
              worker,
              null,
              ManagedChildRegistry.noop(),
              RecordedIngestionLifecycle.denied(),
              null,
              null,
              captured);
      ConfigStore.setGlobal(new ConfigStore(replacement));
      try {
        server.start();
        assertEquals(captured.paths().indexBasePath(), field(server, "indexBasePath"));
        assertEquals(2, field(server, "migrationCutoverMaxFailedJobs"));
        var runtime = (LuceneRuntime) field(server, "searchLifecycle");
        assertSame(
            captured,
            runtime.resolvedConfig(),
            "the actual opened runtime must retain the capture");
        assertEquals(
            1024,
            IndexFingerprint.effectiveVectorDimension(),
            "the applied sparse selection must not use the replacement global snapshot");
        assertTrue(Files.isDirectory(captured.paths().indexBasePath()));
        assertFalse(Files.exists(replacement.paths().indexBasePath()));
      } finally {
        server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void enabledEmbeddingCompositionAndServiceWiringUseCapturedConfiguration() throws Exception {
    var previous = ConfigStore.globalOrNull();
    Path capturedModels = dir.resolve("captured-models");
    Path replacementModels = dir.resolve("replacement-models");
    Path capturedModel = createEmbeddingModel(capturedModels);
    createEmbeddingModel(replacementModels);
    var captured = embeddingConfiguration("captured-encoder", capturedModels, 1536, 4096);
    var replacement = embeddingConfiguration("replacement-encoder", replacementModels, 512, 1024);
    ConfigStore.setGlobal(new ConfigStore(captured));

    SessionHandle sessions = mock(SessionHandle.class);
    var nativeRetired = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(invocation -> {
      nativeRetired.set(true);
      return null;
    }).when(sessions).close();
    when(sessions.retirementStatus()).thenAnswer(invocation ->
        nativeRetired.get() ? SessionHandle.RetirementStatus.RETIRED
            : SessionHandle.RetirementStatus.ACTIVE);
    HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
    var shape =
        new EmbeddingShape(
            1536, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 4096, 768);
    var capabilities =
        new ModelCapabilities(
            PoolingMode.MEAN,
            1536,
            768,
            ModelPrecision.FP32,
            null,
            null,
            null,
            Map.of(),
            List.of());
    var assembly = new EmbeddingAssembly(sessions, shape, tokenizer, capabilities);
    ComponentHandle encoderComponent = mock(ComponentHandle.class);

    try (var executors = new TestEngineExecutors();
        var assembler = mockStatic(OrtSessionAssembler.class);
        var embeddingAssembly = mockStatic(OnnxEmbeddingEncoder.class)) {
      assembler
          .when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
          .thenReturn(sessions);
      embeddingAssembly
          .when(
              () ->
                  OnnxEmbeddingEncoder.buildAssembly(
                      same(sessions), eq(capturedModel), eq(1536), eq(4096), eq(false)))
          .thenReturn(assembly);

      var worker = WorkerConfig.load(captured);
      var server =
          spy(
              new KnowledgeServer(
                  executors,
                  worker,
                  null,
                  ManagedChildRegistry.noop(),
                  RecordedIngestionLifecycle.denied(),
                  null,
                  encoderComponent,
                  captured));
      try {
        doNothing().when(server).startDeferredModelInitialization(any());
        ConfigStore.setGlobal(new ConfigStore(replacement));
        server.start();
        invokeDeferredModelInitialization(server);

        var surface = (InferenceSurface) field(server, "inferenceSurface");
        assertTrue(surface.embedding().isPresent(), "the captured enabled role must compose");
        assertEquals(
            Set.of(EncoderRole.EMBEDDING), surface.componentObservation().requestedRoles());
        assertTrue(surface.componentObservation().missingRoles().isEmpty());
        assertTrue(surface.componentObservation().compositionSatisfied());

        var service = (EmbeddingService) field(server, "embeddingService");
        assertNotNull(service, "successful composition must reach the production service wiring");
        assertTrue(service.isAvailable());
        assertEquals(capturedModel, service.modelPath());
        assertEquals(capturedModel, service.embeddingConfig().modelPath());
        assertEquals(1536, service.embeddingConfig().contextLength());
        assertEquals(4096, service.embeddingConfig().lateChunkingContextLength());

        String digest = surface.componentObservation().configurationDigest().orElseThrow();
        assertEquals(EncoderConfigurationProjection.from(captured).digest(), digest);
        assertNotEquals(EncoderConfigurationProjection.from(replacement).digest(), digest);
        verify(encoderComponent).transition(ComponentState.STARTING, null, null);
        verify(encoderComponent).setDesiredVersion(digest);
        verify(encoderComponent).setAppliedVersion(digest);
        verify(encoderComponent).transition(ComponentState.READY, null, null);
        embeddingAssembly.verify(
            () ->
                OnnxEmbeddingEncoder.buildAssembly(
                    same(sessions), eq(capturedModel), eq(1536), eq(4096), eq(false)));
      } finally {
        server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  private ResolvedConfig configuration(String name, String sparseModel, String maxFailedJobs) {
    return configuration(name, sparseModel, maxFailedJobs, Map.of());
  }

  private ResolvedConfig configuration(
      String name, String sparseModel, String maxFailedJobs, Map<String, String> overrides) {
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), dir.resolve(name).toString());
    values.put("justsearch.index.base_path", dir.resolve(name + "-index").toString());
    values.put("index.migration.cutover.max_failed_jobs", maxFailedJobs);
    values.put(EnvRegistry.SPARSE_MODEL.configKey(), sparseModel);
    for (var key :
        new EnvRegistry[] {
          EnvRegistry.AI_EMBED_ENABLED,
          EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED,
          EnvRegistry.RERANK_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED,
          EnvRegistry.BGE_M3_ENABLED
        }) {
      values.put(key.configKey(), "false");
    }
    values.putAll(overrides);
    return TestResolvedConfigHelper.fromEntries(values);
  }

  @Test
  void serviceReconstructionReusesCapturedDiscoveryAfterModelFilesChange() throws Exception {
    var previous = ConfigStore.globalOrNull();
    Path modelDir = dir.resolve("held-reranker");
    Files.createDirectories(modelDir);
    Path model = modelDir.resolve("model.onnx");
    Files.writeString(model, "stub");
    Files.writeString(modelDir.resolve("tokenizer.json"), "stub");
    var captured = configuration("held-services", "splade", "2", Map.of(
        EnvRegistry.RERANK_CHUNKS_MODEL_PATH.configKey(), modelDir.toString(),
        EnvRegistry.RERANK_CHUNKS_ENABLED.configKey(), "false",
        EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process"));
    ConfigStore.setGlobal(new ConfigStore(captured));
    try (var executors = new TestEngineExecutors()) {
      var server = spy(new KnowledgeServer(executors, WorkerConfig.load(captured), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null, captured));
      doNothing().when(server).startDeferredModelInitialization(any());
      try {
        server.start();
        var initial = (DefaultWorkerAppServices) server.appServices();
        assertEquals(modelDir, initial.chunkRerankerConfig().modelPath());
        Files.delete(model);
        ConfigStore.setGlobal(new ConfigStore(configuration("changed-services", "splade", "9")));
        // Production retires the incumbent before this private reconstruction seam. Reflection
        // must establish that owner precondition while testing captured configuration reuse.
        server.retireServingView();
        var reconstruct = KnowledgeServer.class.getDeclaredMethod("reconstructAppServicesAfterDeferredUpgrade");
        reconstruct.setAccessible(true);
        reconstruct.invoke(server);
        var replacement = (DefaultWorkerAppServices) server.appServices();
        assertNotSame(initial, replacement);
        assertSame(initial.chunkRerankerConfig(), replacement.chunkRerankerConfig());
        assertSame(initial.citationScorerConfig(), replacement.citationScorerConfig());
        assertSame(initial.extractionConfiguration(), replacement.extractionConfiguration());
        assertSame(captured, replacement.resolvedConfig());
      } finally {
        server.close();
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test
  void deferredUpgradePublishesBWhileIssuedAReaderStillWorks() throws Exception {
    var previous = ConfigStore.globalOrNull();
    var captured = configuration("parallel-services", "splade", "2",
        Map.of(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process"));
    ConfigStore.setGlobal(new ConfigStore(captured));
    try {
      // The second boot takes the deferred-reader path only after the first boot writes segments.
      try (var seedExecutors = new TestEngineExecutors()) {
        var seed = spy(new KnowledgeServer(seedExecutors, WorkerConfig.load(captured), null,
            ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null, captured));
        doNothing().when(seed).startDeferredModelInitialization(any());
        try {
          seed.start();
          ((LuceneRuntime) field(seed, "searchLifecycle")).commitOps().commitAndTrack();
        } finally {
          seed.close();
        }
      }
      try (var executors = new TestEngineExecutors()) {
        var server = spy(new KnowledgeServer(executors, WorkerConfig.load(captured), null,
            ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null, captured));
        doNothing().when(server).startDeferredModelInitialization(any());
        try {
          server.start();
          var a = server.captureServingView();
          try {
            assertInstanceOf(DeferredRuntime.class, a.searchRuntime());
            var upgrade = KnowledgeServer.class.getDeclaredMethod("upgradeDeferredServing",
                DeferredRuntime.class);
            upgrade.setAccessible(true);
            upgrade.invoke(server, a.searchRuntime());
            try (var b = server.captureServingView()) {
              assertNotSame(a.services(), b.services());
              assertNotSame(a.searchRuntime(), b.searchRuntime());
              assertSame(b.services(), server.appServices());
              assertEquals(0, a.searchRuntime().readPathOps().search(new MatchAllDocsQuery(),
                  10, Set.of(), RuntimeSearchSort.RELEVANCE, null).hits().size(),
                  "issued A runtime remains usable after B publication");
            }
            a.close();
            assertThrows(RuntimeException.class, () -> a.searchRuntime().readPathOps().search(
                new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null),
                "old runtime must retire after the last A lease leaves");
          } finally {
            a.close();
          }
        } finally {
          server.close();
        }
      }
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
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

  private static Object field(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }
}
