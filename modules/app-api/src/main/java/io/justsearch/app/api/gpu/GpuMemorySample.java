/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpu;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One reading of graphics memory for the adapter the Engine computes on.
 *
 * <p>A reading is either known (source PDH or NVML) or {@link #unknown UNKNOWN}. UNKNOWN counts as
 * neither pressure nor fine and must trigger nothing. Per-process values come only from PDH, are
 * keyed by PID and are not additive across processes; NVML has no per-process memory under WDDM,
 * so an NVML reading has none.
 *
 * @param device the adapter read; empty only when UNKNOWN
 * @param processes dedicated and shared bytes per PID on {@code device}; empty without PDH
 */
public record GpuMemorySample(
    GpuMemorySource source,
    Optional<UnknownReason> unknownReason,
    Optional<GpuDevice> device,
    Optional<String> adapterName,
    OptionalLong adapterTotalBytes,
    OptionalLong adapterUsedBytes,
    Map<Long, ProcessMemory> processes,
    Instant sampledAt) {

  public GpuMemorySample {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(unknownReason, "unknownReason");
    Objects.requireNonNull(device, "device");
    Objects.requireNonNull(adapterName, "adapterName");
    Objects.requireNonNull(adapterTotalBytes, "adapterTotalBytes");
    Objects.requireNonNull(adapterUsedBytes, "adapterUsedBytes");
    processes = Map.copyOf(Objects.requireNonNull(processes, "processes"));
    Objects.requireNonNull(sampledAt, "sampledAt");
    boolean known = source != GpuMemorySource.NONE;
    if (known == unknownReason.isPresent()) {
      throw new IllegalArgumentException("unknownReason is present exactly when source is NONE");
    }
    if (known && (device.isEmpty() || adapterTotalBytes.isEmpty() || adapterUsedBytes.isEmpty())) {
      throw new IllegalArgumentException("a known reading names its device, total and used bytes");
    }
    if (!known && (device.isPresent() || adapterTotalBytes.isPresent()
        || adapterUsedBytes.isPresent() || !processes.isEmpty())) {
      throw new IllegalArgumentException("an UNKNOWN reading carries no values");
    }
    if (source != GpuMemorySource.PDH && !processes.isEmpty()) {
      throw new IllegalArgumentException("per-process memory is read only from PDH");
    }
    if (adapterTotalBytes.isPresent() && adapterTotalBytes.getAsLong() <= 0) {
      throw new IllegalArgumentException("adapterTotalBytes must be positive");
    }
    if (adapterUsedBytes.isPresent() && adapterUsedBytes.getAsLong() < 0) {
      throw new IllegalArgumentException("adapterUsedBytes must be non-negative");
    }
  }

  public static GpuMemorySample unknown(UnknownReason reason, Instant sampledAt) {
    return new GpuMemorySample(
        GpuMemorySource.NONE,
        Optional.of(reason),
        Optional.empty(),
        Optional.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        Map.of(),
        sampledAt);
  }

  public boolean known() {
    return source != GpuMemorySource.NONE;
  }

  /** Total minus used, floored at zero; empty when UNKNOWN. */
  public OptionalLong adapterFreeBytes() {
    if (!known()) return OptionalLong.empty();
    return OptionalLong.of(
        Math.max(0L, adapterTotalBytes.getAsLong() - adapterUsedBytes.getAsLong()));
  }

  /** The reading for one process, when PDH reported it. */
  public Optional<ProcessMemory> process(long pid) {
    return Optional.ofNullable(processes.get(pid));
  }

  /** Dedicated and shared graphics memory of one process on the sampled adapter. */
  public record ProcessMemory(long dedicatedBytes, long sharedBytes) {
    public ProcessMemory {
      if (dedicatedBytes < 0 || sharedBytes < 0) {
        throw new IllegalArgumentException("process memory must be non-negative");
      }
    }
  }

  /** Why a reading is UNKNOWN. */
  public enum UnknownReason {
    /** The counters are read only on Windows. */
    UNSUPPORTED_OS,
    /** No NVIDIA hardware adapter is present. */
    NO_NVIDIA_ADAPTER,
    /** Several NVIDIA adapters and no LUID-to-CUDA map decides which one we compute on. */
    UNMAPPED_MULTI_GPU,
    /** Every counter source failed. */
    READ_FAILED,
    /** The reader was closed. */
    CLOSED
  }
}
