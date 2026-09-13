package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.TagSchema;
import io.justsearch.telemetry.catalog.Unit;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Wire-format integration tests for the NDJSON exporter. Tempdoc 417 Phase 2f: rewritten to use
 * catalog-declared metric definitions instead of the deleted hardcoded {@code ALLOWED_TAG_KEYS}
 * fallback. Per-View {@code setAttributeFilter} now strips non-allowed keys at the SDK layer.
 */
class LocalMetricsExporterTest {

  private static MetricCatalog pipelineCatalog() {
    Set<String> stageKeys = new LinkedHashSet<>(List.of("pipeline_name", "stage_id"));
    Set<String> skippedKeys =
        new LinkedHashSet<>(List.of("pipeline_name", "stage_id", "reason_code"));
    return MetricCatalog.of(
        "pipeline",
        List.of(
            MetricDefinition.histogram("pipeline.stage_ms")
                .unit(Unit.MILLISECONDS)
                .tagKeys(stageKeys)
                .buckets(List.of(10L, 20L, 50L))
                .build(),
            MetricDefinition.counter("pipeline.skipped")
                .unit(Unit.COUNT)
                .tagKeys(skippedKeys)
                .build()));
  }

  private static MetricCatalog apiCatalog() {
    Set<String> apiKeys =
        new LinkedHashSet<>(
            List.of("route", "http_method", "http_status", "http_status_class"));
    return MetricCatalog.of(
        "api",
        List.of(
            MetricDefinition.histogram("api.request_ms")
                .unit(Unit.MILLISECONDS)
                .tagKeys(apiKeys)
                .buckets(List.of(10L, 20L, 50L))
                .build()));
  }

  private static final Set<String> STAGE_KEYS_LH =
      new LinkedHashSet<>(List.of("pipeline_name", "stage_id"));
  private static final Set<String> SKIPPED_KEYS_LH =
      new LinkedHashSet<>(List.of("pipeline_name", "stage_id", "reason_code"));

