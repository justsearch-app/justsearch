/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.model.VariantSelection;
import java.util.Optional;

/**
 * Per-encoder session-configuration decisions. Resolved from
 * {@link io.justsearch.configuration.resolved.ResolvedConfig.Ai} sub-records +
 * {@link io.justsearch.configuration.model.HardwareProfile} + a {@link VariantSelection}.
 *
 * <p>Per Q1 resolution (tempdoc 397 addendum §20): {@code CallPolicy} is collapsed into the
 * nested {@code runOptions} sub-record; {@link ai.onnxruntime.OrtSession.RunOptions} is
 * constructed once per session from these values. If per-call variance ever surfaces
 * (scheduler-driven priority, per-request deadlines), {@code runOptions} promotes to a third
 * tier.
 *
 * <p>Policy is about <em>how to run</em>, not <em>what the model is</em>. Model metadata (label
 * indices, input schema, tokenizer path) lives in
 * {@link io.justsearch.configuration.model.ModelVariant} and is not duplicated here.
 *
 * <p>Fields are deliberately minimal in Stage 1 — only values that are actually applied by
 * {@link OrtSessionAssembler} or consumed by the {@link NativeSessionHandle}-equivalent runtime
 * are modelled. Reserved fields for 394-P3 scheduler ({@code priority}, {@code deadlineNs}),
 * 395-A7-iii shared allocator ({@code streamBinding}), and per-session shrinkage override
 * ({@code disableArenaShrinkage}) have been deferred to the Stage that consumes them — removing
 * them from Stage 1 avoids modelling decisions without evidence of use.
 *
 * @param variant 381's existing variant selection record
 * @param gpu CUDA-EP per-session options actually applied by the assembler
 * @param cpu CPU-session-specific options
 * @param lifecycle runtime lifecycle thresholds consumed by the {@code SessionHandle} Stage 2
 *     introduces
 * @param runOptions values applied to {@link ai.onnxruntime.OrtSession.RunOptions} at session
 *     build time
 */
