/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingShape;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.BertNerInference;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real initializer/shutdown boundary with counted factory tokenizers. */
@Timeout(60)
class KnowledgeServerTokenizerOwnershipTest {
  @TempDir Path dir;

  @Test
  void closingWhileCompositionHasNotReturnedReleasesWrappedAndUnwrappedTokenizers()
      throws Exception {
    run(true);
  }

  @Test
  void laterWrapperFailureRetiresAllComposedRolesBeforeServerShutdown() throws Exception {
    run(false);
  }

  @Test
  void failedInitializationCanBeRetriedInTheSameJvmWithoutAccumulatingOwners() throws Exception {
    run(false);
    run(false);
  }

  @Test
  void failedBootCleanupRetiresBeforeAnotherCompositionCanCreateSessions() throws Exception {
    run(false, true);
  }

  private void run(boolean closeDuringComposition) throws Exception {
    run(closeDuringComposition, false);
  }

  private void run(boolean closeDuringComposition, boolean retryCleanup) throws Exception {
    var previous = ConfigStore.globalOrNull();
    var values = new HashMap<String, String>();
    values.put(EnvRegistry.DATA_DIR.configKey(), dir.resolve("data").toString());
    values.put("justsearch.index.base_path", dir.resolve("index").toString());
    values.put(EnvRegistry.MODELS_DIR.configKey(), dir.resolve("models").toString());
    for (var key : new EnvRegistry[] {EnvRegistry.SPLADE_ENABLED, EnvRegistry.BGE_M3_ENABLED,
        EnvRegistry.RERANK_ENABLED, EnvRegistry.CITATION_SCORER_ENABLED}) {
      values.put(key.configKey(), "false");
    }
    values.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), "true");
    values.put(EnvRegistry.NER_ENABLED.configKey(), "true");
    Path embedDir = model("embed");
    Path nerDir = model("ner");
    values.put(EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey(), embedDir.toString());
    values.put(EnvRegistry.NER_MODEL_PATH.configKey(), nerDir.toString());
    var config = TestResolvedConfigHelper.fromEntries(values);
    ConfigStore.setGlobal(new ConfigStore(config));
    var embedTokenizer = mock(HuggingFaceTokenizer.class);
    var nerTokenizer = mock(HuggingFaceTokenizer.class);
    var embedSession = sessions();
    var nerSession = sessions();
    var refuseCleanup = new AtomicBoolean(retryCleanup);
    var releases = new AtomicInteger();
    if (retryCleanup) {
      doAnswer(ignored -> {
        assertEquals(SessionHandle.RetirementStatus.RETIRED, embedSession.retirementStatus());
        if (refuseCleanup.get()) throw new IllegalStateException("boot tokenizer cleanup refused");
        assertEquals(1, releases.incrementAndGet());
        return null;
      }).when(embedTokenizer).close();
    }
    var capability = mock(ModelCapabilities.class);
    var embedding = new EmbeddingAssembly(embedSession,
        new EmbeddingShape(512, false, OnnxEmbeddingEncoder.PoolingStrategy.MEAN_POOL, 512, 768),
        embedTokenizer, capability);
    // Invalid shape deliberately fails only when the NER wrapper is constructed, after composition.
    var ner = new NerAssembly(nerSession, null, nerTokenizer, null, capability);
    var composed = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    try (var executors = new TestEngineExecutors()) {
      var server = spy(new KnowledgeServer(executors, WorkerConfig.load(config), null,
          ManagedChildRegistry.noop(), RecordedIngestionLifecycle.denied(), null, null, config));
      doNothing().when(server).startDeferredModelInitialization(any());
      try {
        server.start();
        if (closeDuringComposition) {
          server.deferredModelInit = CompletableFuture.runAsync(() -> {
            try { initialize(server, embedding, ner, composed, resume); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
          });
          assertTrue(composed.await(10, TimeUnit.SECONDS));
          var closing = CompletableFuture.runAsync(() -> {
            try { server.close(); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
          });
          ((CompletableFuture<?>) QueryPrewarmFixture.field(server, "closeStartedSignal"))
              .get(10, TimeUnit.SECONDS);
          resume.countDown();
          closing.get(30, TimeUnit.SECONDS);
          assertTrue(server.awaitClosed(0));
        } else {
          resume.countDown();
          initialize(server, embedding, ner, composed, resume);
          if (retryCleanup) {
            assertEquals(0, releases.get());
            assertEquals(1, server.retainedEncoderCount(), "failed boot owner remains counted");
            assertEquals(io.justsearch.app.api.NativeQuiescence.UNQUIESCED, server.nativeQuiescence());
            refuseCleanup.set(false);
            var attempted = new AtomicBoolean();
            try (var assembler = mockStatic(OrtSessionAssembler.class)) {
              assembler.when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
                  .thenAnswer(ignored -> {
                    assertEquals(1, releases.get(), "abandoned boot tokenizer retires before a new session");
                    attempted.set(true);
                    throw new IllegalStateException("stop the second composition before allocating sessions");
                  });
              QueryPrewarmFixture.invokeDeferredModelInitialization(server);
            }
            assertTrue(attempted.get());
          } else verify(embedTokenizer).close();
          verify(nerTokenizer).close();
        }
      } finally {
        resume.countDown();
        refuseCleanup.set(false);
        server.close();
      }
      if (retryCleanup) {
        assertEquals(1, releases.get());
        verify(embedTokenizer, times(2)).close();
      } else verify(embedTokenizer).close();
      verify(nerTokenizer).close();
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  private static void initialize(KnowledgeServer server, EmbeddingAssembly embedding,
      NerAssembly ner, CountDownLatch composed, CountDownLatch resume) throws Exception {
    try (var assembler = mockStatic(OrtSessionAssembler.class);
        var embeddingFactory = mockStatic(OnnxEmbeddingEncoder.class);
        var nerFactory = mockStatic(BertNerInference.class)) {
      assembler.when(() -> OrtSessionAssembler.buildManager(anyString(), any(), any(), any()))
          .thenReturn(embedding.sessions(), ner.sessions());
      embeddingFactory.when(() -> OnnxEmbeddingEncoder.buildAssembly(any(), any(), anyInt(),
          anyInt(), anyBoolean(), any())).thenReturn(embedding);
      nerFactory.when(() -> BertNerInference.buildAssembly(any(), any(), anyInt(), anyBoolean(), any()))
          .thenAnswer(ignored -> {
            composed.countDown();
            assertTrue(resume.await(20, TimeUnit.SECONDS));
            return ner;
          });
      QueryPrewarmFixture.invokeDeferredModelInitialization(server);
    }
  }

  private static SessionHandle sessions() {
    var session = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    when(session.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> { status.set(SessionHandle.RetirementStatus.RETIRED); return null; })
        .when(session).close();
    return session;
  }

  private Path model(String name) throws Exception {
    Path path = dir.resolve("models").resolve(name);
    Files.createDirectories(path);
    Files.writeString(path.resolve("model.onnx"), "model-witness");
    Files.writeString(path.resolve("tokenizer.json"), "tokenizer-witness");
    return path;
  }
}
