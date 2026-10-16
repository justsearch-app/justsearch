/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySample.UnknownReason;
import io.justsearch.app.api.gpu.GpuMemorySource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GPU memory reader")
final class WindowsGpuMemoryReaderTest {

  static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final long GIB = 1L << 30;

  static final String RTX = "luid_0x00000000_0x0000d7a2";
  static final String SECOND = "luid_0x00000000_0x0000e001";
  static final String BASIC = "luid_0x00000000_0x0000ef5d";

  static final AdapterSource.Adapter RTX_4070 =
      new AdapterSource.Adapter(RTX, 0x10DE, "NVIDIA GeForce RTX 4070", 12 * GIB, false);
  static final AdapterSource.Adapter SECOND_NVIDIA =
      new AdapterSource.Adapter(SECOND, 0x10DE, "NVIDIA RTX A2000", 6 * GIB, false);
  static final AdapterSource.Adapter BASIC_RENDER =
      new AdapterSource.Adapter(BASIC, 0x1414, "Microsoft Basic Render Driver", 0, true);
  static final AdapterSource.Adapter INTEL =
      new AdapterSource.Adapter("luid_0x00000000_0x0000a001", 0x8086, "Intel UHD", 128L << 20, false);

  /** PDH as spike S1 saw it: upper-case hex, two adapters, one process on both. */
  static final CounterSource.Counters COUNTERS =
      new CounterSource.Counters(
          Map.of(
              "pid_8392_luid_0x00000000_0x0000D7A2_phys_0", 3 * GIB,
              "pid_8392_luid_0x00000000_0x0000EF5D_phys_0", 7L,
              "pid_1200_luid_0x00000000_0x0000D7A2_phys_0", GIB,
              "pid_1200_luid_0x00000000_0x0000D7A2_phys_1", 2 * GIB,
              "not_an_instance", 5L),
          Map.of(
              "pid_8392_luid_0x00000000_0x0000D7A2_phys_0", 100L,
              "pid_77_luid_0x00000000_0x0000D7A2_phys_0", 50L),
          Map.of(
              "luid_0x00000000_0x0000D7A2_phys_0", 7 * GIB,
              "luid_0x00000000_0x0000EF5D_phys_0", 1L));

  @Test
  @DisplayName("PDH: adapter use and per-PID dedicated and shared use on the NVIDIA LUID only")
  void readsPdh() {
    var reader = reader(adapters(RTX_4070, BASIC_RENDER), counters(COUNTERS), nvml(null));

    GpuMemorySample sample = reader.sampleNow();

    assertEquals(GpuMemorySource.PDH, sample.source());
    assertEquals(new GpuDevice(RTX, OptionalInt.empty()), sample.device().orElseThrow());
    assertEquals(Optional.of("NVIDIA GeForce RTX 4070"), sample.adapterName());
    assertEquals(OptionalLong.of(12 * GIB), sample.adapterTotalBytes(), "total is DXGI's VRAM");
    assertEquals(OptionalLong.of(7 * GIB), sample.adapterUsedBytes());
    assertEquals(OptionalLong.of(5 * GIB), sample.adapterFreeBytes());
    assertEquals(
        Map.of(
            8392L, new ProcessMemory(3 * GIB, 100L),
            1200L, new ProcessMemory(3 * GIB, 0L),
            77L, new ProcessMemory(0L, 50L)),
        sample.processes(),
        "other adapters are ignored, physical nodes are summed, case does not matter");
    assertEquals(NOW, sample.sampledAt());
  }

  @Test
  @DisplayName("no NVIDIA hardware adapter is UNKNOWN, and the adapters are listed again next time")
  void noNvidiaAdapter() {
    var adapters = adapters(INTEL, BASIC_RENDER);
    var reader = reader(adapters, counters(COUNTERS), nvml(new NvmlSource.Reading(1, 8 * GIB, GIB)));

    assertUnknown(UnknownReason.NO_NVIDIA_ADAPTER, reader.sampleNow());
    assertUnknown(UnknownReason.NO_NVIDIA_ADAPTER, reader.sampleNow());
    assertEquals(2, adapters.calls.get());
  }

