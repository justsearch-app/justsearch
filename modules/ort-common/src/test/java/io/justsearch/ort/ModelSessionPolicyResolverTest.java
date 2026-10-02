package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ModelSessionPolicyResolver} per-encoder rules.
 *
 * <p>Each test resolves a policy for one encoder role × one variant and asserts the fields match
 * today's hardcoded defaults (tempdoc 397 §8). Equivalence with the legacy factory's produced
 * {@code SessionOptions} is covered by {@link OrtSessionAssemblerEquivalenceTest}; this suite
 * validates the resolver in isolation.
 */
@DisplayName("ModelSessionPolicyResolver")
class ModelSessionPolicyResolverTest {

  private static final ResolvedConfig CFG = TestResolvedConfigHelper.withDefaults();
  private static final HardwareProfile HW = HardwareProfile.gpuFull(12_000_000_000L);

  private static final long BYTES_PER_MB = 1024L * 1024L;

  @Test
  void fp16CudaSelectionUsesBasicOptimizationForEveryCpuFallback() {
    var variant = fp16OnCuda();
    for (EncoderRole role : EncoderRole.values()) {
      var policy = ModelSessionPolicyResolver.resolve(role, CFG, HW, variant);
      assertEquals(OptLevel.BASIC_OPT, policy.cpu().optLevel(), role.name());
      org.junit.jupiter.api.Assertions.assertSame(variant, policy.variant(),
          "CPU fallback policy must not substitute a variant or generation identity");
    }
  }

  /** Creates an optimal FP16-on-CUDA variant for use in most tests. */
  private static VariantSelection fp16OnCuda() {
    return VariantSelection.optimal(
        Path.of("model_fp16.onnx"), ModelPrecision.FP16, ExecutionProvider.CUDA);
  }

  /** Creates an optimal FP32-on-CPU variant. */
  private static VariantSelection fp32OnCpu() {
    return VariantSelection.optimal(
        Path.of("model.onnx"), ModelPrecision.FP32, ExecutionProvider.CPU);
  }

  // =========================================================================
  // Per-encoder rules.
  // =========================================================================

  @Nested
  @DisplayName("EMBEDDING: deferCpuSession follows variant EP, not cfg.gpuEnabled()")
  class Embedding {

    @Test
    @DisplayName("variant EP = CUDA → deferCpuSession=true (matches KnowledgeServer:620)")
    void variantOnCudaInducesDeferCpu() {
      var policy = resolveEmbedding(fp16OnCuda());
      assertTrue(
          policy.lifecycle().deferCpuSession(),
          "When variant runs on CUDA, the CPU session is deferred until first fallback");
    }

    @Test
    @DisplayName("variant EP = CPU → deferCpuSession=false (regardless of cfg.gpuEnabled)")
    void variantOnCpuDoesNotDefer() {
      var policy = resolveEmbedding(fp32OnCpu());
      assertFalse(
          policy.lifecycle().deferCpuSession(),
          "When variant runs on CPU, the CPU session is eager");
    }

    @Test
    @DisplayName("degraded variant (FP16 on CPU) → deferCpuSession=false — the key #2 fix")
    void degradedFp16OnCpuDoesNotDefer() {
      // This is the scenario that would have broken with my original implementation:
      // user configured embed.gpu_enabled=true but hardware has cudaFunctional=false, so
      // the variant selector drops to CPU EP. deferCpuSession must track the variant, not
      // the intended-config, otherwise we lazy-init a CPU session that's actually the
      // primary path — breaking first-inference latency.
      var degraded =
          VariantSelection.degraded(
              Path.of("model_fp16.onnx"),
              ModelPrecision.FP16,
              ExecutionProvider.CPU,
              "No CUDA — running FP16 on CPU (degraded)");
      var policy = resolveEmbedding(degraded);
      assertFalse(policy.lifecycle().deferCpuSession());
    }

    @Test
    void retryEnabledByDefault() {
      var policy = resolveEmbedding(fp16OnCuda());
      assertTrue(policy.lifecycle().gpuRetryEnabled());
      assertEquals(60_000L, policy.lifecycle().gpuRetryIntervalMs());
    }

    @Test
    void arenaCapFromResolvedConfig() {
      var policy = resolveEmbedding(fp16OnCuda());
      // ResolvedConfigBuilder.buildEmbedding defaults gpu_mem_mb to 6144 (691 §N/F-031)
      assertEquals(6144L * BYTES_PER_MB, policy.gpu().arenaCapBytes());
    }

    @Test
    void cpuOptLevelFromVariantPrecision() {
      // FP16 on CPU (degraded case) → BASIC_OPT
      var fp16CpuDegraded =
          VariantSelection.degraded(
              Path.of("model_fp16.onnx"),
              ModelPrecision.FP16,
              ExecutionProvider.CPU,
              "FP16 on CPU — pathological");
      var policy = resolveEmbedding(fp16CpuDegraded);
      assertEquals(OptLevel.BASIC_OPT, policy.cpu().optLevel());

      // FP32 on CPU → EXTENDED_OPT
      var policyFp32 = resolveEmbedding(fp32OnCpu());
      assertEquals(OptLevel.EXTENDED_OPT, policyFp32.cpu().optLevel());
    }

