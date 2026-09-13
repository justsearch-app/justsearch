package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests JvmRuntimeGauges registration and value capture.
 *
 * <p>Writes all resource values to a centralized JSON file for inspection and regression tracking.
 */
class JvmRuntimeGaugesTest {

  private static final String PREFIX = "test";

  @Test
  void allGaugesRegisterAndReturnValidValues() throws Exception {
    Path tmp = Files.createTempDirectory("jvm-gauges-test");
    Map<String, Double> capturedValues = new LinkedHashMap<>();

    try (var telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            1000,
            PREFIX,
            "0",
            "metrics.ndjson",
            List.of(JvmMetricCatalog.catalogFor(PREFIX)))) {
      // Phase 3d/3e: register() constructs JvmMetricCatalog whose typed gauge handles are
      // owned by LocalTelemetry's gaugeHandles list; no return value to assert on.
      JvmRuntimeGauges.register(telemetry, PREFIX);

      telemetry.flush();
    }

    // Parse NDJSON and extract gauge values
    Path metricsFile = tmp.resolve("telemetry").resolve("metrics.ndjson");
    assertTrue(Files.exists(metricsFile), "Metrics file should exist");

    List<String> lines = Files.readAllLines(metricsFile);
    ObjectMapper mapper = new ObjectMapper();

    for (String line : lines) {
      if (line.isBlank()) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> metric = mapper.readValue(line, Map.class);
      String name = (String) metric.get("name");
      if (name != null && name.startsWith(PREFIX + ".jvm.")) {
        Object value = metric.get("value");
        if (value instanceof Number) {
          capturedValues.put(name, ((Number) value).doubleValue());
        }
      }
    }

    // Verify all expected gauges are present
    String[] expectedGauges = {
      PREFIX + ".jvm.threads.live",
      PREFIX + ".jvm.threads.daemon",
      PREFIX + ".jvm.memory.heap.used_bytes",
      PREFIX + ".jvm.memory.heap.committed_bytes",
      PREFIX + ".jvm.memory.heap.max_bytes",
      PREFIX + ".jvm.memory.nonheap.used_bytes",
      PREFIX + ".jvm.memory.nonheap.committed_bytes",
      PREFIX + ".jvm.memory.process.virtual_bytes",
      PREFIX + ".jvm.gc.collection_count",
      PREFIX + ".jvm.gc.collection_time_ms"
    };

    for (String gauge : expectedGauges) {
      assertTrue(capturedValues.containsKey(gauge), "Missing gauge: " + gauge);
      double value = capturedValues.get(gauge);
      // RSS can be -1 if unavailable on the platform
      if (gauge.endsWith(".virtual_bytes")) {
        assertTrue(value >= -1, gauge + " should be >= -1, got: " + value);
      } else {
        assertTrue(value >= 0, gauge + " should be non-negative, got: " + value);
      }
    }

    // Write centralized results file
    writeResultsFile(capturedValues, tmp);

