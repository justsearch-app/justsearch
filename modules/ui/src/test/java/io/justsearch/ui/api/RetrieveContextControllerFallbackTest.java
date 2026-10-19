/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.context.EngineContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** FP3/U3: fallback metadata survives the real HTTP controller and JSON boundary. */
final class RetrieveContextControllerFallbackTest {
  @Test
  void semanticBudgetFallbackPublishesReasonAndCause() throws Exception {
    var quality = retrieve("BM25", "SEMANTIC_BUDGET_EXCEEDED", "contended");
    assertEquals("BM25", quality.path("retrieval_mode").asText());
    assertEquals("SEMANTIC_BUDGET_EXCEEDED", quality.path("retrieval_mode_reason").asText());
    assertEquals("contended", quality.path("retrieval_mode_cause").asText());
  }

  @Test
  void hybridPublishesItsReasonWithoutAnEmptyCause() throws Exception {
    var quality = retrieve("HYBRID", "HYBRID_AVAILABLE", "");
    assertEquals("HYBRID", quality.path("retrieval_mode").asText());
    assertEquals("HYBRID_AVAILABLE", quality.path("retrieval_mode_reason").asText());
    assertFalse(quality.has("retrieval_mode_cause"));
  }

  private static JsonNode retrieve(String mode, String reason, String cause) throws Exception {
    var documents = mock(DocumentService.class);
    var result = new DocumentService.ContextResult(
        "BM25 context", 1, 1, 1, List.of(), mode, reason, false, List.of(),
        new DocumentService.QualitySignals(0.8f, 0.2f, 1f, 1, 1), cause);
    when(documents.retrieveContext(any(), any(EngineContext.class)))
        .thenReturn(CompletableFuture.completedFuture(result));
    var ai = mock(OnlineAiService.class);
    var controller = new RetrieveContextController(null, () -> documents, ai, () -> "");
    var app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    }).post("/api/knowledge/retrieve-context", controller::handleRetrieveContext).start(0);
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(
          HttpRequest.newBuilder(URI.create(
              "http://localhost:" + app.port() + "/api/knowledge/retrieve-context"))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"Hello\"}"))
              .build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      var body = new ObjectMapper().readTree(response.body());
      assertEquals("BM25 context", body.path("context").asText());
      assertEquals(1, body.path("total_found").asInt());
      return body.path("quality");
    } finally {
      app.stop();
    }
  }
}
