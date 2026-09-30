/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.util;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the OS energy-intent (Windows) via the Java Foreign Function &amp; Memory (FFM) API,
 * in the shape the former {@code WindowsJobObject} used (Win32 kernel32 via {@link Linker}, no external deps,
 * best-effort, null/UNKNOWN off-Windows or on failure). Tempdoc 630.
 *
 * <p>Calls {@code GetSystemPowerStatus}, whose {@code SYSTEM_POWER_STATUS.SystemStatusFlag} byte is
 * the documented "battery/energy saver engaged" bit (Microsoft guidance: "avoid resource-intensive
 * tasks when battery saver is on"). The pure {@link #toEnergyState(Snapshot)} derivation is
 * unit-tested without the host.
 */
public final class WindowsPowerStatus {

  private static final Logger log = LoggerFactory.getLogger(WindowsPowerStatus.class);

  private WindowsPowerStatus() {}

  /**
   * Raw {@code SYSTEM_POWER_STATUS} fields (the four BYTEs we use).
   *
   * @param acLineStatus 0=offline (battery), 1=online (AC), 255=unknown
   * @param batteryFlag bit field; 128 = "no system battery" (desktop)
   * @param batteryLifePercent 0–100, or 255=unknown
   * @param systemStatusFlag non-zero ⇒ battery/energy saver is engaged
   */
  public record Snapshot(
      int acLineStatus, int batteryFlag, int batteryLifePercent, int systemStatusFlag) {}

  /**
   * Reads the current power status, or {@code null} when unavailable (non-Windows platform, or any
   * FFM/native failure). Best-effort and never throws — callers treat {@code null} as UNKNOWN.
   */
  public static Snapshot readOrNull() {
    if (!isWindows()) {
      return null;
    }
    try {
      return readInternal();
    } catch (Throwable t) {
      log.debug("GetSystemPowerStatus probe unavailable: {}", t.getMessage());
      return null;
    }
  }

  /**
   * Pure derivation of the {@link EnergyState} from a {@link Snapshot} ({@code null} ⇒ UNKNOWN).
   * Intent is REDUCED iff the energy/battery-saver flag is engaged; source distinguishes battery
   * vs AC for user-facing wording. Unit-tested.
   */
  public static EnergyState toEnergyState(Snapshot s) {
    if (s == null) {
      return EnergyState.unknown();
    }
    EnergyState.Intent intent =
        s.systemStatusFlag() != 0 ? EnergyState.Intent.REDUCED : EnergyState.Intent.FULL;
    EnergyState.Source source;
    boolean noBattery = (s.batteryFlag() & 128) != 0; // desktop / no system battery ⇒ AC
    if (s.acLineStatus() == 1 || noBattery) {
      source = EnergyState.Source.AC;
    } else if (s.acLineStatus() == 0) {
      source = EnergyState.Source.BATTERY;
    } else {
      source = EnergyState.Source.UNKNOWN; // 255 = unknown
    }
    return new EnergyState(intent, source);
  }

  /** Convenience: read + derive in one call ({@code null}-safe ⇒ UNKNOWN). */
  public static EnergyState read() {
    return toEnergyState(readOrNull());
  }

  // ---- native ----

  private static Snapshot readInternal() throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
      MethodHandle getSystemPowerStatus =
          linker.downcallHandle(
              kernel32.find("GetSystemPowerStatus").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

      // SYSTEM_POWER_STATUS: BYTE ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag,
      // DWORD BatteryLifeTime, DWORD BatteryFullLifeTime (12 bytes).
      MemorySegment buf = arena.allocate(12);
      buf.fill((byte) 0);
      int ok = (int) getSystemPowerStatus.invokeExact(buf);
      if (ok == 0) {
        throw new IllegalStateException("GetSystemPowerStatus returned FALSE");
      }
      return new Snapshot(
          buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF,
          buf.get(ValueLayout.JAVA_BYTE, 1) & 0xFF,
          buf.get(ValueLayout.JAVA_BYTE, 2) & 0xFF,
          buf.get(ValueLayout.JAVA_BYTE, 3) & 0xFF);
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
