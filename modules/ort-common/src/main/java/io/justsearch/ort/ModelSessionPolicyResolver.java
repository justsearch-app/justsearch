/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Optional;

/**
 * Resolves per-encoder {@link ModelSessionPolicy} from resolved-config + hardware + variant.
 *
 * <p>Pure function (tempdoc 397 §7.2). Reads only from its typed parameters. The composition root
 * is responsible for calling
 * {@link io.justsearch.configuration.model.VariantSelector#select(String, io.justsearch.configuration.model.InstallContract, HardwareProfile, java.nio.file.Path)}
 * (plus dev-mode filesystem-probe fallback) to produce a non-null {@link VariantSelection}
 * before invoking this resolver.
 *
 * <p>This class is the single home for:
 *
 * <ul>
 *   <li>{@link #deriveCpuOptLevel(ModelPrecision, ExecutionProvider)} — the one global derivation
 *       rule (FP16-on-CPU → BASIC_OPT; everything else → EXTENDED_OPT) that formerly fanned out
 *       across six call sites.
 *   <li>The "if variant runs on CUDA, defer CPU session" rule for embedding
 *       ({@code KnowledgeServer:620}). Keyed on {@link VariantSelection#executionProvider()}
 *       rather than {@code cfg.gpuEnabled()} because those diverge when the user configures
 *       GPU but hardware lacks CUDA (degraded variant drops to CPU EP).
 *   <li>The "NER gets {@code gpuRetryEnabled=false}" rule.
 * </ul>
 *
 * <p>395 A1/A4/A7 adaptive work (per-hardware arena sizing, concurrent session counts,
 * LLM-coexistence shrinkage) slots in here as new branches that read the {@link HardwareProfile}.
 * Stage 1 preserves today's hardcoded per-encoder defaults verbatim.
 */
public final class ModelSessionPolicyResolver {

  /** Default {@code GPU_RETRY_INTERVAL_MS} — moves from {@link NativeSessionHandle} constant. */
  private static final long DEFAULT_GPU_RETRY_INTERVAL_MS = 60_000L;

  private static final long BYTES_PER_MB = 1024L * 1024L;

  private ModelSessionPolicyResolver() {}

  /**
   * Resolves the policy for a single encoder role.
   *
   * @param role which encoder's policy to resolve
   * @param cfg resolved configuration snapshot
   * @param hardware detected hardware profile (reserved for 395 A1/A4/A7 adaptive work)
   * @param variant non-null variant selection for this role; caller handles null-variant skip
   * @return the resolved policy
   */
  public static ModelSessionPolicy resolve(
      EncoderRole role,
      ResolvedConfig cfg,
      HardwareProfile hardware,
      VariantSelection variant) {
    // Reserved for 395 A1/A4/A7 adaptive work; today the per-encoder policy does not vary with
    // hardware because 397 defers adaptive sizing to follow-up tempdocs.
    requireNonNull(cfg);
    requireNonNull(hardware);
    requireNonNull(variant);

    return switch (role) {
      case EMBEDDING -> resolveEmbedding(cfg.ai().embedding(), variant);
      case SPLADE -> resolveSplade(cfg.ai().splade(), variant);
      case NER -> resolveNer(cfg.ai().ner(), variant);
      case BGE_M3 -> resolveBgeM3(cfg.ai().bgeM3(), variant);
      case RERANKER -> resolveReranker(cfg.ai().reranker(), variant);
      case CITATION -> resolveCitation(cfg.ai().citationScorer(), variant);
    };
  }

  /**
   * Derives the CPU-session {@link OptLevel} from {@code (precision, EP)}.
   *
   * <p>Single home for this derivation (tempdoc 397 §14.13 Stage 4d absorbed the prior factory
   * copy). FP16 on CPU uses {@code BASIC_OPT} to avoid the catastrophic 30+ minute
   * {@code EXTENDED_OPT} where ORT inserts thousands of FP16→FP32 {@code Cast} nodes (tempdoc
   * 376 I4/I5). All other combinations use {@code EXTENDED_OPT}.
   */
  public static OptLevel deriveCpuOptLevel(ModelPrecision precision, ExecutionProvider ep) {
    if (precision == ModelPrecision.FP16 && ep != ExecutionProvider.CUDA) {
      return OptLevel.BASIC_OPT;
    }
    return OptLevel.EXTENDED_OPT;
  }

  // =========================================================================
  // Per-encoder resolution. Each mirrors today's hardcoded defaults from the
  // respective encoder's former buildSessionManager customizer (all deleted §14.10/§14.13).
  // =========================================================================

