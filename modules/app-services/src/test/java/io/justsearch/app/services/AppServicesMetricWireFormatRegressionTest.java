package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.observability.HeadApiTags.ApiErrorTags;
import io.justsearch.app.services.observability.HeadApiTags.ApiRequestTags;
import io.justsearch.app.services.observability.HeadApiTags.ApiStreamTags;
import io.justsearch.app.services.observability.HeadGpuMetricCatalog;
import io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog;
import io.justsearch.app.services.observability.HttpMethod;
import io.justsearch.app.services.observability.HttpStatusClass;
import io.justsearch.app.services.observability.StreamTransport;
import io.justsearch.app.services.vdu.VduMetricCatalog;
import io.justsearch.app.services.vdu.VduOutcome;
import io.justsearch.app.services.vdu.VduOutcomeTags;
import io.justsearch.app.services.vdu.VduTimeoutTags;
import io.justsearch.app.services.worker.IpcMetricCatalog;
import io.justsearch.app.services.worker.IpcTags.WorkerRestartTags;
import io.justsearch.app.services.worker.RagMetricCatalog;
import io.justsearch.app.services.worker.RagRetrievalMode;
import io.justsearch.app.services.worker.RagRetrievalTags;
import io.justsearch.app.services.worker.WorkerRestartOutcome;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.ErrorClass;
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
 * Tempdoc 417 F3 wire-format regression test covering all catalogs hosted in {@code app-services}:
 * IPC, RAG, VDU, and Head HTTP/GPU/inflight (relocated here in F1). Asserts byte-stability of
 * tag values and bucket bounds.
 */
final class AppServicesMetricWireFormatRegressionTest {

  @TempDir Path tmp;

  @Test
  void wireFormatStructuralEquivalence() throws Exception {
    String ndjson;
    try (LocalTelemetry telemetry =
        new LocalTelemetry(
            tmp,
            500,
            "test",
            "0",
            "metrics.ndjson",
            List.of(
                MetricCatalog.of(IpcMetricCatalog.NAMESPACE, IpcMetricCatalog.DEFINITIONS),
                MetricCatalog.of(RagMetricCatalog.NAMESPACE, RagMetricCatalog.DEFINITIONS),
                MetricCatalog.of(VduMetricCatalog.NAMESPACE, VduMetricCatalog.DEFINITIONS),
                MetricCatalog.of(
                    HeadApiMetricCatalog.NAMESPACE, HeadApiMetricCatalog.DEFINITIONS),
                MetricCatalog.of(
                    HeadHttpInflightMetricCatalog.NAMESPACE,
                    HeadHttpInflightMetricCatalog.DEFINITIONS),
                MetricCatalog.of(
                    HeadGpuMetricCatalog.NAMESPACE, HeadGpuMetricCatalog.DEFINITIONS)))) {
      var ipc = new IpcMetricCatalog(telemetry.registry());
      var rag = new RagMetricCatalog(telemetry.registry());
      var vdu = new VduMetricCatalog(telemetry.registry());
      var headApi = new HeadApiMetricCatalog(telemetry.registry());

      ipc.workerRestart.increment(new WorkerRestartTags(WorkerRestartOutcome.SUCCESS));
      ipc.portDiscoveryMs.record(123L, EmptyTags.INSTANCE);

      rag.retrievalTotal.increment(RagRetrievalTags.of(RagRetrievalMode.RAG));
      rag.retrievalTotal.increment(RagRetrievalTags.of(RagRetrievalMode.FALLBACK));

      vdu.timeoutTotal.increment(VduTimeoutTags.of());
      vdu.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.COMPLETED));
      vdu.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.FAILED));

      headApi.requestMs.record(
          42L, new ApiRequestTags("/api/test", HttpMethod.POST, "200", HttpStatusClass.S2XX));
      headApi.streamTtftMs.record(
          99L, new ApiStreamTags("/api/agent", HttpMethod.POST, StreamTransport.SSE));
      headApi.errorTotal.increment(
          new ApiErrorTags(ApiErrorCode.NOT_FOUND, ErrorClass.PERMANENT, "/api/test"));

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    }

    // IPC
    assertTrue(
        anyLineWithName(ndjson, "ipc.worker.restart").stream()
            .anyMatch(l -> l.contains("\"outcome\":\"success\"")),
        "ipc.worker.restart missing outcome=success; got: " + ndjson);
    // Lane F item A10 deleted the circuit breaker with the wire; ipc.circuit_breaker.state_change
    // no longer exists, so the multi-key tag-schema shape it used to pin is carried by the
    // remaining multi-key emitters below (headApi.requestMs, headApi.errorTotal).
    assertTrue(
        containsLine(ndjson, "ipc.port_discovery_ms", "\"type\":\"histogram\""),
        "ipc.port_discovery_ms missing; got: " + ndjson);

    // RAG — F2: component=rag_retrieval restored.
    assertTrue(
        anyLineWithName(ndjson, "rag.retrieval_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"component\":\"rag_retrieval\"") && l.contains("\"mode\":\"rag\"")),
        "rag.retrieval_total missing component=rag_retrieval (F2); got: " + ndjson);

    // VDU — F2: component=vdu_batch and component=vdu restored.
    assertTrue(
        anyLineWithName(ndjson, "vdu.outcome_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"component\":\"vdu_batch\"")
                        && l.contains("\"outcome\":\"completed\"")),
        "vdu.outcome_total missing component=vdu_batch (F2); got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "vdu.timeout_total").stream()
            .anyMatch(l -> l.contains("\"component\":\"vdu\"")),
        "vdu.timeout_total missing component=vdu (F2); got: " + ndjson);

    // Head API
    assertTrue(
        anyLineWithName(ndjson, "api.request_ms").stream()
            .anyMatch(
                l ->
                    l.contains("\"route\":\"/api/test\"")
                        && l.contains("\"http_method\":\"POST\"")
                        && l.contains("\"http_status\":\"200\"")
                        && l.contains("\"http_status_class\":\"2xx\"")),
        "api.request_ms missing tags; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "api.request_ms").stream()
            .anyMatch(l -> l.contains("\"bounds\":[10,20,50,100,200,400,800,1500,3000]")),
        "api.request_ms missing bucket bounds; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "api.stream.ttft_ms").stream()
            .anyMatch(l -> l.contains("\"stream_transport\":\"sse\"")),
        "api.stream.ttft_ms missing stream_transport; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "api.error.total").stream()
            .anyMatch(
                l ->
                    l.contains("\"error_code\":\"NOT_FOUND\"")
                        && l.contains("\"error_class\":\"PERMANENT\"")),
        "api.error.total missing error_code/error_class; got: " + ndjson);
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
