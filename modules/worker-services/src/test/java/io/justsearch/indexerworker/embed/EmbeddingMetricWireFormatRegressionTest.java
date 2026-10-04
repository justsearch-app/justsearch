package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.embed.EmbeddingTags.InvokeFailureTags;
import io.justsearch.indexerworker.embed.EmbeddingTags.UnloadTags;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.InvokeFailureReason;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.Operation;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.MetricCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 413 (F3 precedent): wire-format regression test for
 * {@link EmbeddingMetricCatalog}. Verifies tag values reach NDJSON with their typed enum names.
 */
final class EmbeddingMetricWireFormatRegressionTest {

  @TempDir Path tmp;

  @Test
  void wireFormatStructuralEquivalence() throws Exception {
    String ndjson;
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            500,
            "test",
            "0",
            "metrics.ndjson",
            List.of(
                MetricCatalog.of(
                    EmbeddingMetricCatalog.NAMESPACE, EmbeddingMetricCatalog.DEFINITIONS)))) {
      var catalog = new EmbeddingMetricCatalog(telemetry.registry(), () -> 42L);

      catalog.cacheHitTotal.increment(EmptyTags.INSTANCE);
      catalog.cacheMissTotal.increment(EmptyTags.INSTANCE);
      catalog.chunkCount.record(8, EmptyTags.INSTANCE);
      catalog.invokeFailureTotal.increment(
          new InvokeFailureTags(Operation.BATCH, InvokeFailureReason.BACKEND_EXCEPTION));
      catalog.unloadTotal.increment(new UnloadTags(UnloadReason.GPU_HANDOFF));
      // Tempdoc 413 followup: SHUTDOWN tag pinned alongside GPU_HANDOFF. Production emits this
      // from KnowledgeServer.close() followed by an explicit LocalTelemetry.flush() (the same
      // flush() this test invokes) — pinning both names catches a UnloadReason enum rename or
      // tag-attribute mis-encoding either way.
      catalog.unloadTotal.increment(new UnloadTags(UnloadReason.SHUTDOWN));

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    }

    assertTrue(
        anyLineWithName(ndjson, EmbeddingMetricCatalog.CACHE_HIT_TOTAL).size() >= 1,
        "cache_hit_total missing; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, EmbeddingMetricCatalog.CACHE_MISS_TOTAL).size() >= 1,
        "cache_miss_total missing; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, EmbeddingMetricCatalog.CHUNK_COUNT).size() >= 1,
        "chunk_count histogram missing; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, EmbeddingMetricCatalog.INVOKE_FAILURE_TOTAL).stream()
            .anyMatch(
                l ->
                    l.contains("\"operation\":\"BATCH\"")
                        && l.contains("\"reason\":\"BACKEND_EXCEPTION\"")),
        "invoke_failure_total missing operation=BATCH reason=BACKEND_EXCEPTION; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, EmbeddingMetricCatalog.UNLOAD_TOTAL).stream()
            .anyMatch(l -> l.contains("\"reason\":\"GPU_HANDOFF\"")),
        "unload_total missing reason=GPU_HANDOFF; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, EmbeddingMetricCatalog.UNLOAD_TOTAL).stream()
            .anyMatch(l -> l.contains("\"reason\":\"SHUTDOWN\"")),
        "unload_total missing reason=SHUTDOWN; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, EmbeddingMetricCatalog.CACHE_SIZE).size() >= 1,
        "cache_size gauge missing; got: " + ndjson);
  }

  private static List<String> anyLineWithName(String ndjson, String name) {
    List<String> out = new ArrayList<>();
    for (String line : ndjson.split("\n")) {
      if (line.contains("\"name\":\"" + name + "\"")) {
        out.add(line);
      }
    }
    return out;
  }
}