  private static ModelSessionPolicy resolveEmbedding(
      ResolvedConfig.Ai.Embedding cfg, VariantSelection variant) {
    // deferCpuSession keys on variant EP, not cfg.gpuEnabled() — matches KnowledgeServer:620
    // (`embedOnGpu = variant.executionProvider() == CUDA`). Diverges in the degraded-hardware
    // case (user configures GPU, hardware lacks CUDA, variant drops to CPU EP).
    boolean gpuActuallyUsed = variant.executionProvider() == ExecutionProvider.CUDA;
    // Both sessions use this selected artifact. Its CPU fallback always runs on the CPU EP,
    // independently of the preferred provider; keep the selected variant/generation unchanged.
    return new ModelSessionPolicy(
        variant,
        buildGpu(variant, cfg.gpuMemMb(), cfg.gpuDeviceId()),
        new ModelSessionPolicy.Cpu(
            deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU)),
        new ModelSessionPolicy.Lifecycle(
            /* deferCpuSession= */ gpuActuallyUsed,
            /* gpuRetryEnabled= */ true,
            DEFAULT_GPU_RETRY_INTERVAL_MS),
        defaultRunOptions());
  }

  private static ModelSessionPolicy resolveSplade(
      ResolvedConfig.Ai.Splade cfg, VariantSelection variant) {
    return new ModelSessionPolicy(
        variant,
        buildGpu(variant, cfg.gpuMemMb(), cfg.gpuDeviceId()),
        new ModelSessionPolicy.Cpu(
            deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU)),
        new ModelSessionPolicy.Lifecycle(
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ true,
            DEFAULT_GPU_RETRY_INTERVAL_MS),
        defaultRunOptions());
  }

  private static ModelSessionPolicy resolveNer(
      ResolvedConfig.Ai.Ner cfg, VariantSelection variant) {
    return new ModelSessionPolicy(
        variant,
        buildGpu(variant, cfg.gpuMemMb(), cfg.gpuDeviceId()),
        new ModelSessionPolicy.Cpu(
            deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU)),
        new ModelSessionPolicy.Lifecycle(
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ false, // NER-specific: disable retry (349 item 1)
            DEFAULT_GPU_RETRY_INTERVAL_MS),
        defaultRunOptions());
  }

  private static ModelSessionPolicy resolveBgeM3(
      ResolvedConfig.Ai.BgeM3 cfg, VariantSelection variant) {
    return new ModelSessionPolicy(
        variant,
        buildGpu(variant, cfg.gpuMemMb(), cfg.gpuDeviceId()),
        new ModelSessionPolicy.Cpu(
            deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU)),
        new ModelSessionPolicy.Lifecycle(
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ true,
            DEFAULT_GPU_RETRY_INTERVAL_MS),
        defaultRunOptions());
  }

  private static ModelSessionPolicy resolveReranker(
      ResolvedConfig.Ai.Reranker cfg, VariantSelection variant) {
    return new ModelSessionPolicy(
        variant,
        buildGpu(variant, cfg.gpuMemMb(), cfg.gpuDeviceId()),
        new ModelSessionPolicy.Cpu(
            deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU)),
        new ModelSessionPolicy.Lifecycle(
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ true,
            DEFAULT_GPU_RETRY_INTERVAL_MS),
        defaultRunOptions());
  }

  /**
   * Citation scorer is CPU-only by design. GPU fields still populated with defaults (device 0,
   * zero arena) so the record is uniform; the assembler picks the CPU branch based on
   * {@code variant.executionProvider()}.
   */
  private static ModelSessionPolicy resolveCitation(
      ResolvedConfig.Ai.CitationScorer cfg, VariantSelection variant) {
    requireNonNull(cfg); // threshold/maxSeqLen/deadline belong to the caller, not to policy
    return new ModelSessionPolicy(
        variant,
        new ModelSessionPolicy.Gpu(
            /* arenaCapBytes= */ 0L, // CPU-only; no GPU arena allocated
            /* cudaDeviceId= */ 0,
            Optional.empty()),
        new ModelSessionPolicy.Cpu(
            deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU)),
        new ModelSessionPolicy.Lifecycle(
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ false,
            DEFAULT_GPU_RETRY_INTERVAL_MS),
        defaultRunOptions());
  }

  // =========================================================================
  // Helpers.
  // =========================================================================

  /**
   * Builds the GPU sub-record. Tempdoc 397 §14.28 U2: when the variant's EP is CPU we zero out
   * {@code arenaCapBytes}, so the record is <em>self-describing</em> — {@code arenaCapBytes > 0}
   * ⇔ session will run on GPU. The pre-U2 behaviour populated {@code arenaCapBytes} from
   * {@code cfg.gpuMemMb} unconditionally, which forced {@link NativeSessionHandle} to inspect
   * {@code policy.variant()} as a second source of truth for the same decision (the §14.28
   * critical-review duality). Zeroing at the resolver collapses the handle's derivation to one
   * branch.
   */
  private static ModelSessionPolicy.Gpu buildGpu(
      VariantSelection variant, int gpuMemMb, int gpuDeviceId) {
    long arenaCapBytes =
        variant.executionProvider() == ExecutionProvider.CUDA
            ? (long) gpuMemMb * BYTES_PER_MB
            : 0L;
    return new ModelSessionPolicy.Gpu(
        arenaCapBytes,
        gpuDeviceId,
        Optional.empty()); // arenaExtendStrategyOverride reserved for per-session experiments
  }

  /** Today's default RunOptions — shrinkage enabled. */
  private static ModelSessionPolicy.RunOptions defaultRunOptions() {
    return new ModelSessionPolicy.RunOptions(/* arenaShrinkage= */ true);
  }

  private static <T> T requireNonNull(T value) {
    if (value == null) {
      throw new IllegalArgumentException("resolver parameter must not be null");
    }
    return value;
  }
}
