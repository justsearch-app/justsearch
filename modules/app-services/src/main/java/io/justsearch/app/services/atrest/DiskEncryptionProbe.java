/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.atrest;

import io.justsearch.app.api.runtime.child.OwnedHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 629 (FLOOR) — best-effort, never-throwing probe of the OS disk-encryption state of the
 * volume that hosts the data dir.
 *
 * <p>Windows: reads the Explorer shell property {@code System.Volume.BitLockerProtection} for the
 * data-dir drive via a short PowerShell subprocess. This is the property the drive-padlock uses and
 * — unlike {@code manage-bde} / {@code Get-BitLockerVolume} / WMI {@code Win32_EncryptableVolume},
 * which all require elevation (629 confidence-probe P1) — it is **readable un-elevated**. It yields
 * only the coarse on/off state, not configuration quality.
 *
 * <p>Other platforms / any failure → {@link AtRestProtection.State#UNKNOWN}. The probe never throws;
 * it mirrors the GPU subprocess-probe discipline (best-effort, short timeout, force-kill on hang,
 * cf. {@code VramDetector}) and caches its result for {@link #CACHE_TTL} so both the status View and
 * the {@code AtRestHealthTap} share one subprocess per window.
 */
public final class DiskEncryptionProbe {

  private static final Logger log = LoggerFactory.getLogger(DiskEncryptionProbe.class);
  static final Duration CACHE_TTL = Duration.ofSeconds(5);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);
  private static final String SOURCE = "shell-property";

  /**
   * Sentinel the PowerShell script emits when {@code ExtendedProperty} returns {@code $null} (798
   * Part 2): PowerShell's {@code [int]$null} silently coerces to {@code 0}, a value that then parses
   * cleanly and falls into {@code mapPkeyValue}'s {@code default ->} arm alongside a genuinely
   * indeterminate PKEY 3 read — so "the property doesn't exist here" (e.g. no BitLocker feature
   * present at all) and "the probe read an indeterminate value" became indistinguishable by the time
   * the mapper saw them. The script now checks for {@code $null} explicitly and emits this token
   * instead of coercing, so absence takes a different branch than any numeric PKEY value.
   */
  private static final String PROPERTY_ABSENT = "PROPERTY_ABSENT";

  private final Path volumePath;
  private final java.util.function.LongSupplier nowMs;
  private final java.util.function.Function<Path, AtRestProtection> readMechanism;

  private final AtomicReference<Cached> cache = new AtomicReference<>();

  private record Cached(AtRestProtection value, long atMs) {}

  /** Production: probe the volume hosting {@code volumePath} (e.g. the index base path). */
  public DiskEncryptionProbe(Path volumePath) {
    this(volumePath, System::currentTimeMillis, DiskEncryptionProbe::readWindowsShellProperty);
  }

  /** Test seam: inject the clock + read mechanism so the probe is unit-testable without a host. */
  DiskEncryptionProbe(
      Path volumePath,
      java.util.function.LongSupplier nowMs,
      java.util.function.Function<Path, AtRestProtection> readMechanism) {
    this.volumePath = volumePath;
    this.nowMs = nowMs;
    this.readMechanism = readMechanism;
  }

  /** The current at-rest protection, cached for {@link #CACHE_TTL}. Never throws. */
  public AtRestProtection current() {
    long now = nowMs.getAsLong();
    Cached c = cache.get();
    if (c != null && (now - c.atMs()) < CACHE_TTL.toMillis()) {
      return c.value();
    }
    AtRestProtection fresh;
    try {
      fresh = readMechanism.apply(volumePath);
      if (fresh == null) {
        fresh = AtRestProtection.unknown();
      }
    } catch (Throwable t) {
      log.debug("disk-encryption probe failed: {}", t.toString());
      fresh = AtRestProtection.unknown();
    }
    cache.set(new Cached(fresh, now));
    return fresh;
  }

  /**
   * Reads {@code System.Volume.BitLockerProtection} for the drive root of {@code volumePath} via a
   * non-elevated PowerShell shell-property query. PKEY values (MS docs): 1=On, 2=Off, 3=Unknown,
   * 4=NotApplicable, 5=DecryptionInProgress, 6=EncryptionInProgress.
   *
   * <p>No explicit OS gate (app-services may not read {@code System.getProperty}): on a non-Windows
   * host {@code powershell.exe} is simply not found, the subprocess fails, and the probe degrades to
   * {@link AtRestProtection#unknown()} — the same result a Windows probe failure yields.
   */
  static AtRestProtection readWindowsShellProperty(Path volumePath) {
    if (volumePath == null) {
      return AtRestProtection.unknown();
    }
    Path root = volumePath.toAbsolutePath().getRoot();
    if (root == null) {
      return AtRestProtection.unknown();
    }
    String drive = root.toString().replace("'", "''"); // e.g. C:\
    return parseShellPropertyOutput(runPowerShell(buildProbeScript(drive)));
  }

  /**
   * Builds the PowerShell one-liner that reads the shell property. Extracted to a pure function
   * (798 Part 2) so the {@code $null} check is table-testable without a subprocess: the previous
   * one-liner — {@code [int]$ns.Self.ExtendedProperty(...)} unconditionally — let PowerShell's
   * {@code [int]$null} coerce a null (absent) property straight to the string {@code "0"}, making it
   * indistinguishable from a genuinely probed {@code 0} by the time {@link
   * #parseShellPropertyOutput} saw it. This version checks {@code $v} for {@code $null} explicitly
   * and emits {@link #PROPERTY_ABSENT} instead of coercing.
   */
  static String buildProbeScript(String drive) {
    return "$ns=(New-Object -ComObject Shell.Application).Namespace('"
        + drive
        + "'); if($ns){$v=$ns.Self.ExtendedProperty('System.Volume.BitLockerProtection');"
        + " if($null -eq $v){'"
        + PROPERTY_ABSENT
        + "'}else{[int]$v}}";
  }

  /**
   * Pure parse of the raw PowerShell stdout from {@link #readWindowsShellProperty}. Package-private
   * and pure (no subprocess) so the empty / absent / numeric / non-numeric shapes are table-testable
   * without a Windows host — mirroring the extraction precedent in {@code
   * WindowsPowerStatus#toEnergyState} (raw read vs. pure derivation).
   *
   * <p>{@code PROPERTY_ABSENT} takes a different branch than any parsed integer (798 Part 2): a
   * {@code null} property degrades to {@link AtRestProtection#unknown()} (source {@code "none"} — no
   * answer at all), the same honest "no signal" result an execution failure yields, rather than being
   * silently coerced into the numeric PKEY table and reported as an indeterminate-but-probed reading.
   */
  static AtRestProtection parseShellPropertyOutput(String rawOut) {
    String out = rawOut == null ? "" : rawOut.trim();
    if (out.isEmpty() || PROPERTY_ABSENT.equals(out)) {
      return AtRestProtection.unknown();
    }
    int value;
    try {
      value = Integer.parseInt(out);
    } catch (NumberFormatException e) {
      return AtRestProtection.unknown();
    }
    return mapPkeyValue(value);
  }

  /**
   * Maps a {@code System.Volume.BitLockerProtection} PKEY value to the domain state. Package-private
   * so the mapping is testable without a Windows host or a subprocess.
   *
   * <p>PKEY 4 ({@code NotApplicable} — the volume cannot be encrypted by the OS at all) gets its own
   * state rather than falling into {@code UNKNOWN}: both are "not on/off", but only PKEY 3 is
   * genuinely indeterminate. Merging them made the status surface tell the user to elevate in a case
   * where elevation cannot produce an answer.
   */
  static AtRestProtection mapPkeyValue(int value) {
    return switch (value) {
      case 1 -> new AtRestProtection(AtRestProtection.State.ENCRYPTED, SOURCE, AtRestProtection.Confidence.MEDIUM);
      case 2 -> new AtRestProtection(AtRestProtection.State.NOT_ENCRYPTED, SOURCE, AtRestProtection.Confidence.MEDIUM);
      case 4 -> new AtRestProtection(AtRestProtection.State.NOT_APPLICABLE, SOURCE, AtRestProtection.Confidence.MEDIUM);
      case 5, 6 -> new AtRestProtection(AtRestProtection.State.ENCRYPTING, SOURCE, AtRestProtection.Confidence.MEDIUM);
      default -> new AtRestProtection(AtRestProtection.State.UNKNOWN, SOURCE, AtRestProtection.Confidence.LOW);
    };
  }

  /** Best-effort PowerShell runner: short timeout, force-kill on hang, empty string on any failure. */
  private static String runPowerShell(String script) {
    Process p = null;
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
              "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
      pb.redirectErrorStream(true);
      p = OwnedHelper.start(pb, "disk encryption probe");
      boolean exited = p.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        OwnedHelper.terminate(p, "timeout");
        return "";
      }
      String firstLine;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        firstLine = r.readLine();
      }
      if (p.exitValue() != 0 || firstLine == null) {
        return "";
      }
      return firstLine;
    } catch (Exception e) {
      if (p != null) {
        p.destroyForcibly();
      }
      return "";
    }
  }
}
