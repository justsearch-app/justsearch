/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.justsearch.app.api.IndexingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The automatic path-label read replaced {@code POST /api/operations/core.resolve-path-hash/invoke}.
 * For malformed or invalid input it must answer with the same client-error status and body shape.
 * Real HTTP through the production JSON mapper; no subprocess or filesystem cleanup.
 */
class PathLabelErrorContractTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test void malformedAndInvalidInputMatchTheReplacedInvocationErrorShape() throws Exception {
    var indexing = mock(IndexingService.class);
    var labels = new IndexingController(() -> indexing, null, null, null);
    var operations = new OperationsController(
        List.of(new io.justsearch.app.services.registry.operations.CoreOperationCatalog()),
        mock(io.justsearch.agent.api.registry.OperationDispatcher.class));
    JsonNode replaced = post(operations::handleInvoke, "/api/operations/{id}/invoke", "{");
    assertEquals(400, replaced.get("status").asInt());
    for (String body : List.of("{", "", "[]", "{}", "{\"pathHash\":42}", "{\"pathHash\":\" \"}",
        "{\"pathHash\":\"abc\",\"path\":\"C:/secret\"}")) {
      JsonNode label = post(labels::handlePathLabel, "/api/library/path-label", body);
      assertEquals(400, label.get("status").asInt(), body + " => " + label);
      assertEquals(fields(replaced.get("body")), fields(label.get("body")), body + " => " + label);
      assertEquals(false, label.get("body").get("success").asBoolean(), body);
      assertEquals("BAD_REQUEST", label.get("body").get("errorClass").asString(), body);
    }
    verifyNoInteractions(indexing);
  }

  private static TreeSet<String> fields(JsonNode body) {
    return new TreeSet<>(body.propertyNames());
  }

  private static JsonNode post(Handler handler, String route, String body) throws Exception {
    var app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    app.post(route, handler);
    app.start("127.0.0.1", 0);
    try (var client = HttpClient.newHttpClient()) {
      var uri = URI.create("http://127.0.0.1:" + app.port() + route.replace("{id}", "core.resolve-path-hash"));
      var response = client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      return JSON.valueToTree(Map.of("status", response.statusCode(), "body", JSON.readTree(response.body())));
    } finally {
      app.stop();
    }
  }
}
