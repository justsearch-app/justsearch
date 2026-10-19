/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-U3, the pure rows of the external-pressure machine (section 3.3): raise after 3
 * consecutive PDH-known samples with free below 512 MiB and external use above the allowance plus
 * the margin (1,536 MiB); clear after 5 with free at least 768 MiB or external use at most the
 * allowance (1,024 MiB); counters frozen on UNKNOWN; cleared after 30 s with no PDH-known sample.
 */
final class ExternalPressureMachineTest {

  private static final long MIB = 1024L * 1024L;
  private static final long GIB = 1024L * MIB;
  private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

  // three low samples raise; two do not
  @Test
  void raisesOnTheThirdConsecutiveLowSample() {
    var machine = new ExternalPressureMachine();
    assertFalse(machine.advanceKnown(300 * MIB, 6 * GIB, at(0)));
    assertFalse(machine.advanceKnown(300 * MIB, 6 * GIB, at(2)));
    assertTrue(machine.advanceKnown(300 * MIB, 6 * GIB, at(4)));
  }

  // allowance rows: external use must exceed allowance plus margin (1,536 MiB) to raise
  @Test
  void externalUseAtTheAllowancePlusMarginDoesNotRaise() {
    var atBound = new ExternalPressureMachine();
    for (int i = 0; i < 6; i++) assertFalse(atBound.advanceKnown(300 * MIB, 1_536 * MIB, at(2 * i)));
    var above = new ExternalPressureMachine();
    above.advanceKnown(300 * MIB, 1_537 * MIB, at(0));
    above.advanceKnown(300 * MIB, 1_537 * MIB, at(2));
    assertTrue(above.advanceKnown(300 * MIB, 1_537 * MIB, at(4)));
  }

  // free memory at the margin (512 MiB) does not raise, whatever the external use
  @Test
  void freeAtTheMarginDoesNotRaise() {
    var machine = new ExternalPressureMachine();
    for (int i = 0; i < 6; i++) assertFalse(machine.advanceKnown(512 * MIB, 6 * GIB, at(2 * i)));
  }

  // a sample meeting neither condition breaks the run of low samples
  @Test
  void anInBetweenSampleBreaksTheRaiseRun() {
    var machine = new ExternalPressureMachine();
    machine.advanceKnown(300 * MIB, 6 * GIB, at(0));
    machine.advanceKnown(300 * MIB, 6 * GIB, at(2));
    assertFalse(machine.advanceKnown(600 * MIB, 1_100 * MIB, at(4)), "neither raise nor clear");
    assertFalse(machine.advanceKnown(300 * MIB, 6 * GIB, at(6)), "the run starts again");
  }

  // five clear samples clear; four do not; external at or below the allowance clears too
  @Test
  void clearsOnTheFifthConsecutiveClearSample() {
    var machine = raised();
    for (int i = 0; i < 4; i++) assertTrue(machine.advanceKnown(768 * MIB, 6 * GIB, at(10 + 2 * i)));
    assertFalse(machine.advanceKnown(768 * MIB, 6 * GIB, at(18)));

    var allowance = raised();
    for (int i = 0; i < 4; i++) allowance.advanceKnown(300 * MIB, 1_024 * MIB, at(10 + 2 * i));
    assertFalse(allowance.advanceKnown(300 * MIB, 1_024 * MIB, at(18)),
        "external use at the allowance clears even with little free memory");
  }

  // UNKNOWN freezes the counters: it neither raises, nor breaks, nor restarts a run
  @Test
  void unknownSamplesFreezeTheCounters() {
    var machine = new ExternalPressureMachine();
    machine.advanceKnown(300 * MIB, 6 * GIB, at(0));
    machine.advanceKnown(300 * MIB, 6 * GIB, at(2));
    for (int i = 0; i < 5; i++) assertFalse(machine.advanceUnknown(at(4 + 2 * i)));
    assertTrue(machine.advanceKnown(300 * MIB, 6 * GIB, at(14)), "the frozen run resumes at 3");
  }

  // unknown-clear: no PDH-known sample for 30 s clears raised pressure; 29 s does not
  @Test
  void raisedPressureClearsAfterThirtySecondsWithoutAKnownSample() {
    var machine = raised();
    assertTrue(machine.advanceUnknown(at(4).plus(Duration.ofSeconds(29))));
    assertFalse(machine.advanceUnknown(at(4).plus(Duration.ofSeconds(30))));
  }

  private static ExternalPressureMachine raised() {
    var machine = new ExternalPressureMachine();
    machine.advanceKnown(300 * MIB, 6 * GIB, at(0));
    machine.advanceKnown(300 * MIB, 6 * GIB, at(2));
    assertTrue(machine.advanceKnown(300 * MIB, 6 * GIB, at(4)));
    return machine;
  }

  private static Instant at(long seconds) {
    return T0.plusSeconds(seconds);
  }
}
