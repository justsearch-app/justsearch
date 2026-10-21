/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Walks the routes registered by the real production server, including future routes. */
class ProductionRouteTokenBoundaryTest {
  private static final String TOKEN = "route-walk-secret";
  private static final Set<String> PUBLIC = Set.of("/api/health", "/api/runtime/live",
      "/api/runtime/ready", "/api/status", "/api/runtime/manifest",
      "/.well-known/justsearch/manifest.json");
  @TempDir Path dir;

  @Test
  void everyRegisteredProductionRouteRequiresTokenExceptPublicReadsAndOptions() throws Exception {
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(
        Map.of("justsearch.prod", "true"))));
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(false);
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, dir.resolve("settings.json")),
          dir.resolve("index")).onlineAiService(ai).sessionToken(TOKEN).build();
      try (var client = HttpClient.newHttpClient()) {
        String base = "http://127.0.0.1:" + server.getPort();
        var routesResponse = send(client, base, "GET", "/api/meta/routes", TOKEN);
        assertEquals(200, routesResponse.statusCode(), routesResponse.body());
        var routes = new ObjectMapper().readTree(routesResponse.body()).path("routes");
        assertTrue(routes.size() > 100, "must walk the real router");
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (var route : routes) {
          String method = route.path("method").asText();
          String pattern = route.path("path").asText();
          String path = pattern.replaceAll("\\{[^}]+\\}", "probe").replace("*", "probe");
          boolean exempt = method.equals("OPTIONS") ||
              ((method.equals("GET") || method.equals("HEAD")) && PUBLIC.contains(pattern));
          checks.add(() -> {
            var response = client.send(request(base, method, path, null), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
              if (exempt) assertNotEquals(401, response.statusCode(), method + " " + pattern);
              else assertEquals(401, response.statusCode(), method + " " + pattern);
              assertFalse(new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains(TOKEN), method + " " + pattern);
            }
          });
        }
        assertAll(checks);
      } finally { server.stop(); }
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  @Test
  void removedBootstrapReturns401WithoutTokenAnd404WithToken() throws Exception {
    var previous = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of("justsearch.prod", "true"))));
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      var ai = mock(OnlineAiService.class);
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY, dir.resolve("settings.json")),
          dir.resolve("index")).onlineAiService(ai).sessionToken(TOKEN).build();
      try (var client = HttpClient.newHttpClient()) {
        String base = "http://127.0.0.1:" + server.getPort();
        assertEquals(401, send(client, base, "GET", "/api/mcp/token", null).statusCode());
        var authenticated = send(client, base, "GET", "/api/mcp/token", TOKEN);
        assertEquals(404, authenticated.statusCode());
        assertFalse(authenticated.body().contains(TOKEN));
      } finally { server.stop(); }
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  private static HttpResponse<String> send(HttpClient client, String base, String method,
      String path, String token) throws Exception {
    return client.send(request(base, method, path, token), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest request(String base, String method, String path, String token) {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
        .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) request.header(LocalApiServer.SESSION_TOKEN_HEADER, token);
    return request.build();
  }
}
