/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.atrest;

import io.justsearch.app.api.runtime.child.OwnedHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
 * it mirrors the GPU subprocess-probe discipline (best-effort, short timeout, force-kill on hang).
 *
 * <p>Task b6d536 b101: the state of a volume changes rarely, so each volume is probed at most once
 * per {@link #CACHE_TTL} (ten minutes) for the whole process, whichever probe instance asks; other
 * reads are served from that reading, whose age and read time {@link #reading()} reports (the
 * read time is on {@code /api/status} as {@code atRestProtection.probedAtMs}). A volume not read
 * before is probed on its first read. A failed probe keeps the last known value, marked stale (source {@code stale:<source>}, LOW
 * confidence), and a stale value is never reported as protected: a stale ENCRYPTED or ENCRYPTING
 * reads as UNKNOWN.
 */
public final class DiskEncryptionProbe {

  private static final Logger log = LoggerFactory.getLogger(DiskEncryptionProbe.class);
  static final Duration CACHE_TTL = Duration.ofMinutes(10);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);
  private static final String SOURCE = "shell-property";
  static final String STALE_PREFIX = "stale:";

  /** Every production probe shares these per-volume readings. */
  private static final Volumes SHARED = new Volumes();

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
  private final Volumes volumes;

  /**
   * One volume's reading: the value the last successful probe gave, when it was taken, and whether
   * the latest probe failed so the value is only the last known one. {@code age} is as of this read.
   */
  public record Reading(AtRestProtection value, long probedAtMs, Duration age, boolean stale) {
    /** What this reading tells consumers ({@link DiskEncryptionProbe#verdict}); same reading, same time. */
    public AtRestProtection verdict() {
      return DiskEncryptionProbe.verdict(this);
    }
  }

  /** The per-volume readings, keyed by volume root. */
  private static final class Volumes {
    private final java.util.concurrent.ConcurrentHashMap<String, Volume> byRoot =
        new java.util.concurrent.ConcurrentHashMap<>();

    Volume of(Path volumePath) {
      Path root = volumePath == null ? null : volumePath.toAbsolutePath().getRoot();
      String key = root == null ? "" : root.toString().toLowerCase(java.util.Locale.ROOT);
      return byRoot.computeIfAbsent(key, k -> new Volume());
    }
  }

  /** One volume; its lock keeps at most one probe of the volume in flight. */
  private static final class Volume {
    private final java.util.concurrent.locks.ReentrantLock lock =
        new java.util.concurrent.locks.ReentrantLock();
    private AtRestProtection lastKnown;
    private long probedAtMs;
    private long attemptedAtMs;
    private boolean stale;
    private boolean probed;
  }

  /** Production: probe the volume hosting {@code volumePath} (e.g. the index base path). */
  public DiskEncryptionProbe(Path volumePath) {
    this(volumePath, System::currentTimeMillis, DiskEncryptionProbe::readWindowsShellProperty, SHARED);
  }

  /** Test seam: inject the clock + read mechanism so the probe is unit-testable without a host. */
  DiskEncryptionProbe(
      Path volumePath,
      java.util.function.LongSupplier nowMs,
      java.util.function.Function<Path, AtRestProtection> readMechanism) {
    this(volumePath, nowMs, readMechanism, new Volumes());
  }

  private DiskEncryptionProbe(
      Path volumePath,
      java.util.function.LongSupplier nowMs,
      java.util.function.Function<Path, AtRestProtection> readMechanism,
      Volumes volumes) {
    this.volumePath = volumePath;
    this.nowMs = nowMs;
    this.readMechanism = readMechanism;
    this.volumes = volumes;
  }

  /** The current at-rest protection, probed at most once per volume per {@link #CACHE_TTL}. Never throws. */
  public AtRestProtection current() {
    return verdict(reading());
  }

  /** The current reading with its age, probing only when the volume's reading is older than the TTL. */
  public Reading reading() {
    Volume volume = volumes.of(volumePath);
    volume.lock.lock();
    try {
      long now = nowMs.getAsLong();
      if (!volume.probed || now - volume.attemptedAtMs >= CACHE_TTL.toMillis()) {
        probe(volume, now);
      }
      return new Reading(volume.lastKnown, volume.probedAtMs,
          Duration.ofMillis(Math.max(0, now - volume.probedAtMs)), volume.stale);
    } finally {
      volume.lock.unlock();
    }
  }

  private void probe(Volume volume, long now) {
    AtRestProtection fresh = null;
    try {
      fresh = readMechanism.apply(volumePath);
    } catch (Throwable t) {
      log.debug("disk-encryption probe failed: {}", t.toString());
    }
    volume.probed = true;
    volume.attemptedAtMs = now;
    if (fresh != null) {
      volume.lastKnown = fresh;
      volume.probedAtMs = now;
      volume.stale = false;
    } else if (volume.lastKnown == null || volume.lastKnown.state() == AtRestProtection.State.UNKNOWN) {
      // Nothing known to keep: unknown as of now; the failed probe is retried after the TTL.
      volume.lastKnown = AtRestProtection.unknown();
      volume.probedAtMs = now;
      volume.stale = false;
    } else {
      // Keep the last known value and its age; it stays stale until a probe succeeds again. The
      // next probe still waits for the TTL, counted from this attempt.
      volume.stale = true;
    }
  }

  /** What a reading tells consumers: a stale value is marked, and never reported as protected. */
  static AtRestProtection verdict(Reading reading) {
    AtRestProtection value = reading.value() == null ? AtRestProtection.unknown() : reading.value();
    if (!reading.stale()) {
      return value;
    }
    boolean protective = value.state() == AtRestProtection.State.ENCRYPTED
        || value.state() == AtRestProtection.State.ENCRYPTING;
    return new AtRestProtection(protective ? AtRestProtection.State.UNKNOWN : value.state(),
        STALE_PREFIX + value.source(), AtRestProtection.Confidence.LOW);
  }

  /**
   * Reads {@code System.Volume.BitLockerProtection} for the drive root of {@code volumePath} via a
   * non-elevated PowerShell shell-property query. PKEY values (MS docs): 1=On, 2=Off, 3=Unknown,
   * 4=NotApplicable, 5=DecryptionInProgress, 6=EncryptionInProgress.
   *
   * <p>No explicit OS gate (app-services may not read {@code System.getProperty}): on a non-Windows
   * host {@code powershell.exe} is simply not found, the subprocess fails, and this returns null (no
   * answer), which reads as {@link AtRestProtection#unknown()} unless a last known value is kept.
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
    String out = runPowerShell(buildProbeScript(drive));
    // A probe that did not run to a clean exit has no answer: null keeps the last known value.
    return out == null ? null : parseShellPropertyOutput(out);
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

  /** Best-effort PowerShell runner: short timeout, force-kill on hang, null when the probe failed. */
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
        return null;
      }
      String firstLine;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        firstLine = r.readLine();
      }
      if (p.exitValue() != 0 || firstLine == null) {
        return null;
      }
      return firstLine;
    } catch (Exception e) {
      if (p != null) {
        p.destroyForcibly();
      }
      return null;
    }
  }
}
