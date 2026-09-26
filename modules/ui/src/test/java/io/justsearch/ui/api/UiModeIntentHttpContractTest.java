/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 923 capability-realization closure — mode intent through the real loopback HTTP stack.
 *
 * <p>The controller-level tests prove merge rules directly, while frontend tests prove header
 * production. This contract joins those halves through {@link LocalApiServer}: the production CORS
 * and Host filters, route registration, JSON controller, whole-document store, and a fresh-server
 * reload all participate. A stale mode request must keep its unrelated patch without restoring the
 * older mode.
 */
final class UiModeIntentHttpContractTest {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String ORIGIN = "http://localhost:5173";
  private static final String CLIENT_ID = "ui-mode-http-contract";

  private io.justsearch.app.observability.operations.SqliteOperationStore operations;
  @AfterEach void closeOperations() throws Exception { if (operations != null) operations.close(); }

  @Test
  void staleModeIntentSurvivesCorsRoutingAndRestartWithoutDroppingOtherFields(@TempDir Path tmp)
      throws Exception {
    Path settingsPath = tmp.resolve("settings.json");
    Path indexPath = Files.createDirectories(tmp.resolve("index"));
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    operations = new io.justsearch.app.observability.operations.SqliteOperationStore(tmp.resolve("operations.db"));
    LocalApiServer server = start(settingsPath, indexPath);
    try {
      HttpResponse<String> preflight = preflight(client, server);
      assertEquals(200, preflight.statusCode(), preflight.body());
      String allowHeaders =
          preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("");
      assertTrue(
          allowHeaders.toLowerCase(java.util.Locale.ROOT)
              .contains(SettingsController.UI_MODE_INTENT_HEADER.toLowerCase(java.util.Locale.ROOT)),
          "preflight must allow the mode-intent header: " + allowHeaders);
      assertEquals(
          ORIGIN,
          preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(""),
          "the real CORS filter must admit the shipped dev origin");

      assertEquals(
          200,
          post(client, server, "{\"ui\":{\"mode\":\"simple\"}}", 1).statusCode());
      assertEquals(
          200,
          post(client, server, "{\"ui\":{\"mode\":\"advanced\"}}", 2).statusCode());
      HttpResponse<String> stale =
          post(
              client,
              server,
              "{\"ui\":{\"mode\":\"simple\",\"theme\":\"dark\"}}",
              1);
      assertEquals(200, stale.statusCode(), stale.body());

      var invalid = (tools.jackson.databind.node.ObjectNode) JSON.readTree("{\"ui\":{\"theme\":\"light\"}}");
      invalid.set("witness", JSON.readTree(get(client, server).body()).get("witness"));
      invalid.put("operationKey", "invalid");
      var invalidResponse = sendFrozen(client, server, JSON.writeValueAsString(invalid), 3);
      assertEquals(400, invalidResponse.statusCode());
      assertEquals("OPERATION_KEY_INVALID", JSON.readTree(invalidResponse.body()).path("errorCode").asText());
      invalid.remove("operationKey");
      var missingKey = sendFrozen(client, server, JSON.writeValueAsString(invalid), 3);
      assertEquals(400, missingKey.statusCode());
      assertEquals("OPERATION_KEY_INVALID", JSON.readTree(missingKey.body()).path("errorCode").asText());

      String staleKey = io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC());
      invalid.put("operationKey", staleKey);
      assertEquals(200, post(client, server, "{\"ui\":{\"theme\":\"dark\"}}", 3).statusCode());
      var conflict = sendFrozen(client, server, JSON.writeValueAsString(invalid), 3);
      assertEquals(409, conflict.statusCode());
      var conflictBody = JSON.readTree(conflict.body());
      assertEquals("VERSION_CONFLICT", conflictBody.path("errorCode").asText());
      assertEquals(staleKey, conflictBody.path("operationKey").asText());
      assertTrue(conflictBody.path("operationRecordId").asLong() > 0);
      assertEquals("FAILED", conflictBody.path("state").asText());
      org.junit.jupiter.api.Assertions.assertFalse(conflictBody.has("witness"));

      assertModeAndTheme(get(client, server), "advanced", "dark");
    } finally {
      server.stop();
    }

