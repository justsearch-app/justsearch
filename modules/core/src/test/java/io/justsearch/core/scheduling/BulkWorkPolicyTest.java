/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.scheduling.BulkWorkGate.Mode;
import io.justsearch.core.scheduling.BulkWorkGate.Profile;
import io.justsearch.core.scheduling.BulkWorkGate.Reason;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Spec E E-U1 (the Switch A policy, §1.2) and E-P7d's gate half (energy never holds). */
@DisplayName("BulkWorkPolicy / BulkWorkGate: Switch A")
final class BulkWorkPolicyTest {

  // GPU profiles: game and pressure hold; generating floors at 50% / 1 s
  @ParameterizedTest(name = "gpuProfileRows({0})") // the method name, for evidence selection
  @EnumSource(value = Profile.class, names = {"P16_PLUS", "P12", "P8"})
  void gpuProfileRows(Profile profile) {
    var game = BulkWorkPolicy.decide(profile, false, false, true, false);
    assertEquals(Mode.HOLD, game.mode());
    assertEquals(List.of(Reason.GAME_MODE), game.reasons());
    assertTrue(game.held());

    var pressure = BulkWorkPolicy.decide(profile, false, true, false, false);
    assertEquals(List.of(Reason.GPU_BUSY_EXTERNAL), pressure.reasons());
    assertTrue(pressure.held());

    var generating = BulkWorkPolicy.decide(profile, true, false, false, false);
    assertEquals(Mode.DUTY_FLOOR, generating.mode());
    assertEquals(List.of(Reason.CHAT_GENERATING), generating.reasons());
    assertTrue(generating.dutyFloor());
    assertFalse(generating.held());
    assertEquals(50, generating.generationDutyPct());
    assertEquals(1_000L, generating.generationMaxDebtMs());

    var none = BulkWorkPolicy.decide(profile, false, false, false, false);
    assertEquals(Mode.RUN, none.mode());
    assertEquals(List.of(), none.reasons());
    assertFalse(none.held());
    assertFalse(none.dutyFloor());
  }

  // E-P7d: energy saver is status only on every profile; it never holds
  @ParameterizedTest(name = "energyIsStatusOnly({0})") // the method name, for evidence selection
  @EnumSource(Profile.class)
  void energyIsStatusOnly(Profile profile) {
    var energy = BulkWorkPolicy.decide(profile, false, false, false, true);
    assertEquals(Mode.HOLD, energy.mode(), "status reads PAUSED");
    assertEquals(List.of(Reason.ENERGY_SAVER), energy.reasons());
    assertFalse(energy.held(), "actuation stays with the existing energy path (P7)");
    assertFalse(energy.dutyFloor());
  }

  // reasons accumulate in precedence order; the mode is the strongest present
  @Test
  void reasonsAccumulate() {
    var all = BulkWorkPolicy.decide(Profile.P12, true, true, true, true);
    assertEquals(
        List.of(Reason.GAME_MODE, Reason.GPU_BUSY_EXTERNAL, Reason.ENERGY_SAVER,
            Reason.CHAT_GENERATING),
        all.reasons());
    assertEquals(Mode.HOLD, all.mode());
    assertTrue(all.held());
    assertTrue(all.dutyFloor());

    var energyAndGenerating = BulkWorkPolicy.decide(Profile.P8, true, false, false, true);
    assertEquals(Mode.HOLD, energyAndGenerating.mode());
    assertFalse(energyAndGenerating.held());
    assertTrue(energyAndGenerating.dutyFloor());
  }

  // NO_GPU never holds and keeps today's duty while CPU chat decodes
  @Test
  void noGpu() {
    var d = BulkWorkPolicy.decide(Profile.NO_GPU, true, true, true, false);
    assertFalse(d.held());
    assertEquals(List.of(Reason.CHAT_GENERATING), d.reasons(), "nothing to pause for");
    assertEquals(Mode.DUTY_FLOOR, d.mode());
    assertEquals(20, d.generationDutyPct());
    assertEquals(2_000L, d.generationMaxDebtMs());
  }

  // UNRESOLVED keeps today's duty; the hold rule is the same as on every profile
  @Test
  void unresolved() {
    var generating = BulkWorkPolicy.decide(Profile.UNRESOLVED, true, false, false, false);
    assertEquals(20, generating.generationDutyPct());
    assertEquals(2_000L, generating.generationMaxDebtMs());
    assertTrue(BulkWorkPolicy.decide(Profile.UNRESOLVED, false, false, true, false).held());
  }

  // the gate starts UNRESOLVED and RUN, and answers from the policy
  @Test
  void gateDefaultsAndActuation() {
    var gate = new BulkWorkGate();
    assertEquals(Profile.UNRESOLVED, gate.snapshot().profile());
    assertEquals(Mode.RUN, gate.verdict().mode());
    assertFalse(gate.held());
    assertFalse(gate.dutyFloor());

    gate.setEnergyReduced(true);
    assertFalse(gate.held(), "E-P7d: energy saver on, held() is false");
    assertEquals(List.of(Reason.ENERGY_SAVER), gate.verdict().reasons());

    gate.setProfile(Profile.P16_PLUS);
    gate.setGenerating(true);
    assertTrue(gate.dutyFloor());
    assertEquals(50, gate.generationDutyPct());
    assertEquals(1_000L, gate.generationMaxDebtMs());

    gate.setExternalPressure(true);
    assertTrue(gate.held());
    gate.setExternalPressure(false);
    gate.setGame(true);
    assertTrue(gate.held());
    gate.setGame(false);
    assertFalse(gate.held(), "resumes afterwards");
  }

  // since moves only when the mode or reasons change
  @Test
  void sinceTracksTransitions() {
    var now = new AtomicReference<>(Instant.parse("2026-10-06T08:00:00Z"));
    Clock clock = new Clock() {
      @Override public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override public Instant instant() {
        return now.get();
      }
    };
    var gate = new BulkWorkGate(clock);
    Instant t0 = gate.verdict().since();

    now.set(t0.plusSeconds(5));
    gate.setProfile(Profile.P12); // no reason changes
    assertEquals(t0, gate.verdict().since());

    gate.setGenerating(true);
    Instant t1 = gate.verdict().since();
    assertNotEquals(t0, t1);

    now.set(t1.plusSeconds(5));
    gate.setGenerating(true);
    assertEquals(t1, gate.verdict().since(), "the same input again is not a transition");
  }

  private static long statusCounter() {
    try {
      var type = Class.forName("io.justsearch.core.status.StatusChangeCounter");
      return (long) type.getMethod("current").invoke(type.getMethod("process").invoke(null));
    } catch (ClassNotFoundException baseline) { return 0L; }
    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  @Test void statusCounterTracksVerdictChangesAndNotRepeatedInputs() {
    var gate = new BulkWorkGate(); gate.setProfile(Profile.P12);
    long before = statusCounter();
    gate.setEnergyReduced(true);
    assertEquals(before + 1, statusCounter(), "indexing pause publication must invalidate status");
    gate.snapshot(); gate.verdict(); gate.setEnergyReduced(true);
    assertEquals(before + 1, statusCounter(), "read/replayed input must leave counter unchanged");
    gate.setEnergyReduced(false);
    assertEquals(before + 2, statusCounter());
  }
}
