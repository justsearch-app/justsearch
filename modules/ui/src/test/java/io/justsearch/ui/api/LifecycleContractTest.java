package io.justsearch.ui.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Lifecycle contract: /api/health and /api/status schema v1")
final class LifecycleContractTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern REASON_CODE =
      Pattern.compile("^(head|worker|ipc|inference)\\.[a-z0-9_]+(\\.[a-z0-9_]+)*$");

  @TempDir Path tempDir;

  private ConfigStore prevStore;

  @BeforeEach
  void setUpConfigStore() {
    prevStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restoreConfigStore() {
    TestResolvedConfigHelper.restoreGlobal(prevStore);
  }

  @Test
  @DisplayName("/api/health returns schema v1 and gates with 200 for DEGRADED")
  void healthReturnsSchemaV1AndGates200ForDegraded() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService onlineAi = mock(OnlineAiService.class);
    when(onlineAi.isAvailable()).thenReturn(false);
    when(onlineAi.isStartingUp()).thenReturn(true);
    OnlineAiService __onlineAi = onlineAi;

    // No worker configured, inference offline => DEGRADED should still return 200.
    LocalApiServer server = LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi).build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/health"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      ContractSchemaAssertions.assertConforms(
          "GET /api/health status 200", "lifecycle-snapshot.v1.json", resp.body());
      JsonNode json = MAPPER.readTree(resp.body());
      assertSchemaV1(json);
      assertEquals("LIFECYCLE_STATE_DEGRADED", json.path("lifecycle").path("state").asText());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/health returns schema v1 and gates with 503 for ERROR")
  void healthReturnsSchemaV1AndGates503ForError() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService __onlineAi = OnlineAiService.unavailable();

    // Simulate worker startup failure (worker bootstrap error) => ERROR => 503.
    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi)
            .knowledgeServerStartError("worker failed to start (test)")
            .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/health"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(503, resp.statusCode());
      ContractSchemaAssertions.assertConforms(
          "GET /api/health status 503", "lifecycle-snapshot.v1.json", resp.body());
      JsonNode json = MAPPER.readTree(resp.body());
      assertSchemaV1(json);
      assertEquals("LIFECYCLE_STATE_ERROR", json.path("lifecycle").path("state").asText());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status includes the schema v1 minimum stable subset")
  void statusIncludesSchemaV1Subset() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService __onlineAi = OnlineAiService.unavailable();
    LocalApiServer server = LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi).build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      ContractSchemaAssertions.assertConforms(
          "GET /api/status status 200", "lifecycle-snapshot.v1.json", resp.body());
      JsonNode json = MAPPER.readTree(resp.body());
      // /api/status is a superset of the schema v1 health fields;
      // check the required v1 fields are present, not exact match.
      assertTrue(json.has("schema_version"), "must include schema_version");
      assertTrue(json.has("observed_at"), "must include observed_at");
      assertTrue(json.has("lifecycle"), "must include lifecycle");
      assertTrue(json.has("components"), "must include components");
      assertEquals(1, json.path("schema_version").asInt(-1));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status includes additive AI readiness fields when Worker reports them")
  void statusIncludesAiReadinessFields() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService onlineAi = mock(OnlineAiService.class);
    when(onlineAi.isAvailable()).thenReturn(true);
    when(onlineAi.isStartingUp()).thenReturn(false);
    OnlineAiService __onlineAi = onlineAi;

    var inferenceCap = new io.justsearch.app.services.lifecycle.InferenceCapability(true);
    inferenceCap.transition(io.justsearch.app.api.lifecycle.CapabilityHealth.READY, null);

    KnowledgeServerBootstrap mockKs = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient mockClient = mock(KnowledgeClient.class);
    stubWorkerReady(mockKs);
    when(mockKs.client()).thenReturn(mockClient);
    when(mockClient.getWorkerOperationalView(TestRequestContexts.internal()))
        .thenReturn(testWorkerView(true, 2, 0, true, false));

    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi)
            .knowledgeServer(mockKs)
            .perSourceSearch(mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class))
            .inferenceCapability(inferenceCap)
            .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());
      assertTrue(json.has("aiReady"), "aiReady should be present when Worker reports it");
      assertTrue(json.path("aiReady").asBoolean(false));
      assertTrue(json.has("embeddingReady"), "embeddingReady should be present when Worker reports it");
      assertFalse(json.path("embeddingReady").asBoolean(true));
      assertTrue(json.has("readiness"), "readiness envelope should be present");
      assertEquals(1, json.path("readiness").path("schemaVersion").asInt(-1));
      assertEquals(
          "READY",
          json.path("readiness").path("components").path("ai").path("state").asText(""));
      assertEquals(
          "lifecycle_inference",
          json.path("readiness").path("components").path("ai").path("source").asText(""));
      assertEquals(
          "NOT_READY",
          json.path("readiness").path("components").path("embedding").path("state").asText(""));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status readiness maps missing worker probes to UNKNOWN")
  void statusReadinessMapsMissingProbeToUnknown() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService __onlineAi = OnlineAiService.unavailable();
    KnowledgeServerBootstrap mockKs = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient mockClient = mock(KnowledgeClient.class);
    stubWorkerReady(mockKs);
    when(mockKs.client()).thenReturn(mockClient);
    when(mockClient.getWorkerOperationalView(TestRequestContexts.internal()))
        .thenReturn(testWorkerView(true, 3, 0, null, null));

    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi)
            .knowledgeServer(mockKs)
            .perSourceSearch(mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class))
            .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());
      assertEquals(
          "DEGRADED",
          json.path("readiness").path("components").path("ai").path("state").asText(""));
      assertEquals(
          "UNKNOWN",
          json.path("readiness").path("components").path("embedding").path("state").asText(""));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status decouples AI readiness from worker probes (inference offline + embedding ready)")
  void statusReadinessDecouplesAiFromEmbeddingProbe() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService __onlineAi = OnlineAiService.unavailable();
    KnowledgeServerBootstrap mockKs = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient mockClient = mock(KnowledgeClient.class);
    stubWorkerReady(mockKs);
    when(mockKs.client()).thenReturn(mockClient);
    when(mockClient.getWorkerOperationalView(TestRequestContexts.internal()))
        .thenReturn(testWorkerView(true, 3, 0, null, true));

    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi)
            .knowledgeServer(mockKs)
            .perSourceSearch(mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class))
            .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());
      assertFalse(json.path("aiReady").asBoolean(true), "aiReady alias should follow inference lifecycle");
      assertTrue(
          json.path("embeddingReady").asBoolean(false),
          "embeddingReady alias should follow embedding readiness component");
      assertEquals(
          "DEGRADED",
          json.path("readiness").path("components").path("ai").path("state").asText(""));
      assertEquals(
          "inference.offline",
          json.path("readiness").path("components").path("ai").path("reasonCode").asText(""));
      assertEquals(
          "READY",
          json.path("readiness").path("components").path("embedding").path("state").asText(""));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status maps inference STARTING to AI NOT_READY while embedding can remain ready")
  void statusReadinessInferenceStarting() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService onlineAi = mock(OnlineAiService.class);
    when(onlineAi.isAvailable()).thenReturn(false);
    when(onlineAi.isStartingUp()).thenReturn(true);
    OnlineAiService __onlineAi = onlineAi;

    // Inference capability in PENDING state → maps to STARTING in lifecycle snapshot
    var inferenceCap = new io.justsearch.app.services.lifecycle.InferenceCapability(true);
    // PENDING is the initial state for configured=true — no transition needed

    KnowledgeServerBootstrap mockKs = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient mockClient = mock(KnowledgeClient.class);
    stubWorkerReady(mockKs);
    when(mockKs.client()).thenReturn(mockClient);
    when(mockClient.getWorkerOperationalView(TestRequestContexts.internal()))
        .thenReturn(testWorkerView(true, 3, 0, null, true));

    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi)
            .knowledgeServer(mockKs)
            .perSourceSearch(mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class))
            .inferenceCapability(inferenceCap)
            .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());
      assertFalse(json.path("aiReady").asBoolean(true), "aiReady alias should be false while inference starts");
      assertEquals(
          "NOT_READY",
          json.path("readiness").path("components").path("ai").path("state").asText(""));
      assertEquals(
          "inference.starting",
          json.path("readiness").path("components").path("ai").path("reasonCode").asText(""));
      assertEquals(
          "READY",
          json.path("readiness").path("components").path("embedding").path("state").asText(""));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status maps idle PENDING inference to OFFLINE, not STARTING")
  void statusReadinessPendingInferenceOfflineWhenRuntimeIdle() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService onlineAi = mock(OnlineAiService.class);
    when(onlineAi.isAvailable()).thenReturn(false);
    when(onlineAi.isStartingUp()).thenReturn(false);

    var inferenceCap = new io.justsearch.app.services.lifecycle.InferenceCapability(true);

    KnowledgeServerBootstrap mockKs = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient mockClient = mock(KnowledgeClient.class);
    stubWorkerReady(mockKs);
    when(mockKs.client()).thenReturn(mockClient);
    when(mockClient.getWorkerOperationalView(TestRequestContexts.internal()))
        .thenReturn(testWorkerView(true, 3, 0, null, true));

    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(onlineAi)
            .knowledgeServer(mockKs)
            .perSourceSearch(mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class))
            .inferenceCapability(inferenceCap)
            .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());
      assertEquals(
          "DEGRADED",
          json.path("readiness").path("components").path("ai").path("state").asText(""));
      assertEquals(
          "inference.offline",
          json.path("readiness").path("components").path("ai").path("reasonCode").asText(""));
      assertEquals(
          "inference.offline",
          json.path("lifecycle").path("reason_code").asText(""));
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status includes indexStatusReason when Worker is ready but status fetch throws")
  void statusIncludesIndexStatusReasonWhenWorkerThrows() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService __onlineAi = OnlineAiService.unavailable();

    // Mock a KnowledgeServerBootstrap that is "ready" but throws on status fetch.
    KnowledgeServerBootstrap mockKs = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient mockClient = mock(KnowledgeClient.class);
    stubWorkerReady(mockKs);
    when(mockKs.client()).thenReturn(mockClient);
    when(mockClient.getWorkerOperationalView(TestRequestContexts.internal()))
        .thenThrow(new RuntimeException("Worker unreachable (test)"));

    LocalApiServer server = LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi)
        .knowledgeServer(mockKs)
            .perSourceSearch(mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class))
        .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());

      // indexStatusReason must be present and contain the exception class + message.
      assertTrue(json.has("indexStatusReason"), "indexStatusReason must be present when Worker throws");
      String reason = json.get("indexStatusReason").asText();
      assertTrue(reason.contains("RuntimeException"), "indexStatusReason must include exception class");
      assertTrue(reason.contains("Worker unreachable (test)"), "indexStatusReason must include exception message");

      // Fallback defaults must be set. Since tempdoc 384 moved WorkerOperationalView to a nested
      // "worker" key (previously @JsonUnwrapped), indexHealthy + indexState live under
      // worker.core.* rather than at the top level.
      assertFalse(
          json.path("worker").path("core").path("indexHealthy").asBoolean(true),
          "worker.core.indexHealthy must be false");
      assertEquals(
          "UNAVAILABLE", json.path("worker").path("core").path("indexState").asText());
    } finally {
      server.stop();
    }
  }

  @Test
  @DisplayName("/api/status degrades indexServing readiness when throughput is stalled under active work")
  void statusReadinessDegradesIndexServingWhenThroughputStalls() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    UiSettingsStore settingsStore =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));

    OnlineAiService __onlineAi = OnlineAiService.unavailable();
    KnowledgeServerBootstrap mockKs = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient mockClient = mock(KnowledgeClient.class);
    stubWorkerReady(mockKs);
    when(mockKs.client()).thenReturn(mockClient);
    when(mockClient.getWorkerOperationalView(TestRequestContexts.internal()))
        .thenReturn(testWorkerView(true, 3, 0, null, true, 8, 4, 2, "STALLED"));

    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore, tempDir.resolve("index")).onlineAiService(__onlineAi)
            .knowledgeServer(mockKs)
            .perSourceSearch(mock(io.justsearch.app.services.worker.SearchPerSourceExecutor.class))
            .build();
    try {
      HttpResponse<String> resp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());
      assertEquals(
          "DEGRADED",
          json.path("readiness").path("components").path("indexServing").path("state").asText(""));
      assertEquals(
          "worker.throughput_stalled",
          json.path("readiness").path("components").path("indexServing").path("reasonCode").asText(""));
    } finally {
      server.stop();
    }
  }

  // Tempdoc 419 C3 V2 P1: an end-to-end "telemetry readiness DEGRADED" integration test was
  // considered here mirroring statusReadinessDegradesIndexServingWhenThroughputStalls(). It
  // requires constructing a real LocalTelemetry with every head-side MetricCatalog
  // (HeadApiMetricCatalog, HeadGpuMetricCatalog, HeadHttpInflightMetricCatalog, etc.) so
  // LocalApiServer can wire its instruments. The setup cost outweighs the marginal coverage
  // — TelemetryHealthClassifierTest already exhaustively covers the classification rules,
  // TelemetryHealthControllerTest verifies the existing /api/telemetry/health surface still
  // works after the helper extraction, and the StatusLifecycleHandler "case TELEMETRY" arm
  // is a 5-line delegate to the classifier. Phase 3 (gpu-saturated) will land an integration
  // test that exercises the readiness-envelope wiring path with a synthetic monitor result;
  // the same pattern can backfill telemetry coverage if needed.

  /** Creates a WorkerOperationalView with specific key fields and defaults for everything else. */
  private static WorkerOperationalView testWorkerView(
      boolean indexHealthy, long indexedDocuments, long pendingJobs,
      Boolean aiReady, Boolean embeddingReady) {
    return testWorkerView(
        indexHealthy, indexedDocuments, pendingJobs, aiReady, embeddingReady, pendingJobs, 0, 0, "");
  }

  /** Creates a WorkerOperationalView with key throughput and queue state overrides. */
  private static WorkerOperationalView testWorkerView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      Boolean aiReady,
      Boolean embeddingReady,
      long pendingJobsCount,
      long processingJobsCount,
      long pendingReadyJobsCount,
      String throughputWindowState) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(indexHealthy, indexedDocuments, pendingJobs, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationViewBuilder.builder()
            .pendingJobsCount(pendingJobsCount)
            .processingJobsCount(processingJobsCount)
            .pendingReadyJobsCount(pendingReadyJobsCount)
            .migrationEnumerator(MigrationGenerationView.empty().migrationEnumerator())
            .build())
        .compatibility(CompatibilityStatusView.empty())
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.0, throughputWindowState))
        .searchConfig(SearchConfigView.empty())
        .aiReady(aiReady)
        .embeddingReady(embeddingReady)
        .build();
  }

  private static void assertSchemaV1(JsonNode json) {
    // Exact field set: catches accidental additions or removals
    assertExactFields(json, "health root",
        "schema_version", "observed_at", "lifecycle", "components");

    assertEquals(1, json.path("schema_version").asInt(-1));
    String observedAt = json.path("observed_at").asText("");
    assertFalse(observedAt.isBlank(), "observed_at must be non-empty");
    assertDoesNotThrow(() -> Instant.parse(observedAt), "observed_at must be ISO-8601");

    JsonNode lifecycle = json.path("lifecycle");
    assertTrue(lifecycle.isObject(), "lifecycle must be an object");
    assertExactFields(lifecycle, "lifecycle", "state", "reason_code", "message");
    assertFalse(lifecycle.path("state").asText("").isBlank(), "lifecycle.state must be present");
    assertReasonCodeIfPresent(lifecycle.path("reason_code").asText(null));

    JsonNode components = json.path("components");
    assertTrue(components.isObject(), "components must be an object");
    assertExactFields(components, "components", "head", "worker", "inference");
    assertComponent(components.path("head"));
    assertComponent(components.path("worker"));
    assertComponent(components.path("inference"));
  }

  private static void assertComponent(JsonNode component) {
    assertTrue(component.isObject(), "component must be an object");
    assertExactFields(component, "component", "state", "reason_code");
    assertFalse(component.path("state").asText("").isBlank(), "component.state must be present");
    assertReasonCodeIfPresent(component.path("reason_code").asText(null));
  }

  private static void assertExactFields(JsonNode node, String context, String... expected) {
    var actual = new java.util.ArrayList<String>();
    for (var entry : node.properties()) {
      actual.add(entry.getKey());
    }
    assertThat(actual)
        .as("Exact field set for %s", context)
        .containsExactlyInAnyOrder(expected);
  }

  private static void assertReasonCodeIfPresent(String reasonCode) {
    if (reasonCode == null || reasonCode.isBlank()) {
      return;
    }
    assertTrue(REASON_CODE.matcher(reasonCode).matches(), "Invalid reason_code format: " + reasonCode);
    assertTrue(LifecycleReasonCode.isKnown(reasonCode), "Unknown reason_code: " + reasonCode);
  }

  private static void stubWorkerReady(KnowledgeServerBootstrap mockKs) {
    var cap = new io.justsearch.app.services.lifecycle.WorkerCapability();
    cap.transition(io.justsearch.app.api.lifecycle.CapabilityHealth.READY, null);
    when(mockKs.workerCapability()).thenReturn(cap);
    when(mockKs.isReady()).thenReturn(true);
  }

}
