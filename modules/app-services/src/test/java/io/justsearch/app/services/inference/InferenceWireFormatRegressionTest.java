package io.justsearch.app.services.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.inference.RuntimeIdentity;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.inference.TargetPhase;
import io.justsearch.app.api.TransitionCode;
import io.justsearch.app.inference.telemetry.RequestKind;
import io.justsearch.app.inference.telemetry.RequestOutcome;
import io.justsearch.app.inference.telemetry.StartupReason;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.telemetry.LocalTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 412 follow-up wire-format regression test. Drives a fixed sequence of emissions
 * through the catalog-backed adapter and asserts structural properties of the NDJSON output:
 *
 * <ul>
 *   <li>Each metric name appears with its declared instrument type ({@code counter} or
 *       {@code histogram}).
 *   <li>Histogram bucket bounds match the catalog declaration ({@code Buckets.TIME_HISTOGRAM}
 *       for transition / startup durations, {@code Buckets.SLO_LATENCY_MS} for request
 *       durations).
 *   <li>Reason / code / phase tags reach the wire format with their declared {@code wireValue()}
 *       strings — byte-stable across the migration.
 *   <li>{@code inference.transition.duration_ms} carries the {@code "exemplars"} key (it
 *       declares {@code Exemplars.TRACE_BASED}); other histograms without exemplar declarations
 *       must NOT carry the key.
 * </ul>
 *
 * <p>Mirrors {@code IndexRuntimeWireFormatRegressionTest}'s structure from tempdoc 417.
 */
final class InferenceWireFormatRegressionTest {

  @TempDir Path tmp;

  private static final InferenceConfig FAKE_SCHEMA =
      new InferenceConfig(
          Path.of("/fake/llama-server.exe"),
          Path.of("/fake/model.gguf"),
          null,
          9999,
          4096,
          0,
          false);