  @Test
  void writesRequiredSeriesWithTags() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-test");
    try (var t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "test", "0", "metrics.ndjson", List.of(pipelineCatalog()))) {
      t.registry()
          .<MapTags>buildHistogram("pipeline.stage_ms")
          .record(
              5,
              new MapTags(
                  Map.of("pipeline_name", "search_default", "stage_id", "rerank"),
                  STAGE_KEYS_LH));
      t.registry()
          .<MapTags>buildCounter("pipeline.skipped")
          .increment(
              new MapTags(
                  Map.of(
                      "pipeline_name", "search_default",
                      "stage_id", "rerank",
                      "reason_code", "rerank_skipped_deadline"),
                  SKIPPED_KEYS_LH));
      t.flush();
    }
    Path file = tmp.resolve("telemetry").resolve("metrics.ndjson");
    String content = Files.readString(file);
    assertTrue(content.contains("\"name\":\"pipeline.stage_ms\""));
    assertTrue(content.contains("\"type\":\"histogram\""));
    assertTrue(content.contains("\"buckets\""));
    assertTrue(content.contains("\"pipeline_name\":\"search_default\""));
    assertTrue(content.contains("\"stage_id\":\"rerank\""));
    assertTrue(content.contains("\"name\":\"pipeline.skipped\""));
    assertTrue(content.contains("\"reason_code\":\"rerank_skipped_deadline\""));
  }

  @Test
  void writesExemplarsWhenEnabledViaSystemProperty() throws Exception {
    System.setProperty("justsearch.telemetry.metrics.exemplars", "true");
    try {
      Path tmp = Files.createTempDirectory("telemetry-exemplars");
      try (var t =
          new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
              tmp, 500, "test", "0", "metrics.ndjson", List.of(pipelineCatalog()))) {
        var hist = t.registry().<MapTags>buildHistogram("pipeline.stage_ms");
        hist.record(
            5,
            new MapTags(
                Map.of("pipeline_name", "search_default", "stage_id", "rerank"), STAGE_KEYS_LH));
        hist.record(
            8,
            new MapTags(
                Map.of("pipeline_name", "search_default", "stage_id", "rerank"), STAGE_KEYS_LH));
        t.flush();
      }
      String content = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
      assertTrue(content.contains("\"exemplars\":"));
    } finally {
      System.clearProperty("justsearch.telemetry.metrics.exemplars");
    }
  }

  /** Inline {@link TagSchema} for tests — wraps a literal map. */
  private static final class MapTags implements TagSchema {
    private final Map<String, String> values;
    private final Set<String> allowed;

    MapTags(Map<String, String> values, Set<String> allowed) {
      this.values = new LinkedHashMap<>(values);
      this.allowed = allowed;
    }

    @Override
    public Set<String> allowedKeys() {
      return allowed;
    }

    @Override
    public Attributes toAttributes() {
      AttributesBuilder b = Attributes.builder();
      values.forEach((k, v) -> b.put(AttributeKey.stringKey(k), v));
      return b.build();
    }
  }

  @Test
  void writesApiMetricTagsWhenAllowlisted() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-api");
    Set<String> allowed =
        new LinkedHashSet<>(List.of("route", "http_method", "http_status", "http_status_class"));
    try (var t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 200, "test", "0", "metrics.ndjson", List.of(apiCatalog()))) {
      var hist = t.registry().<MapTags>buildHistogram("api.request_ms");
      hist.record(
          12,
          new MapTags(
              Map.of(
                  "route", "/api/knowledge/search",
                  "http_method", "POST",
                  "http_status", "200",
                  "http_status_class", "2xx"),
              allowed));
      t.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    assertTrue(content.contains("\"name\":\"api.request_ms\""));
    assertTrue(content.contains("\"route\":\"/api/knowledge/search\""));
    assertTrue(content.contains("\"http_method\":\"POST\""));
    assertTrue(content.contains("\"http_status_class\":\"2xx\""));
  }

  @Test
  void doesNotWriteNonAllowlistedTagKeys() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-api-tags");
    Set<String> allowed =
        new LinkedHashSet<>(List.of("route", "http_method", "http_status", "http_status_class"));
    try (var t =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 200, "test", "0", "metrics.ndjson", List.of(apiCatalog()))) {
      var hist = t.registry().<MapTags>buildHistogram("api.request_ms");
      hist.record(
          7,
          new MapTags(
              Map.of("route", "/api/preview", "debug_secret", "DO_NOT_EXPORT"), allowed));
      t.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    assertTrue(content.contains("\"route\":\"/api/preview\""));
    assertFalse(content.contains("debug_secret"));
    assertFalse(content.contains("DO_NOT_EXPORT"));
  }

  @Test
  void rotatesAndRetainsFiles() throws Exception {
    System.setProperty("justsearch.telemetry.metrics.max_mb", "1");
    System.setProperty("justsearch.telemetry.metrics.retention.days", "1");
    try {
      Path tmp = Files.createTempDirectory("telemetry-rotate");
      try (var t =
          new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
              tmp, 100, "test", "0", "metrics.ndjson", List.of(pipelineCatalog()))) {
        var counter = t.registry().<MapTags>buildCounter("pipeline.skipped");
        MapTags tags =
            new MapTags(
                Map.of("pipeline_name", "p", "stage_id", "s", "reason_code", "r"),
                SKIPPED_KEYS_LH);
        for (int i = 0; i < 200; i++) {
          counter.increment(tags);
        }
        t.flush();
      }
      Path dir = tmp.resolve("telemetry");
      assertTrue(Files.exists(dir));
      try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
        long ndjsonFiles =
            paths.filter(p -> p.getFileName().toString().endsWith(".ndjson")).count();
        assertTrue(ndjsonFiles >= 1);
      }
    } finally {
      System.clearProperty("justsearch.telemetry.metrics.max_mb");
      System.clearProperty("justsearch.telemetry.metrics.retention.days");
    }
  }
}
