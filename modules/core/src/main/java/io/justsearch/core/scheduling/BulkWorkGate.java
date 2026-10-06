/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Switch A (Spec E §1.2, step E1): the single upstream owner of the bulk-work verdict, one per
 * Engine, next to {@link GpuSchedulingGauge}. It holds five inputs and answers two actuation
 * questions ({@link #held()}, {@link #dutyFloor()}) and one status question ({@link #verdict()});
 * {@link BulkWorkPolicy} decides.
 *
 * <p>Inputs and their producers:
 *
 * <ul>
 *   <li>{@link #setEnergyReduced} — mirrored from the energy poller. <b>Status only</b>: energy
 *       saver reads PAUSED here, while the existing energy path keeps acting on it (P7), so {@link
 *       #held()} never covers energy.
 *   <li>{@link #setProfile} — the GPU profile; UNRESOLVED until a resolver publishes one.
 *   <li>{@link #setGenerating} — chat generation, from the chat server's generation edges (step E2,
 *       wired by the Engine composition root). The indexing duty cycle reads it as a {@link
 *       BulkPacingInput}.
 *   <li>{@link #setExternalPressure}, {@link #setGame} — external GPU memory pressure and a running
 *       game. Their producers land in later steps; until then the inputs stay false and the gate
 *       never holds for them.
 * </ul>
 *
 * <p>The reason and profile vocabularies mirror {@code app.api.status.operating.IndexingReason} and
 * {@code Profile} name for name and in order. They are declared here because {@code core} carries no
 * project dependency, and both Engine halves already see it (see {@link GpuSchedulingGauge}).
 *
 * <p>Thread-safe: inputs are written from poll and listener threads, read from loop and status
 * threads. Every method is synchronized; the work inside is constant.
 */
public final class BulkWorkGate implements BulkPacingInput {

  /** Actuation strength, weakest first. HOLD for energy saver alone is status only. */
  public enum Mode {
    RUN,
    DUTY_FLOOR,
    HOLD
  }

  /** Why bulk work is slowed or paused. Declaration order is precedence (IndexingReason's). */
  public enum Reason {
    GAME_MODE,
    GPU_BUSY_EXTERNAL,
    ENERGY_SAVER,
    CHAT_GENERATING
  }

  /** The GPU co-residence profile (operating {@code Profile}'s vocabulary). */
  public enum Profile {
    P16_PLUS,
    P12,
    P8,
    NO_GPU,
    UNRESOLVED
  }

  /** The status view: the strongest mode, every reason in precedence order, and when it began. */
  public record Verdict(Mode mode, List<Reason> reasons, Instant since) {
    public Verdict {
      Objects.requireNonNull(mode, "mode");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
      Objects.requireNonNull(since, "since");
    }
  }

  /** One consistent read of the verdict and the profile. */
  public record Snapshot(Verdict verdict, Profile profile, long sampledAtNanos) {
    public Snapshot {
      Objects.requireNonNull(verdict, "verdict");
      Objects.requireNonNull(profile, "profile");
    }
  }

  private final Clock clock;
  private Profile profile = Profile.UNRESOLVED;
  private boolean generating;
  private boolean externalPressure;
  private boolean game;
  private boolean energyReduced;
  private BulkWorkPolicy.Decision decision;
  private Verdict verdict;

  public BulkWorkGate() {
    this(Clock.systemUTC());
  }

  /** @param clock the source of {@link Verdict#since()} */
  public BulkWorkGate(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    recompute();
  }

  public synchronized void setProfile(Profile p) {
    this.profile = Objects.requireNonNull(p, "profile");
    recompute();
  }

  public synchronized void setGenerating(boolean g) {
    this.generating = g;
    recompute();
  }

  public synchronized void setExternalPressure(boolean p) {
    this.externalPressure = p;
    recompute();
  }

  public synchronized void setGame(boolean g) {
    this.game = g;
    recompute();
  }

  /** Mirrored from the energy poller. Status only: it never makes {@link #held()} true. */
  public synchronized void setEnergyReduced(boolean e) {
    this.energyReduced = e;
    recompute();
  }

  /** Actuation: no new bulk GPU batch starts. Game or external pressure only, never energy. */
  public synchronized boolean held() {
    return decision.held();
  }

  /** Actuation: bulk runs at {@link #generationDutyPct()} because chat is generating. */
  @Override
  public synchronized boolean dutyFloor() {
    return decision.dutyFloor();
  }

  @Override
  public synchronized int generationDutyPct(int searchDutyPct) {
    return BulkWorkPolicy.generationDutyPct(profile, searchDutyPct);
  }

  @Override
  public synchronized long generationMaxDebtMs(long searchMaxDebtMs) {
    return BulkWorkPolicy.generationMaxDebtMs(profile, searchMaxDebtMs);
  }

  /** The bulk duty while chat generates: 50 on a GPU profile, today's 20 otherwise. */
  public synchronized int generationDutyPct() {
    return decision.generationDutyPct();
  }

  /** The yield-debt cap paired with {@link #generationDutyPct()}. */
  public synchronized long generationMaxDebtMs() {
    return decision.generationMaxDebtMs();
  }

  /** Status: the mode and reasons, energy saver included. */
  public synchronized Verdict verdict() {
    return verdict;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(verdict, profile, System.nanoTime());
  }

  private void recompute() {
    decision = BulkWorkPolicy.decide(profile, generating, externalPressure, game, energyReduced);
    if (verdict == null
        || verdict.mode() != decision.mode()
        || !verdict.reasons().equals(decision.reasons())) {
      verdict = new Verdict(decision.mode(), decision.reasons(), clock.instant());
    }
  }

  @Override
  public synchronized String toString() {
    return "BulkWorkGate[profile=" + profile + ", verdict=" + verdict.mode() + verdict.reasons()
        + "]";
  }
}
