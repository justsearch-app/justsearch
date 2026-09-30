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

  @Test
  void staleModeIntentSurvivesCorsRoutingAndRestartWithoutDroppingOtherFields(@TempDir Path tmp)
      throws Exception {
    Path settingsPath = tmp.resolve("settings.json");
    Path indexPath = Files.createDirectories(tmp.resolve("index"));
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
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

  private static LocalApiServer start(Path settingsPath, Path indexPath) {
    return LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsPath), indexPath)
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
