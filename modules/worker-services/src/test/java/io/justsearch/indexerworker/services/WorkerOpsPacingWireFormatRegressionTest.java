/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire-format regression test for the three foreground-pacing instruments (tempdoc 885 item 3).
 *
 * <p>The duty cycle is only attributable in the field through these names: the pause it replaced
 * logged at TRACE/DEBUG under a package the Worker pins to INFO, so no field run could count one
 * (885 §B.2a). A rename or a type change would therefore break attribution silently — this test
 * freezes both against the NDJSON a consumer actually reads, the same way
 * {@code IndexRuntimeWireFormatRegressionTest} freezes the {@code index.runtime.*} contract.
 */
final class WorkerOpsPacingWireFormatRegressionTest {

  @TempDir Path tmp;

  @Test
  @DisplayName("the three pacing instruments reach the NDJSON with their declared names and types")
  void pacingMetricsWireFormat() throws Exception {
    // A deterministic policy that has actually yielded once, so the counter is non-zero and the
    // duty gauge reports a throttled window rather than the unthrottled default.
    long[] clockMs = {0L};
    long[] nanos = {0L};
    ForegroundLoad load = new ForegroundLoad(() -> clockMs[0]);
    IndexingPacing pacing =
        new IndexingPacing(
            load,
            20,
            500L,
            () -> clockMs[0],
            () -> nanos[0],
            ms -> {
              clockMs[0] += ms;
              nanos[0] += TimeUnit.MILLISECONDS.toNanos(ms);
            });
    pacing.pace(); // establishes the thread's work clock
    load.started(); // a foreground search is now in flight
    nanos[0] += TimeUnit.MILLISECONDS.toNanos(100); // 100 ms of indexing work
    pacing.pace(); // yields 400 ms => duty 20%
    assertEquals(1L, pacing.pacedIntervalsTotal(), "the fixture must have paced exactly once");
    assertEquals(20L, pacing.observedDutyPct());
    assertEquals(1, load.inFlight());

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
                    WorkerOpsMetricCatalog.NAMESPACE, WorkerOpsMetricCatalog.DEFINITIONS)))) {
      new WorkerOpsMetricCatalog(
          telemetry.registry(),
          OperationalMetrics.getInstance(),
          new WorkerOpsMetricCatalog.Sources(
              () -> 0L,
              () -> 0L,
              () -> 0L,
              () -> 0L,
              () -> 0L,
              () -> 0L,
              () -> 0L,
              () -> 0L,
              () -> 0L,
              pacing::pacedIntervalsTotal,
              pacing::observedDutyPct,
              () -> (long) load.inFlight(),
              // Tempdoc 885 item 21e widened Sources with the queue throughput + contention
              // suppliers. This case is about the pacing trio, so they are zeroed here rather
              // than faked: WorkerOpsQueueMetricWireFormatTest owns their wire assertions.
              () -> 0L,
              () -> 0L,
              () -> 0L,
              () -> 0L));

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-worker.ndjson"));
    }

    Map<String, String> expectedTypes =
        Map.of(
            WorkerOpsMetricCatalog.INDEXING_PACED_INTERVALS_TOTAL, "counter",
            WorkerOpsMetricCatalog.INDEXING_DUTY_PCT, "gauge",
            WorkerOpsMetricCatalog.INDEXING_FOREGROUND_IN_FLIGHT, "gauge");

    for (Map.Entry<String, String> e : expectedTypes.entrySet()) {
      assertTrue(
          containsLine(ndjson, e.getKey(), "\"type\":\"" + e.getValue() + "\""),
          "Missing wire-format line for '"
              + e.getKey()
              + "' with type "
              + e.getValue()
              + "; got:\n"
              + ndjson);
    }

    // The names are the contract; assert them literally so a constant rename is visible here even
    // if every reference is updated in lockstep.
    assertEquals(
        "worker.indexing.paced_intervals_total",
        WorkerOpsMetricCatalog.INDEXING_PACED_INTERVALS_TOTAL);
    assertEquals("worker.indexing.duty_pct", WorkerOpsMetricCatalog.INDEXING_DUTY_PCT);
    assertEquals(
        "worker.indexing.foreground_in_flight",
        WorkerOpsMetricCatalog.INDEXING_FOREGROUND_IN_FLIGHT);

    // The observed values reach the wire, not just the names: a source wired to the wrong supplier
    // would still emit three lines.
    assertTrue(
        lineWithName(ndjson, WorkerOpsMetricCatalog.INDEXING_DUTY_PCT).contains("20"),
        "duty_pct must carry the observed 20% window; got:\n" + ndjson);
    assertTrue(
        lineWithName(ndjson, WorkerOpsMetricCatalog.INDEXING_FOREGROUND_IN_FLIGHT).contains("1"),
        "foreground_in_flight must carry the in-flight search; got:\n" + ndjson);
  }

  @Test
  @DisplayName("all three are declared on the catalog and inside the worker namespace")
  void declaredInTheCatalog() {
    List<String> names =
        WorkerOpsMetricCatalog.DEFINITIONS.stream().map(MetricDefinition::name).toList();
    for (String n :
        List.of(
            WorkerOpsMetricCatalog.INDEXING_PACED_INTERVALS_TOTAL,
            WorkerOpsMetricCatalog.INDEXING_DUTY_PCT,
            WorkerOpsMetricCatalog.INDEXING_FOREGROUND_IN_FLIGHT)) {
      assertTrue(names.contains(n), n + " is not declared in WorkerOpsMetricCatalog.DEFINITIONS");
      assertTrue(
          n.startsWith(WorkerOpsMetricCatalog.NAMESPACE + "."),
          n + " escapes the '" + WorkerOpsMetricCatalog.NAMESPACE + "' namespace");
    }
  }

  private static boolean containsLine(String ndjson, String name, String fragment) {
    for (String line : ndjson.split("\n")) {
      if (line.contains("\"name\":\"" + name + "\"") && line.contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  private static String lineWithName(String ndjson, String name) {
    for (String line : ndjson.split("\n")) {
      if (line.contains("\"name\":\"" + name + "\"")) {
        return line;
      }
    }
    return "";
  }
}
