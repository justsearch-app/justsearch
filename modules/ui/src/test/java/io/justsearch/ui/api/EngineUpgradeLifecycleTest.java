/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End-to-end lifecycle coverage for one controller owning leases and engine admission. */
@Timeout(30)
final class EngineUpgradeLifecycleTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TOKEN = "engine-upgrade-test-token";

  @Test
  void sharedControllerFreezesReopensAndCommitsOnlyAfterOwnedLeaseDrains(@TempDir Path temporary)
      throws Exception {
    var admission = new EngineAdmissionController(2, 2, 1);
    var shutdown = new CountDownLatch(1);
    var shutdownPreparation = new AtomicReference<String>();
    var shutdownNonce = new AtomicReference<String>();
    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(),
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                temporary.resolve("index"))
            .operationLeaseService(admission)
            .engineAdmission(admission)
            .sessionToken(TOKEN)
            .upgradeShutdownAction(
                (preparationId, receiptNonce) -> {
                  shutdownPreparation.set(preparationId);
                  shutdownNonce.set(receiptNonce);
                  shutdown.countDown();
                })
            .build();
    try (var client = HttpClient.newHttpClient();
        var _ = admission.admit(TestRequestContexts.mcp("durable-owner"), false)) {
      // The HTTP control plane needs one of the two aggregate slots. Fill both only long enough to
      // prove that this exact controller enforces the process-wide bound, then release that probe.
      try (var _ = admission.admit(TestRequestContexts.mcp("capacity-probe"), false)) {
        EngineAdmissionException full =
            assertThrows(
                EngineAdmissionException.class,
                () -> admission.admit(TestRequestContexts.mcp("refused"), false));
        assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, full.reason());
      }

      JsonNode effectiveConfig =
          JSON.readTree(get(client, server, "/api/debug/effective-config").body());
      JsonNode limits = effectiveConfig.path("engineAdmission");
      assertEquals(2, limits.path("perContextLimit").asInt());
      assertEquals(2, limits.path("aggregateLimit").asInt());
      assertEquals(1, limits.path("retryAfterSeconds").asInt());
      assertEquals(1, limits.path("activeWorkCount").asInt(),
          "diagnostics exclude their own front work while preserving the other owner");

      assertEquals(200, post(client, server, "/api/ui/ready", readyBody()).statusCode());
      JsonNode firstPreparation =
          JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
      assertTrue(firstPreparation.path("admissionFrozen").asBoolean());

      HttpResponse<String> frozen = post(client, server, "/api/ui/ready", readyBody());
      assertEquals(503, frozen.statusCode());
      assertTrue(frozen.body().contains("UPGRADE_PREPARING"));
      HttpResponse<String> frozenStatus = get(client, server, "/api/status");
      assertEquals(503, frozenStatus.statusCode());
      assertTrue(frozenStatus.body().contains("UPGRADE_PREPARING"));
      assertEquals(200, get(client, server, "/api/health").statusCode());

      HttpResponse<String> refreshed = post(client, server, "/api/upgrade/prepare", "{}");
      assertEquals(200, refreshed.statusCode());
      JsonNode refreshedPreparation = JSON.readTree(refreshed.body());
      assertEquals(
          firstPreparation.path("preparationId").asText(),
          refreshedPreparation.path("preparationId").asText());
      assertEquals(
          firstPreparation.path("shutdownNonce").asText(),
          refreshedPreparation.path("shutdownNonce").asText());

      HttpResponse<String> cancelled =
          post(client, server, "/api/upgrade/cancel", capabilityBody(refreshedPreparation));
      assertEquals(200, cancelled.statusCode());
      assertTrue(JSON.readTree(cancelled.body()).path("cancelled").asBoolean());
      assertEquals(200, post(client, server, "/api/ui/ready", readyBody()).statusCode());

      var mustComplete =
          admission.register("indexing.migration", OpCriticality.MUST_COMPLETE, 60, Map.of());
      JsonNode secondPreparation;
      try {
        secondPreparation =
            JSON.readTree(post(client, server, "/api/upgrade/prepare", "{}").body());
        assertFalse(secondPreparation.path("ready").asBoolean());
        HttpResponse<String> blocked =
            post(
                client,
                server,
                "/api/upgrade/commit-shutdown",
                capabilityBody(secondPreparation));
        assertEquals(409, blocked.statusCode());
        assertTrue(blocked.body().contains("indexing.migration"));
      } finally {
        mustComplete.close();
      }

      HttpResponse<String> committed =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              capabilityBody(secondPreparation));
      assertEquals(200, committed.statusCode());
      assertTrue(shutdown.await(3, TimeUnit.SECONDS));
      assertEquals(secondPreparation.path("preparationId").asText(), shutdownPreparation.get());
      assertEquals(secondPreparation.path("shutdownNonce").asText(), shutdownNonce.get());
    } finally {
      server.stop();
    }
  }

  private static HttpResponse<String> post(
      HttpClient client, LocalApiServer server, String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(server, path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header(LocalApiServer.SESSION_TOKEN_HEADER, TOKEN)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(
      HttpClient client, LocalApiServer server, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(server, path))
            .timeout(Duration.ofSeconds(5))
            .header(LocalApiServer.SESSION_TOKEN_HEADER, TOKEN)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String readyBody() {
    return "{\"schema\":\"UI_READY_HANDSHAKE_V1\",\"runtime\":\"browser\","
        + "\"apiSource\":\"url\"}";
  }

  private static String capabilityBody(JsonNode preparation) {
    return "{\"schemaVersion\":1,\"preparationId\":\""
        + preparation.path("preparationId").asText()
        + "\",\"shutdownNonce\":\""
        + preparation.path("shutdownNonce").asText()
        + "\"}";
  }

  private static URI uri(LocalApiServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.getPort() + path);
  }
}
