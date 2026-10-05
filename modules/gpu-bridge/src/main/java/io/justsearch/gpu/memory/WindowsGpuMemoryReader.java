/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySample.UnknownReason;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.configuration.PlatformPaths;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one {@link GpuMemoryReader} (ADR-0050, spike S1): PDH first, NVML as the fallback, DXGI for
 * the adapter LUIDs.
 *
 * <ol>
 *   <li>DXGI lists the adapters. Hardware adapters of vendor 0x10DE are the NVIDIA candidates;
 *       none is {@code NO_NVIDIA_ADAPTER}. With one candidate, that is the adapter we compute on.
 *       With several, only a caller-supplied device (its LUID, from {@code cudaDeviceProp.luid})
 *       decides; without one the reading is {@code UNMAPPED_MULTI_GPU}.
 *   <li>PDH gives the adapter's dedicated use and each process's dedicated and shared use on that
 *       LUID. Instance names are matched case-insensitively (PDH and Get-Counter differ in case).
 *       The total is DXGI's dedicated video memory.
 *   <li>If PDH fails, NVML gives total and used for device 0, only when exactly one NVIDIA adapter
 *       exists and NVML sees exactly one device: NVML has no LUID, so nothing else maps it.
 *   <li>Anything else is {@code READ_FAILED}. {@link #sampleNow()} never throws.
 * </ol>
 *
 * <p>The adapter list is cached and listed again when it was empty, failed, or when PDH no longer
 * reports the cached LUID (a driver reset can give the adapter a new LUID). Native sources are
 * opened on first use and kept until {@link #close()}. Samples are serialized by this reader's
 * lock, so concurrent callers never interleave native reads.
 */
public final class WindowsGpuMemoryReader implements GpuMemoryReader {
  private static final Logger LOG = LoggerFactory.getLogger(WindowsGpuMemoryReader.class);

  private static final Pattern PROCESS_INSTANCE =
      Pattern.compile("pid_(\\d+)_(luid_0x[0-9a-f]{8}_0x[0-9a-f]{8})_phys_\\d+");
  private static final Pattern ADAPTER_INSTANCE =
      Pattern.compile("(luid_0x[0-9a-f]{8}_0x[0-9a-f]{8})_phys_\\d+");

  private final boolean supported;
  private final AdapterSource adapterSource;
  private final CounterSource counterSource;
  private final NvmlSource nvmlSource;
  private final Optional<GpuDevice> computeDevice;
  private final Clock clock;

  private List<AdapterSource.Adapter> adapters;
  private boolean closed;
  private UnknownReason lastLogged;

  WindowsGpuMemoryReader(
      boolean supported,
      AdapterSource adapterSource,
      CounterSource counterSource,
      NvmlSource nvmlSource,
      Optional<GpuDevice> computeDevice,
      Clock clock) {
    this.supported = supported;
    this.adapterSource = Objects.requireNonNull(adapterSource, "adapterSource");
    this.counterSource = Objects.requireNonNull(counterSource, "counterSource");
    this.nvmlSource = Objects.requireNonNull(nvmlSource, "nvmlSource");
    this.computeDevice = Objects.requireNonNull(computeDevice, "computeDevice");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The reader for this machine. Nothing native is loaded until the first sample; on another OS
   * every sample is {@code UNSUPPORTED_OS}.
   *
   * @param computeDevice the adapter the Engine computes on, when known (LUID and CUDA ordinal);
   *     required to read anything on a machine with several NVIDIA adapters
   */
  public static WindowsGpuMemoryReader open(Optional<GpuDevice> computeDevice, Clock clock) {
    return new WindowsGpuMemoryReader(
        PlatformPaths.isWindows(),
        new DxgiAdapterSource(),
        new PdhCounterSource(),
        new NvmlMemorySource(),
        computeDevice,
        clock);
  }

  @Override
  public synchronized GpuMemorySample sampleNow() {
    Instant now = clock.instant();
    if (closed) return GpuMemorySample.unknown(UnknownReason.CLOSED, now);
    if (!supported) return GpuMemorySample.unknown(UnknownReason.UNSUPPORTED_OS, now);
    try {
      return sample(now);
    } catch (RuntimeException | LinkageError e) {
      // The sources wrap native failures; this is the last guard of "never throws".
      return unknown(UnknownReason.READ_FAILED, now, e);
    }
  }

  private GpuMemorySample sample(Instant now) {
    if (adapters == null) {
      try {
        adapters = List.copyOf(adapterSource.adapters());
      } catch (Exception | LinkageError e) {
        return unknown(UnknownReason.READ_FAILED, now, e);
      }
    }
    List<AdapterSource.Adapter> nvidia =
        adapters.stream().filter(AdapterSource.Adapter::nvidiaHardware).toList();
    if (nvidia.isEmpty()) {
      adapters = null; // an adapter can appear (driver install, eGPU); list again next time
      return unknown(UnknownReason.NO_NVIDIA_ADAPTER, now, null);
    }
    Optional<AdapterSource.Adapter> target = target(nvidia);
    if (target.isEmpty()) return unknown(UnknownReason.UNMAPPED_MULTI_GPU, now, null);
    AdapterSource.Adapter adapter = target.get();
    GpuDevice device =
        computeDevice
            .filter(d -> d.luid().equals(adapter.luid()))
            .orElseGet(() -> new GpuDevice(adapter.luid(), OptionalInt.empty()));

    Exception pdhFailure;
    try {
      Optional<GpuMemorySample> fromPdh = fromPdh(counterSource.collect(), adapter, device, now);
      if (fromPdh.isPresent()) return logged(fromPdh.get());
      adapters = null; // PDH does not know this LUID any more; list the adapters again
      pdhFailure = new IllegalStateException("PDH reports no adapter instance for " + adapter.luid());
    } catch (Exception e) {
      pdhFailure = e;
    }
    LOG.debug("PDH GPU memory read failed; trying NVML", pdhFailure);

    if (nvidia.size() == 1) {
      try {
        NvmlSource.Reading reading = nvmlSource.read();
        if (reading.deviceCount() == 1 && reading.totalBytes() > 0 && reading.usedBytes() >= 0) {
          return logged(
              new GpuMemorySample(
                  GpuMemorySource.NVML,
                  Optional.empty(),
                  Optional.of(device),
                  Optional.of(adapter.name()),
                  OptionalLong.of(reading.totalBytes()),
                  OptionalLong.of(reading.usedBytes()),
                  Map.of(),
                  now));
        }
        return unknown(
            UnknownReason.READ_FAILED,
            now,
            new IllegalStateException("NVML sees " + reading.deviceCount() + " devices"));
      } catch (Exception | LinkageError e) {
        return unknown(UnknownReason.READ_FAILED, now, e);
      }
    }
    return unknown(UnknownReason.READ_FAILED, now, pdhFailure);
  }

  private Optional<AdapterSource.Adapter> target(List<AdapterSource.Adapter> nvidia) {
    if (computeDevice.isPresent()) {
      String luid = computeDevice.get().luid();
      Optional<AdapterSource.Adapter> mapped =
          nvidia.stream().filter(a -> a.luid().equals(luid)).findFirst();
      if (mapped.isPresent()) return mapped;
    }
    return nvidia.size() == 1 ? Optional.of(nvidia.get(0)) : Optional.empty();
  }

  /** The PDH reading of one adapter, or empty when PDH has no instance for its LUID. */
  static Optional<GpuMemorySample> fromPdh(
      CounterSource.Counters counters,
      AdapterSource.Adapter adapter,
      GpuDevice device,
      Instant now) {
    if (adapter.dedicatedBytes() <= 0) return Optional.empty();
    long used = 0;
    boolean seen = false;
    for (Map.Entry<String, Long> instance : counters.adapterDedicated().entrySet()) {
      Matcher m = ADAPTER_INSTANCE.matcher(instance.getKey().toLowerCase(Locale.ROOT));
      if (m.matches() && m.group(1).equals(adapter.luid())) {
        used += instance.getValue();
        seen = true;
      }
    }
    if (!seen) return Optional.empty();

    Map<Long, Long> dedicated = perProcess(counters.processDedicated(), adapter.luid());
    Map<Long, Long> shared = perProcess(counters.processShared(), adapter.luid());
    Map<Long, ProcessMemory> processes = new HashMap<>();
    for (Long pid : union(dedicated, shared)) {
      processes.put(
          pid, new ProcessMemory(dedicated.getOrDefault(pid, 0L), shared.getOrDefault(pid, 0L)));
    }
    return Optional.of(
        new GpuMemorySample(
            GpuMemorySource.PDH,
            Optional.empty(),
            Optional.of(device),
            Optional.of(adapter.name()),
            OptionalLong.of(adapter.dedicatedBytes()),
            OptionalLong.of(Math.max(0L, used)),
            processes,
            now));
  }

  /** Bytes per PID on one LUID, summed over the adapter's physical nodes. */
  private static Map<Long, Long> perProcess(Map<String, Long> instances, String luid) {
    Map<Long, Long> out = new HashMap<>();
    for (Map.Entry<String, Long> instance : instances.entrySet()) {
      Matcher m = PROCESS_INSTANCE.matcher(instance.getKey().toLowerCase(Locale.ROOT));
      if (!m.matches() || !m.group(2).equals(luid) || instance.getValue() < 0) continue;
      long pid;
      try {
        pid = Long.parseLong(m.group(1));
      } catch (NumberFormatException e) {
        continue;
      }
      out.merge(pid, instance.getValue(), Long::sum);
    }
    return out;
  }

  private static Set<Long> union(Map<Long, Long> a, Map<Long, Long> b) {
    Set<Long> keys = new HashSet<>(a.keySet());
    keys.addAll(b.keySet());
    return keys;
  }

  private GpuMemorySample unknown(UnknownReason reason, Instant now, Throwable cause) {
    if (reason != lastLogged) {
      lastLogged = reason;
      if (cause == null) LOG.info("GPU memory is UNKNOWN: {}", reason);
      else LOG.warn("GPU memory is UNKNOWN: {} ({})", reason, cause.toString());
    }
    return GpuMemorySample.unknown(reason, now);
  }

  private GpuMemorySample logged(GpuMemorySample known) {
    if (lastLogged != null) {
      LOG.info("GPU memory readable again from {}", known.source());
      lastLogged = null;
    }
    return known;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    try {
      counterSource.close();
    } finally {
      try {
        nvmlSource.close();
      } finally {
        adapterSource.close();
      }
    }
  }
}
