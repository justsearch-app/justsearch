package io.justsearch.app.services.observability.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricChangeRegistry;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricResourceCatalog;
import io.justsearch.app.observability.metrics.TimeseriesSnapshot;
import io.justsearch.app.observability.metrics.TimeseriesSnapshotHolder;
import io.justsearch.telemetry.RrdMetricStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobQueueDepthMetricProducer} (slice 3a.1.4 Phase 5).
 *
 * <p>Covers the core tick logic:
 *
 * <ul>
 *   <li>RrdMetricStore query → TimeseriesSnapshot construction with correct shape.
 *   <li>Holder update + change-registry broadcast on a real value change.
 *   <li>No-op suppression when the new snapshot has identical {@code values[]} to the previous.
 *   <li>Graceful fallback when the supplier returns null (no broadcasts).
 *   <li>Graceful fallback when the supplier throws (no broadcasts; no exception leak).
 * </ul>
 */
final class JobQueueDepthMetricProducerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);

  /**
   * Stub RRD store that returns a configurable {@link RrdMetricStore.TimeSeriesResult} for any
   * query. The full {@code RrdMetricStore} class is final/concrete so we use a per-call
   * {@link AtomicReference} via a thin subclass — but since {@code RrdMetricStore} is heavy
   * (file-backed RRD), the test instead uses the {@code Supplier} path and returns a stand-in
   * via a custom subclass with overridden {@link RrdMetricStore#query}.
   */
  private static RrdMetricStore stubStore(double[] values) {
    return new RrdMetricStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))) {
      @Override
      public TimeSeriesResult query(String name, long startSec, long endSec) {
        long[] timestamps = new long[values.length];
        for (int i = 0; i < values.length; i++) {
          timestamps[i] = startSec + i * 30L;
        }
        return new TimeSeriesResult(name, timestamps, values);
      }
    };
  }

  @Test
  @DisplayName("first tick broadcasts when the holder is empty")
  void firstTickBroadcasts() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    JobQueueDepthMetricChangeRegistry registry = new JobQueueDepthMetricChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    double[] values = {10.0, 12.0, 15.0, 14.0};
    JobQueueDepthMetricProducer producer =
        new JobQueueDepthMetricProducer(
            processExecutors,
            () -> stubStore(values), holder, registry, FIXED_CLOCK);

    producer.tick();

    TimeseriesSnapshot snapshot = holder.current();
    assertNotNull(snapshot);
    assertArrayEquals(values, snapshot.values());
    assertEquals(JobQueueDepthMetricResourceCatalog.JOB_QUEUE_DEPTH_ID, snapshot.resourceId());
    assertEquals(30L * 60_000L, snapshot.windowMs());
    assertEquals(30_000L, snapshot.sampleIntervalMs());
    assertEquals("count", snapshot.unit());

    // Registry should have received exactly one UPDATE frame carrying the snapshot.
    assertEquals(1, seen.size());
    assertEquals(SseFrameKind.UPDATE, seen.get(0).frameKind());

    sub.unsubscribe();
  }

  @Test
  @DisplayName("identical-values tick is no-op-suppressed")
  void identicalValuesSuppressed() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    JobQueueDepthMetricChangeRegistry registry = new JobQueueDepthMetricChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    double[] values = {1.0, 2.0, 3.0};
    JobQueueDepthMetricProducer producer =
        new JobQueueDepthMetricProducer(
            processExecutors,
            () -> stubStore(values), holder, registry, FIXED_CLOCK);

    producer.tick();
    producer.tick();
    producer.tick();

    // Three ticks, but only the first should have broadcast (subsequent ticks see
    // identical values → suppressed).
    assertEquals(1, seen.size());
    sub.unsubscribe();
  }

  @Test
  @DisplayName("changed values trigger a fresh broadcast")
  void changedValuesBroadcasts() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    JobQueueDepthMetricChangeRegistry registry = new JobQueueDepthMetricChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    AtomicReference<double[]> currentValues = new AtomicReference<>(new double[] {1.0, 2.0});
    JobQueueDepthMetricProducer producer =
        new JobQueueDepthMetricProducer(
            processExecutors,
            () -> stubStore(currentValues.get()), holder, registry, FIXED_CLOCK);

    producer.tick();
    currentValues.set(new double[] {1.0, 2.0, 3.0});
    producer.tick();
    currentValues.set(new double[] {1.0, 2.0, 3.0}); // same as last → suppressed
    producer.tick();
    currentValues.set(new double[] {5.0, 6.0, 7.0});
    producer.tick();

    // 4 ticks: first broadcasts, second broadcasts (length change), third suppressed,
    // fourth broadcasts. Total = 3 broadcasts.
    assertEquals(3, seen.size());
    sub.unsubscribe();
  }

  @Test
  @DisplayName("null supplier yields no broadcast and leaves holder empty")
  void nullSupplierGracefulFallback() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    JobQueueDepthMetricChangeRegistry registry = new JobQueueDepthMetricChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    JobQueueDepthMetricProducer producer =
        new JobQueueDepthMetricProducer(processExecutors, () -> null, holder, registry, FIXED_CLOCK);
    producer.tick();

    assertNull(holder.current());
    assertTrue(seen.isEmpty());
    sub.unsubscribe();
  }

  @Test
  @DisplayName("supplier throwing does not propagate to the scheduler")
  void supplierThrowsGracefulFallback() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    JobQueueDepthMetricChangeRegistry registry = new JobQueueDepthMetricChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    JobQueueDepthMetricProducer producer =
        new JobQueueDepthMetricProducer(
            processExecutors,
            () -> {
              throw new RuntimeException("simulated RRD failure");
            },
            holder,
            registry,
            FIXED_CLOCK);

    // Must not throw — the scheduler-driven path swallows tick exceptions.
    producer.tick();

    assertNull(holder.current());
    assertTrue(seen.isEmpty());
    sub.unsubscribe();
  }

  @Test
  @DisplayName("snapshot started/ended timestamps reflect the configured 30-min window from clock")
  void snapshotTimestampsMatchClock() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    JobQueueDepthMetricChangeRegistry registry = new JobQueueDepthMetricChangeRegistry();
    JobQueueDepthMetricProducer producer =
        new JobQueueDepthMetricProducer(
            processExecutors,
            () -> stubStore(new double[] {1.0, 2.0}), holder, registry, FIXED_CLOCK);
    producer.tick();
    TimeseriesSnapshot snapshot = holder.current();
    assertNotNull(snapshot);
    Instant clockNow = FIXED_CLOCK.instant();
    assertEquals(clockNow.getEpochSecond(), snapshot.endedAt().getEpochSecond());
    assertEquals(
        clockNow.getEpochSecond() - 30L * 60L, snapshot.startedAt().getEpochSecond());
    assertFalse(snapshot.values().length == 0);
  }
}