    private ModelSessionPolicy resolveEmbedding(VariantSelection variant) {
      return ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, CFG, HW, variant);
    }
  }

  @Nested
  @DisplayName("NER: gpuRetryEnabled=false by design (349 item 1)")
  class Ner {

    @Test
    void gpuRetryDisabled() {
      var policy =
          ModelSessionPolicyResolver.resolve(EncoderRole.NER, CFG, HW, fp16OnCuda());
      assertFalse(policy.lifecycle().gpuRetryEnabled());
    }

    @Test
    void arenaCapFromResolvedConfig() {
      var policy =
          ModelSessionPolicyResolver.resolve(EncoderRole.NER, CFG, HW, fp16OnCuda());
      // ResolvedConfigBuilder defaults NER gpu_mem_mb to 2048 (tempdoc 691: 512 OOMed the
      // fp16 variant's attention intermediates, degrading batched NER to per-doc fallback)
      assertEquals(2048L * BYTES_PER_MB, policy.gpu().arenaCapBytes());
    }
  }

  @Nested
  @DisplayName("SPLADE: retry enabled, arena 4096 MB")
  class Splade {

    @Test
    void defaults() {
      var policy =
          ModelSessionPolicyResolver.resolve(EncoderRole.SPLADE, CFG, HW, fp16OnCuda());
      assertTrue(policy.lifecycle().gpuRetryEnabled());
      assertFalse(policy.lifecycle().deferCpuSession());
      assertEquals(4096L * BYTES_PER_MB, policy.gpu().arenaCapBytes());
    }
  }

  @Nested
  @DisplayName("RERANKER: arena 2048 MB default")
  class Reranker {

    @Test
    void defaults() {
      var policy =
          ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, CFG, HW, fp16OnCuda());
      assertTrue(policy.lifecycle().gpuRetryEnabled());
      assertEquals(2048L * BYTES_PER_MB, policy.gpu().arenaCapBytes());
    }
  }

  @Nested
  @DisplayName("BGE_M3: retry enabled, arena from config")
  class BgeM3 {

    @Test
    void defaults() {
      var policy =
          ModelSessionPolicyResolver.resolve(EncoderRole.BGE_M3, CFG, HW, fp16OnCuda());
      assertTrue(policy.lifecycle().gpuRetryEnabled());
    }
  }

  @Nested
  @DisplayName("CITATION: CPU-only, no GPU arena")
  class Citation {

    @Test
    void cpuOnly() {
      var policy =
          ModelSessionPolicyResolver.resolve(EncoderRole.CITATION, CFG, HW, fp32OnCpu());
      assertEquals(0L, policy.gpu().arenaCapBytes(), "Citation is CPU-only; no GPU arena");
      assertFalse(policy.lifecycle().gpuRetryEnabled());
    }
  }

  // =========================================================================
  // RunOptions defaults (Q1 resolution: runOptions is session-granular).
  // =========================================================================

  @Test
  @DisplayName("runOptions.arenaShrinkage=true by default (394 item 4 reverted)")
  void defaultRunOptionsShrinkageEnabled() {
    var policy =
        ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, CFG, HW, fp16OnCuda());
    assertTrue(policy.runOptions().arenaShrinkage());
  }

  // =========================================================================
  // Arena-cap role variance (addresses #9 critique — role-invariance test was weak).
  // =========================================================================

  @Test
  @DisplayName("arena caps differ per role — proves role-specific resolver behavior")
  void arenaCapsVaryPerRole() {
    long embed =
        ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, CFG, HW, fp16OnCuda())
            .gpu()
            .arenaCapBytes();
    long splade =
        ModelSessionPolicyResolver.resolve(EncoderRole.SPLADE, CFG, HW, fp16OnCuda())
            .gpu()
            .arenaCapBytes();
    long ner =
        ModelSessionPolicyResolver.resolve(EncoderRole.NER, CFG, HW, fp16OnCuda())
            .gpu()
            .arenaCapBytes();
    long reranker =
        ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, CFG, HW, fp16OnCuda())
            .gpu()
            .arenaCapBytes();
    long citation =
        ModelSessionPolicyResolver.resolve(EncoderRole.CITATION, CFG, HW, fp32OnCpu())
            .gpu()
            .arenaCapBytes();

    // Defaults from ResolvedConfigBuilder's per-encoder builders:
    //   embed 6144 (691 §N/F-031, was 3072), splade 4096, ner 2048 (691, was 512), reranker 2048 MB;
    //   citation CPU-only → 0.
    assertEquals(6144L * BYTES_PER_MB, embed);
    assertEquals(4096L * BYTES_PER_MB, splade);
    assertEquals(2048L * BYTES_PER_MB, ner);
    assertEquals(2048L * BYTES_PER_MB, reranker);
    assertEquals(0L, citation);

    // The former all-distinct tripwire (copy-paste guard) retired in tempdoc 691:
    // NER == reranker == 2048 MB is an intentional, independently-measured pairing
    // (each cap is an independent per-session budget, not a shared allocation).
    assertEquals(
        3,
        new java.util.HashSet<>(java.util.List.of(embed, splade, ner, reranker)).size(),
        "GPU encoder defaults: embed/splade distinct; ner == reranker intentionally");
  }

  @Test
  @DisplayName("CPU variant zeros arenaCapBytes — policy record is self-describing (§14.28 U2)")
  void cpuVariantProducesZeroArenaCap() {
    // Given: embedding configured with the default arena but the VARIANT resolved to CPU EP
    // (degraded hardware case: user configured GPU but hardware lacks CUDA).
    VariantSelection cpuVariant = fp32OnCpu();
    long arenaCap =
        ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, CFG, HW, cpuVariant)
            .gpu()
            .arenaCapBytes();
    // Expected: zero — resolver respects variant EP. NativeSessionHandle reads this directly
    // and skips GPU-session construction without needing a second "is this GPU?" branch.
    assertEquals(0L, arenaCap);

    // Symmetry check: same role, CUDA variant ⇒ non-zero.
    long cudaCap =
        ModelSessionPolicyResolver.resolve(EncoderRole.EMBEDDING, CFG, HW, fp16OnCuda())
            .gpu()
            .arenaCapBytes();
    assertEquals(6144L * BYTES_PER_MB, cudaCap);
  }
}
