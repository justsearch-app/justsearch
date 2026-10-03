/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.catalog.InstrumentKind;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.Unit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 885 item 21e — RISK-002's instrument, asserted on the wire.
 *
 * <p>RISK-002 has sat at status "Monitoring" since tempdoc 269 with no metric behind it. A test
 * that only checked "the constant exists" would not have caught the failure mode that kept it
 * unmonitored for that long: a declared name that nothing emits. So this drives real values through
 * a real {@link LocalTelemetry} and asserts they reach the NDJSON.
 */
@DisplayName("Worker queue throughput metrics (885 item 21e)")
final class WorkerOpsQueueMetricWireFormatTest {

  @TempDir Path tmp;

  @Test
  @DisplayName("the queue throughput + contention metrics are declared under the worker namespace")
  void queueMetricsAreDeclared() {
    assertDefined(
        WorkerOpsMetricCatalog.JOB_QUEUE_ENQUEUE_RATE_PER_MIN, InstrumentKind.GAUGE, Unit.COUNT);
    assertDefined(
        WorkerOpsMetricCatalog.JOB_QUEUE_DEQUEUE_RATE_PER_MIN, InstrumentKind.GAUGE, Unit.COUNT);
    assertDefined(
        WorkerOpsMetricCatalog.JOB_QUEUE_LOCK_WAIT_MAX_MS,
        InstrumentKind.GAUGE,
        Unit.MILLISECONDS);
    assertDefined(
        WorkerOpsMetricCatalog.JOB_QUEUE_LOCK_WAIT_AVG_MS,
        InstrumentKind.GAUGE,
        Unit.MILLISECONDS);

    MetricDefinition outcome =
        definition(WorkerOpsMetricCatalog.JOB_QUEUE_OUTCOME_TOTAL).orElseThrow();
    assertEquals(InstrumentKind.COUNTER, outcome.kind());
    assertEquals(
        java.util.Set.of(QueueOutcomeTags.KEY_OUTCOME_CLASS),
        outcome.allowedTagKeys(),
        "outcome_class is the ONLY admitted tag — nothing path- or exception-derived");
    assertEquals(
        Integer.valueOf(32),
        outcome.cardinalityLimit(),
        "the outcome vocabulary is closed, so the limit must be tight");
  }

  @Test
  @DisplayName("the metric names are pinned literally")
  void metricNamesArePinned() {
    // The names ARE the contract — RISK-002's row and any dashboard refer to them as strings. A
    // test that only compares constants to themselves stays green through a rename that silently
    // breaks every external reference.
    assertEquals(
        "worker.job_queue.enqueue_rate_per_min",
        WorkerOpsMetricCatalog.JOB_QUEUE_ENQUEUE_RATE_PER_MIN);
    assertEquals(
        "worker.job_queue.dequeue_rate_per_min",
        WorkerOpsMetricCatalog.JOB_QUEUE_DEQUEUE_RATE_PER_MIN);
    assertEquals(
        "worker.job_queue.lock_wait_max_ms", WorkerOpsMetricCatalog.JOB_QUEUE_LOCK_WAIT_MAX_MS);
    assertEquals(
        "worker.job_queue.lock_wait_avg_ms", WorkerOpsMetricCatalog.JOB_QUEUE_LOCK_WAIT_AVG_MS);
    assertEquals(
        "worker.job_queue.outcome.total", WorkerOpsMetricCatalog.JOB_QUEUE_OUTCOME_TOTAL);
    assertEquals("outcome_class", QueueOutcomeTags.KEY_OUTCOME_CLASS);
  }

  @Test
  @DisplayName("emitted queue metrics reach the NDJSON with their tag values intact")
  void queueMetricsReachTheWire() throws Exception {
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
                    WorkerOpsMetricCatalog.NAMESPACE, WorkerOpsMetricCatalog.DEFINITIONS)))) {
      var sources =
          new WorkerOpsMetricCatalog.Sources(
              () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L, () -> 0L,
              () -> 0L, () -> 0L, () -> 100L, () -> 0L,
              () -> 4242L, () -> 1717L, () -> 999L, () -> 7L);
      var catalog =
          new WorkerOpsMetricCatalog(
              telemetry.registry(), OperationalMetrics.getInstance(), sources);

      catalog.jobQueueOutcomeTotal.increment(new QueueOutcomeTags("IO_FAILED"));
      catalog.jobQueueOutcomeTotal.increment(new QueueOutcomeTags("PARSER_FAILED"));
      catalog.jobQueueOutcomeTotal.increment(new QueueOutcomeTags(null));

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    }

    assertTrue(
        ndjson.contains(WorkerOpsMetricCatalog.JOB_QUEUE_ENQUEUE_RATE_PER_MIN),
        "the enqueue rate must reach the wire");
    assertTrue(
        ndjson.contains(WorkerOpsMetricCatalog.JOB_QUEUE_DEQUEUE_RATE_PER_MIN),
        "the dequeue rate must reach the wire — this is the name RISK-002 needs");
    assertTrue(ndjson.contains(WorkerOpsMetricCatalog.JOB_QUEUE_LOCK_WAIT_MAX_MS));
    assertTrue(ndjson.contains(WorkerOpsMetricCatalog.JOB_QUEUE_LOCK_WAIT_AVG_MS));
    assertTrue(ndjson.contains(WorkerOpsMetricCatalog.JOB_QUEUE_OUTCOME_TOTAL));
    // The supplier values, not just the names — a gauge wired to the wrong supplier would still
    // print its name.
    assertTrue(ndjson.contains("4242"), "the enqueue-rate supplier's value must reach the wire");
    assertTrue(ndjson.contains("1717"), "the dequeue-rate supplier's value must reach the wire");
    assertTrue(ndjson.contains("IO_FAILED"), "the outcome_class tag value must reach the wire");
    assertTrue(ndjson.contains("PARSER_FAILED"));
    assertTrue(ndjson.contains("UNKNOWN"), "a null outcome class is tagged UNKNOWN, never dropped");
  }

  private static void assertDefined(String name, InstrumentKind kind, Unit unit) {
    MetricDefinition def =
        definition(name).orElseThrow(() -> new AssertionError(name + " is not declared"));
    assertEquals(kind, def.kind(), name + " kind");
    assertEquals(unit, def.unit(), name + " unit");
    assertTrue(
        name.startsWith(WorkerOpsMetricCatalog.NAMESPACE + "."),
        name + " must live under the worker namespace");
  }

  private static Optional<MetricDefinition> definition(String name) {
    return WorkerOpsMetricCatalog.DEFINITIONS.stream()
        .filter(d -> d.name().equals(name))
        .findFirst();
  }
}