  @Test
  void wireFormatStructuralEquivalence() throws Exception {
    String ndjson;
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            500,
            "test",
            "0",
            "metrics-inference.ndjson",
            List.of(
                io.justsearch.telemetry.catalog.MetricCatalog.of(
                    InferenceMetricCatalog.NAMESPACE, InferenceMetricCatalog.DEFINITIONS)))) {
      InferenceMetricCatalog catalog = new InferenceMetricCatalog(telemetry.registry());
      InferenceTelemetryAdapter adapter = new InferenceTelemetryAdapter(catalog);

      // Fixed sequence — exhaustive over the 11 metrics.
      adapter.onTransition(
          "OFFLINE", "ONLINE", TransitionReason.AUTO_START, Duration.ofMillis(2_500));
      adapter.onTransition(
          "ONLINE", "INDEXING", TransitionReason.USER_SWITCH, Duration.ofMillis(1_200));
      adapter.onStartupAttempt(FAKE_SCHEMA, StartupReason.COLD_START, TargetPhase.ONLINE);
      adapter.onStartupAttempt(FAKE_SCHEMA, StartupReason.CONFIG_APPLY, TargetPhase.INDEXING);
      adapter.onStartupComplete(
          FAKE_SCHEMA,
          Duration.ofMillis(2_400),
          new RuntimeIdentity(1L, "model", 8080, 1_700_000_000_000L),
          TargetPhase.ONLINE);
      adapter.onStartupFailure(
          new InferenceFailure.StartupFailure(StartupCode.MISSING_DLL, "no DLL", null));
      adapter.onConfigApplyAttempt(FAKE_SCHEMA, FAKE_SCHEMA, true);
      adapter.onConfigApplyAttempt(FAKE_SCHEMA, FAKE_SCHEMA, false);
      adapter.onConfigApplyFailure(
          new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, "bad path"));
      adapter.onConfigApplyFailure(
          new InferenceFailure.TransitionFailure(
              TransitionCode.CONFIG_APPLY_FAILED, "rollback failed", null));
      adapter.onHealthFailure(
          new InferenceFailure.HealthFailure(HealthCode.HEALTH_TIMEOUT, "x", null), 1, false);
      adapter.onHealthFailure(
          new InferenceFailure.HealthFailure(HealthCode.PROCESS_DIED, "x", null), 3, true);
      adapter.onHealthRecovered(2);
      adapter.onRequestStarted(RequestKind.CHAT, Duration.ofMillis(15));
      adapter.onRequestCompleted(RequestKind.CHAT, Duration.ofMillis(800), RequestOutcome.OK);
      adapter.onRequestCompleted(
          RequestKind.STREAM, Duration.ofMillis(1_500), RequestOutcome.ERROR);

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-inference.ndjson"));
    }

    // 1. Every catalog-declared metric name appears in the wire format with its instrument type.
    Map<String, String> expectedTypes = new HashMap<>();
    expectedTypes.put("inference.transition.total", "counter");
    expectedTypes.put("inference.transition.duration_ms", "histogram");
    expectedTypes.put("inference.startup.attempt_total", "counter");
    expectedTypes.put("inference.startup.duration_ms", "histogram");
    expectedTypes.put("inference.startup.failure_total", "counter");
    expectedTypes.put("inference.config.apply_total", "counter");
    expectedTypes.put("inference.config.apply_failure_total", "counter");
    expectedTypes.put("inference.health.failure_total", "counter");
    expectedTypes.put("inference.health.recovered_total", "counter");
    expectedTypes.put("inference.request.queue_wait_ms", "histogram");
    expectedTypes.put("inference.request.duration_ms", "histogram");

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

    // 2. Histogram bucket bounds reach the wire format.
    // Transition + startup durations use TIME_HISTOGRAM = [100,250,500,1000,2000,5000,10000,20000].
    String timeBoundsExpected = "\"bounds\":[100,250,500,1000,2000,5000,10000,20000]";
    assertTrue(
        anyLineWithName(ndjson, "inference.transition.duration_ms").stream()
            .anyMatch(l -> l.contains(timeBoundsExpected)),
        "transition.duration_ms missing TIME_HISTOGRAM bounds; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "inference.startup.duration_ms").stream()
            .anyMatch(l -> l.contains(timeBoundsExpected)),
        "startup.duration_ms missing TIME_HISTOGRAM bounds; got: " + ndjson);
    // Request histograms use SLO_LATENCY_MS = [5,10,20,50,100,200,400,800,1500,3000,5000,10000].
    String sloBoundsExpected = "\"bounds\":[5,10,20,50,100,200,400,800,1500,3000,5000,10000]";
    assertTrue(
        anyLineWithName(ndjson, "inference.request.duration_ms").stream()
            .anyMatch(l -> l.contains(sloBoundsExpected)),
        "request.duration_ms missing SLO_LATENCY_MS bounds; got: " + ndjson);

    // 3. Tag values reach wire format with byte-stable wire strings.
    assertTrue(
        anyLineWithName(ndjson, "inference.transition.total").stream()
            .anyMatch(l -> l.contains("\"from_phase\":\"OFFLINE\"")),
        "transition.total missing from_phase=OFFLINE; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "inference.transition.total").stream()
            .anyMatch(l -> l.contains("\"to_phase\":\"ONLINE\"")),
        "transition.total missing to_phase=ONLINE; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "inference.transition.total").stream()
            .anyMatch(l -> l.contains("\"reason\":\"auto_start\"")),
        "transition.total missing reason=auto_start (snake_case); got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "inference.startup.failure_total").stream()
            .anyMatch(l -> l.contains("\"code\":\"missing_dll\"")),
        "startup.failure_total missing code=missing_dll; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "inference.health.failure_total").stream()
            .anyMatch(l -> l.contains("\"severity\":\"restart_triggered\"")),
        "health.failure_total missing severity=restart_triggered; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "inference.config.apply_total").stream()
            .anyMatch(l -> l.contains("\"restart_required\":\"true\"")),
        "config.apply_total missing restart_required=true; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "inference.request.duration_ms").stream()
            .anyMatch(
                l -> l.contains("\"kind\":\"chat\"") && l.contains("\"outcome\":\"ok\"")),
        "request.duration_ms missing kind=chat,outcome=ok; got: " + ndjson);

    // 4. Exemplar wire format invariant.
    // - inference.transition.duration_ms declares Exemplars.TRACE_BASED → wire format MUST
    //   carry the "exemplars" key (empty array in unit tests with no span context;
    //   non-empty under a real trace).
    // - All other histograms declare Exemplars.OFF → wire format MUST NOT carry the
    //   "exemplars" key. Tempdoc 412 follow-up Bug G: the prior pass omitted the OFF
    //   declaration, leaving the empty-array key on every histogram and weakening this
    //   invariant.
    for (String line : anyLineWithName(ndjson, "inference.transition.duration_ms")) {
      assertTrue(
          line.contains("\"exemplars\""),
          "transition.duration_ms must carry the exemplars key (TRACE_BASED declared). Got: "
              + line);
    }
    for (String line : anyLineWithName(ndjson, "inference.startup.duration_ms")) {
      assertFalse(
          line.contains("\"exemplars\""),
          "startup.duration_ms declares Exemplars.OFF; wire format must suppress them. Got: "
              + line);
    }
    for (String line : anyLineWithName(ndjson, "inference.request.queue_wait_ms")) {
      assertFalse(
          line.contains("\"exemplars\""),
          "request.queue_wait_ms declares Exemplars.OFF; wire format must suppress them. Got: "
              + line);
    }
    for (String line : anyLineWithName(ndjson, "inference.request.duration_ms")) {
      assertFalse(
          line.contains("\"exemplars\""),
          "request.duration_ms declares Exemplars.OFF; wire format must suppress them. Got: "
              + line);
    }

    // 5. Smoke check — at least one counter line for each counter metric.
    assertTrue(
        anyLineWithName(ndjson, "inference.transition.total").stream()
            .anyMatch(l -> l.contains("\"type\":\"counter\"")),
        "expected at least one transition.total counter line");
    assertTrue(
        anyLineWithName(ndjson, "inference.health.recovered_total").stream()
            .anyMatch(l -> l.contains("\"type\":\"counter\"")),
        "expected at least one health.recovered_total counter line");
  }

  // ---- helpers (mirror IndexRuntimeWireFormatRegressionTest) ----

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

  @Test
  void helpersFindKnownLines() {
    String fixture =
        "{\"t\":\"x\",\"name\":\"a.b\",\"type\":\"counter\",\"value\":1,\"tags\":{}}\n"
            + "{\"t\":\"y\",\"name\":\"a.b\",\"type\":\"histogram\",\"bounds\":[1,2,3]}\n";
    assertTrue(containsLine(fixture, "a.b", "\"type\":\"counter\""));
    assertEquals(2, anyLineWithName(fixture, "a.b").size());
  }
}