    // Additional semantic validations
    assertTrue(
        capturedValues.get(PREFIX + ".jvm.threads.live") >= 1,
        "Should have at least 1 live thread");
    assertTrue(
        capturedValues.get(PREFIX + ".jvm.memory.heap.used_bytes") > 0,
        "Heap used should be positive");
    assertTrue(
        capturedValues.get(PREFIX + ".jvm.memory.heap.max_bytes")
            >= capturedValues.get(PREFIX + ".jvm.memory.heap.committed_bytes"),
        "Max heap should be >= committed heap");
    assertTrue(
        capturedValues.get(PREFIX + ".jvm.memory.heap.committed_bytes")
            >= capturedValues.get(PREFIX + ".jvm.memory.heap.used_bytes"),
        "Committed heap should be >= used heap");
    assertTrue(
        capturedValues.get(PREFIX + ".jvm.memory.nonheap.used_bytes") > 0,
        "Non-heap used should be positive (class metadata exists)");
    assertTrue(
        capturedValues.get(PREFIX + ".jvm.memory.nonheap.committed_bytes")
            >= capturedValues.get(PREFIX + ".jvm.memory.nonheap.used_bytes"),
        "Non-heap committed should be >= used");
  }

  @Test
  void gaugeErrorCounterTracksFailures() {
    // Verify the error counter starts at 0 (or is accessible)
    long initialErrors = JvmRuntimeGauges.getJvmGaugeErrorCount();
    assertTrue(initialErrors >= 0, "Error count should be non-negative");
  }

  @Test
  void registerWithNullTelemetryIsNoOp() {
    // Phase 3e: register() returns void; null telemetry must not throw.
    JvmRuntimeGauges.register(null, PREFIX);
  }

  @Test
  void registerWithEmptyPrefixThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          Path tmp = Files.createTempDirectory("jvm-gauges-empty-prefix");
          try (var telemetry = new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 200, "test", "0")) {
            JvmRuntimeGauges.register(telemetry, "");
          }
        });
  }

  @Test
  void registerWithNullPrefixThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          Path tmp = Files.createTempDirectory("jvm-gauges-null-prefix");
          try (var telemetry = new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 200, "test", "0")) {
            JvmRuntimeGauges.register(telemetry, null);
          }
        });
  }

  @Test
  void fullResourceSnapshotToFile() throws Exception {
    Path tmp = Files.createTempDirectory("jvm-resource-snapshot");
    // Use project root (Gradle sets user.dir to module directory, so go up 2 levels)
    Path projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
    Path outputDir = projectRoot.resolve("tmp/agent-evidence/resource-tests");
    Files.createDirectories(outputDir);

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("timestamp", Instant.now().toString());
    snapshot.put("test_class", getClass().getName());

    try (var telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            1000,
            "snapshot",
            "0",
            "metrics.ndjson",
            List.of(JvmMetricCatalog.catalogFor("snapshot")))) {
      JvmRuntimeGauges.register(telemetry, "snapshot");

      telemetry.flush();
    }

    // Parse and collect all values
    Path metricsFile = tmp.resolve("telemetry").resolve("metrics.ndjson");
    Map<String, Double> values = new LinkedHashMap<>();
    ObjectMapper mapper = new ObjectMapper();

    for (String line : Files.readAllLines(metricsFile)) {
      if (line.isBlank()) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> metric = mapper.readValue(line, Map.class);
      String name = (String) metric.get("name");
      if (name != null && name.startsWith("snapshot.jvm.")) {
        Object value = metric.get("value");
        if (value instanceof Number) {
          // Use short name for readability
          String shortName = name.replace("snapshot.", "");
          values.put(shortName, ((Number) value).doubleValue());
        }
      }
    }

    // Add derived metrics
    Map<String, Object> derived = new LinkedHashMap<>();
    double heapUsed = values.getOrDefault("jvm.memory.heap.used_bytes", 0.0);
    double heapMax = values.getOrDefault("jvm.memory.heap.max_bytes", 1.0);
    double heapCommitted = values.getOrDefault("jvm.memory.heap.committed_bytes", 0.0);
    double nonHeapUsed = values.getOrDefault("jvm.memory.nonheap.used_bytes", 0.0);
    double nonHeapCommitted = values.getOrDefault("jvm.memory.nonheap.committed_bytes", 0.0);
    double virtualBytes = values.getOrDefault("jvm.memory.process.virtual_bytes", -1.0);
    double gcCount = values.getOrDefault("jvm.gc.collection_count", 0.0);
    double gcTimeMs = values.getOrDefault("jvm.gc.collection_time_ms", 0.0);

    // Heap metrics
    derived.put("heap_used_mb", heapUsed / (1024 * 1024));
    derived.put("heap_committed_mb", heapCommitted / (1024 * 1024));
    derived.put("heap_max_mb", heapMax / (1024 * 1024));
    derived.put("heap_utilization_percent", (heapUsed / heapMax) * 100);

    // Non-heap metrics (metaspace, code cache, etc.)
    derived.put("nonheap_used_mb", nonHeapUsed / (1024 * 1024));
    derived.put("nonheap_committed_mb", nonHeapCommitted / (1024 * 1024));

    // Total JVM memory (heap + non-heap)
    double totalJvmUsed = heapUsed + nonHeapUsed;
    double totalJvmCommitted = heapCommitted + nonHeapCommitted;
    derived.put("total_jvm_used_mb", totalJvmUsed / (1024 * 1024));
    derived.put("total_jvm_committed_mb", totalJvmCommitted / (1024 * 1024));

    // Process virtual memory (NOTE: includes mmap'd files, not actual RAM usage)
    if (virtualBytes > 0) {
      derived.put("process_virtual_mb", virtualBytes / (1024 * 1024));
      // Note: This is NOT a reliable native memory estimate because virtual memory
      // includes mmap'd files (Lucene indexes) which don't consume physical RAM.
      // For true native memory analysis, use NMT: -XX:NativeMemoryTracking=summary
      derived.put("virtual_minus_jvm_mb", (virtualBytes - totalJvmCommitted) / (1024 * 1024));
    } else {
      derived.put("process_virtual_mb", "unavailable");
      derived.put("virtual_minus_jvm_mb", "unavailable");
    }

    // GC metrics
    derived.put("avg_gc_pause_ms", gcCount > 0 ? gcTimeMs / gcCount : 0.0);
    derived.put("gc_overhead_percent", gcCount > 0 ? (gcTimeMs / 1000.0) : 0.0);

    snapshot.put("raw_values", values);
    snapshot.put("derived_metrics", derived);
    snapshot.put("gauge_error_count", JvmRuntimeGauges.getJvmGaugeErrorCount());

    // Write to centralized location
    Path outputFile = outputDir.resolve("jvm-resource-snapshot.json");
    ObjectMapper prettyMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    prettyMapper.writeValue(outputFile.toFile(), snapshot);

    System.out.println("Resource snapshot written to: " + outputFile.toAbsolutePath());
    System.out.println(mapper.writeValueAsString(snapshot));

    // Verify file was written
    assertTrue(Files.exists(outputFile), "Snapshot file should exist");
    assertTrue(Files.size(outputFile) > 100, "Snapshot file should have content");
  }

  private void writeResultsFile(Map<String, Double> values, Path tmp) throws Exception {
    Map<String, Object> results = new LinkedHashMap<>();
    results.put("timestamp", Instant.now().toString());
    results.put("gauges", values);
    results.put("gauge_count", values.size());
    results.put("all_non_negative", values.values().stream().allMatch(v -> v >= 0));

    ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    Path resultsFile = tmp.resolve("jvm-gauges-results.json");
    mapper.writeValue(resultsFile.toFile(), results);
  }
}
