/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArbiter;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.OnnxSessionCache;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import java.lang.reflect.Method;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Intentional CPU placement preserves the captured model and the real query-owner lifecycle. */
final class PlannedCpuCompositionTest {
  @Test
  void capturedNonFp16ModelRemainsComposableOnCpu(@TempDir Path dir) throws Exception {
    Path model = Files.write(dir.resolve("model.onnx"), new byte[128]);
    VariantSelection variant = VariantSelection.degraded(model, ModelPrecision.FP32,
        ExecutionProvider.CUDA, "selected FP32 export");
    ResolvedConfig cfg = plannedConfiguration(dir, dir);
    ModelSessionPolicy policy = ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, cfg,
        HardwareProfile.gpuFull(12_282L * 1024 * 1024), variant);
    assertEquals(0L, policy.gpu().arenaCapBytes(), "the planned FP32 role intentionally uses CPU");
    Method compose = InferenceCompositionRoot.class.getDeclaredMethod("composeCapturedHandle",
        String.class, RuntimePolicy.class, ModelSessionPolicy.class, VariantSelection.class,
        GpuArbiter.class, OrtSessionTelemetryEvents.class);
    compose.setAccessible(true);
    var cpu = mock(OrtSession.class);
    try (var envs = mockStatic(OrtEnvironment.class);
        var _ = mockConstruction(OrtSession.SessionOptions.class);
        var cache = mockStatic(OnnxSessionCache.class)) {
      envs.when(OrtEnvironment::getEnvironment).thenReturn(mock(OrtEnvironment.class));
      cache.when(() -> OnnxSessionCache.createCachedSession(any(OrtEnvironment.class),
          any(Path.class), any(OrtSession.SessionOptions.class),
          any(OrtSession.SessionOptions.OptLevel.class))).thenReturn(cpu);
      try (var handle = assertDoesNotThrow(() -> (SessionHandle) compose.invoke(null,
          EncoderRole.EMBEDDING.consumerName(), RuntimePolicy.defaults(), policy, variant,
          (GpuArbiter) () -> true, OrtSessionTelemetryEvents.NOOP),
          "captured CPU placement must preserve the exact variant accepted by composition");
          var lease = handle.acquire(SessionAcquisitionRequest.within(
              SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(5)))) {
        assertTrue(lease.isCpu());
        assertSame(cpu, lease.session());
        assertEquals(variant, policy.variant(), "placement must not rewrite captured identity");
      }
    }
  }

  @Test
  void plannedFp32QueryPreparesAndValidatesItsIntentionalCpuSession(@TempDir Path dir)
      throws Exception {
    Path pack = Files.createDirectories(dir.resolve("planned-fp32-reranker"));
    Files.write(pack.resolve("model.onnx"), new byte[128]);
    Files.write(pack.resolve("model_cuda.onnx"), new byte[256]);
    Files.writeString(pack.resolve("tokenizer.json"), "mocked tokenizer", StandardCharsets.UTF_8);
    Files.writeString(pack.resolve("model_manifest.json"), """
        {"cpu":"model.onnx","gpu":"model_cuda.onnx",
         "capabilities":{"cpu_precision":"fp32","gpu_precision":"fp32"}}
        """, StandardCharsets.UTF_8);
    ResolvedConfig desired = plannedConfiguration(dir, pack);
    var candidate = new UiSettings();
    candidate.setRerankerModelPath(pack.toString());
    var env = mock(OrtEnvironment.class);
    var probe = mock(OrtSession.class);
    when(probe.getInputNames()).thenReturn(Set.of("input_ids", "attention_mask"));
    when(probe.getOutputNames()).thenReturn(Set.of("logits"));
    when(env.createSession(anyString(), any(OrtSession.SessionOptions.class))).thenReturn(probe);
    var cpu = mock(OrtSession.class);
    var result = mock(OrtSession.Result.class);
    var scores = mock(OnnxTensor.class);
    when(scores.getValue()).thenReturn(new float[][] {{0.25f}, {0}, {0}, {0}});
    when(result.get(0)).thenReturn(scores);
    when(cpu.run(anyMap())).thenReturn(result);
    var tokenizer = mock(HuggingFaceTokenizer.class);
    var encoding = mock(Encoding.class);
    when(encoding.getIds()).thenReturn(new long[] {1, 2});
    when(encoding.getAttentionMask()).thenReturn(new long[] {1, 1});
    when(encoding.getTypeIds()).thenReturn(new long[] {0, 0});
    when(tokenizer.encode(anyString(), anyString())).thenReturn(encoding);
    // The source fixture stubs its environment in one Mockito expression, so create and close
    // it outside the static environment seam used only for the candidate's real native factory.
    try (var fixture = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L)) {
      try (var envs = mockStatic(OrtEnvironment.class);
          var _ = mockConstruction(OrtSession.SessionOptions.class);
          var cache = mockStatic(OnnxSessionCache.class);
          var contracts = mockStatic(InstallContractIO.class);
          var tokenizers = mockStatic(HuggingFaceTokenizer.class);
          var tensors = mockStatic(OnnxTensor.class)) {
        envs.when(OrtEnvironment::getEnvironment).thenReturn(env);
        cache.when(() -> OnnxSessionCache.createCachedSession(any(OrtEnvironment.class),
            any(Path.class), any(OrtSession.SessionOptions.class),
            any(OrtSession.SessionOptions.OptLevel.class))).thenReturn(cpu);
        contracts.when(() -> InstallContractIO.read(any(Path.class))).thenReturn(new InstallContract(
            2, 0, HardwareProfile.gpuFull(12_282L * 1024 * 1024), null, Map.of()));
        tokenizers.when(() -> HuggingFaceTokenizer.newInstance(any(Path.class))).thenReturn(tokenizer);
        tensors.when(() -> OnnxTensor.createTensor(any(OrtEnvironment.class),
            any(LongBuffer.class), any(long[].class))).thenReturn(mock(OnnxTensor.class));
        // Mockito's native static seams belong to this thread. Keep the real warm-up factory
        // and future/exit ownership, while executing its task here rather than entering JNI.
        var executorField = KnowledgeServer.class.getDeclaredField("deferredModelExecutor");
        executorField.setAccessible(true);
        Object originalExecutor = executorField.get(fixture.server);
        var sameThread = mock(ExecutorService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
            .when(sameThread).execute(any(Runnable.class));
        executorField.set(fixture.server, sameThread);
        try {
          var prepared = assertDoesNotThrow(() -> fixture.server.prepareQueryRoleSettings(candidate,
              desired, Set.of(EnvRegistry.RERANK_MODEL_PATH.configKey()), fixture.selection),
              "an intentional planned CPU placement must satisfy the real query-owner factory");
          try {
            assertEquals(ModelPrecision.FP32, prepared.selection().reranker().precision());
            assertEquals(ExecutionProvider.CUDA, prepared.selection().reranker().targetEp());
            assertDoesNotThrow(prepared::validate,
                "publication must accept the same intentional CPU placement as preparation");
            var field = prepared.getClass().getDeclaredField("query");
            field.setAccessible(true);
            QueryRoleSet query = (QueryRoleSet) field.get(prepared);
            ModelSessionPolicy policy = query.surfaceForOwner().policies().models()
                .get(EncoderRole.RERANKER);
            assertEquals(0L, policy.gpu().arenaCapBytes());
            assertFalse(query.reranker().isGpuAvailable());
            assertEquals(List.of(0.25f), query.reranker().rerank("query", List.of("document"),
                30_000).scores());
          } finally {
            prepared.abort();
          }
          fixture.assertA();
        } finally {
          executorField.set(fixture.server, originalExecutor);
        }
      }
    }
  }

  @ParameterizedTest(name = "cudaWarmupRemainsStrictForUnknownOrPositivePolicy({0})")
  @ValueSource(booleans = {false, true})
  void cudaWarmupRemainsStrictForUnknownOrPositivePolicy(boolean knownPolicy, @TempDir Path dir)
      throws Exception {
    try (var fixture = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L);
        var composition = fixture.composition()) {
      InferenceSurface surface = withGpuPolicy(fixture.freshSurface(true), knownPolicy, dir);
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any())).thenReturn(surface);
      assertThrows(SettingsCommitOwner.Refused.class, fixture::prepare,
          "an intended CUDA role must not accept an unexplained CPU fallback");
      fixture.assertA();
    }
  }

  @ParameterizedTest(name = "cudaPublicationRemainsStrictForUnknownOrPositivePolicy({0})")
  @ValueSource(booleans = {false, true})
  void cudaPublicationRemainsStrictForUnknownOrPositivePolicy(boolean knownPolicy, @TempDir Path dir)
      throws Exception {
    try (var fixture = new KnowledgeServerQuerySettingsOwnerTest.QueryFixture(dir, 4096L);
        var composition = fixture.composition()) {
      InferenceSurface surface = withGpuPolicy(fixture.freshSurface(false), knownPolicy, dir);
      composition.when(() -> InferenceCompositionRoot.composeQueryRoles(any(), any(), any(), any(),
          any())).thenReturn(surface);
      var prepared = fixture.prepare();
      try {
        when(surface.reranker().orElseThrow().sessions().isGpuAvailable()).thenReturn(false);
        assertThrows(IllegalStateException.class, prepared::validate,
            "a required CUDA device must remain realized through publication");
      } finally {
        prepared.abort();
      }
      fixture.assertA();
    }
  }

  private static ResolvedConfig plannedConfiguration(Path data, Path reranker) {
    return new ResolvedConfigBuilder().putDefault(EnvRegistry.DATA_DIR.configKey(), data.toString())
        .putDefault(EnvRegistry.RERANK_MODEL_PATH.configKey(), reranker.toString())
        .putDefault(EnvRegistry.RERANK_GPU_ENABLED.configKey(), "true")
        .contributeAutoDetected(Map.of(EnvRegistry.GPU_MEMORY_PLAN.configKey(), "p12")).build();
  }

  private static InferenceSurface withGpuPolicy(InferenceSurface surface, boolean known, Path dir) {
    PolicySnapshot policies = null;
    if (known) {
      var variant = VariantSelection.optimal(dir.resolve("source-reranker/model_fp16.onnx"),
          ModelPrecision.FP16, ExecutionProvider.CUDA);
      var policy = new ModelSessionPolicy(variant,
          new ModelSessionPolicy.Gpu(64L * 1024 * 1024, 0, Optional.empty()),
          new ModelSessionPolicy.Cpu(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT),
          new ModelSessionPolicy.Lifecycle(true, false, 60_000),
          new ModelSessionPolicy.RunOptions(true));
      policies = new PolicySnapshot(RuntimePolicy.defaults(), Map.of(EncoderRole.RERANKER, policy));
    }
    return new InferenceSurface(surface.embedding(), surface.ner(), surface.reranker(),
        surface.citation(), surface.splade(), surface.bgeM3(), policies, surface.handles(),
        surface.componentObservation(), surface.handleRoles());
  }
}
