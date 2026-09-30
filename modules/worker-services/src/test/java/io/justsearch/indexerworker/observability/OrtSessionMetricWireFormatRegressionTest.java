package io.justsearch.indexerworker.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ort.telemetry.AssemblerEvent;
import io.justsearch.ort.telemetry.AssemblerFailureKind;
import io.justsearch.ort.telemetry.CpuRecreateCause;
import io.justsearch.ort.telemetry.FailureCause;
import io.justsearch.ort.telemetry.TransitionReason;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.catalog.MetricCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire-format regression test for {@code ort.session.*} metrics. Boots a real
 * {@link LocalTelemetry} with {@link OrtSessionMetricCatalog#DEFINITIONS}, fires every
 * {@link TransitionReason} permit + {@link AssemblerEvent} permit through
 * {@link OrtSessionTelemetryAdapter}, then asserts the NDJSON output carries the expected tags
 * and bucket bounds.
 *
 * <p>Pattern reference: {@code AppServicesMetricWireFormatRegressionTest} (417 F3).
 */
final class OrtSessionMetricWireFormatRegressionTest {

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
                    OrtSessionMetricCatalog.NAMESPACE, OrtSessionMetricCatalog.DEFINITIONS)))) {
      var catalog = new OrtSessionMetricCatalog(telemetry.registry());
      var adapter = new OrtSessionTelemetryAdapter(catalog, List.of("embed", "splade"));

      // Cover every transition permit at least once.
      adapter.onTransition(new TransitionReason.GpuInitialized("embed"));
      adapter.onTransition(
          new TransitionReason.GpuInitFailed("splade", FailureCause.CUDA_UNAVAILABLE));
      adapter.onTransition(new TransitionReason.GpuReleaseCompleted("embed"));
      adapter.onTransition(new TransitionReason.GpuReleaseFailed("splade"));
      adapter.onTransition(new TransitionReason.GpuFallbackTaken("embed"));
      adapter.onTransition(
          new TransitionReason.CpuSessionRecreated("embed", CpuRecreateCause.BFC_ARENA_FAILURE));
      adapter.onTransition(new TransitionReason.GpuRetryAttempted("splade", 60_000L));

      // AssemblerEvent permit.
      adapter.onAssemblerEvent(
          new AssemblerEvent.Failed("stress", AssemblerFailureKind.NULL_VARIANT));

      // Histograms.
      adapter.onSemaphoreWait("embed", 42L);
      adapter.onSemaphoreWait("splade", 7L);

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    }

    assertTrue(
        anyLineWithName(ndjson, "ort.session.gpu_init_total").stream()
            .anyMatch(l -> l.contains("\"consumer\":\"embed\"") && l.contains("\"outcome\":\"success\"")),
        "gpu_init_total missing consumer/outcome tags; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.gpu_init_failure_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"consumer\":\"splade\"")
                        && l.contains("\"cause\":\"cuda_unavailable\"")),
        "gpu_init_failure_total missing tags; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.fallback_total").stream()
            .anyMatch(l -> l.contains("\"consumer\":\"embed\"")),
        "fallback_total missing consumer; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.recovery_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"consumer\":\"embed\"")
                        && l.contains("\"cause\":\"bfc_arena_failure\"")),
        "recovery_total missing tags; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.release_total").stream()
            .anyMatch(l -> l.contains("\"outcome\":\"success\"")),
        "release_total{success} missing; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.release_total").stream()
            .anyMatch(l -> l.contains("\"outcome\":\"failure\"")),
        "release_total{failure} missing; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.retry_total").stream()
            .anyMatch(l -> l.contains("\"consumer\":\"splade\"")),
        "retry_total missing consumer; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.retry_interval_ms").stream()
            .anyMatch(l -> l.contains("\"type\":\"histogram\"") && l.contains("\"consumer\":\"splade\"")),
        "retry_interval_ms histogram missing or wrong tag; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.assembler_failure_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"consumer\":\"stress\"")
                        && l.contains("\"kind\":\"null_variant\"")),
        "assembler_failure_total missing tags; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.semaphore_wait_us").stream()
            .anyMatch(
                l ->
                    l.contains("\"type\":\"histogram\"") && l.contains("\"consumer\":\"embed\"")),
        "semaphore_wait_us missing tags or wrong type; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.semaphore_wait_us").stream()
            .anyMatch(l -> l.contains("\"bounds\":[1,10,100,1000,10000,100000]")),
        "semaphore_wait_us missing WRITE_BARRIER_HISTOGRAM bucket bounds; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "ort.session.semaphore_wait_us").stream()
            .noneMatch(l -> l.contains("\"accelerator\"")),
        "semaphore_wait_us must not carry an accelerator tag (GPU-only); got: " + ndjson);
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
