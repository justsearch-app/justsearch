/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;

/**
 * Applies {@link RuntimePolicy} + {@link ModelSessionPolicy} fields to ORT setter surfaces.
 *
 * <p>Tempdoc 397 §14.24 FA: single apply site that makes {@link RuntimePolicy} causally determine
 * session behavior. Before FA, both {@link NativeSessionHandle} and
 * {@link OrtSessionAssembler#verifyModelSession} contained independent hardcoded copies of the
 * same CUDA / GPU session-option list — the 394 item-4 failure shape the §6 closure property was
 * meant to eliminate. After FA, every setter reads a record field; two call paths can no longer
 * silently drift.
 *
 * <p>Package-private by design: only {@link NativeSessionHandle} (CPU + GPU session construction)
 * and {@link OrtSessionAssembler} ({@code verifyModelSession}) call into this class. External
 * callers reach ORT session construction via {@link OrtSessionAssembler#buildManager}; they never
 * touch the applier directly.
 */
final class SessionOptionsApplier {

  private SessionOptionsApplier() {}

  /**
   * Applies the base session options shared by every session (CPU + GPU): inter-op threads,
   * allow_spinning, memoryPatternOptimization, and diagnostic verbose logging.
   *
   * <p>Tempdoc 397 §14.24 FB: verbose logging reads from {@code runtime.profiling().verboseLogging()}
   * instead of {@link System#getenv} — closes the last env-var bypass of the closure property.
   */
  static void applyBase(RuntimePolicy runtime, SessionOptions opts) throws OrtException {
    RuntimePolicy.Session session = runtime.session();
    opts.setInterOpNumThreads(session.interOpThreads());
    // Lane F PR 0b — pin the intra-op pool when asked. ORT otherwise sizes it from hardware
    // concurrency, and on the CPU execution provider the intra-op thread count decides how a
    // GEMM's reduction is partitioned: a different partition sums the same floats in a different
    // ORDER, so the low bits of an embedding move with the thread count. That is invisible on one
    // machine and a silent difference across two, which is exactly what a deterministic capture
    // cannot tolerate. Null (the default, and every caller before this) leaves ORT's own choice
    // untouched, so behaviour is unchanged unless the knob is set.
    if (session.intraOpThreads() != null) {
      opts.setIntraOpNumThreads(session.intraOpThreads());
    }
    opts.addConfigEntry(
        "session.intra_op.allow_spinning", session.allowSpinning() ? "1" : "0");
    opts.setMemoryPatternOptimization(runtime.arena().memoryPatternOptimization());
    if (runtime.profiling().verboseLogging()) {
      opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
    }
  }

  /**
   * Applies the GPU-only session-option deltas on top of {@link #applyBase}: device_allocator,
   * force_spinning_stop, and — per §14.24 FB — the {@code ortProfilingDir} diagnostic surface.
   *
   * <p>Caller must invoke {@link #applyBase} first and {@code opts.addCUDA(cudaOpts)} separately.
   * The setter order inside this method matches the former
   * {@code NativeSessionHandle.configureGpuSessionOptions}.
   */
  static void applyGpuSessionOptions(RuntimePolicy runtime, SessionOptions opts)
      throws OrtException {
    RuntimePolicy.Session session = runtime.session();
    // Variable-length sequences on GPU defeat shape-based memory planning. GPU-specific override
    // of the base memoryPatternOptimization; if arena.memoryPatternOptimization is already false
    // (today's default), this is idempotent.
    opts.setMemoryPatternOptimization(runtime.arena().memoryPatternOptimization());
    opts.addConfigEntry(
        "session.use_device_allocator_for_initializers",
        session.useDeviceAllocatorForInitializers() ? "1" : "0");
    opts.addConfigEntry(
        "session.force_spinning_stop", session.forceSpinningStop() ? "1" : "0");
    // 334 Phase 11 / §14.24 FB: profiling directory reads from the typed policy, not
    // System.getenv. Empty Optional = disabled.
    if (runtime.profiling().ortProfilingDir().isPresent()) {
      opts.enableProfiling(
          runtime.profiling().ortProfilingDir().get().toString() + "/ort_profile");
    }
  }

  /**
   * Applies CUDA provider options from {@link RuntimePolicy#cudaProvider} +
   * {@link RuntimePolicy#arena} + {@link ModelSessionPolicy#gpu}.
   *
   * <p>Setter order matches the former
   * {@code NativeSessionHandle.configureCudaProviderOptions}. The
   * {@code arenaExtendStrategyOverride} on {@code policy.gpu} takes precedence when present —
   * reserved for 394 item-4-style per-session experiments; default (always empty today) yields
   * the runtime-wide {@link RuntimePolicy.Arena#extendStrategy}.
   */
  static void applyCudaProviderOptions(
      RuntimePolicy runtime, ModelSessionPolicy policy, OrtCUDAProviderOptions cudaOpts)
      throws OrtException {
    RuntimePolicy.CudaProvider cuda = runtime.cudaProvider();
    cudaOpts.add("gpu_mem_limit", String.valueOf(policy.gpu().arenaCapBytes()));
    String arenaStrategy =
        policy.gpu().arenaExtendStrategyOverride().orElse(runtime.arena().extendStrategy());
    cudaOpts.add("arena_extend_strategy", arenaStrategy);
    cudaOpts.add("enable_cuda_graph", cuda.cudaGraphsEnabled() ? "1" : "0");
    cudaOpts.add("tunable_op_enable", cuda.tunableOpEnabled() ? "1" : "0");
    cudaOpts.add("tunable_op_tuning_enable", cuda.tunableOpTuningEnabled() ? "1" : "0");
    cudaOpts.add("cudnn_conv_use_max_workspace", cuda.cudnnMaxWorkspace() ? "1" : "0");
    cudaOpts.add("use_ep_level_unified_stream", cuda.epLevelUnifiedStream() ? "1" : "0");
  }

  /**
   * Builds a GPU {@link OrtSession.RunOptions} instance from
   * {@link ModelSessionPolicy.RunOptions#arenaShrinkage}. Before §14.24 FA, this entry was set
   * unconditionally; the policy field was read-only. After FA, the field causally determines
   * whether the run-config entry fires.
   *
   * <p>Returned RunOptions must be closed by the caller (typically at
   * {@link NativeSessionHandle#releaseGpu()} or {@link NativeSessionHandle#close()}).
   */
  static OrtSession.RunOptions buildGpuRunOptions(ModelSessionPolicy policy) throws OrtException {
    OrtSession.RunOptions runOptions = new OrtSession.RunOptions();
    if (policy.runOptions().arenaShrinkage()) {
      runOptions.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "gpu:0");
    }
    return runOptions;
  }
}
