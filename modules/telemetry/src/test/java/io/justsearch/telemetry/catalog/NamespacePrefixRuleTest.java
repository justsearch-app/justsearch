package io.justsearch.telemetry.catalog;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.telemetry.LocalTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 417 Phase 0: structural test verifying that {@link LocalTelemetry}'s constructor
 * rejects {@link MetricCatalog} instances whose metric names violate the catalog's declared
 * namespace prefix. This is the runtime-side complement to the ArchUnit
 * {@code AttributesUseRuleTest} — it catches drift in catalog declarations rather than emit
 * callsites.
 */
class NamespacePrefixRuleTest {

  @Test
  void mismatchedMetricNameRejectedAtConstruction() throws Exception {
    Path tmp = Files.createTempDirectory("ns-prefix-rule");
    MetricCatalog bad =
        new MetricCatalog() {
          @Override
          public String namespace() {
            return "scope.runtime";
          }

          @Override
          public List<MetricDefinition> definitions() {
            return List.of(
                MetricDefinition.counter("scope.runtime.ok").build(),
                // Metric name does not start with namespace prefix — must throw.
                MetricDefinition.counter("other.bad").build());
          }
        };

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "t", "0", "metrics.ndjson", List.of(bad)).close(),
            "expected namespace-mismatch detection");
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("does not match catalog namespace"),
        "diagnostic should mention namespace mismatch; got: " + ex.getMessage());
  }

  @Test
  void compliantCatalogConstructsCleanly() throws Exception {
    Path tmp = Files.createTempDirectory("ns-prefix-ok");
    MetricCatalog good =
        new MetricCatalog() {
          @Override
          public String namespace() {
            return "scope.runtime";
          }

          @Override
          public List<MetricDefinition> definitions() {
            return List.of(
                MetricDefinition.counter("scope.runtime.ops_total").build(),
                MetricDefinition.histogram("scope.runtime.latency_ms")
                    .buckets(List.of(10L, 100L, 1_000L))
                    .build());
          }
        };

    try (LocalTelemetry t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "t", "0", "metrics.ndjson", List.of(good))) {
      org.junit.jupiter.api.Assertions.assertNotNull(t.registry());
    }
  }
}
