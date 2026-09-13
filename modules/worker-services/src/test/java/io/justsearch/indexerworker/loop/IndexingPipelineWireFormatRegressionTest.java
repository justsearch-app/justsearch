package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ExtractionMetricCatalog;
import io.justsearch.indexerworker.extract.ExtractionSandboxRestartTags;
import io.justsearch.indexerworker.extract.ExtractionTimeoutTags;
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
 * Tempdoc 417 F3 wire-format regression test for {@link IndexingPipelineMetricCatalog} and
 * {@link ExtractionMetricCatalog}. Drives a fixed sequence of catalog-typed emissions through a
 * real {@link LocalTelemetry} and asserts NDJSON structural properties.
 */
final class IndexingPipelineWireFormatRegressionTest {

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
            "metrics-worker.ndjson",
            List.of(
                MetricCatalog.of(
                    IndexingPipelineMetricCatalog.NAMESPACE,
                    IndexingPipelineMetricCatalog.DEFINITIONS),
                MetricCatalog.of(
                    ExtractionMetricCatalog.NAMESPACE, ExtractionMetricCatalog.DEFINITIONS)))) {
      var pipelineCatalog = new IndexingPipelineMetricCatalog(telemetry.registry());
      var extractionCatalog = new ExtractionMetricCatalog(telemetry.registry());

      pipelineCatalog.stageMs.record(150L, PipelineStageTags.of("extract", null));
      pipelineCatalog.stageMs.record(80L, PipelineStageTags.of("post_commit", "buffer_full"));
      extractionCatalog.timeoutTotal.increment(ExtractionTimeoutTags.of());
      extractionCatalog.sandboxRestartTotal.increment(ExtractionSandboxRestartTags.of("timeout"));
      extractionCatalog.sandboxSpawnTotal.increment(EmptyTags.INSTANCE);

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-worker.ndjson"));
    }

    // Metric names + types reach wire.
    assertTrue(
        containsLine(ndjson, "pipeline.stage_ms", "\"type\":\"histogram\""),
        "pipeline.stage_ms missing; got: " + ndjson);
    assertTrue(
        containsLine(ndjson, "extraction.timeout_total", "\"type\":\"counter\""),
        "extraction.timeout_total missing; got: " + ndjson);

    // Bucket bounds: pipeline.stage_ms uses [10,20,50,100,200,400,800,1500,3000].
    assertTrue(
        anyLineWithName(ndjson, "pipeline.stage_ms").stream()
            .anyMatch(l -> l.contains("\"bounds\":[10,20,50,100,200,400,800,1500,3000]")),
        "pipeline.stage_ms missing bucket bounds; got: " + ndjson);

    // Tags reach wire format.
    assertTrue(
        anyLineWithName(ndjson, "pipeline.stage_ms").stream()
            .anyMatch(l -> l.contains("\"pipeline_name\":\"indexing.worker\"")),
        "pipeline.stage_ms missing pipeline_name; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "pipeline.stage_ms").stream()
            .anyMatch(l -> l.contains("\"stage_id\":\"extract\"")),
        "pipeline.stage_ms missing stage_id=extract; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "pipeline.stage_ms").stream()
            .anyMatch(l -> l.contains("\"reason_code\":\"buffer_full\"")),
        "pipeline.stage_ms missing reason_code=buffer_full; got: " + ndjson);

    // F2 verification: extraction.timeout_total carries component=content_extractor.
    assertTrue(
        anyLineWithName(ndjson, "extraction.timeout_total").stream()
            .anyMatch(l -> l.contains("\"component\":\"content_extractor\"")),
        "extraction.timeout_total missing component=content_extractor (F2); got: " + ndjson);

    // Tempdoc 885 item 14: the sandbox pool's observables.
    assertTrue(
        containsLine(ndjson, "extraction.sandbox_restart_total", "\"type\":\"counter\""),
        "extraction.sandbox_restart_total missing; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "extraction.sandbox_restart_total").stream()
            .anyMatch(l -> l.contains("\"reason\":\"timeout\"")),
        "extraction.sandbox_restart_total missing reason=timeout; got: " + ndjson);
    assertTrue(
        containsLine(ndjson, "extraction.sandbox_spawn_total", "\"type\":\"counter\""),
        "extraction.sandbox_spawn_total missing; got: " + ndjson);
  }

  private static boolean containsLine(String ndjson, String name, String fragment) {
    for (String line : ndjson.split("\n")) {
      if (line.contains("\"name\":\"" + name + "\"") && line.contains(fragment)) {
        return true;
      }
    }
    return false;
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