public record ModelSessionPolicy(
    VariantSelection variant, Gpu gpu, Cpu cpu, Lifecycle lifecycle, RunOptions runOptions) {

  /**
   * Synthesises a minimal {@link ModelSessionPolicy} for the {@code verifyModel} Gradle task
   * (tempdoc 397 §14.24 FA). The verifier needs only {@link Gpu#arenaCapBytes} and
   * {@link Gpu#cudaDeviceId} from the supplied {@link GpuSessionConfig}; all other fields take
   * their default values. The synthesised {@link VariantSelection} is intentionally null because
   * the verifier does not consult it — {@link OrtSessionAssembler#verifyModelSession} drives
   * session creation directly from the {@link GpuSessionConfig} + the verifier's
   * {@code modelPath} argument.
   *
   * <p>Called only by {@link OrtSessionAssembler#verifyModelSession}; not intended for
   * production session construction, which flows through {@link ModelSessionPolicyResolver}.
   */
  public static ModelSessionPolicy forVerification(GpuSessionConfig gpuConfig) {
    return new ModelSessionPolicy(
        /* variant= */ null,
        new Gpu(gpuConfig.gpuMemLimitBytes(), gpuConfig.gpuDeviceId(), Optional.empty()),
        new Cpu(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.EXTENDED_OPT),
        new Lifecycle(false, false, 0L),
        new RunOptions(/* arenaShrinkage= */ true));
  }

  /**
   * Synthesises a {@link ModelSessionPolicy} for fallback session construction (tempdoc 397
   * §14.26 T1-B). Replaces the former {@link NativeSessionHandle}-internal synthesis block that
   * absorbed flat Builder fields ({@code .gpuConfig}, {@code .deferCpuSession}, {@code .cpuOptLevel},
   * {@code .gpuRetryEnabled}, {@code .gpuRetryIntervalMs}) into a policy record. After T1-B the
   * Builder accepts only {@code .runtime} + {@code .policy} for policy inputs; this factory is
   * the one site where scalar inputs become a policy record, mirroring
   * {@link #forVerification(GpuSessionConfig)} for the verifier.
   *
   * <p>Called by the testFixtures helper {@code InferenceCompositionRootTestHelper.sessionFor}
   * (post-§14.28 U1); not intended for variant-driven production paths, which route through
   * {@link ModelSessionPolicyResolver}.
   *
   * @param gpuConfig GPU session config; {@code null} for CPU-only sessions
   * @param cpuOptLevel CPU session optimisation level; {@code null} yields
   *     {@link OptLevel#EXTENDED_OPT}
   * @param deferCpuSession lazy-create the CPU session on first fallback
   * @param gpuRetryEnabled retry GPU session creation after failure
   * @param gpuRetryIntervalMs interval between retry attempts (typically
   *     {@code NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS})
   */
  public static ModelSessionPolicy forFallback(
      GpuSessionConfig gpuConfig,
      OptLevel cpuOptLevel,
      boolean deferCpuSession,
      boolean gpuRetryEnabled,
      long gpuRetryIntervalMs) {
    Gpu gpu =
        gpuConfig != null
            ? new Gpu(gpuConfig.gpuMemLimitBytes(), gpuConfig.gpuDeviceId(), Optional.empty())
            : new Gpu(0L, 0, Optional.empty());
    return new ModelSessionPolicy(
        /* variant= */ null,
        gpu,
        new Cpu(cpuOptLevel != null ? cpuOptLevel : OptLevel.EXTENDED_OPT),
        new Lifecycle(deferCpuSession, gpuRetryEnabled, gpuRetryIntervalMs),
        new RunOptions(/* arenaShrinkage= */ true));
  }

  /**
   * CUDA-EP options scoped to one encoder's session.
   *
   * @param arenaCapBytes {@code gpu_mem_limit} in bytes (from
   *     {@code cfg.ai().{role}().gpuMemMb()})
   * @param cudaDeviceId target CUDA device index
   * @param arenaExtendStrategyOverride optional per-session override of
   *     {@link RuntimePolicy.Arena#extendStrategy()} (for the embed-only {@code kNextPowerOfTwo}
   *     experiment from 394); empty = use the runtime-wide default
   */
  public record Gpu(
      long arenaCapBytes, int cudaDeviceId, Optional<String> arenaExtendStrategyOverride) {}

  /**
   * CPU-session options. Today's only field is {@link OptLevel}, derived from
   * {@code variant.precision()} with the CPU execution provider per the rule that
   * {@link ModelSessionPolicyResolver#deriveCpuOptLevel} encodes.
   *
   * @param optLevel graph-optimisation level applied to the CPU session
   */
  public record Cpu(OptLevel optLevel) {}

  /**
   * Runtime lifecycle thresholds. Consumed today by {@link NativeSessionHandle}; consumed by
   * {@code SessionHandle} once Stage 2 introduces it.
   *
   * @param deferCpuSession lazy-create the CPU session on first fallback
   * @param gpuRetryEnabled retry GPU session creation after failure
   * @param gpuRetryIntervalMs interval between retry attempts (replacement for
   *     {@code NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS})
   */
  public record Lifecycle(
      boolean deferCpuSession, boolean gpuRetryEnabled, long gpuRetryIntervalMs) {}

  /**
   * Values applied to {@link ai.onnxruntime.OrtSession.RunOptions} at session build time.
   * Session-granular per Q1 resolution; {@link OrtSessionAssembler} constructs a single
   * {@code RunOptions} instance per session from these values.
   *
   * @param arenaShrinkage enable memory arena shrinkage on {@link Gpu#cudaDeviceId()} (default
   *     true; 394 item 4 explored per-session disable then reverted)
   */
  public record RunOptions(boolean arenaShrinkage) {}
}