  @Test
  @DisplayName("several NVIDIA adapters without a LUID map are UNMAPPED_MULTI_GPU, never a guess")
  void unmappedMultiGpu() {
    var reader = reader(adapters(SECOND_NVIDIA, RTX_4070), counters(COUNTERS), nvml(null));
    assertUnknown(UnknownReason.UNMAPPED_MULTI_GPU, reader.sampleNow());
  }

  @Test
  @DisplayName("a mapped compute device picks the adapter among several, keeping its CUDA ordinal")
  void mappedMultiGpu() {
    GpuDevice compute = new GpuDevice(RTX, OptionalInt.of(1));
    var reader =
        new WindowsGpuMemoryReader(
            true,
            adapters(SECOND_NVIDIA, RTX_4070),
            counters(COUNTERS),
            nvml(null),
            Optional.of(compute),
            CLOCK);

    GpuMemorySample sample = reader.sampleNow();
    assertEquals(GpuMemorySource.PDH, sample.source());
    assertEquals(compute, sample.device().orElseThrow());
    assertEquals(OptionalLong.of(7 * GIB), sample.adapterUsedBytes());
  }

  @Test
  @DisplayName("PDH failing falls back to NVML totals, with no per-process memory")
  void nvmlFallback() {
    var reader =
        reader(adapters(RTX_4070), failingCounters(), nvml(new NvmlSource.Reading(1, 12 * GIB, 9 * GIB)));

    GpuMemorySample sample = reader.sampleNow();
    assertEquals(GpuMemorySource.NVML, sample.source());
    assertEquals(OptionalLong.of(12 * GIB), sample.adapterTotalBytes());
    assertEquals(OptionalLong.of(9 * GIB), sample.adapterUsedBytes());
    assertTrue(sample.processes().isEmpty());
  }

  @Test
  @DisplayName("NVML is not used when it cannot be mapped: several adapters or several NVML devices")
  void nvmlOnlyWhenUnambiguous() {
    GpuDevice compute = new GpuDevice(RTX, OptionalInt.of(0));
    var several =
        new WindowsGpuMemoryReader(
            true,
            adapters(RTX_4070, SECOND_NVIDIA),
            failingCounters(),
            nvml(new NvmlSource.Reading(2, 12 * GIB, GIB)),
            Optional.of(compute),
            CLOCK);
    assertUnknown(UnknownReason.READ_FAILED, several.sampleNow());

    var twoNvmlDevices =
        reader(adapters(RTX_4070), failingCounters(), nvml(new NvmlSource.Reading(2, 12 * GIB, GIB)));
    assertUnknown(UnknownReason.READ_FAILED, twoNvmlDevices.sampleNow());
  }

  @Test
  @DisplayName("every source failing is READ_FAILED, including linkage errors, and never throws")
  void everythingFails() {
    var reader = reader(adapters(RTX_4070), failingCounters(), nvml(null));
    assertUnknown(UnknownReason.READ_FAILED, reader.sampleNow());

    AdapterSource linkage =
        new FakeAdapters(List.of()) {
          @Override
          public List<Adapter> adapters() {
            calls.incrementAndGet();
            throw new UnsatisfiedLinkError("dxgi.dll");
          }
        };
    var broken = reader(linkage, counters(COUNTERS), nvml(null));
    assertUnknown(UnknownReason.READ_FAILED, broken.sampleNow());
  }

  @Test
  @DisplayName("an adapter-list failure is retried on the next sample")
  void adapterFailureRetried() {
    FakeAdapters adapters = adapters(RTX_4070);
    adapters.failNext = true;
    var reader = reader(adapters, counters(COUNTERS), nvml(null));

    assertUnknown(UnknownReason.READ_FAILED, reader.sampleNow());
    assertEquals(GpuMemorySource.PDH, reader.sampleNow().source());
    assertEquals(GpuMemorySource.PDH, reader.sampleNow().source());
    assertEquals(2, adapters.calls.get(), "listed once more after the failure, then cached");
  }

  @Test
  @DisplayName("a LUID PDH no longer reports (driver reset) relists the adapters; NVML covers the gap")
  void staleLuidRelists() {
    FakeAdapters adapters = adapters(RTX_4070);
    CounterSource.Counters afterReset =
        new CounterSource.Counters(Map.of(), Map.of(), Map.of("luid_0x00000000_0x0000F000_phys_0", GIB));
    var reader =
        reader(adapters, counters(afterReset), nvml(new NvmlSource.Reading(1, 12 * GIB, GIB)));

    assertEquals(GpuMemorySource.NVML, reader.sampleNow().source());
    reader.sampleNow();
    assertEquals(2, adapters.calls.get());
  }

