/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.configuration.SystemAccess;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one nvidia-smi reading shared by every reader in this process (task b6d536 b101).
 *
 * <p>Each call to {@link #read()} used to start its own nvidia-smi, three per GPU snapshot and
 * one snapshot per status, telemetry or saturation tick. Now one process answers total memory,
 * free memory and driver version together; at most one runs at a time; and a reading younger than
 * {@link #MIN_INTERVAL} is served to everyone without a new process. Readers that arrive while a
 * probe runs wait for it and share its outcome, a timeout or failure included: they receive its
 * reading when it gave one, otherwise the last good reading marked stale, or the failure when there
 * is none. They never start a probe of their own.
 *
 * <p>{@link #invalidate()} marks the reading stale; it never shortens the interval. A read inside
 * the interval returns the last reading marked {@link Reading#stale() stale}, and the next probe
 * the interval allows answers every reader waiting for it. A failure to start nvidia-smi, or a
 * non-zero exit, is sticky until an invalidation, which retries it once the interval allows.
 */
final class NvidiaSmiReadings {
  private static final Logger LOG = LoggerFactory.getLogger(NvidiaSmiReadings.class);

  /** The longest a shared reading is served before the next reader probes again. */
  static final Duration MIN_INTERVAL = Duration.ofSeconds(5);

  private static final int TIMEOUT_SECONDS = 5;
  private static final NvidiaSmiReadings SHARED =
      new NvidiaSmiReadings(Clock.systemUTC(), NvidiaSmiReadings::launch, NvidiaSmiReadings::onSearchPath);

  /**
   * One nvidia-smi answer; any figure it could not give is null. {@code stale}: a fresh reading was
   * asked for inside the minimum interval, so this is the last one, taken at {@code at}.
   */
  record Reading(boolean available, String error, Long totalBytes, Long freeBytes, String driverVersion, Instant at,
      boolean stale) {
    Reading(boolean available, String error, Long totalBytes, Long freeBytes, String driverVersion, Instant at) {
      this(available, error, totalBytes, freeBytes, driverVersion, at, false);
    }

    Reading markedStale() {
      return stale ? this : new Reading(available, error, totalBytes, freeBytes, driverVersion, at, true);
    }
  }

  /** What one probe process returned; {@code output} is null when it did not finish in time. */
  record Outcome(int exitCode, String output) {}

  /**
   * The readers of one probe: its outcome for them, set once when the probe settles. A reader keeps
   * the cohort it joined, so a later probe never changes what it receives.
   */
  private static final class Cohort {
    private Reading outcome;
    private boolean done;
  }

  /** Runs one probe process; an exception means it could not be started. */
  @FunctionalInterface
  interface Runner {
    Outcome run() throws Exception;
  }

  private final Clock clock;
  private final Runner runner;
  private final java.util.function.BooleanSupplier installed;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition settled = lock.newCondition();
  private Reading last;
  private Reading lastGood;
  private Cohort inFlight;
  private Instant probedAt;
  private boolean staleRequested;
  private boolean unavailable;
  private Instant presenceAt;
  private boolean present;

  NvidiaSmiReadings(Clock clock, Runner runner) {
    this(clock, runner, () -> false);
  }

  NvidiaSmiReadings(Clock clock, Runner runner, java.util.function.BooleanSupplier installed) {
    this.clock = clock;
    this.runner = runner;
    this.installed = installed;
  }

  /** The process-wide readings. */
  static NvidiaSmiReadings shared() {
    return SHARED;
  }

  /**
   * The current reading: the shared one while it is younger than {@link #MIN_INTERVAL}, marked
   * stale when a fresh one was asked for; otherwise one fresh probe. Never two probes within the
   * interval, whatever was invalidated.
   */
  Reading read() {
    Instant now;
    Cohort cohort;
    lock.lock();
    try {
      if (inFlight != null) {
        // One probe is in flight: wait for it and return its outcome, whatever the clock says now
        // and whatever probe runs after it.
        Cohort joined = inFlight;
        while (!joined.done) settled.awaitUninterruptibly();
        return joined.outcome;
      }
      now = clock.instant();
      boolean allowed = probedAt == null || Duration.between(probedAt, now).compareTo(MIN_INTERVAL) >= 0;
      if (!allowed) {
        return staleRequested && last != null ? last.markedStale() : last;
      }
      if (unavailable && !staleRequested) {
        return last; // sticky until an invalidation asks again
      }
      staleRequested = false;
      unavailable = false;
      probedAt = now;
      cohort = new Cohort();
      inFlight = cohort;
    } finally {
      lock.unlock();
    }
    Probed probed = null;
    try {
      probed = probe(now);
      return probed.reading();
    } finally {
      settle(cohort, probed, now);
    }
  }

  /** Publishes the probe's outcome to its cohort and wakes every reader waiting on it. */
  private void settle(Cohort cohort, Probed probed, Instant now) {
    Reading reading = probed != null ? probed.reading()
        : new Reading(false, "nvidia-smi probe failed", null, null, null, now);
    lock.lock();
    try {
      last = reading;
      unavailable = probed != null && probed.sticky();
      if (reading.available()) lastGood = reading;
      cohort.outcome = reading.available() || lastGood == null ? reading : lastGood.markedStale();
      cohort.done = true;
      inFlight = null;
      settled.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Asks for a fresh reading: the current one is stale, and the next probe the interval allows
   * takes it, retrying a sticky failure. Never starts a probe sooner than the interval.
   */
  void invalidate() {
    lock.lock();
    try {
      staleRequested = true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Whether nvidia-smi is available, without starting it: the last reading's answer when one was
   * taken, otherwise whether the executable is where a launch would find it. Its presence is looked
   * up at most once per {@link #MIN_INTERVAL}.
   */
  boolean toolAvailable() {
    lock.lock();
    try {
      if (last != null) return last.available();
      Instant now = clock.instant();
      if (presenceAt == null || Duration.between(presenceAt, now).compareTo(MIN_INTERVAL) >= 0) {
        present = installed.getAsBoolean();
        presenceAt = now;
      }
      return present;
    } finally {
      lock.unlock();
    }
  }

  /** Whether an nvidia-smi executable is where {@code new ProcessBuilder("nvidia-smi")} finds it. */
  private static boolean onSearchPath() {
    boolean windows = java.io.File.separatorChar == '\\';
    String name = windows ? "nvidia-smi.exe" : "nvidia-smi";
    java.util.List<String> directories = new java.util.ArrayList<>();
    String root = SystemAccess.envVar("SystemRoot");
    if (windows && root != null) {
      directories.add(root + "\\System32");
      directories.add(root);
    }
    String path = SystemAccess.envVar("PATH");
    if (path != null) directories.addAll(java.util.List.of(path.split(java.io.File.pathSeparator)));
    for (String directory : directories) {
      if (directory.isBlank()) continue;
      try {
        if (java.nio.file.Files.isRegularFile(java.nio.file.Path.of(directory.strip()).resolve(name))) return true;
      } catch (RuntimeException invalidEntry) {
        // a malformed PATH entry finds nothing
      }
    }
    return false;
  }

  /** One probe's reading; {@code sticky}: no further probe until an invalidation. */
  private record Probed(Reading reading, boolean sticky) {}

  /** Runs one probe without holding the lock, so waiting readers park on {@link #settled}. */
  private Probed probe(Instant now) {
    Outcome outcome;
    try {
      outcome = runner.run();
    } catch (Exception e) {
      LOG.debug("nvidia-smi not available — disabling future polls: {}", e.getMessage());
      return new Probed(new Reading(false, "nvidia-smi not available: " + e.getMessage(), null, null, null, now),
          true);
    }
    if (outcome.output() == null) {
      LOG.warn("nvidia-smi timed out");
      return new Probed(new Reading(false, "nvidia-smi timed out", null, null, null, now), false);
    }
    if (outcome.exitCode() != 0 || outcome.output().isBlank()) {
      LOG.debug("nvidia-smi failed or returned empty output — disabling future polls");
      return new Probed(new Reading(false, "nvidia-smi exited " + outcome.exitCode(), null, null, null, now), true);
    }
    return new Probed(parse(outcome.output(), now), false);
  }

  /**
   * Parses {@code memory.total, memory.free, driver_version} for the first GPU. A figure that does
   * not parse is null; unparseable output is transient and never sticky.
   */
  static Reading parse(String output, Instant at) {
    String first = output.trim().lines().findFirst().orElse("").trim();
    String[] fields = first.split(",", -1);
    Long total = fields.length > 0 ? bytes(fields[0]) : null;
    Long free = fields.length > 1 ? bytes(fields[1]) : null;
    String driver = fields.length > 2 && !fields[2].isBlank() && !fields[2].contains("N/A") ? fields[2].trim() : null;
    boolean available = (total != null && total > 0) || driver != null;
    return new Reading(available, available ? null : "unparseable nvidia-smi output", total, free, driver, at);
  }

  private static Long bytes(String megabytes) {
    long parsed = VramDetector.parseNvidiaSmiFirstGpuBytes(megabytes);
    return parsed < 0 ? null : parsed;
  }

  private static Outcome launch() throws Exception {
    ProcessBuilder pb = new ProcessBuilder("nvidia-smi",
        "--query-gpu=memory.total,memory.free,driver_version", "--format=csv,noheader,nounits");
    pb.redirectErrorStream(true);
    Process proc = OwnedHelper.start(pb, "nvidia-smi VRAM probe");
    // Wait before reading so the timeout bounds the call: the one short line fits the pipe
    // buffer, so the process never blocks on write, while a read first could hang on it.
    if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      OwnedHelper.terminate(proc, "timeout");
      return new Outcome(-1, null);
    }
    String output;
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
      output = reader.readLine();
    }
    return new Outcome(proc.exitValue(), output == null ? "" : output);
  }
}
