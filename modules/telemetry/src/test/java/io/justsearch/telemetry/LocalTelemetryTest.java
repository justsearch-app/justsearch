package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.Unit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke for the catalog-driven NDJSON wire path. Tempdoc 417 Phase 3e: the historic
 * {@code t.timer/counter} legacy entrypoints are gone — every assertion now flows through the
 * catalog registry's {@code buildHistogram(...).record(...)} / {@code buildCounter(...).increment(...)}.
 */
class LocalTelemetryTest {

  private static MetricCatalog catalog() {
    return MetricCatalog.of(
        "pipeline",
        List.of(
            MetricDefinition.histogram("pipeline.stage_ms")
                .unit(Unit.MILLISECONDS)
                .buckets(List.of(10L, 20L))
                .build(),
            MetricDefinition.counter("pipeline.skipped").unit(Unit.COUNT).build(),
            MetricDefinition.observableCounter("pipeline.observed").unit(Unit.COUNT).build()));
  }

  @Test
  void writesMetricsNdjson() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-test");
    try (var t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "test", "0", "metrics.ndjson", List.of(catalog()))) {
      t.registry().<EmptyTags>buildHistogram("pipeline.stage_ms").record(15, EmptyTags.INSTANCE);
      t.flush();
    }
    Path file = tmp.resolve("telemetry").resolve("metrics.ndjson");
    assertTrue(Files.exists(file));
    String content = Files.readString(file);
    assertTrue(content.contains("\"name\":\"pipeline.stage_ms\""));
  }

  @Test
  void flushesOnShutdownWithoutWaitingForPeriod() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-flush");
    Path file = tmp.resolve("telemetry").resolve("metrics.ndjson");
    try (var t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 60_000, "test", "0", "metrics.ndjson", List.of(catalog()))) {
      // long period — verify shutdown forces flush.
      t.registry().<EmptyTags>buildCounter("pipeline.skipped").increment(EmptyTags.INSTANCE);
    }
    assertTrue(Files.exists(file));
    String content = Files.readString(file);
    assertTrue(content.contains("\"name\":\"pipeline.skipped\""));
  }

  /**
   * Regression pin for observations.md L84: ObservableCounter callbacks must fire during the
   * close-time final flush. Before the fix, {@code close()} unregistered gauge handles before
   * calling {@code forceFlush()}, so the final cumulative value of every ObservableCounter
   * (e.g. {@code worker.documents.indexed.total}) never reached NDJSON on graceful shutdown.
   */
  @Test
  void observableCounterEmitsFinalValueOnShutdown() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-observable-shutdown");
    Path file = tmp.resolve("telemetry").resolve("metrics.ndjson");
    AtomicLong supplied = new AtomicLong(42L);
    try (var t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 60_000, "test", "0", "metrics.ndjson", List.of(catalog()))) {
      // Long period — the value must reach NDJSON via close-time forceFlush, not periodic flush.
      t.registry()
          .<EmptyTags>buildObservableCounter("pipeline.observed", EmptyTags.INSTANCE, supplied::get);
    }
    assertTrue(Files.exists(file), "metrics file should be written");
    String content = Files.readString(file);
    assertTrue(
        content.contains("\"name\":\"pipeline.observed\""),
        "ObservableCounter must emit its final value on graceful shutdown; got:\n" + content);
    assertTrue(
        content.contains("\"value\":42"),
        "ObservableCounter final value must be the supplier's last reading; got:\n" + content);
  }
}
