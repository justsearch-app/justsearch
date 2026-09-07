/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Optional;

/**
 * Resolves the JVM-wide {@link RuntimePolicy} from resolved-config + hardware.
 *
 * <p>Pure function (tempdoc 397 §7.2). Reads only from its typed parameters. Does not call
 * {@code System.getenv}, {@code EnvRegistry.get(...)}, or {@code ConfigStore.global()} — all
 * environment precedence is applied upstream by
 * {@link io.justsearch.configuration.resolved.ResolvedConfigBuilder} through its ordinal chain.
 *
 * <p>Stage 1 defaults are materialised by {@link RuntimePolicy#defaults()} and applied by
 * {@link SessionOptionsApplier} (tempdoc 397 §14.24 FA). The causality + parity tests in
 * {@code OrtSessionOptionsTest} prove that non-default field values reach the ORT setters and
 * that defaults match the pre-FA hardcoded values byte-for-byte. Adaptive sizing (395 A1/A7)
 * lands by branching on the {@link HardwareProfile} inside this resolver; every downstream
 * consumer (applier, handle) is hardware-independent by design.
 *
 * <p>Note on diagnostic env vars: {@code JUSTSEARCH_ORT_PROFILING_DIR} and
 * {@code JUSTSEARCH_ORT_VERBOSE} are read by {@link SessionOptionsApplier} via direct
 * {@code System.getenv} calls. Tempdoc 397 §14.24 FB promotes them to typed
 * {@code RuntimePolicy.Profiling} fields resolved from {@link ResolvedConfig.Ai.Profiling}
 * so observability values flow through the same policy chain as every other setter. Until
 * then, the env-var reads remain inside the applier as a clearly-marked carry-over.
 */
public final class RuntimePolicyResolver {

  /** Today's hardcoded arena extend strategy (tempdoc 394 Runs B+C ruled out alternatives). */
  private static final String DEFAULT_ARENA_STRATEGY = "kSameAsRequested";

  private RuntimePolicyResolver() {}

  /**
   * Produces a {@link RuntimePolicy} reflecting the current runtime and hardware state.
   *
   * @param cfg resolved configuration snapshot (parameter retained for a stable signature;
   *     Stage 1 produces the same {@link RuntimePolicy} regardless — 395 A1 adaptive work
   *     adds branches here)
   * @param hardware detected hardware profile (same note as {@code cfg})
   * @return the resolved runtime policy
   */
  public static RuntimePolicy resolve(ResolvedConfig cfg, HardwareProfile hardware) {
    // Parameters reserved for 395 A1/A4/A7 adaptive work; today the runtime policy is
    // hardware- and config-independent because 397 explicitly defers adaptive sizing to
    // follow-up tempdocs. The method signature is stable so that when adaptive logic lands,
    // callers do not change.
    requireNonNull(cfg);
    requireNonNull(hardware);

    RuntimePolicy.Arena arena =
        new RuntimePolicy.Arena(DEFAULT_ARENA_STRATEGY, /* memoryPatternOptimization= */ false);

    RuntimePolicy.CudaProvider cudaProvider =
        new RuntimePolicy.CudaProvider(
            /* cudaGraphsEnabled= */ false,
            /* tunableOpEnabled= */ true,
            /* tunableOpTuningEnabled= */ false,
            /* cudnnMaxWorkspace= */ true,
            /* epLevelUnifiedStream= */ true);

    // Lane F PR 0b — the intra-op pin rides the same Profiling sub-record the other ORT session
    // knobs do, so it reaches SessionOptionsApplier through the resolved config rather than a
    // System.getenv read in the apply path (the closure property §14.24 FB established).
    ResolvedConfig.Ai.Profiling sessionKnobs = cfg.ai().profiling();
    RuntimePolicy.Session session =
        new RuntimePolicy.Session(
            /* interOpThreads= */ 1,
            /* allowSpinning= */ false,
            /* forceSpinningStop= */ true,
            /* useDeviceAllocatorForInitializers= */ true,
            /* intraOpThreads= */ sessionKnobs != null ? sessionKnobs.intraOpThreads() : null);

    // Tempdoc 397 §14.24 FB: Profiling sub-record carries the formerly env-var-only diagnostic
    // knobs. Resolver populates from cfg.ai().profiling(); SessionOptionsApplier reads these
    // fields at session creation — no System.getenv calls remain in the apply path.
    ResolvedConfig.Ai.Profiling cfgProfiling = cfg.ai().profiling();
    RuntimePolicy.Profiling profiling =
        new RuntimePolicy.Profiling(
            Optional.ofNullable(cfgProfiling != null ? cfgProfiling.ortProfilingDir() : null),
            cfgProfiling != null && cfgProfiling.verboseLogging());

    return new RuntimePolicy(arena, cudaProvider, session, profiling);
  }

  private static <T> T requireNonNull(T value) {
    if (value == null) {
      throw new IllegalArgumentException("resolver parameter must not be null");
    }
    return value;
  }
}
