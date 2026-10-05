/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.Profile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Projects the one {@link GpuMemoryReader} into the {@code operating.gpu} block (ADR-0050, CH11).
 * Diagnostic only: no control decision reads this projection.
 *
 * <ul>
 *   <li>The reader is sampled at most once per {@link #CACHE_WINDOW}; a status pass inside the
 *       window reuses the cached sample and only its age moves. A pass outside it takes exactly one
 *       sample, under this projection's lock and the reader's own serialization.
 *   <li>Our use is the PDH dedicated and shared use summed over our processes: the Engine and its
 *       descendant processes (the children it launched), read from the OS when the sample is
 *       taken. If they cannot be listed, the Engine alone.
 *   <li>External use is the adapter's used memory minus our dedicated use, floored at zero.
 *   <li>An UNKNOWN reading is the unknown block. NVML has no per-process memory, so an NVML reading
 *       has adapter figures only, and no our-use or external-use figures.
 * </ul>
 */
final class OperatingGpuProjection {
  private static final Logger log = LoggerFactory.getLogger(OperatingGpuProjection.class);

  /** At most one reader sample per window. */
  static final Duration CACHE_WINDOW = Duration.ofSeconds(2);

  /** A reading older than this many windows is reported stale. */
  static final int STALE_WINDOWS = 3;

  private final GpuMemoryReader reader;
  private final Supplier<Set<Long>> ourPids;
  private final Clock clock;

  private Reading cached;
  private Instant cachedAt;

  OperatingGpuProjection(GpuMemoryReader reader, Supplier<Set<Long>> ourPids, Clock clock) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.ourPids = Objects.requireNonNull(ourPids, "ourPids");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** The Engine process and every process it launched, as the OS reports them now. */
  static Set<Long> engineAndDescendants() {
    ProcessHandle self = ProcessHandle.current();
    Set<Long> pids = new HashSet<>();
    pids.add(self.pid());
    self.descendants().forEach(p -> pids.add(p.pid()));
    return pids;
  }

  /** The block for this status pass. Never throws. */
  synchronized OperatingGpuView view() {
    Instant now = clock.instant();
    if (cached == null || !now.isBefore(cachedAt.plus(CACHE_WINDOW))) {
      cached = read();
      cachedAt = now;
    }
    return cached.view(now);
  }

  private Reading read() {
    GpuMemorySample sample = reader.sampleNow();
    if (sample.source() != GpuMemorySource.PDH) return new Reading(sample, null, null, null);
    Set<Long> pids;
    try {
      pids = ourPids.get();
    } catch (RuntimeException e) {
      log.debug("Listing the Engine's child processes failed; counting the Engine alone", e);
      pids = Set.of(ProcessHandle.current().pid());
    }
    long dedicated = 0;
    long shared = 0;
    for (Long pid : pids) {
      var memory = sample.process(pid);
      if (memory.isPresent()) {
        dedicated += memory.get().dedicatedBytes();
        shared += memory.get().sharedBytes();
      }
    }
    long external = Math.max(0L, sample.adapterUsedBytes().getAsLong() - dedicated);
    return new Reading(sample, dedicated, shared, external);
  }

  /** One cached sample with the figures derived from it when it was taken. */
  private record Reading(GpuMemorySample sample, Long ourDedicated, Long ourShared, Long external) {
    OperatingGpuView view(Instant now) {
      if (!sample.known()) return OperatingGpuView.unknown();
      long ageMs = Math.max(0L, Duration.between(sample.sampledAt(), now).toMillis());
      return new OperatingGpuView(
          Profile.UNRESOLVED,
          null,
          sample.adapterName().orElse(null),
          sample.adapterTotalBytes().getAsLong(),
          sample.adapterFreeBytes().getAsLong(),
          ourDedicated,
          ourShared,
          external,
          null,
          sample.source(),
          sample.sampledAt().toString(),
          ageMs,
          ageMs > STALE_WINDOWS * CACHE_WINDOW.toMillis());
    }
  }
}
