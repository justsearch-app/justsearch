/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySample.ProcessMemory;
import io.justsearch.app.api.gpu.GpuMemorySample.UnknownReason;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.Profile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("operating.gpu projection of the GPU memory reader")
final class OperatingGpuProjectionTest {

  static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");
  static final long GIB = 1L << 30;
  static final long ENGINE = 4100L;
  static final long LLAMA = 4200L;
  static final GpuDevice DEVICE = new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty());

  @Test
  @DisplayName("PDH: total, free, our dedicated and shared over Engine and children, external = used - ours")
  void pdhValues() {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader = new FakeReader(clock, at -> pdh(at, 10 * GIB));
    var projection = sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE, LLAMA), clock));

    OperatingGpuView view = projection.view(6_000L);

    assertEquals(GpuMemorySource.PDH, view.source());
    assertEquals(Profile.UNRESOLVED, view.profile());
    assertEquals("NVIDIA GeForce RTX 4070", view.adapter());
    assertEquals(12 * GIB, view.adapterTotalBytes());
    assertEquals(2 * GIB, view.adapterFreeBytes());
    assertEquals(7 * GIB, view.ourDedicatedBytes(), "Engine 1 GiB + llama-server 6 GiB");
    assertEquals(300L, view.ourSharedBytes());
    assertEquals(3 * GIB, view.externalUsedBytes(), "10 GiB used - 7 GiB ours; the game's 2 GiB and more");
    assertEquals(T0.toString(), view.sampledAt());
    assertEquals(0L, view.ageMs());
    assertFalse(view.stale());
    assertNull(view.spillSuspected(), "no spill producer yet");
  }

  @Test
  @DisplayName("external use is floored at zero")
  void externalFlooredAtZero() {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader = new FakeReader(clock, at -> pdh(at, 5 * GIB));
    var projection = sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE, LLAMA), clock));
    assertEquals(0L, projection.view(6_000L).externalUsedBytes());
  }

  @Test
  @DisplayName("listing child processes failing counts the Engine alone")
  void pidListingFailure() {
    long self = ProcessHandle.current().pid();
    ManualClock clock = new ManualClock(T0);
    FakeReader reader =
        new FakeReader(
            clock,
            at ->
                new GpuMemorySample(GpuMemorySource.PDH, Optional.empty(), Optional.of(DEVICE),
                    Optional.of("RTX"), OptionalLong.of(12 * GIB), OptionalLong.of(4 * GIB),
                    Map.of(self, new ProcessMemory(GIB, 0L), LLAMA, new ProcessMemory(2 * GIB, 0L)),
                    at));
    var projection =
        sampled(
            new OperatingGpuProjection(
                reader,
                () -> {
                  throw new IllegalStateException("snapshot failed");
                },
                clock));

    OperatingGpuView view = projection.view(6_000L);
    assertEquals(GIB, view.ourDedicatedBytes());
    assertEquals(3 * GIB, view.externalUsedBytes());
  }

  @Test
  @DisplayName("NVML: adapter figures only, no our-use or external-use")
  void nvml() {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader =
        new FakeReader(
            clock,
            at ->
                new GpuMemorySample(GpuMemorySource.NVML, Optional.empty(), Optional.of(DEVICE),
                    Optional.of("RTX"), OptionalLong.of(12 * GIB), OptionalLong.of(9 * GIB),
                    Map.of(), at));
    OperatingGpuView view =
        sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE), clock)).view(6_000L);

    assertEquals(GpuMemorySource.NVML, view.source());
    assertEquals(3 * GIB, view.adapterFreeBytes());
    assertNull(view.ourDedicatedBytes());
    assertNull(view.ourSharedBytes());
    assertNull(view.externalUsedBytes());
  }

  @Test
  @DisplayName("any UNKNOWN reading is the unknown block")
  void unknown() {
    for (UnknownReason reason : UnknownReason.values()) {
      ManualClock clock = new ManualClock(T0);
      FakeReader reader = new FakeReader(clock, at -> GpuMemorySample.unknown(reason, at));
      OperatingGpuView view =
          sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE), clock)).view(6_000L);
      assertEquals(OperatingGpuView.unknown(), view, reason.name());
    }
  }

  @Test
  @DisplayName("before the first sample exists a pass is unknown and never calls the reader")
  void noSampleYet() {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader = new FakeReader(clock, at -> pdh(at, 10 * GIB));
    var projection = new OperatingGpuProjection(reader, () -> Set.of(ENGINE, LLAMA), clock);

    assertEquals(OperatingGpuView.unknown(), projection.view(6_000L));
    clock.advance(Duration.ofSeconds(10));
    assertEquals(OperatingGpuView.unknown(), projection.view(6_000L));
    assertEquals(0, reader.samples.get(), "a status pass never takes the first sample");

    projection.sampleFirst();
    assertEquals(GpuMemorySource.PDH, projection.view(6_000L).source());
    assertEquals(1, reader.samples.get());
  }

  @Test
  @DisplayName("a pass does not wait for a first sample still in flight")
  void passDoesNotWaitForFirstSample() throws Exception {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader = new FakeReader(clock, at -> pdh(at, 10 * GIB));
    CountDownLatch release = new CountDownLatch(1);
    reader.gate = release;
    var projection = new OperatingGpuProjection(reader, () -> Set.of(ENGINE), clock);

    Thread first = Thread.ofPlatform().daemon().start(projection::sampleFirst);
    try {
      assertTrue(reader.entered.await(10, TimeUnit.SECONDS), "the first sample started");
      OperatingGpuView during =
          assertTimeoutPreemptively(Duration.ofSeconds(5), () -> projection.view(6_000L));
      assertEquals(OperatingGpuView.unknown(), during);
    } finally {
      release.countDown();
      first.join(10_000);
    }
    assertEquals(GpuMemorySource.PDH, projection.view(6_000L).source());
    assertEquals(1, reader.samples.get());
  }

  @Test
  @DisplayName("a reader that throws is the unknown block, cached for the window like any reading")
  void readerFailureIsCachedUnknown() {
    ManualClock clock = new ManualClock(T0);
    AtomicReference<RuntimeException> failure =
        new AtomicReference<>(new IllegalStateException("PDH query failed"));
    FakeReader reader =
        new FakeReader(
            clock,
            at -> {
              RuntimeException e = failure.get();
              if (e != null) throw e;
              return pdh(at, 10 * GIB);
            });
    var projection = new OperatingGpuProjection(reader, () -> Set.of(ENGINE), clock);

    projection.sampleFirst();
    assertEquals(OperatingGpuView.unknown(), projection.view(6_000L));
    clock.advance(Duration.ofMillis(1_999));
    assertEquals(OperatingGpuView.unknown(), projection.view(6_000L));
    assertEquals(1, reader.samples.get(), "a failing reader is not called again inside the window");

    clock.advance(Duration.ofMillis(1));
    assertEquals(OperatingGpuView.unknown(), projection.view(6_000L));
    assertEquals(2, reader.samples.get(), "after the window it is tried once more");

    failure.set(null);
    clock.advance(Duration.ofSeconds(2));
    assertEquals(GpuMemorySource.PDH, projection.view(6_000L).source(), "a recovered reader is read");
    assertEquals(3, reader.samples.get());
  }

  @Test
  @DisplayName("a reader that fails to link is the unknown block too")
  void readerLinkageFailure() {
    ManualClock clock = new ManualClock(T0);
    GpuMemoryReader reader =
        new GpuMemoryReader() {
          @Override
          public GpuMemorySample sampleNow() {
            throw new UnsatisfiedLinkError("pdh.dll");
          }

          @Override
          public void close() {}
        };
    var projection = sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE), clock));
    assertEquals(OperatingGpuView.unknown(), projection.view(6_000L));
  }

  @Test
  @DisplayName("one sample per 2 s window; inside it only the age moves")
  void cacheWindow() {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader = new FakeReader(clock, at -> pdh(at, 10 * GIB));
    var projection = sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE, LLAMA), clock));

    projection.view(6_000L);
    clock.advance(Duration.ofMillis(1_999));
    OperatingGpuView cached = projection.view(6_000L);
    assertEquals(1, reader.samples.get(), "inside the window the cached sample is reused");
    assertEquals(1_999L, cached.ageMs());
    assertEquals(T0.toString(), cached.sampledAt());

    clock.advance(Duration.ofMillis(1));
    OperatingGpuView fresh = projection.view(6_000L);
    assertEquals(2, reader.samples.get(), "at 2 s exactly one new sample is taken");
    assertEquals(0L, fresh.ageMs());
    assertEquals(T0.plusSeconds(2).toString(), fresh.sampledAt());
  }

  @Test
  @DisplayName("a reading older than three windows is stale")
  void staleReading() {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader = new FakeReader(clock, at -> pdh(at.minusMillis(6_001), 10 * GIB));
    OperatingGpuView view =
        sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE), clock)).view(6_000L);
    assertTrue(view.stale());
    assertEquals(6_001L, view.ageMs());
  }

  @Test
  @DisplayName("concurrent status passes after the window take exactly one new sample")
  void concurrentPassesShareOneSample() throws Exception {
    ManualClock clock = new ManualClock(T0);
    FakeReader reader = new FakeReader(clock, at -> pdh(at, 10 * GIB));
    var projection = sampled(new OperatingGpuProjection(reader, () -> Set.of(ENGINE), clock));
    clock.advance(Duration.ofSeconds(2));
    reader.delayMs = 20;

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<OperatingGpuView>> views = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        views.add(
            pool.submit(
                () -> {
                  start.await();
                  return projection.view(6_000L);
                }));
      }
      start.countDown();
      for (Future<OperatingGpuView> v : views) {
        assertEquals(GpuMemorySource.PDH, v.get(30, TimeUnit.SECONDS).source());
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(2, reader.samples.get(), "the first sample and one more");
  }

  private static OperatingGpuProjection sampled(OperatingGpuProjection projection) {
    projection.sampleFirst();
    return projection;
  }

  /** 10 GiB used on a 12 GiB card: Engine 1 GiB, llama-server 6 GiB, a game 2 GiB. */
  static GpuMemorySample pdh(Instant at, long used) {
    return new GpuMemorySample(
        GpuMemorySource.PDH,
        Optional.empty(),
        Optional.of(DEVICE),
        Optional.of("NVIDIA GeForce RTX 4070"),
        OptionalLong.of(12 * GIB),
        OptionalLong.of(used),
        Map.of(
            ENGINE, new ProcessMemory(GIB, 100L),
            LLAMA, new ProcessMemory(6 * GIB, 200L),
            9999L, new ProcessMemory(2 * GIB, 0L)),
        at);
  }

  static final class FakeReader implements GpuMemoryReader {
    final Function<Instant, GpuMemorySample> sample;
    final AtomicInteger samples = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    volatile long delayMs;
    volatile CountDownLatch gate;
    private final Clock clock;

    /** Stamps each sample with {@code clock}'s time, as the real reader does. */
    FakeReader(Clock clock, Function<Instant, GpuMemorySample> sample) {
      this.clock = clock;
      this.sample = sample;
    }

    @Override
    public synchronized GpuMemorySample sampleNow() {
      samples.incrementAndGet();
      entered.countDown();
      try {
        CountDownLatch g = gate;
        if (g != null) g.await();
        if (delayMs > 0) Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return sample.apply(clock.instant());
    }

    @Override
    public void close() {}
  }

  static final class ManualClock extends Clock {
    private volatile Instant now;

    ManualClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
