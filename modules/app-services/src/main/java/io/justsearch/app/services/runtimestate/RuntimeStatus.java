/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Observed runtime state (tempdoc 737 §12a), Condition-shaped per axis (§11a — the KEP-1623 /
 * {@code CapabilityHealth} vocabulary this layer reuses). Immutable snapshot; the reconciler owns
 * the single mutable reference and swaps a fresh {@code RuntimeStatus} in on each observation.
 *
 * <p>Axes: {@code ENGINE} (Down / Starting / Healthy / Recovering), {@code ADOPTION}
 * (own / external), {@code LEASE} (CHAT / INDEXING / NONE), and (Phase 2) {@code PROCEDURE} — the
 * in-flight machine-actor overlay. A procedure is the ONLY sanctioned way a machine actor holds
 * the engine in a non-spec state (§12a); install / activation join {@code VDU_BATCH} later.
 *
 * <p><b>Reason codes are internal strings in this phase, with one exception.</b> They are NOT wired
 * into {@code LifecycleReasonCode} / {@code check-readiness-reason-codes} yet — that join (which
 * requires paired FE rows) is Phase 3. Where a {@code TransitionReason} name fits, it is reused.
 * The exception is {@link #REASON_ENGINE_UP_FOR_BACKGROUND}, which tempdoc 837 S4 promoted into the
 * closed vocabulary because {@code InferenceCapabilityWiring} and this axis were deliberately built
 * to stamp the SAME string for the same soft-off state (§12c item 2) — half of the Phase-3 join,
 * done where the two surfaces already agreed.
 */
public record RuntimeStatus(List<Condition> conditions) {

  /** The axes a Condition can describe. */
  public enum Axis {
    ENGINE,
    ADOPTION,
    LEASE,
    PROCEDURE
  }

  /**
   * A machine-actor procedure holding the engine in a non-spec state (§12a). Multiple procedures of
   * distinct kinds may be active at once; the reconciler suppresses drift convergence while ANY is
   * active and returns the engine to spec only when the last one ends (tempdoc 737 §12a fix pack).
   * Carries {@code startedAt}, a coarse {@code phase} string, and the internal {@code reason} code.
   *
   * <ul>
   *   <li>{@link #VDU_BATCH} — the offline VDU→embeddings run.
   *   <li>{@link #ACTIVATION} — a runtime-variant activation window (engine comes up via
   *       {@code applyRuntimeOverrides}; the bracket stops the reconciler from fighting it before
   *       the desired-state write lands).
   *   <li>{@link #INSTALL_SMOKE_TEST} — the post-install smoke test that briefly needs the engine up
   *       to answer one question, even when the user's spec still has chat disabled (install ≠
   *       enable — the engine converges back down when the procedure ends).
   *   <li>{@link #INSTALL_ACQUISITION} — the staged model-acquisition window (tempdoc 840 Phase 3).
   *       Stages apply configuration for acquired artifacts and report ordered Engine restart
   *       guidance; acquisition does not restart an index component at each boundary. Chat-model
   *       acquisition can also update inference runtime overrides. One procedure over the whole
   *       window suppresses drift convergence until acquisition ends.
   *       {@link #INSTALL_SMOKE_TEST} nests inside it — overlapping kinds are supported, and the
   *       smoke test's own bracket stays the thing that requires the engine.
   * </ul>
   */
  public enum ProcedureKind {
    VDU_BATCH,
    ACTIVATION,
    INSTALL_SMOKE_TEST,
    INSTALL_ACQUISITION
  }

  /** In-flight procedure overlay descriptor (immutable). */
  public record Procedure(ProcedureKind kind, Instant startedAt, String phase, String reason) {}

  /**
   * One per-axis condition. {@code status} is the axis's vocabulary value (e.g. {@code "Healthy"},
   * {@code "external"}, {@code "CHAT"}); {@code reason} is an internal string code; {@code message}
   * is human-facing; {@code observedSpecVersion} is the {@link RuntimeSpec} version this condition
   * was derived against; {@code lastTransition} is when this status was observed.
   */
  public record Condition(
      Axis axis,
      String status,
      String reason,
      String message,
      long observedSpecVersion,
      Instant lastTransition) {}

  public RuntimeStatus {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }

  public Optional<Condition> condition(Axis axis) {
    return conditions.stream().filter(c -> c.axis() == axis).findFirst();
  }

  // ==================== Engine axis vocabulary ====================

  public static final String ENGINE_DOWN = "Down";
  public static final String ENGINE_STARTING = "Starting";
  public static final String ENGINE_HEALTHY = "Healthy";
  public static final String ENGINE_RECOVERING = "Recovering";

  // Internal reason codes (Phase-1/2 strings; not LifecycleReasonCode members yet).
  public static final String REASON_ENGINE_DOWN = "engine-down";
  public static final String REASON_GPU_YIELDED_TO_INDEXING = "gpu-yielded-to-indexing";
  public static final String REASON_ENGINE_STARTING = "engine-starting";
  public static final String REASON_ENGINE_HEALTHY = "engine-healthy";
  /**
   * Soft-off (§15 decision 1): a background procedure holds the engine UP while the user's spec
   * has chat disabled. The engine is healthy but chat is intentionally NOT offered to the user —
   * the reconciler stamps this reason so the state is legible instead of looking like a bug.
   *
   * <p>Tempdoc 837 S4: this is now a real {@link LifecycleReasonCode} member rather than an internal
   * string. Both stampers (this axis and {@code InferenceCapabilityWiring.deriveAndApply}) keep
   * referring to this ONE constant, so the two surfaces go on agreeing by construction — and the
   * value they agree on is now a worded, gate-enforced member of the closed readiness vocabulary.
   */
  public static final String REASON_ENGINE_UP_FOR_BACKGROUND =
      LifecycleReasonCode.INFERENCE_UP_FOR_BACKGROUND.code();
  /**
   * Anti-flap hold (item 2): the same foreign flip recurred past the cap inside the window, so the
   * reconciler stopped fighting it and is holding until spec / procedure / policy input changes.
   */
  public static final String REASON_CONVERGENCE_HELD_FLAP = "convergence-held-flap-suspected";
  public static final String REASON_PROCEDURE_ACTIVE = "procedure-active";

  /**
   * Derives the ENGINE-axis condition from the observed {@link Mode}. Pure and deterministic —
   * the derivation table under test.
   *
   * <ul>
   *   <li>{@code OFFLINE} → Down (engine-down)
   *   <li>{@code INDEXING} → Down (gpu-yielded-to-indexing) — the GPU lease is held by indexing, chat is
   *       intentionally not running
   *   <li>{@code TRANSITIONING} → Starting (engine-starting). {@code Recovering} is a Phase-2
   *       refinement gated on a {@code TransitionReason == CRASH_RECOVERY} signal that the
   *       bare {@code ModeChangeListener} does not carry.
   *   <li>{@code ONLINE} → Healthy (engine-healthy)
   * </ul>
   */
  public static Condition deriveEngine(Mode mode, long specVersion, Instant now) {
    Mode m = mode == null ? Mode.OFFLINE : mode;
    return switch (m) {
      case OFFLINE ->
          new Condition(Axis.ENGINE, ENGINE_DOWN, REASON_ENGINE_DOWN, "Inference engine is down", specVersion, now);
      case INDEXING ->
          new Condition(
              Axis.ENGINE,
              ENGINE_DOWN,
              REASON_GPU_YIELDED_TO_INDEXING,
              "GPU yielded to indexing; chat engine not running",
              specVersion,
              now);
      case TRANSITIONING ->
          new Condition(
              Axis.ENGINE, ENGINE_STARTING, REASON_ENGINE_STARTING, "Inference engine transitioning", specVersion, now);
      case ONLINE ->
          new Condition(Axis.ENGINE, ENGINE_HEALTHY, REASON_ENGINE_HEALTHY, "Inference engine healthy", specVersion, now);
    };
  }

  /** Derives the ADOPTION-axis condition from the observed external-server flag. */
  public static Condition deriveAdoption(boolean external, long specVersion, Instant now) {
    return external
        ? new Condition(
            Axis.ADOPTION, "external", "external-server-adopted", "Using an external llama-server", specVersion, now)
        : new Condition(Axis.ADOPTION, "own", "own-process", "Using the managed llama-server process", specVersion, now);
  }

  /** Derives the LEASE-axis condition from the current lease holder. */
  public static Condition deriveLease(RuntimeGpuLease.Holder holder, long specVersion, Instant now) {
    RuntimeGpuLease.Holder h = holder == null ? RuntimeGpuLease.Holder.NONE : holder;
    return new Condition(Axis.LEASE, h.name(), "lease-holder", "GPU lease holder: " + h.name(), specVersion, now);
  }

  /** Derives the PROCEDURE-axis condition from the in-flight procedure overlay (nullable). */
  public static Condition deriveProcedure(Procedure procedure, long specVersion, Instant now) {
    if (procedure == null) {
      return new Condition(Axis.PROCEDURE, "none", "no-procedure", "No procedure in flight", specVersion, now);
    }
    return new Condition(
        Axis.PROCEDURE,
        procedure.kind().name(),
        procedure.reason() == null ? REASON_PROCEDURE_ACTIVE : procedure.reason(),
        "Procedure "
            + procedure.kind().name()
            + (procedure.phase() == null ? "" : " (" + procedure.phase() + ")")
            + " started at "
            + procedure.startedAt(),
        specVersion,
        now);
  }

  /** Three-axis derivation (ENGINE / ADOPTION / LEASE) for the given observation. */
  public static RuntimeStatus derive(
      Mode mode, boolean external, RuntimeGpuLease.Holder leaseHolder, long specVersion, Instant now) {
    Instant t = now == null ? Instant.now() : now;
    return new RuntimeStatus(
        List.of(
            deriveEngine(mode, specVersion, t),
            deriveAdoption(external, specVersion, t),
            deriveLease(leaseHolder, specVersion, t)));
  }

  /**
   * Four-axis derivation adding the PROCEDURE overlay. {@code procedure} may be {@code null} (no
   * procedure in flight — a {@code "none"} condition is still emitted so the axis is always
   * present for consumers).
   */
  public static RuntimeStatus derive(
      Mode mode,
      boolean external,
      RuntimeGpuLease.Holder leaseHolder,
      Procedure procedure,
      long specVersion,
      Instant now) {
    Instant t = now == null ? Instant.now() : now;
    return new RuntimeStatus(
        List.of(
            deriveEngine(mode, specVersion, t),
            deriveAdoption(external, specVersion, t),
            deriveLease(leaseHolder, specVersion, t),
            deriveProcedure(procedure, specVersion, t)));
  }

  /** Initial status before the first observation: engine down, own process, no lease. */
  public static RuntimeStatus initial() {
    return derive(Mode.OFFLINE, false, RuntimeGpuLease.Holder.NONE, 0L, Instant.now());
  }
}
