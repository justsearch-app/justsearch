package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.RrdArchive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 417 Phase 3d smoke test for {@link JvmMetricCatalog}.
 *
 * <p>Verifies (1) every legacy {@code <prefix>.jvm.*} metric name is declared by the catalog at
 * the requested prefix, (2) curated baseline metrics ({@code <prefix>.jvm.threads.live} and
 * {@code <prefix>.jvm.memory.heap.used_bytes}) declare {@code archivedTo(STANDARD)}, and (3)
 * constructing the catalog against a real {@code LocalTelemetry} registry wires async-gauge
 * suppliers without throwing.
 */
final class JvmMetricCatalogSmokeTest {

  private static final List<String> EXPECTED_SHORT_NAMES =
      List.of(
          "jvm.threads.live",
          "jvm.threads.daemon",
          "jvm.memory.heap.used_bytes",
          "jvm.memory.heap.committed_bytes",
          "jvm.memory.heap.max_bytes",
          "jvm.memory.nonheap.used_bytes",
          "jvm.memory.nonheap.committed_bytes",
          "jvm.memory.process.virtual_bytes",
          "jvm.gc.collection_count",
          "jvm.gc.collection_time_ms");

  @Test
  void definitionsForPrefixDeclaresAllExpectedMetrics() {
    List<MetricDefinition> defs = JvmMetricCatalog.definitionsFor("head");
    Set<String> names = defs.stream().map(MetricDefinition::name).collect(Collectors.toSet());
    assertEquals(EXPECTED_SHORT_NAMES.size(), defs.size(), "catalog size mismatch");
    for (String shortName : EXPECTED_SHORT_NAMES) {
      assertTrue(names.contains("head." + shortName), "missing: head." + shortName);
    }
  }

  @Test
  void curatedBaselineMetricsDeclareArchivedToStandard() {
    List<MetricDefinition> defs = JvmMetricCatalog.definitionsFor("worker");
    MetricDefinition threadsLive =
        defs.stream()
            .filter(d -> d.name().equals("worker.jvm.threads.live"))
            .findFirst()
            .orElseThrow();
    MetricDefinition heapUsed =
        defs.stream()
            .filter(d -> d.name().equals("worker.jvm.memory.heap.used_bytes"))
            .findFirst()
            .orElseThrow();
    MetricDefinition heapMax =
        defs.stream()
            .filter(d -> d.name().equals("worker.jvm.memory.heap.max_bytes"))
            .findFirst()
            .orElseThrow();
    assertEquals(RrdArchive.STANDARD, threadsLive.rrdArchive());
    assertEquals(RrdArchive.STANDARD, heapUsed.rrdArchive());
    // Tempdoc 430 Phase 8 (rev 3.11 §B.X.1): heapMax is archived so the memory.pressure
    // rule's CEL expression `signals['<prefix>.jvm.memory.heap.max_bytes'].latest()` resolves
    // via RRD. Without this, the rule cannot evaluate.
    assertEquals(RrdArchive.STANDARD, heapMax.rrdArchive());
    // Other JVM metrics must NOT declare an archive.
    long archived = defs.stream().filter(d -> d.rrdArchive() != null).count();
    assertEquals(3L, archived, "expected exactly 3 archived JVM metrics");
  }

  @Test
  void constructingAgainstLocalTelemetryWiresGauges() throws Exception {
    Path tmp = Files.createTempDirectory("jvm-catalog-smoke");
    try (LocalTelemetry tel =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp, 500, "test-jvm", "0", "metrics.ndjson", List.of(JvmMetricCatalog.catalogFor("head")))) {
      JvmMetricCatalog cat = new JvmMetricCatalog(tel.registry(), "head");
      assertNotNull(cat.threadsLive);
      assertNotNull(cat.heapUsedBytes);
      assertNotNull(cat.gcCollectionCount);
      tel.flush();
    }
    Path file = tmp.resolve("telemetry").resolve("metrics.ndjson");
    String content = Files.readString(file);
    assertTrue(
        content.contains("\"name\":\"head.jvm.threads.live\""),
        "expected head.jvm.threads.live in wire format; got: " + content);
  }

  @Test
  void emptyOrBlankPrefixIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> JvmMetricCatalog.definitionsFor(""));
    assertThrows(IllegalArgumentException.class, () -> JvmMetricCatalog.definitionsFor("   "));
    assertThrows(IllegalArgumentException.class, () -> JvmMetricCatalog.definitionsFor(null));
  }
}
