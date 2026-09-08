/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

/**
 * The Engine supervisor's budget, mirrored into Java so the declared contract has a drift subject
 * (design 7.1, stage B item B7; the question is {@code stages/B.md} Q3 and the answer is (a)).
 *
 * <p><b>This class is the mirror, not the authority.</b> {@code
 * governance/supervision-contract.v1.json}'s {@code engine} row is the authority, and it is that
 * way round because the supervisor is deliberately <em>not</em> a JVM (7.1's {@code never} clause).
 * Its two implementations — the dev-runner in development and the Tauri shell in production — read
 * the register at runtime rather than reading these constants: {@code
 * scripts/dev/lib/engine-supervisor.cjs} parses the JSON, {@code
 * modules/shell/src-tauri/src/supervisor.rs} embeds it with {@code include_str!}. Nothing in this
 * repository's Java calls the fields below.
 *
 * <p><b>So why does it exist at all?</b> Because {@code SupervisionContractTest} enforces that a
 * <em>live</em> process row's declared policy equals a live record's defaults, and a row whose
 * numbers no code is ever compared against is documentation with a JSON extension. The alternative
 * — dropping the equality check for this one row — deletes the only drift protection the row has,
 * which is the predictable evasion {@code stages/B.md} Q3 names. The check lives in {@link
 * EngineSupervisionPolicyTest} rather than in {@code SupervisionContractTest} because that test is
 * in {@code app-services} and the module edge runs {@code app-engine -> app-services}; the register
 * row's {@code driftCheck} field is what stops that relocation becoming a way to skip the check.
 *
 * <p><b>Deliberately a pure constant holder.</b> No instance state, no behaviour, no factory. That
 * shape is what {@code WholeProgramDeadCodeTest.isConstantHolder} recognises, and it is honest
 * rather than a dodge: javac inlines these constants at any call site, so a holder like this one
 * genuinely cannot be shown dead by a bytecode reference count. A record with defaults, the shape
 * {@code BrainSupervisionPolicy} uses, would have failed the whole-program dead-class rule — and
 * that rule is right, because a record with no caller is a different thing from a constant table.
 */
public final class EngineSupervisionPolicy {

  private EngineSupervisionPolicy() {}

  /**
   * How many times a <em>counted</em> death is retried before the terminal state. Unchanged from
   * the 627 seed: the count bounds a loop, and nothing about one process makes a different count
   * right (stage B §2).
   */
  public static final int MAX_RESTART_ATTEMPTS = 3;

  /**
   * The linear step added per attempt over the cooldown floor.
   *
   * <p>The floor itself is not a number and is not declared here: it is the process handle closing,
   * which Windows makes load-bearing (file handles survive the process until then) and which the
   * death path has to wait for anyway. The 627 seed's exponential 1/2/4 s existed to protect a live
   * API in front of the child; there is no such API now, so the cooldown is dead-API time and the
   * ramp is linear.
   */
  public static final long COOLDOWN_INCREMENT_MS = 1000L;

  /**
   * The ceiling on the linear ramp.
   *
   * <p><b>Inert at the shipped budget, and that is stated rather than hidden:</b> with {@link
   * #MAX_RESTART_ATTEMPTS} of 3 the ramp reaches 3000 ms on the last attempt, so this bound never
   * binds. It exists so that raising the attempt budget cannot silently produce a minute-long
   * outage between attempts. The one conformance case that reaches it raises the budget explicitly.
   */
  public static final long MAX_COOLDOWN_MS = 5000L;

  /**
   * How long the Engine must stay up before the restart count resets, counted from {@link
   * #STABILITY_WINDOW_COUNTED_FROM}.
   */
  public static final long STABILITY_WINDOW_MS = 300_000L;

  /**
   * Where the stability window starts. {@code ready}, not {@code spawn} — the Engine's boot carries
   * the encoder load (about 40 s measured at stage A), so a window counted from spawn is mostly
   * consumed by boot and a crash-on-startup loop would look stable.
   */
  public static final String STABILITY_WINDOW_COUNTED_FROM = "ready";

  /**
   * How long {@code starting} may last before the supervisor stops waiting. Hang detection is
   * suspended in {@code starting}, so without this deadline a boot that never publishes a port is a
   * supervisor that waits forever. The per-component start deadlines design 7.6 describes are D1's.
   */
  public static final long START_DEADLINE_MS = 120_000L;

  /**
   * The deadline the supervisor writes into {@code shutdown-request.v1.json}, after which it force
   * kills. This is 7.1's graceful-stop budget: the memory-mapped signal bus that used to carry it
   * went at stage A item A10, so the request file's own {@code deadlineEpochMs} is the only
   * out-of-band channel left.
   */
  public static final long GRACEFUL_STOP_DEADLINE_MS = 15_000L;

  /**
   * Liveness poll interval. <b>Placeholder — set with the collector at stage E</b> (7.1, 17.7).
   * Health and indexing allocation now share one heap, so interval times count must exceed the
   * worst safepoint pause the soak observes, or a long pause reads as a hang. Ported 627 values
   * would do exactly that.
   */
  public static final long HANG_POLL_INTERVAL_MS = 10_000L;

  /**
   * Consecutive liveness misses that constitute a hang. <b>Placeholder — set at stage E</b>, for
   * the reason on {@link #HANG_POLL_INTERVAL_MS}.
   */
  public static final int HANG_UNHEALTHY_THRESHOLD = 3;
}
