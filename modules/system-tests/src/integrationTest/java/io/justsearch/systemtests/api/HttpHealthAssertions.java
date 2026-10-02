package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Health preconditions for fixture-owned HTTP suites; contract drift must fail, never skip. */
final class HttpHealthAssertions {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpHealthAssertions() {}

  static void assertIndexReady(HttpClient client, int port) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health"))
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    assertIndexReady(MAPPER.readTree(response.body()));
  }

  static void assertSchema2(JsonNode health) {
    assertEquals(2, health.path("schema_version").asInt(-1), "Expected /api/health schema 2");
    JsonNode components = health.path("components");
    for (String component : List.of("api", "index", "encoders", "generative")) {
      JsonNode state = components.path(component).path("state");
      assertTrue(state.isTextual(), "Missing component state: " + component);
      assertFalse(state.asText("").isBlank(), "Empty component state: " + component);
    }
  }

  static void assertIndexReady(JsonNode health) {
    assertSchema2(health);
    assertEquals("READY", health.path("components").path("api").path("state").asText(""));
    assertEquals(
        "READY", health.path("components").path("index").path("state").asText(""),
        "Isolated Engine index must be ready: " + health);
  }
}
