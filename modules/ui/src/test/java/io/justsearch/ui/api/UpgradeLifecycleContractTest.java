package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class UpgradeLifecycleContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void prepareFreezesMutationsKeepsReadsAndCommitRequestsOrderlyShutdown(@TempDir Path tmp)
      throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var shutdown = new CountDownLatch(1);
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .operationLeaseService(leases)
            .upgradeShutdownAction(shutdown::countDown)
            .build();
    try {
      HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpResponse<String> admitted =
          post(
              client,
              server,
              "/api/ui/ready",
              "{\"schema\":\"UI_READY_HANDSHAKE_V1\",\"runtime\":\"browser\","
                  + "\"apiSource\":\"url\"}");
      assertEquals(200, admitted.statusCode());
      assertEquals(0, leases.snapshot().activeLeases().size());

      HttpResponse<String> prepared = post(client, server, "/api/upgrade/prepare", "{}");
      assertEquals(200, prepared.statusCode());
      var preparation = JSON.readTree(prepared.body());
      String preparationId = preparation.get("preparationId").asText();
      assertTrue(!preparationId.isBlank());
      assertTrue(preparation.get("shutdownNonce").asText().length() >= 32);

      HttpResponse<String> rejected =
          post(client, server, "/api/settings/v2", "{\"ui\":{}}");
      assertEquals(503, rejected.statusCode());
      assertTrue(rejected.body().contains("UPGRADE_PREPARING"));

      HttpResponse<String> status =
          client.send(
              HttpRequest.newBuilder(uri(server, "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, status.statusCode());

      HttpResponse<String> wrongNonce =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              "{\"schemaVersion\":1,\"preparationId\":\""
                  + preparationId
                  + "\",\"shutdownNonce\":\"wrong\"}");
      assertEquals(409, wrongNonce.statusCode());

      HttpResponse<String> committed =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              capabilityBody(preparation));
      assertEquals(200, committed.statusCode());
      assertEquals(
          preparation.get("shutdownNonce").asText(),
          JSON.readTree(committed.body()).get("shutdownNonce").asText());
      assertTrue(shutdown.await(2, TimeUnit.SECONDS));
    } finally {
      server.stop();
    }
  }

  @Test
  void mustCompleteLeaseBlocksShutdownUntilReleased(@TempDir Path tmp) throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var active =
        leases.register("indexing.migration", OpCriticality.MUST_COMPLETE, 60, Map.of());
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .operationLeaseService(leases)
            .build();
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> prepared = post(client, server, "/api/upgrade/prepare", "{}");
      var preparation = JSON.readTree(prepared.body());

      HttpResponse<String> blocked =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              capabilityBody(preparation));
      assertEquals(409, blocked.statusCode());
      assertTrue(blocked.body().contains("indexing.migration"));

      active.close();
      HttpResponse<String> accepted =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              capabilityBody(preparation));
      assertEquals(200, accepted.statusCode());
    } finally {
      server.stop();
    }
  }

  @Test
  void interruptibleLeaseAlsoBlocksUntilOwnerAcknowledgesRelease(@TempDir Path tmp)
      throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var active =
        leases.register("agent.answer", OpCriticality.INTERRUPTIBLE, 60, Map.of());
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .operationLeaseService(leases)
            .build();
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> prepared = post(client, server, "/api/upgrade/prepare", "{}");
      var preparation = JSON.readTree(prepared.body());
      assertEquals(false, preparation.get("ready").asBoolean());

      HttpResponse<String> blocked =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              capabilityBody(preparation));
      assertEquals(409, blocked.statusCode());
      assertTrue(blocked.body().contains("agent.answer"));

      active.close();
      assertEquals(
          200,
          post(
                  client,
                  server,
                  "/api/upgrade/commit-shutdown",
                  capabilityBody(preparation))
              .statusCode());
    } finally {
      server.stop();
    }
  }

  @Test
  void prepareRequestsOwnerCancellationAndReportsAcknowledgement(@TempDir Path tmp)
      throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var ownerHandle =
        new java.util.concurrent.atomic.AtomicReference<
            io.justsearch.app.api.OperationLeaseHandle>();
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var active =
        leases.register(
            "agent.answer",
            OpCriticality.INTERRUPTIBLE_WITH_LOSS,
            60,
            Map.of("partialOutput", true),
            () -> {
              requests.incrementAndGet();
              ownerHandle.get().release(io.justsearch.app.api.OpLeaseOutcome.CANCELLED);
            });
    ownerHandle.set(active);
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .operationLeaseService(leases)
            .build();
    try {
      HttpResponse<String> prepared =
          post(HttpClient.newHttpClient(), server, "/api/upgrade/prepare", "{}");
      var body = JSON.readTree(prepared.body());

      assertEquals(200, prepared.statusCode());
      assertEquals(1, requests.get());
      assertTrue(body.get("ready").asBoolean());
      assertEquals(active.opId(), body.get("cancellationRequestedOpIds").get(0).asText());
      assertEquals(0, body.get("activeLeases").size());
    } finally {
      server.stop();
    }
  }

  @Test
  void reconciliationAttestsTheTargetProcessAndClosedOwnerSet(@TempDir Path tmp)
      throws Exception {
    writeReconcilingIntent(tmp);
    LocalApiServer server = reconciliationServer(tmp, true, true);
    try {
      Map<String, Object> request = reconciliationRequest();
      HttpResponse<String> reconciled =
          post(
              HttpClient.newHttpClient(),
              server,
              "/api/upgrade/reconcile",
              JSON.writeValueAsString(request));
      var body = JSON.readTree(reconciled.body());

      assertEquals(200, reconciled.statusCode());
      assertTrue(body.get("ready").asBoolean());
      assertTrue(body.get("headReady").asBoolean());
      assertTrue(body.get("workerReady").asBoolean());
      assertEquals("attempt-1", body.get("attemptId").asText());
      assertEquals("nonce-1", body.get("shutdownNonce").asText());
      assertEquals("2.0.0", body.get("targetVersion").asText());
      assertEquals(ProcessHandle.current().pid(), body.get("headPid").asLong());
      assertTrue(body.get("owners").size() > 0);
      body.get("owners").forEach(owner -> assertTrue(owner.get("healthy").asBoolean()));
    } finally {
      server.stop();
    }
  }

  @Test
  void reconciliationRejectsOwnerMismatch(@TempDir Path tmp) throws Exception {
    writeReconcilingIntent(tmp);
    LocalApiServer server = reconciliationServer(tmp, true, true);
    try {
      Map<String, Object> request = reconciliationRequest();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> owners = (List<Map<String, Object>>) request.get("owners");
      owners.get(0).put("formatVersion", 999);

      HttpResponse<String> rejected =
          post(
              HttpClient.newHttpClient(),
              server,
              "/api/upgrade/reconcile",
              JSON.writeValueAsString(request));

      assertEquals(409, rejected.statusCode());
      assertTrue(!JSON.readTree(rejected.body()).get("ready").asBoolean());
    } finally {
      server.stop();
    }
  }

  @Test
  void reconciliationReportsUnavailableHeadOrWorker(@TempDir Path tmp) throws Exception {
    writeReconcilingIntent(tmp);
    LocalApiServer headUnavailable = reconciliationServer(tmp, false, true);
    try {
      HttpResponse<String> response =
          post(
              HttpClient.newHttpClient(),
              headUnavailable,
              "/api/upgrade/reconcile",
              JSON.writeValueAsString(reconciliationRequest()));
      var body = JSON.readTree(response.body());
      assertEquals(503, response.statusCode());
      assertTrue(!body.get("ready").asBoolean());
      assertTrue(!body.get("headReady").asBoolean());
    } finally {
      headUnavailable.stop();
    }

    LocalApiServer workerUnavailable = reconciliationServer(tmp, true, false);
    try {
      HttpResponse<String> response =
          post(
              HttpClient.newHttpClient(),
              workerUnavailable,
              "/api/upgrade/reconcile",
              JSON.writeValueAsString(reconciliationRequest()));
      var body = JSON.readTree(response.body());
      assertEquals(503, response.statusCode());
      assertTrue(!body.get("ready").asBoolean());
      assertTrue(!body.get("workerReady").asBoolean());
    } finally {
      workerUnavailable.stop();
    }
  }

  @Test
  void deadEngineReconciliationAttestsAttemptWithoutInventingNonce(@TempDir Path tmp)
      throws Exception {
    Map<String, Object> request = deadEngineReconciliationRequest();
    writeDeadEngineIntent(tmp, request);
    LocalApiServer server = reconciliationServer(tmp, true, true);
    try {
      HttpResponse<String> response = post(HttpClient.newHttpClient(), server,
          "/api/upgrade/reconcile", JSON.writeValueAsString(request));
      assertEquals(200, response.statusCode());
      var body = JSON.readTree(response.body());
      assertTrue(body.get("ready").asBoolean());
      assertEquals("ENGINE_UNRECOVERABLE", body.get("stopEvidenceKind").asText());
      assertEquals("attempt-1", body.get("attemptId").asText());
      assertTrue(body.get("shutdownNonce") == null);
      assertEquals(ProcessHandle.current().pid(), body.get("headPid").asLong());
    } finally {
      server.stop();
    }
  }

  @Test
  void deadEngineReconciliationRejectsMixedEvidenceAndDifferentIdentity(@TempDir Path tmp)
      throws Exception {
    Map<String, Object> request = deadEngineReconciliationRequest();
    LocalApiServer server = reconciliationServer(tmp, true, true);
    try {
      for (String field : List.of("preparationId", "shutdownNonce", "shutdownReceipt",
          "headShutdownReceipt", "headPid")) {
        Map<String, Object> intent = writeDeadEngineIntent(tmp, request);
        intent.put(field, field.equals("headPid") ? 42 : "mixed");
        Files.writeString(tmp.resolve("upgrade/intent.v1.json"), JSON.writeValueAsString(intent));
        HttpResponse<String> response = post(HttpClient.newHttpClient(), server,
            "/api/upgrade/reconcile", JSON.writeValueAsString(request));
        assertEquals(409, response.statusCode(), field);
      }
      writeDeadEngineIntent(tmp, request);
      request.put("attemptId", "another-attempt");
      assertEquals(409, post(HttpClient.newHttpClient(), server,
          "/api/upgrade/reconcile", JSON.writeValueAsString(request)).statusCode());
      request.put("attemptId", "attempt-1");
      request.put("shutdownNonce", "invented-nonce");
      assertEquals(400, post(HttpClient.newHttpClient(), server,
          "/api/upgrade/reconcile", JSON.writeValueAsString(request)).statusCode());
    } finally {
      server.stop();
    }
  }

  @Test
  void reconciliationCannotSwitchBetweenPreparedAndDeadEngineEvidence(@TempDir Path tmp)
      throws Exception {
    LocalApiServer server = reconciliationServer(tmp, true, true);
    try {
      writeReconcilingIntent(tmp);
      assertEquals(409, post(HttpClient.newHttpClient(), server,
          "/api/upgrade/reconcile", JSON.writeValueAsString(deadEngineReconciliationRequest()))
          .statusCode());
      writeDeadEngineIntent(tmp, deadEngineReconciliationRequest());
      assertEquals(409, post(HttpClient.newHttpClient(), server,
          "/api/upgrade/reconcile", JSON.writeValueAsString(reconciliationRequest())).statusCode());
    } finally {
      server.stop();
    }
  }

  private static Map<String, Object> deadEngineReconciliationRequest() throws Exception {
    Map<String, Object> request = reconciliationRequest();
    request.remove("shutdownNonce");
    request.put("stopEvidenceKind", "ENGINE_UNRECOVERABLE");
    return request;
  }

  private static Map<String, Object> writeDeadEngineIntent(
      Path dataDir, Map<String, Object> request) throws Exception {
    Map<String, Object> intent = new LinkedHashMap<>(request);
    intent.remove("headPid");
    intent.remove("owners");
    intent.remove("stopEvidenceKind");
    intent.put("phase", "RECONCILING");
    intent.put("stopEvidence", Map.of("kind", "ENGINE_UNRECOVERABLE",
        "attemptId", "attempt-1", "enginePid", 42, "confirmedGoneAtEpochMs", 1000));
    Path path = dataDir.resolve("upgrade/intent.v1.json");
    Files.createDirectories(path.getParent());
    Files.writeString(path, JSON.writeValueAsString(intent));
    return intent;
  }

  private static LocalApiServer reconciliationServer(
      Path dataDir, boolean headReady, boolean workerReady) {
    return LocalApiServer.builder(
            new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
            dataDir.resolve("index"))
        .upgradeReconciliation(dataDir, () -> "2.0.0", () -> headReady, () -> workerReady)
        .build();
  }

  private static void writeReconcilingIntent(Path dataDir) throws Exception {
    Path path = dataDir.resolve("upgrade").resolve("intent.v1.json");
    Files.createDirectories(path.getParent());
    Files.writeString(
        path,
        JSON.writeValueAsString(
            Map.of(
                "schemaVersion", 1,
                "phase", "RECONCILING",
                "attemptId", "attempt-1",
                "shutdownNonce", "nonce-1",
                "sourceVersion", "1.0.0",
                "targetVersion", "2.0.0",
                "releaseSequence", 7)));
  }

  private static Map<String, Object> reconciliationRequest() throws Exception {
    List<Map<String, Object>> owners = new ArrayList<>();
    try (var in =
        UpgradeLifecycleContractTest.class.getResourceAsStream(
            "/governance/store-recoverability.v1.json")) {
      var stores = JSON.readTree(in).get("durableStores");
      stores.forEach(
          store -> {
            Map<String, Object> owner = new LinkedHashMap<>();
            owner.put("ownerId", store.get("id").asText());
            owner.put("formatVersion", store.get("currentVersion").asInt());
            owners.add(owner);
          });
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("schemaVersion", 1);
    request.put("attemptId", "attempt-1");
    request.put("shutdownNonce", "nonce-1");
    request.put("sourceVersion", "1.0.0");
    request.put("targetVersion", "2.0.0");
    request.put("releaseSequence", 7);
    request.put("headPid", ProcessHandle.current().pid());
    request.put("owners", owners);
    return request;
  }

  private static HttpResponse<String> post(
      HttpClient client, LocalApiServer server, String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(server, path))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String capabilityBody(tools.jackson.databind.JsonNode preparation) {
    return "{\"schemaVersion\":1,\"preparationId\":\""
        + preparation.get("preparationId").asText()
        + "\",\"shutdownNonce\":\""
        + preparation.get("shutdownNonce").asText()
        + "\"}";
  }

  private static URI uri(LocalApiServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.getPort() + path);
  }
}
