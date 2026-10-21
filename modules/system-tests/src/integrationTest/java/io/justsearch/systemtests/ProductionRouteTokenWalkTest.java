/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Every route on a fully booted production Engine inherits the authentication boundary. */
class ProductionRouteTokenWalkTest {
  private static final Set<String> PUBLIC = Set.of("/api/health", "/api/runtime/live",
      "/api/runtime/ready", "/api/status", "/api/runtime/manifest",
      "/.well-known/justsearch/manifest.json");

  @Test
  void walksEveryRegisteredRouteOnTheRealProductionEngine() throws Exception {
    var backend = new IsolatedBackendFixture().withSystemProperty("justsearch.prod", "true");
    try {
      backend.start();
      var mapper = new ObjectMapper();
      var manifest = mapper.readTree(Files.readString(backend.dataDir().resolve("runtime/manifest.json")));
      String token = manifest.path("head").path("sessionToken").asText();
      assertFalse(token.isBlank());
      String base = "http://127.0.0.1:" + backend.port();
      try (var client = HttpClient.newHttpClient()) {
        var routesResponse = send(client, base, "GET", "/api/meta/routes", token);
        assertEquals(200, routesResponse.statusCode(), routesResponse.body());
        var routes = mapper.readTree(routesResponse.body()).path("routes");
        Set<String> seen = new HashSet<>();
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (var route : routes) {
          String method = route.path("method").asText();
          String pattern = route.path("path").asText();
          seen.add(method + " " + pattern);
          String path = pattern.replaceAll("\\{[^}]+\\}", "probe").replace("*", "probe");
          boolean exempt = method.equals("OPTIONS") ||
              ((method.equals("GET") || method.equals("HEAD")) && PUBLIC.contains(pattern));
          checks.add(() -> {
            var response = client.send(request(base, method, path, null), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
              if (exempt) assertNotEquals(401, response.statusCode(), method + " " + pattern);
              else assertEquals(401, response.statusCode(), method + " " + pattern);
              assertFalse(new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains(token), method + " " + pattern);
            }
          });
        }
        for (String path : PUBLIC) assertTrue(seen.contains("GET " + path), path);
        assertTrue(seen.contains("GET /infra/capabilities/stream"), "must include ResourceApiModule");
        assertAll(checks);
        assertEquals(401, send(client, base, "GET", "/api/mcp/token", null).statusCode());
        assertEquals(404, send(client, base, "GET", "/api/mcp/token", token).statusCode());
      }
    } finally { backend.stop(); }
  }

  private static HttpResponse<String> send(HttpClient client, String base, String method,
      String path, String token) throws Exception {
    return client.send(request(base, method, path, token), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest request(String base, String method, String path, String token) {
    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
        .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) request.header("X-JustSearch-Session", token);
    return request.build();
  }
}
