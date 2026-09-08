package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RuntimePolicyResolver}. The runtime policy is hardware-independent in Stage 1
 * (tempdoc 397 §13); this suite asserts that the resolver produces today's hardcoded defaults
 * across all input variations.
 *
 * <p>Adaptive sizing (395 A1/A4/A7) will introduce hardware-dependent branches; additional
 * parametric cases should land in this suite at that time.
 */
@DisplayName("RuntimePolicyResolver")
class RuntimePolicyResolverTest {

  private static final ResolvedConfig CFG = TestResolvedConfigHelper.withDefaults();

  @Test
  @DisplayName("arena strategy = kSameAsRequested on all hardware tiers")
  void arenaStrategyIsKSameAsRequested() {
    RuntimePolicy onGpuFull = RuntimePolicyResolver.resolve(CFG, HardwareProfile.gpuFull(12_000_000_000L));
    RuntimePolicy onCpuOnly = RuntimePolicyResolver.resolve(CFG, HardwareProfile.cpuOnly());
    RuntimePolicy onSandbox = RuntimePolicyResolver.resolve(CFG, HardwareProfile.gpuDetectedNoCuda(8_000_000_000L));

    assertEquals("kSameAsRequested", onGpuFull.arena().extendStrategy());
    assertEquals("kSameAsRequested", onCpuOnly.arena().extendStrategy());
    assertEquals("kSameAsRequested", onSandbox.arena().extendStrategy());
  }

  @Test
  @DisplayName("PR 0b: intraOpThreads is UNSET by default — ORT keeps its own choice")
  void intraOpThreadsUnsetByDefault() {
    // The default must be null, not a number: any number would silently re-partition every
    // GEMM's reduction on every existing deployment, which is a behaviour change disguised as
    // a default.
    assertNull(
        RuntimePolicyResolver.resolve(CFG, HardwareProfile.gpuFull(12_000_000_000L))
            .session().intraOpThreads());
    assertNull(
        RuntimePolicyResolver.resolve(CFG, HardwareProfile.cpuOnly()).session().intraOpThreads());
  }

  @Test
  @DisplayName("PR 0b: a positive justsearch.onnxruntime.intra_op_threads reaches the session")
  void intraOpThreadsPinReachesTheSession() {
    ResolvedConfig pinned =
        TestResolvedConfigHelper.fromEntries(
            java.util.Map.of("justsearch.onnxruntime.intra_op_threads", "1"));
    assertEquals(
        1,
        RuntimePolicyResolver.resolve(pinned, HardwareProfile.cpuOnly())
            .session().intraOpThreads());
  }

  @Test
  @DisplayName("PR 0b: a non-positive intra-op count is ignored rather than passed to ORT")
  void intraOpThreadsNonPositiveIsIgnored() {
    // ORT rejects a non-positive intra-op count; resolving it to null leaves ORT's default
    // instead of failing session creation on a typo.
    for (String bad : java.util.List.of("0", "-1")) {
      ResolvedConfig cfg =
          TestResolvedConfigHelper.fromEntries(
              java.util.Map.of("justsearch.onnxruntime.intra_op_threads", bad));
      assertNull(
          RuntimePolicyResolver.resolve(cfg, HardwareProfile.cpuOnly()).session().intraOpThreads(),
          "intra_op_threads=" + bad + " must leave ORT's default");
    }
  }

  @Test
  @DisplayName("memoryPatternOptimization = false (tempdoc 311/349)")
  void memoryPatternOptimizationIsFalse() {
    RuntimePolicy policy = RuntimePolicyResolver.resolve(CFG, HardwareProfile.cpuOnly());
    assertFalse(policy.arena().memoryPatternOptimization());
  }

  @Test
  @DisplayName("CUDA provider — matches pre-FA hardcoded values (parity baseline)")
  void cudaProviderDefaults() {
    RuntimePolicy policy = RuntimePolicyResolver.resolve(CFG, HardwareProfile.gpuFull(12_000_000_000L));
    var cuda = policy.cudaProvider();

    assertFalse(cuda.cudaGraphsEnabled(), "CUDA graphs disabled — allows arena shrinkage (311)");
    assertTrue(cuda.tunableOpEnabled(), "TunableOp framework enabled (334 Phase 10)");
    assertFalse(cuda.tunableOpTuningEnabled(), "Runtime tuning disabled — no persistence (334)");
    assertTrue(cuda.cudnnMaxWorkspace(), "cuDNN max workspace for FP16 tensor cores");
    assertTrue(cuda.epLevelUnifiedStream(), "Single CUDA stream per EP instance");
  }

  @Test
  @DisplayName("session options — matches SessionOptionsApplier.applyBase on defaults")
  void sessionDefaults() {
    RuntimePolicy policy = RuntimePolicyResolver.resolve(CFG, HardwareProfile.gpuFull(12_000_000_000L));
    var session = policy.session();

    assertEquals(1, session.interOpThreads(), "Reduce CPU contention (349)");
    assertFalse(session.allowSpinning(), "Reduce CPU contention");
    assertTrue(session.forceSpinningStop(), "Stop spinning when last op completes (334 Phase 10)");
    assertTrue(session.useDeviceAllocatorForInitializers(), "Weights bypass arena (311)");
  }

  @Test
  @DisplayName("hardware-independence in Stage 1 (adaptive branches land in 395 A1/A4/A7)")
  void runtimePolicyIsIdenticalAcrossHardwareTiers() {
    var gpuFull = RuntimePolicyResolver.resolve(CFG, HardwareProfile.gpuFull(24_000_000_000L));
    var gpuLite = RuntimePolicyResolver.resolve(CFG, HardwareProfile.gpuFull(8_000_000_000L));
    var cpuOnly = RuntimePolicyResolver.resolve(CFG, HardwareProfile.cpuOnly());

    assertEquals(gpuFull, gpuLite);
    assertEquals(gpuFull, cpuOnly);
  }

  @Test
  @DisplayName("profiling defaults to empty / disabled (tempdoc 397 §14.24 FB)")
  void profilingDefaultsToEmpty() {
    RuntimePolicy policy = RuntimePolicyResolver.resolve(CFG, HardwareProfile.cpuOnly());
    assertTrue(policy.profiling().ortProfilingDir().isEmpty());
    assertFalse(policy.profiling().verboseLogging());
  }

  @Test
  @DisplayName("profiling fields populated from cfg.ai().profiling() (tempdoc 397 §14.24 FB)")
  void profilingFlowsFromResolvedConfig() {
    java.nio.file.Path profDir = java.nio.file.Path.of("/tmp/ort-prof");
    ResolvedConfig cfg =
        TestResolvedConfigHelper.fromEntries(
            java.util.Map.of(
                "justsearch.ort.profiling_dir", profDir.toString(),
                "justsearch.ort.verbose", "true"));
    RuntimePolicy policy = RuntimePolicyResolver.resolve(cfg, HardwareProfile.cpuOnly());

    assertTrue(policy.profiling().ortProfilingDir().isPresent());
    assertEquals(profDir, policy.profiling().ortProfilingDir().get());
    assertTrue(policy.profiling().verboseLogging());
  }
}
