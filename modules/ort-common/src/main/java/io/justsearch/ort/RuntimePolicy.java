/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.nio.file.Path;
import java.util.Optional;

/**
 * JVM-wide session-configuration decisions that apply identically to every encoder.
 *
 * <p>Resolved once at boot by {@link RuntimePolicyResolver} from
 * {@link io.justsearch.configuration.resolved.ResolvedConfig} +
 * {@link io.justsearch.configuration.model.HardwareProfile}, then handed to every {@link
 * Composition} assembled during the lifetime of the process.
 *
 * <p>Sub-records group related options by ORT's own surface (arena on the CUDA provider options;
 * session knobs on {@link ai.onnxruntime.OrtSession.SessionOptions}; diagnostic knobs on {@link
 * Profiling}) so that adding a field lands in one spot and so positional constructors stay short
 * (tempdoc 301 warned against wide flat records).
 *
 * <p>Tempdoc 397 §14.24 FB added {@link Profiling}, closing the last env-var bypass. Every
 * setter value — including {@code JUSTSEARCH_ORT_PROFILING_DIR} and {@code JUSTSEARCH_ORT_VERBOSE}
 * — now flows through the single typed chain: {@code EnvRegistry} → {@code ResolvedConfig.Ai.Profiling}
 * → {@link RuntimePolicyResolver} → {@link RuntimePolicy} → {@link SessionOptionsApplier}. The
 * {@code /api/debug/session-policies} endpoint's {@code runtime.profiling} section reflects the
 * live values.
 *
 * <p>{@code CudaProvider.useEnvAllocators} is reserved for 395 A7-iii shared-allocator work.
 *
 * @param arena arena / memory-pattern knobs
 * @param cudaProvider CUDA-EP provider options
 * @param session {@link ai.onnxruntime.OrtSession.SessionOptions} thread/execution knobs
 * @param profiling diagnostic observability knobs (profiling dir + verbose logging)
 */
public record RuntimePolicy(
    Arena arena, CudaProvider cudaProvider, Session session, Profiling profiling) {

  /**
   * Returns today's default runtime policy — the single source of truth for "what every ORT
   * session looks like when no caller-supplied policy overrides apply."
   *
   * <p>Tempdoc 397 §14.24 FA: used by {@link OrtSessionAssembler#verifyModelSession} and by
   * test-fixture / benchmark callers (via {@code InferenceCompositionRootTestHelper}) that don't
   * have a {@link io.justsearch.configuration.resolved.ResolvedConfig} in scope. Production paths
   * resolve via {@link RuntimePolicyResolver#resolve} and pass the result through the
   * {@link Composition} record.
   */
  public static RuntimePolicy defaults() {
    return new RuntimePolicy(
        new Arena("kSameAsRequested", /* memoryPatternOptimization= */ false),
        new CudaProvider(
            /* cudaGraphsEnabled= */ false,
            /* tunableOpEnabled= */ true,
            /* tunableOpTuningEnabled= */ false,
            /* cudnnMaxWorkspace= */ true,
            /* epLevelUnifiedStream= */ true),
        new Session(
            /* interOpThreads= */ 1,
            /* allowSpinning= */ false,
            /* forceSpinningStop= */ true,
            /* useDeviceAllocatorForInitializers= */ true),
        new Profiling(/* ortProfilingDir= */ Optional.empty(), /* verboseLogging= */ false));
  }

  /**
   * Arena extension strategy and memory-pattern optimisation flags. Today's default:
   * {@code kSameAsRequested} + {@code memoryPatternOptimization=false} (tempdoc 311/349).
   *
   * @param extendStrategy CUDA arena extend strategy ({@code kSameAsRequested} or
   *     {@code kNextPowerOfTwo})
   * @param memoryPatternOptimization pass-through to
   *     {@link ai.onnxruntime.OrtSession.SessionOptions#setMemoryPatternOptimization(boolean)}
   */
  public record Arena(String extendStrategy, boolean memoryPatternOptimization) {}

  /**
   * CUDA-EP provider options applied to every encoder's CUDA session. Walked by
   * {@link SessionOptionsApplier#applyCudaProviderOptions}.
   */
  public record CudaProvider(
      boolean cudaGraphsEnabled,
      boolean tunableOpEnabled,
      boolean tunableOpTuningEnabled,
      boolean cudnnMaxWorkspace,
      boolean epLevelUnifiedStream) {}

  /**
   * Per-session thread / execution knobs on
   * {@link ai.onnxruntime.OrtSession.SessionOptions}. Walked by
   * {@link SessionOptionsApplier#applyBase} (CPU + GPU base) and
   * {@link SessionOptionsApplier#applyGpuSessionOptions} (GPU-only deltas).
   */
  public record Session(
      int interOpThreads,
      boolean allowSpinning,
      boolean forceSpinningStop,
      boolean useDeviceAllocatorForInitializers,
      Integer intraOpThreads) {

    /**
     * Back-compat constructor (pre-lane-F-PR-0b): {@code intraOpThreads} unset, which is
     * ONNX Runtime's own default and therefore today's behaviour exactly.
     */
    public Session(
        int interOpThreads,
        boolean allowSpinning,
        boolean forceSpinningStop,
        boolean useDeviceAllocatorForInitializers) {
      this(interOpThreads, allowSpinning, forceSpinningStop, useDeviceAllocatorForInitializers,
          null);
    }
  }

  /**
   * Diagnostic observability knobs (tempdoc 397 §14.24 FB). Both fields default to
   * disabled; operators flip them via the {@code JUSTSEARCH_ORT_PROFILING_DIR} and
   * {@code JUSTSEARCH_ORT_VERBOSE} env vars (or the corresponding {@code justsearch.ort.*}
   * system properties). {@link SessionOptionsApplier} reads these fields and applies
   * {@code enableProfiling} / {@code setSessionLogLevel} on each session; no {@code System.getenv}
   * call remains in the apply path.
   *
   * @param ortProfilingDir directory for per-session profile files; empty = disabled
   * @param verboseLogging enables ORT VERBOSE-level session logging
   */
  public record Profiling(Optional<Path> ortProfilingDir, boolean verboseLogging) {}
}
