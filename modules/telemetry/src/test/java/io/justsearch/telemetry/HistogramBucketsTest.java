package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.Unit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that catalog-declared bucket bounds reach the NDJSON wire format. Tempdoc 417 Phase
 * 2f: this test exercised hardcoded {@code pipeline.stage_ms} View bucket bounds pre-refactor;
 * the bounds now live on {@code MetricDefinition.bucketBoundaries()} and the View is registered
 * automatically by the catalog loop in {@code LocalTelemetry}. Phase 3d: rewritten to use the
 * catalog registry's {@code buildHistogram(...).record(...)} entrypoint after deletion of the
 * legacy {@code Telemetry.histogram(...)} default.
 */
class HistogramBucketsTest {
  @Test
  void catalogDeclaredBucketsReachWireFormat() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-buckets");
    var def =
        MetricDefinition.histogram("test.buckets.latency_ms")
            .unit(Unit.MILLISECONDS)
            .buckets(List.of(5L, 10L, 20L, 50L, 100L, 200L, 400L, 800L, 1_500L, 3_000L))
            .build();
    var catalog = MetricCatalog.of("test.buckets", List.of(def));
    try (var t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "test", "0", "metrics.ndjson", List.of(catalog))) {
      var hist = t.registry().<EmptyTags>buildHistogram("test.buckets.latency_ms");
      hist.record(12, EmptyTags.INSTANCE);
      t.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    assertTrue(
        content.contains("\"bounds\":[5,10,20,50,100,200,400,800,1500,3000]"),
        "expected catalog-declared bucket bounds in wire format; got: " + content);
  }
}