  @Test
  @DisplayName("another OS is UNSUPPORTED_OS and touches no native source")
  void unsupportedOs() {
    FakeAdapters adapters = adapters(RTX_4070);
    var reader =
        new WindowsGpuMemoryReader(
            false, adapters, counters(COUNTERS), nvml(null), Optional.empty(), CLOCK);
    assertUnknown(UnknownReason.UNSUPPORTED_OS, reader.sampleNow());
    assertEquals(0, adapters.calls.get());
  }

  @Test
  @DisplayName("close releases every source once; later samples are CLOSED")
  void closeReleases() {
    FakeAdapters adapters = adapters(RTX_4070);
    FakeCounters counters = counters(COUNTERS);
    FakeNvml nvml = nvml(null);
    var reader = reader(adapters, counters, nvml);
    reader.sampleNow();

    reader.close();
    reader.close();

    assertEquals(1, adapters.closes.get());
    assertEquals(1, counters.closes.get());
    assertEquals(1, nvml.closes.get());
    assertUnknown(UnknownReason.CLOSED, reader.sampleNow());
    assertEquals(1, counters.calls.get());
  }

  @Test
  @DisplayName("concurrent samples are serialized: native reads never interleave")
  void samplesAreSerialized() throws Exception {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    FakeCounters slow =
        new FakeCounters(COUNTERS) {
          @Override
          public Counters collect() throws Exception {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
              Thread.sleep(5);
              return super.collect();
            } finally {
              inFlight.decrementAndGet();
            }
          }
        };
    var reader = reader(adapters(RTX_4070), slow, nvml(null));

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<GpuMemorySample>> futures = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return reader.sampleNow();
                }));
      }
      start.countDown();
      for (Future<GpuMemorySample> f : futures) {
        assertEquals(GpuMemorySource.PDH, f.get(30, TimeUnit.SECONDS).source());
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, maxInFlight.get());
    assertEquals(32, slow.calls.get());
  }

  private static void assertUnknown(UnknownReason reason, GpuMemorySample sample) {
    assertFalse(sample.known());
    assertEquals(Optional.of(reason), sample.unknownReason());
    assertEquals(NOW, sample.sampledAt());
  }

  private static WindowsGpuMemoryReader reader(
      AdapterSource adapters, CounterSource counters, NvmlSource nvml) {
    return new WindowsGpuMemoryReader(true, adapters, counters, nvml, Optional.empty(), CLOCK);
  }

  static FakeAdapters adapters(AdapterSource.Adapter... adapters) {
    return new FakeAdapters(List.of(adapters));
  }

  static FakeCounters counters(CounterSource.Counters counters) {
    return new FakeCounters(counters);
  }

  static FakeCounters failingCounters() {
    return new FakeCounters(null);
  }

  /** A null reading makes NVML fail. */
  static FakeNvml nvml(NvmlSource.Reading reading) {
    return new FakeNvml(reading);
  }

  static class FakeAdapters implements AdapterSource {
    final List<Adapter> adapters;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    boolean failNext;

    FakeAdapters(List<Adapter> adapters) {
      this.adapters = adapters;
    }

    @Override
    public List<Adapter> adapters() throws Exception {
      calls.incrementAndGet();
      if (failNext) {
        failNext = false;
        throw new IllegalStateException("CreateDXGIFactory1 status=0x80004005");
      }
      return adapters;
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  static class FakeCounters implements CounterSource {
    final Counters counters;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();

    FakeCounters(Counters counters) {
      this.counters = counters;
    }

    @Override
    public Counters collect() throws Exception {
      calls.incrementAndGet();
      if (counters == null) throw new IllegalStateException("PdhCollectQueryData status=0xc0000bc6");
      return counters;
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  static final class FakeNvml implements NvmlSource {
    final Reading reading;
    final AtomicInteger closes = new AtomicInteger();

    FakeNvml(Reading reading) {
      this.reading = reading;
    }

    @Override
    public Reading read() {
      if (reading == null) throw new IllegalStateException("nvmlInit_v2 status=0x9");
      return reading;
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }
}
