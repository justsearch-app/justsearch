/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import io.justsearch.core.scheduling.BulkWorkGate.Mode;
import io.justsearch.core.scheduling.BulkWorkGate.Profile;
import io.justsearch.core.scheduling.BulkWorkGate.Reason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The bulk-work rule of Switch A (Spec E §1.2, step E1), as one pure function. {@link BulkWorkGate}
 * holds the inputs; this class decides.
 *
 * <ul>
 *   <li><b>Game</b> and <b>external memory pressure</b> hold bulk GPU work: no new batch starts.
 *       Status: PAUSED [{@code game_mode}] / [{@code gpu_busy_external}].
 *   <li><b>Energy saver</b> is status only: PAUSED [{@code energy_saver}], while actuation stays with
 *       the existing energy path (P7), so {@code held} is never true for energy alone.
 *   <li><b>Chat generating</b> puts bulk on the duty floor. Status: SLOWED [{@code chat_generating}].
 * </ul>
 *
 * <p>Reasons accumulate in {@link Reason} precedence; the mode is the strongest one present.
 *
 * <p><b>Profiles.</b> The rule is the same on every profile (Spec E §1.2) with two exceptions. On
 * NO_GPU nothing holds: there is no GPU bulk to hold, so game and pressure are not listed either. The
 * 50% generation duty applies only on a known GPU profile (P16_PLUS, P12, P8); NO_GPU and UNRESOLVED
 * keep today's search-load duty and debt cap while chat generates (re-review N6; Q8 "today's
 * behaviour").
 */
public final class BulkWorkPolicy {

  /** Bulk duty while chat generates and no search runs, on a GPU profile (Spec E §0). */
  public static final int GENERATION_DUTY_PCT = 50;

  /** Yield-debt cap paired with {@link #GENERATION_DUTY_PCT} (Spec E §0). */
  public static final long GENERATION_MAX_DEBT_MS = 1_000L;

  /** Today's duty under foreground load ({@code IndexingPacing.DEFAULT_DUTY_PCT}, ADR-0048). */
  public static final int FOREGROUND_DUTY_PCT = 20;

  /** Today's yield-debt cap ({@code IndexingPacing.MAX_DEBT_MS}). */
  public static final long FOREGROUND_MAX_DEBT_MS = 2_000L;

  private BulkWorkPolicy() {}

  /**
   * One decision.
   *
   * @param mode the strongest mode present (status)
   * @param reasons every present reason, in precedence order (status)
   * @param held whether no new bulk GPU batch may start (actuation; never for energy alone)
   * @param dutyFloor whether bulk runs at the generation duty (actuation)
   * @param generationDutyPct the duty while generating
   * @param generationMaxDebtMs the yield-debt cap while generating
   */
  public record Decision(
      Mode mode,
      List<Reason> reasons,
      boolean held,
      boolean dutyFloor,
      int generationDutyPct,
      long generationMaxDebtMs) {
    public Decision {
      Objects.requireNonNull(mode, "mode");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
  }

  /**
   * Decides. NO_GPU and UNRESOLVED keep today's search-load duty and debt cap while chat generates.
   */
  public static Decision decide(
      Profile profile,
      boolean generating,
      boolean externalPressure,
      boolean game,
      boolean energyReduced) {
    Objects.requireNonNull(profile, "profile");
    boolean canHold = profile != Profile.NO_GPU;
    List<Reason> reasons = new ArrayList<>(4);
    if (canHold && game) reasons.add(Reason.GAME_MODE);
    if (canHold && externalPressure) reasons.add(Reason.GPU_BUSY_EXTERNAL);
    if (energyReduced) reasons.add(Reason.ENERGY_SAVER);
    if (generating) reasons.add(Reason.CHAT_GENERATING);
    boolean held = canHold && (game || externalPressure);
    return new Decision(
        modeOf(reasons),
        reasons,
        held,
        generating,
        generationDutyPct(profile, FOREGROUND_DUTY_PCT),
        generationMaxDebtMs(profile, FOREGROUND_MAX_DEBT_MS));
  }

  /**
   * The bulk duty while chat generates: {@link #GENERATION_DUTY_PCT} on a GPU profile, else the
   * search duty given (today's behaviour on NO_GPU and UNRESOLVED).
   */
  public static int generationDutyPct(Profile profile, int searchDutyPct) {
    return isGpuProfile(profile) ? GENERATION_DUTY_PCT : searchDutyPct;
  }

  /** The debt cap paired with {@link #generationDutyPct(Profile, int)}. */
  public static long generationMaxDebtMs(Profile profile, long searchMaxDebtMs) {
    return isGpuProfile(profile) ? GENERATION_MAX_DEBT_MS : searchMaxDebtMs;
  }

  /**
   * The strongest mode the reasons give: HOLD for game, external pressure or energy saver;
   * DUTY_FLOOR for chat generating; RUN with none. For energy saver alone HOLD is the status
   * (PAUSED), not an actuation: see {@link Decision#held()}.
   */
  public static Mode modeOf(Collection<Reason> reasons) {
    Mode mode = Mode.RUN;
    for (Reason reason : reasons) {
      Mode m = reason == Reason.CHAT_GENERATING ? Mode.DUTY_FLOOR : Mode.HOLD;
      if (m.compareTo(mode) > 0) mode = m;
    }
    return mode;
  }

  /** P16_PLUS, P12 and P8: a usable GPU whose facts are known. */
  private static boolean isGpuProfile(Profile profile) {
    return profile == Profile.P16_PLUS || profile == Profile.P12 || profile == Profile.P8;
  }
}
