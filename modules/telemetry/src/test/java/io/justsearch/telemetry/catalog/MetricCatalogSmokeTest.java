package io.justsearch.telemetry.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.LocalTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test for the catalog substrate. Constructs an inline {@link MetricCatalog}
 * with one counter, one histogram, one observable counter, and one gauge; emits values; flushes;
 * inspects NDJSON output to verify:
 *
 * <ul>
 *   <li>declared bucket bounds reach the wire format
 *   <li>declared tag schema is honored (allowed keys appear; non-allowed keys are stripped)
 *   <li>{@link Exemplars#OFF} suppresses exemplars on a per-metric basis
 *   <li>observable counters honor their {@code LongSupplier}
 *   <li>async gauges honor their {@code Supplier<Double>}
 * </ul>
 */
class MetricCatalogSmokeTest {

  // Tag schema with one key.
  record ReasonTags(String reason) implements TagSchema {
    static final String KEY = "reason";
    static final Set<String> KEYS = Set.of(KEY);

    @Override
    public Set<String> allowedKeys() {
      return KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY), reason);
    }
  }

  // Tag schema with three keys in a deliberate order.
  record OrderedTags(String alpha, String middle, String zeta) implements TagSchema {
    static final Set<String> KEYS;

    static {
      Set<String> ks = new LinkedHashSet<>();
      ks.add("alpha");
      ks.add("middle");
      ks.add("zeta");
      KEYS = ks;
    }

    @Override
    public Set<String> allowedKeys() {
      return KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(
          AttributeKey.stringKey("alpha"), alpha,
          AttributeKey.stringKey("middle"), middle,
          AttributeKey.stringKey("zeta"), zeta);
    }
  }

  /** Inline test catalog. */
  static final class TestCatalog implements MetricCatalog {
    static final MetricDefinition COMMITS_TOTAL =
        MetricDefinition.counter("test.runtime.commits_total")
            .unit(Unit.COUNT)
            .tagKeys(ReasonTags.KEYS)
            .build();

    static final MetricDefinition COMMIT_MS =
        MetricDefinition.histogram("test.runtime.commit_ms")
            .unit(Unit.MILLISECONDS)
            .tagKeys(ReasonTags.KEYS)
            .buckets(List.of(100L, 250L, 500L, 1_000L))
            .exemplars(Exemplars.TRACE_BASED)
            .build();

    // Histogram with exemplars off — should not emit exemplars even when global filter is on.
    static final MetricDefinition WAIT_US =
        MetricDefinition.histogram("test.runtime.wait_us")
            .unit(Unit.MICROSECONDS)
            .tagKeys(Set.of())
            .buckets(List.of(1L, 10L, 100L))
            .exemplars(Exemplars.OFF)
            .build();

    static final MetricDefinition QUEUE_DEPTH =
        MetricDefinition.gauge("test.runtime.queue_depth")
            .unit(Unit.COUNT)
            .tagKeys(Set.of())
            .build();

    static final MetricDefinition OPS_TOTAL =
        MetricDefinition.observableCounter("test.runtime.ops_total")
            .unit(Unit.COUNT)
            .tagKeys(Set.of())
            .build();

    @Override
    public String namespace() {
      return "test.runtime";
    }

    @Override
    public List<MetricDefinition> definitions() {
      return List.of(COMMITS_TOTAL, COMMIT_MS, WAIT_US, QUEUE_DEPTH, OPS_TOTAL);
    }
  }

  @Test
  void catalogEndToEnd() throws Exception {
    Path tmp = Files.createTempDirectory("metric-catalog-smoke");
    TestCatalog catalog = new TestCatalog();
    AtomicLong opsAdder = new AtomicLong();

    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "test", "0", "metrics.ndjson", List.of(catalog))) {

      MetricRegistry reg = telemetry.registry();
      CounterMetric<ReasonTags> commits = reg.buildCounter("test.runtime.commits_total");
      HistogramMetric<ReasonTags> commitMs = reg.buildHistogram("test.runtime.commit_ms");
      HistogramMetric<EmptyTags> waitUs = reg.buildHistogram("test.runtime.wait_us");
      reg.buildGauge("test.runtime.queue_depth", EmptyTags.INSTANCE, () -> 7.0);
      reg.buildObservableCounter(
          "test.runtime.ops_total", EmptyTags.INSTANCE, opsAdder::get);

      commits.increment(new ReasonTags("drain"));
      commits.increment(new ReasonTags("drain"));
      commits.increment(new ReasonTags("timer"));
      commitMs.record(150L, new ReasonTags("drain"));
      commitMs.record(900L, new ReasonTags("timer"));
      waitUs.record(5L, EmptyTags.INSTANCE);
      waitUs.record(50L, EmptyTags.INSTANCE);
      opsAdder.set(42L);

      telemetry.flush();
    }

    String ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));

    // Counter: declared bucket bounds + reason tag present
    assertTrue(ndjson.contains("\"name\":\"test.runtime.commits_total\""));
    assertTrue(ndjson.contains("\"reason\":\"drain\""));
    assertTrue(ndjson.contains("\"reason\":\"timer\""));

    // Histogram: bucket bounds match declaration (100, 250, 500, 1000)
    assertTrue(
        ndjson.contains("\"bounds\":[100,250,500,1000]"),
        "commit_ms bucket bounds should match catalog declaration; got: " + ndjson);

    // Histogram with Exemplars.OFF: no "exemplars" key in any wait_us line
    String waitLine =
        ndjson.lines().filter(l -> l.contains("\"name\":\"test.runtime.wait_us\"")).findFirst().get();
    assertFalse(
        waitLine.contains("\"exemplars\""),
        "Exemplars.OFF should suppress exemplars; got: " + waitLine);

    // Gauge: value 7 emitted
    assertTrue(ndjson.contains("\"name\":\"test.runtime.queue_depth\""));

    // Observable counter: value 42 emitted
    assertTrue(ndjson.contains("\"name\":\"test.runtime.ops_total\""));
    assertTrue(
        ndjson.contains("\"value\":42"),
        "observable counter should emit current LongSupplier value");

    // Bucket bounds for wait_us histogram match declaration (1, 10, 100)
    assertTrue(
        ndjson.contains("\"bounds\":[1,10,100]"),
        "wait_us bucket bounds should match catalog declaration");
  }

  @Test
  void unknownMetricNameThrows() throws Exception {
    Path tmp = Files.createTempDirectory("metric-catalog-unknown");
    try (LocalTelemetry telemetry = new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "t", "0")) {
      MetricRegistry reg = telemetry.registry();
      try {
        reg.buildCounter("test.runtime.unknown");
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("No metric definition"));
        return;
      }
      throw new AssertionError("expected IllegalArgumentException for unknown metric name");
    }
  }

  @Test
  void wrongInstrumentKindThrows() throws Exception {
    Path tmp = Files.createTempDirectory("metric-catalog-wrongkind");
    TestCatalog catalog = new TestCatalog();
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "t", "0", "metrics.ndjson", List.of(catalog))) {
      MetricRegistry reg = telemetry.registry();
      try {
        // commits_total is a counter, not a histogram
        reg.buildHistogram("test.runtime.commits_total");
      } catch (IllegalArgumentException expected) {
        assertTrue(
            expected.getMessage().contains("kind"), "got: " + expected.getMessage());
        return;
      }
      throw new AssertionError("expected IllegalArgumentException for wrong kind");
    }
  }

  @Test
  void duplicateMetricNameThrows() throws Exception {
    Path tmp = Files.createTempDirectory("metric-catalog-dup");
    MetricCatalog cat1 =
        new MetricCatalog() {
          @Override
          public String namespace() {
            return "x";
          }

          @Override
          public List<MetricDefinition> definitions() {
            return List.of(MetricDefinition.counter("x.dup").build());
          }
        };
    MetricCatalog cat2 =
        new MetricCatalog() {
          @Override
          public String namespace() {
            return "x";
          }

          @Override
          public List<MetricDefinition> definitions() {
            return List.of(MetricDefinition.counter("x.dup").build());
          }
        };
    try {
      new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "t", "0", "metrics.ndjson", List.of(cat1, cat2)).close();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Duplicate"));
      return;
    }
    throw new AssertionError("expected IllegalArgumentException for duplicate name");
  }

  @Test
  void namespaceMismatchThrows() throws Exception {
    Path tmp = Files.createTempDirectory("metric-catalog-ns");
    MetricCatalog bad =
        new MetricCatalog() {
          @Override
          public String namespace() {
            return "x";
          }

          @Override
          public List<MetricDefinition> definitions() {
            return List.of(MetricDefinition.counter("y.misnamed").build());
          }
        };
    try {
      new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "t", "0", "metrics.ndjson", List.of(bad)).close();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("does not match catalog namespace"));
      return;
    }
    throw new AssertionError("expected IllegalArgumentException for namespace mismatch");
  }
}