    assertTrue(Files.isRegularFile(settingsPath), "the HTTP write must reach the durable store");
    LocalApiServer restarted = start(settingsPath, indexPath);
    try {
      assertModeAndTheme(get(client, restarted), "advanced", "dark");
    } finally {
      restarted.stop();
    }
  }

  private LocalApiServer start(Path settingsPath, Path indexPath) {
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath);
    var config = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings.inspect().settings()));
    var owner = new io.justsearch.app.services.settings.SettingsCommitCoordinator(settings, config,
        () -> { throw new AssertionError("Unexpected settings restart"); }, candidate -> {
          var projection = io.justsearch.app.services.settings.SettingsV2Projection.toSettingsV2(candidate, settings.mode());
          return io.justsearch.agent.api.registry.OperationResult.success("Settings committed", java.util.Map.of(
              "ui", projection.ui(), "llm", projection.llm(), "indexPaths", projection.indexPaths(),
              "settingsMode", projection.settingsMode()));
        });
    var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operations,
        java.time.Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
            io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), owner);
    return LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settings, indexPath)
        .settingsService(new io.justsearch.app.services.settings.SettingsServiceImpl(settings, runner))
        .build();
  }

  private static HttpResponse<String> preflight(HttpClient client, LocalApiServer server)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(server))
            .timeout(Duration.ofSeconds(3))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", ORIGIN)
            .header("Access-Control-Request-Method", "POST")
            .header(
                "Access-Control-Request-Headers",
                "Content-Type, " + SettingsController.UI_MODE_INTENT_HEADER)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(
      HttpClient client, LocalApiServer server, String body, long sequence) throws Exception {
    var input = (tools.jackson.databind.node.ObjectNode) JSON.readTree(body);
    input.set("witness", JSON.readTree(get(client, server).body()).get("witness"));
    input.put("operationKey", io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC()));
    String frozen = JSON.writeValueAsString(input);
    var response = sendFrozen(client, server, frozen, sequence);
    var retry = sendFrozen(client, server, frozen, sequence);
    assertEquals(response.statusCode(), retry.statusCode(), retry.body());
    var strict = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    var first = strict.readValue(response.body(), io.justsearch.app.api.settings.SettingsV2.class);
    var replay = strict.readValue(retry.body(), io.justsearch.app.api.settings.SettingsV2.class);
    assertEquals("COMPLETE", first.state());
    assertEquals(first.witness(), replay.witness());
    assertEquals(first.operationKey(), replay.operationKey());
    org.junit.jupiter.api.Assertions.assertNull(replay.ui(), "replay cannot resample current settings");
    return response;
  }

  private static HttpResponse<String> sendFrozen(HttpClient client, LocalApiServer server, String body, long sequence)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(server))
            .timeout(Duration.ofSeconds(3))
            .header("Origin", ORIGIN)
            .header("Content-Type", "application/json")
            .header(SettingsController.UI_MODE_INTENT_HEADER, CLIENT_ID + ":" + sequence)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(HttpClient client, LocalApiServer server) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(server))
            .timeout(Duration.ofSeconds(3))
            .header("Origin", ORIGIN)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(LocalApiServer server) {
    return URI.create("http://127.0.0.1:" + server.getPort() + "/api/settings/v2");
  }

  private static void assertModeAndTheme(
      HttpResponse<String> response, String expectedMode, String expectedTheme) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    JsonNode ui = JSON.readTree(response.body()).path("ui");
    assertEquals(expectedMode, ui.path("mode").asText(), response.body());
    assertEquals(expectedTheme, ui.path("theme").asText(), response.body());
  }
}
