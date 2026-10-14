/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

import io.justsearch.app.api.runtime.child.OwnedHelper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
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
 * probe runs wait for it and share its answer.
 *
 * <p>{@link #invalidate()} makes the next read a fresh one whatever its age: admission and
 * pressure decisions use it. Concurrent forced readers still share a single probe. A failure to
 * start nvidia-smi, or a non-zero exit, is sticky until the next invalidation, as before.
 */
final class NvidiaSmiReadings {
  private static final Logger LOG = LoggerFactory.getLogger(NvidiaSmiReadings.class);

  /** The longest a shared reading is served before the next reader probes again. */
  static final Duration MIN_INTERVAL = Duration.ofSeconds(5);

  private static final int TIMEOUT_SECONDS = 5;
  private static final NvidiaSmiReadings SHARED = new NvidiaSmiReadings(Clock.systemUTC(), NvidiaSmiReadings::launch);

  /** One nvidia-smi answer; any figure it could not give is null. */
  record Reading(boolean available, String error, Long totalBytes, Long freeBytes, String driverVersion, Instant at) {}

  /** What one probe process returned; {@code output} is null when it did not finish in time. */
  record Outcome(int exitCode, String output) {}

  /** Runs one probe process; an exception means it could not be started. */
  @FunctionalInterface
  interface Runner {
    Outcome run() throws Exception;
  }

  private final Clock clock;
  private final Runner runner;
  private final ReentrantLock lock = new ReentrantLock();
  private Reading last;
  private boolean forceNext;
  private boolean unavailable;

  NvidiaSmiReadings(Clock clock, Runner runner) {
    this.clock = clock;
    this.runner = runner;
  }

  /** The process-wide readings. */
  static NvidiaSmiReadings shared() {
    return SHARED;
  }

  /** The current reading: the shared one while it is young enough, otherwise one fresh probe. */
  Reading read() {
    lock.lock();
    try {
      Instant now = clock.instant();
      if (unavailable && !forceNext) {
        return last;
      }
      if (!forceNext && last != null && Duration.between(last.at(), now).compareTo(MIN_INTERVAL) < 0) {
        return last;
      }
      forceNext = false;
      last = probe(now);
      return last;
    } finally {
      lock.unlock();
    }
  }

  /** The next {@link #read()} probes afresh, and a sticky failure is retried. */
  void invalidate() {
    lock.lock();
    try {
      forceNext = true;
      unavailable = false;
    } finally {
      lock.unlock();
    }
  }

  private Reading probe(Instant now) {
    Outcome outcome;
    try {
      outcome = runner.run();
    } catch (Exception e) {
      LOG.debug("nvidia-smi not available — disabling future polls: {}", e.getMessage());
      unavailable = true;
      return new Reading(false, "nvidia-smi not available: " + e.getMessage(), null, null, null, now);
    }
    if (outcome.output() == null) {
      LOG.warn("nvidia-smi timed out");
      return new Reading(false, "nvidia-smi timed out", null, null, null, now);
    }
    if (outcome.exitCode() != 0 || outcome.output().isBlank()) {
      LOG.debug("nvidia-smi failed or returned empty output — disabling future polls");
      unavailable = true;
      return new Reading(false, "nvidia-smi exited " + outcome.exitCode(), null, null, null, now);
    }
    return parse(outcome.output(), now);
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
