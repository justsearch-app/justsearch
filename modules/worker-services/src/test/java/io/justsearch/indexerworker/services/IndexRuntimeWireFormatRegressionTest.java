package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.adapters.lucene.runtime.ValidationReason;
import io.justsearch.telemetry.LocalTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 417 Phase 1 wire-format regression test. Drives a fixed sequence of emissions through
 * the catalog-backed adapter and asserts structural properties of the NDJSON output:
 *
 * <ul>
 *   <li>Each metric name appears with its declared instrument type ({@code counter} or
 *       {@code histogram}).
 *   <li>Histogram bucket bounds match the catalog declaration ({@link
 *       io.justsearch.telemetry.catalog.Buckets#TIME_HISTOGRAM} for commit/swap, {@link
 *       io.justsearch.telemetry.catalog.Buckets#WRITE_BARRIER_HISTOGRAM} for write-barrier).
 *   <li>Reason tags reach the wire format with their declared {@code wireValue()} strings —
 *       byte-stable across the migration.
 *   <li>{@code write_barrier_wait_us} has {@code Exemplars.OFF} declared in the catalog and
 *       therefore must NOT carry an {@code "exemplars"} key in its NDJSON line.
 * </ul>
 *
 * <p>This test is the structural complement to {@link WorkerLuceneTelemetryAdapterTest}: that
 * test verifies emission-by-emission semantics; this one freezes the wire-format contract so
 * a future refactor cannot silently break NDJSON consumers.
 */
final class IndexRuntimeWireFormatRegressionTest {

  private static final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();

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
                io.justsearch.telemetry.catalog.MetricCatalog.of(
                    IndexRuntimeMetricCatalog.NAMESPACE,
                    IndexRuntimeMetricCatalog.DEFINITIONS)))) {
      IndexRuntimeMetricCatalog catalog = new IndexRuntimeMetricCatalog(telemetry.registry());
      WorkerLuceneTelemetryAdapter adapter = new WorkerLuceneTelemetryAdapter(catalog);

      // Fixed sequence — exhaustive over the 9 metrics + each tag-bearing metric's typed values.
      adapter.onHardDelete(3);
      adapter.onSoftDelete(2);
      adapter.onBackpressure();
      adapter.onCommit(150L, CommitReason.DRAIN);
      adapter.onCommit(900L, CommitReason.TIMER);
      adapter.onCommit(50L, CommitReason.GRPC_DELETE_BY_PATH);
      adapter.onValidationFailure(ValidationReason.MISSING_ID_FIELD);
      adapter.onSwapStart(SwapReason.ADMIN_TRIGGERED);
      adapter.onSwapComplete(2_500L, SwapReason.ADMIN_TRIGGERED);
      adapter.onDrainTimeout(5_000L, 7L);
      adapter.onWriteBarrierContention(2_500L); // 2.5 us
      adapter.onWriteBarrierContention(150_000L); // 150 us

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-worker.ndjson"));
    }

    // 1. Every catalog-declared metric name appears in the wire format.
    Map<String, String> expectedTypes = new HashMap<>();
    expectedTypes.put("index.runtime.hard_delete_total", "counter");
    expectedTypes.put("index.runtime.soft_delete_total", "counter");
    expectedTypes.put("index.runtime.backpressure_total", "counter");
    expectedTypes.put("index.runtime.drain_timeout_total", "counter");
    expectedTypes.put("index.runtime.commit_ms", "histogram");
    expectedTypes.put("index.runtime.swap_started_total", "counter");
    expectedTypes.put("index.runtime.swap_duration_ms", "histogram");
    expectedTypes.put("index.runtime.write_barrier_wait_us", "histogram");
    expectedTypes.put("index.runtime.validation_failure_total", "counter");
    expectedTypes.put("index.runtime.commit_total", "counter");

    for (Map.Entry<String, String> e : expectedTypes.entrySet()) {
      assertTrue(
          containsLine(ndjson, e.getKey(), "\"type\":\"" + e.getValue() + "\""),
          "Missing wire-format line for metric '"
              + e.getKey()
              + "' with type "
              + e.getValue()
              + "; got:\n"
              + ndjson);
    }

    // 2. Bucket bounds reach the wire format.
    // commit_ms + swap_duration_ms use TIME_HISTOGRAM = [100, 250, 500, 1000, 2000, 5000, 10000, 20000].
    String timeBoundsExpected = "\"bounds\":[100,250,500,1000,2000,5000,10000,20000]";
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.commit_ms").stream()
            .anyMatch(l -> l.contains(timeBoundsExpected)),
        "commit_ms missing TIME_HISTOGRAM bounds; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.swap_duration_ms").stream()
            .anyMatch(l -> l.contains(timeBoundsExpected)),
        "swap_duration_ms missing TIME_HISTOGRAM bounds; got: " + ndjson);

    // write_barrier_wait_us uses WRITE_BARRIER_HISTOGRAM = [1, 10, 100, 1000, 10000, 100000].
    String writeBarrierBoundsExpected = "\"bounds\":[1,10,100,1000,10000,100000]";
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.write_barrier_wait_us").stream()
            .anyMatch(l -> l.contains(writeBarrierBoundsExpected)),
        "write_barrier_wait_us missing WRITE_BARRIER_HISTOGRAM bounds; got: " + ndjson);

    // 3. Reason tags reach wire format with byte-stable wire values.
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.commit_ms").stream()
            .anyMatch(l -> l.contains("\"reason\":\"drain\"")),
        "commit_ms missing reason=drain tag; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.commit_ms").stream()
            .anyMatch(l -> l.contains("\"reason\":\"timer\"")),
        "commit_ms missing reason=timer tag; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.commit_ms").stream()
            .anyMatch(l -> l.contains("\"reason\":\"grpc/deleteByPath\"")),
        "commit_ms missing reason=grpc/deleteByPath tag (path-style preserved); got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.swap_duration_ms").stream()
            .anyMatch(l -> l.contains("\"reason\":\"admin_triggered\"")),
        "swap_duration_ms missing reason=admin_triggered (snake_case preserved); got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "index.runtime.validation_failure_total").stream()
            .anyMatch(l -> l.contains("\"reason\":\"missing_id_field\"")),
        "validation_failure_total missing reason=missing_id_field; got: " + ndjson);

    // 4. Exemplars.OFF on write_barrier_wait_us → wire format must NOT carry "exemplars".
    for (String line : anyLineWithName(ndjson, "index.runtime.write_barrier_wait_us")) {
      assertFalse(
          line.contains("\"exemplars\""),
          "Exemplars.OFF declared on write_barrier_wait_us; wire format must suppress exemplars."
              + " Got: "
              + line);
    }

    // 5. Smoke check — counter values are non-zero where expected.
    int counterLineCount = 0;
    for (String line : anyLineWithName(ndjson, "index.runtime.hard_delete_total")) {
      if (line.contains("\"type\":\"counter\"")) counterLineCount++;
    }
    assertTrue(counterLineCount >= 1, "expected at least one hard_delete_total counter line");
  }

  /**
   * Tempdoc 885 item 19: the cadence trio must reach the NDJSON, because that file is what the
   * jseval comparison reads. Driven through a real {@code RuntimeGaugesSnapshot} rather than the
   * zero-valued EMPTY supplier, so the assertion distinguishes "the gauge is declared" from "the
   * gauge is wired to the snapshot field the runtime actually stamps".
   */
  @Test
  void cadenceGaugesReachTheWireFormat() throws Exception {
    String ndjson;
    var snapshot =
        new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeGaugesSnapshot(
            0L, 0L, 11L, 0L, 7L, 3L);
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            500,
            "test",
            "0",
            "metrics-cadence.ndjson",
            List.of(
                io.justsearch.telemetry.catalog.MetricCatalog.of(
                    IndexRuntimeMetricCatalog.NAMESPACE,
                    IndexRuntimeMetricCatalog.DEFINITIONS)))) {
      new IndexRuntimeMetricCatalog(telemetry.registry(), () -> snapshot);
      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-cadence.ndjson"));
    }

    assertTrue(
        containsLine(ndjson, IndexRuntimeMetricCatalog.REOPEN_COUNT, "\"type\":\"gauge\""),
        "reopen_count missing from the wire format; got:\n" + ndjson);
    assertTrue(
        containsLine(ndjson, IndexRuntimeMetricCatalog.SEGMENTS_SINCE_REOPEN, "\"type\":\"gauge\""),
        "segments_since_reopen missing from the wire format; got:\n" + ndjson);
    assertTrue(
        anyLineWithName(ndjson, IndexRuntimeMetricCatalog.REOPEN_COUNT).stream()
            .anyMatch(l -> l.contains("7")),
        "reopen_count must carry the supplier's value, not a zero; got:\n" + ndjson);
    assertTrue(
        anyLineWithName(ndjson, IndexRuntimeMetricCatalog.SEGMENTS_SINCE_REOPEN).stream()
            .anyMatch(l -> l.contains("3")),
        "segments_since_reopen must carry the supplier's value; got:\n" + ndjson);
    assertTrue(
        anyLineWithName(ndjson, IndexRuntimeMetricCatalog.COMMIT_COUNT).stream()
            .anyMatch(l -> l.contains("11")),
        "commit_count is the third leg of the cadence trio and must still be the same instrument;"
            + " got:\n"
            + ndjson);
  }

  // ---- helpers ----

  /**
   * Tempdoc 912 item 2 — {@code index.runtime.commit_total} reaches the NDJSON as ONE cumulative
   * series per reason, which is the shape jseval's cadence block reads to attribute a run's
   * commits to their triggers. Asserted with an asymmetric distribution (3 timer / 1 drain) so a
   * counter that recorded one increment per reason regardless of how many commits fired — or that
   * pooled every reason into one series — fails instead of passing on symmetry.
   */
  @Test
  void commitTotalCarriesOneCumulativeSeriesPerReason() throws Exception {
    String ndjson;
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            500,
            "test",
            "0",
            "metrics-commit-total.ndjson",
            List.of(
                io.justsearch.telemetry.catalog.MetricCatalog.of(
                    IndexRuntimeMetricCatalog.NAMESPACE,
                    IndexRuntimeMetricCatalog.DEFINITIONS)))) {
      WorkerLuceneTelemetryAdapter adapter =
          new WorkerLuceneTelemetryAdapter(new IndexRuntimeMetricCatalog(telemetry.registry()));

      adapter.onCommit(10L, CommitReason.TIMER);
      adapter.onCommit(11L, CommitReason.TIMER);
      adapter.onCommit(12L, CommitReason.TIMER);
      adapter.onCommit(13L, CommitReason.DRAIN);

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-commit-total.ndjson"));
    }

    List<String> lines = anyLineWithName(ndjson, "index.runtime.commit_total");
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("\"reason\":\"timer\"") && l.contains("\"value\":3")),
        "commit_total must report 3 for reason=timer; got:\n" + ndjson);
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("\"reason\":\"drain\"") && l.contains("\"value\":1")),
        "commit_total must report 1 for reason=drain; got:\n" + ndjson);
    assertFalse(
        lines.stream().anyMatch(l -> l.contains("\"reason\":\"prune\"")),
        "A reason that never committed must not appear as a series; got:\n" + ndjson);
  }

  /**
   * Tempdoc 915 — the two migration reasons are the whole point of the 912 fold-in, and a reason
   * that never reaches the wire is attribution nobody can read. Asserted at the NDJSON, not at
   * CommitCounters: the counter and the exporter are different code, and only the exporter is what
   * jseval cadence attributes from.
   */
  @Test
  void theMigrationCommitReasonsReachTheWireAsTheirOwnSeries() throws Exception {
    String ndjson;
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            500,
            "test",
            "0",
            "metrics-migration-reasons.ndjson",
            List.of(
                io.justsearch.telemetry.catalog.MetricCatalog.of(
                    IndexRuntimeMetricCatalog.NAMESPACE,
                    IndexRuntimeMetricCatalog.DEFINITIONS)))) {
      WorkerLuceneTelemetryAdapter adapter =
          new WorkerLuceneTelemetryAdapter(new IndexRuntimeMetricCatalog(telemetry.registry()));

      adapter.onCommit(10L, CommitReason.MIGRATION_CUTOVER);
      adapter.onCommit(11L, CommitReason.SWITCH_BUFFER_REPLAY);
      adapter.onCommit(12L, CommitReason.SWITCH_BUFFER_REPLAY);

      telemetry.flush();
      ndjson =
          Files.readString(tmp.resolve("telemetry").resolve("metrics-migration-reasons.ndjson"));
    }

    // Parsed, not substring-matched: "value":1 can appear anywhere in a line (another tag, a
    // bucket count), so a contains() pair proves the two fragments are on the same LINE and
    // nothing more — it would pass on a line whose reason and value belong to different series.
    Map<String, Long> byReason = commitTotalsByReason(ndjson);
    assertEquals(
        1L,
        byReason.get("migration/cutover"),
        "the cutover commit must reach the wire under its own label; got:\n" + ndjson);
    assertEquals(
        2L,
        byReason.get("migration/switch-buffer-replay"),
        "the switch-buffer replay must reach the wire under its own label; got:\n" + ndjson);
    assertFalse(
        byReason.containsKey("unknown"),
        "neither migration commit may fall through to unknown; got:\n" + ndjson);
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

  /**
   * Reads {@code index.runtime.commit_total} out of the NDJSON as a real map from the {@code reason}
   * TAG to its value, so an assertion is about one series rather than about two substrings that
   * happen to share a line.
   */
  private static Map<String, Long> commitTotalsByReason(String ndjson) {
    Map<String, Long> out = new HashMap<>();
    for (String line : anyLineWithName(ndjson, "index.runtime.commit_total")) {
      tools.jackson.databind.JsonNode node = JSON.readTree(line);
      tools.jackson.databind.JsonNode tags = node.path("tags");
      if (tags.isMissingNode() || !tags.has("reason")) {
        continue;
      }
      out.merge(tags.get("reason").asString(), node.path("value").asLong(), Long::sum);
    }
    return out;
  }

  /**
   * Sanity-check that the helpers themselves don't lie. If this ever fails, fix the helpers
   * before trusting other assertions.
   */
  @Test
  void helpersFindKnownLines() {
    String fixture =
        "{\"t\":\"x\",\"name\":\"a.b\",\"type\":\"counter\",\"value\":1,\"tags\":{}}\n"
            + "{\"t\":\"y\",\"name\":\"a.b\",\"type\":\"histogram\",\"bounds\":[1,2,3]}\n";
    assertTrue(containsLine(fixture, "a.b", "\"type\":\"counter\""));
    assertEquals(2, anyLineWithName(fixture, "a.b").size());
  }
}
