package io.justsearch.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.EnergyState.Intent;
import io.justsearch.app.util.EnergyState.Source;
import io.justsearch.app.util.WindowsPowerStatus.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Truth table for the pure {@link WindowsPowerStatus#toEnergyState} derivation (tempdoc 630). */
final class WindowsPowerStatusTest {

  @Test
  @DisplayName("null snapshot (probe unavailable) ⇒ UNKNOWN, not reduced")
  void nullIsUnknown() {
    EnergyState e = WindowsPowerStatus.toEnergyState(null);
    assertEquals(Intent.UNKNOWN, e.intent());
    assertEquals(Source.UNKNOWN, e.source());
    assertFalse(e.reduced(), "UNKNOWN must never throttle");
  }

  @Test
  @DisplayName("energy/battery saver flag set ⇒ REDUCED")
  void saverFlagIsReduced() {
    // SystemStatusFlag != 0, on battery.
    EnergyState e = WindowsPowerStatus.toEnergyState(new Snapshot(0, 1, 40, 1));
    assertEquals(Intent.REDUCED, e.intent());
    assertEquals(Source.BATTERY, e.source());
    assertTrue(e.reduced());
  }

  @Test
  @DisplayName("energy saver on AC (Win11 24H2) ⇒ REDUCED + AC source")
  void saverOnAcIsReduced() {
    // SystemStatusFlag != 0 while plugged in — the modern Energy Saver case.
    EnergyState e = WindowsPowerStatus.toEnergyState(new Snapshot(1, 1, 80, 1));
    assertEquals(Intent.REDUCED, e.intent());
    assertEquals(Source.AC, e.source());
  }

  @Test
  @DisplayName("no saver, on AC ⇒ FULL + AC")
  void acFull() {
    EnergyState e = WindowsPowerStatus.toEnergyState(new Snapshot(1, 1, 100, 0));
    assertEquals(Intent.FULL, e.intent());
    assertEquals(Source.AC, e.source());
    assertFalse(e.reduced());
  }

  @Test
  @DisplayName("no saver, on battery ⇒ FULL + BATTERY (battery alone does NOT throttle)")
  void batteryNoSaverIsFull() {
    EnergyState e = WindowsPowerStatus.toEnergyState(new Snapshot(0, 1, 55, 0));
    assertEquals(Intent.FULL, e.intent());
    assertEquals(Source.BATTERY, e.source());
    assertFalse(e.reduced());
  }

  @Test
  @DisplayName("desktop (no system battery, flag 128) ⇒ AC source")
  void desktopIsAc() {
    EnergyState e = WindowsPowerStatus.toEnergyState(new Snapshot(1, 128, 255, 0));
    assertEquals(Source.AC, e.source());
  }

  @Test
  @DisplayName("unknown AC line (255) with a battery present ⇒ UNKNOWN source")
  void unknownAcLine() {
    // acLineStatus=255 (unknown) AND a battery exists (flag 1=high, NOT 128=no-battery), so the
    // source genuinely cannot be determined. (A 128/no-battery flag would resolve to AC by design.)
    EnergyState e = WindowsPowerStatus.toEnergyState(new Snapshot(255, 1, 50, 0));
    assertEquals(Source.UNKNOWN, e.source());
  }
}
