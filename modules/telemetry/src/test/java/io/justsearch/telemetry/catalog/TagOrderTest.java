package io.justsearch.telemetry.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.LocalTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 417 F1 fix: per-metric tag-key emission order in NDJSON. Verifies that two metrics
 * with different declared key orders both emit their tags in their respective declared order
 * (not in the order of the global allowlist).
 */
class TagOrderTest {

  @TempDir Path tmp;

  /** Tag schema with two keys in alpha-then-zeta order. */
  record AlphaThenZetaTags(String alpha, String zeta) implements TagSchema {
    static final Set<String> KEYS;

    static {
      Set<String> ks = new LinkedHashSet<>();
      ks.add("alpha");
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
          AttributeKey.stringKey("zeta"), zeta);
    }
  }

  /** Tag schema with the same two keys but reversed declaration order: zeta first. */
  record ZetaThenAlphaTags(String zeta, String alpha) implements TagSchema {
    static final Set<String> KEYS;

    static {
      Set<String> ks = new LinkedHashSet<>();
      ks.add("zeta");
      ks.add("alpha");
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
          AttributeKey.stringKey("zeta"), zeta);
    }
  }

  /** Catalog with two histograms whose tag-key declaration orders differ. */
  static final class TwoOrderingsCatalog implements MetricCatalog {
    static final MetricDefinition AZ =
        MetricDefinition.histogram("test.order.alpha_then_zeta_ms")
            .unit(Unit.MILLISECONDS)
            .tagKeys(AlphaThenZetaTags.KEYS)
            .buckets(List.of(10L, 100L))
            .build();

    static final MetricDefinition ZA =
        MetricDefinition.histogram("test.order.zeta_then_alpha_ms")
            .unit(Unit.MILLISECONDS)
            .tagKeys(ZetaThenAlphaTags.KEYS)
            .buckets(List.of(10L, 100L))
            .build();

    @Override
    public String namespace() {
      return "test.order";
    }

    @Override
    public List<MetricDefinition> definitions() {
      return List.of(AZ, ZA);
    }
  }

  @Test
  void perMetricTagKeyOrderReachesNdjson() throws Exception {
    TwoOrderingsCatalog catalog = new TwoOrderingsCatalog();
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), tmp, 500, "test", "0", "metrics.ndjson", List.of(catalog))) {

      HistogramMetric<AlphaThenZetaTags> az =
          telemetry.registry().buildHistogram("test.order.alpha_then_zeta_ms");
      HistogramMetric<ZetaThenAlphaTags> za =
          telemetry.registry().buildHistogram("test.order.zeta_then_alpha_ms");

      az.record(50L, new AlphaThenZetaTags("A", "Z"));
      za.record(50L, new ZetaThenAlphaTags("Z", "A"));

      telemetry.flush();
    }

    String ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));

    // alpha_then_zeta: tag emission order alpha first, zeta second
    String azLine = lineForMetric(ndjson, "test.order.alpha_then_zeta_ms");
    int azAlphaIdx = azLine.indexOf("\"alpha\":");
    int azZetaIdx = azLine.indexOf("\"zeta\":");
    assertTrue(azAlphaIdx > 0 && azZetaIdx > 0, "both keys present in az line: " + azLine);
    assertTrue(
        azAlphaIdx < azZetaIdx,
        "alpha must precede zeta for alpha_then_zeta declaration; got: " + azLine);

    // zeta_then_alpha: tag emission order zeta first, alpha second
    String zaLine = lineForMetric(ndjson, "test.order.zeta_then_alpha_ms");
    int zaAlphaIdx = zaLine.indexOf("\"alpha\":");
    int zaZetaIdx = zaLine.indexOf("\"zeta\":");
    assertTrue(zaAlphaIdx > 0 && zaZetaIdx > 0, "both keys present in za line: " + zaLine);
    assertTrue(
        zaZetaIdx < zaAlphaIdx,
        "zeta must precede alpha for zeta_then_alpha declaration; got: " + zaLine);
  }

  private static String lineForMetric(String ndjson, String metricName) {
    for (String line : ndjson.split("\n")) {
      if (line.contains("\"name\":\"" + metricName + "\"")) {
        return line;
      }
    }
    throw new AssertionError("No NDJSON line for metric " + metricName + "; got: " + ndjson);
  }
}
