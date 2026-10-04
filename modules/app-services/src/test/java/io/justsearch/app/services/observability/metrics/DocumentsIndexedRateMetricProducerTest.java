package io.justsearch.app.services.observability.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricChangeRegistry;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricResourceCatalog;
import io.justsearch.app.observability.metrics.TimeseriesSnapshot;
import io.justsearch.app.observability.metrics.TimeseriesSnapshotHolder;
import io.justsearch.telemetry.RrdMetricStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DocumentsIndexedRateMetricProducer} (slice 3a.1.4b cohort
 * follow-up). Asserts the producer-configuration constants (metric name, unit, resource
 * id) and that the canonical algorithm — exhaustively tested in
 * {@code JobQueueDepthMetricProducerTest} — operates correctly through this instance.
 */
final class DocumentsIndexedRateMetricProducerTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @AfterEach
  void closeProcessExecutors() {
    processExecutors.close();
  }

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC);

  private static RrdMetricStore stubStore(double[] values, String expectedMetricName) {
    return new RrdMetricStore(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))) {
      @Override
      public TimeSeriesResult query(String name, long startSec, long endSec) {
        // Producer must query the right metric — fail loudly if the constant drifted.
        assertEquals(expectedMetricName, name);
        long[] timestamps = new long[values.length];
        for (int i = 0; i < values.length; i++) {
          timestamps[i] = startSec + i * 30L;
        }
        return new TimeSeriesResult(name, timestamps, values);
      }
    };
  }

  @Test
  @DisplayName("first tick broadcasts with correct metric configuration")
  void firstTickBroadcasts() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    DocumentsIndexedRateMetricChangeRegistry registry =
        new DocumentsIndexedRateMetricChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    double[] values = {0.5, 1.0, 1.5};
    DocumentsIndexedRateMetricProducer producer =
        new DocumentsIndexedRateMetricProducer(
            processExecutors,
            () -> stubStore(values, "worker.documents.indexed.rate_per_sec"),
            holder,
            registry,
            FIXED_CLOCK);

    producer.tick();

    TimeseriesSnapshot snapshot = holder.current();
    assertNotNull(snapshot);
    assertArrayEquals(values, snapshot.values());
    assertEquals(
        DocumentsIndexedRateMetricResourceCatalog.DOCUMENTS_INDEXED_RATE_ID,
        snapshot.resourceId());
    assertEquals("docs/s", snapshot.unit());
    assertEquals(1, seen.size());
    assertEquals(SseFrameKind.UPDATE, seen.get(0).frameKind());
    sub.unsubscribe();
  }

  @Test
  @DisplayName("null supplier yields no broadcast — graceful fallback")
  void nullSupplierGracefulFallback() {
    TimeseriesSnapshotHolder holder = new TimeseriesSnapshotHolder();
    DocumentsIndexedRateMetricChangeRegistry registry =
        new DocumentsIndexedRateMetricChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    DocumentsIndexedRateMetricProducer producer =
        new DocumentsIndexedRateMetricProducer(processExecutors, () -> null, holder, registry, FIXED_CLOCK);
    producer.tick();

    assertNull(holder.current());
    assertTrue(seen.isEmpty());
    sub.unsubscribe();
  }
}
