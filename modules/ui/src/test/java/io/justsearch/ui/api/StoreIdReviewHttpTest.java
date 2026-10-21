/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentRunQueries;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StoreIdReviewHttpTest {
  @Test
  void genericUndoRefusesUnsafeIdsBeforeApprovalOrDispatchEvenWithConfirmation() throws Exception {
    var dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.undo(any(), any(), any(), any(), any()))
        .thenAnswer(call -> {
          if (((java.util.Optional<?>) call.getArgument(3)).isEmpty()) {
            throw new io.justsearch.agent.api.registry.ConfirmationRequiredException(
                AgentToolsOperationCatalog.FILE_OPERATIONS,
                io.justsearch.agent.api.registry.GateBehavior.TYPED_CONFIRM,
                io.justsearch.agent.api.registry.ConfirmStrategy.None.INSTANCE,
                io.justsearch.agent.api.registry.SourceTier.TRUSTED);
          }
          return OperationResult.failure("Invalid store id");
        });
    var pending = mock(io.justsearch.app.services.intent.PendingAuthorizationStore.class);
    var controller = new OperationsController(List.of(new AgentToolsOperationCatalog()), dispatcher,
        java.time.Clock.systemUTC(), pending);
    var app = app();
    try {
      app.post("/api/undo/{id}", controller::handleUndo);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String id : List.of("../sentinel", "..\\..", "a/b", "a\\b", "C:stream", "a\u0000b",
            "COM¹", "LPT².txt", "CONIN$", "NUL .txt")) {
          assertAll(List.of("", "approved-capsule").stream().map(token ->
              (org.junit.jupiter.api.function.Executable) () -> {
            var response = client.send(post(app, "/api/undo/core.file-operations",
                Map.of("executionId", id, "confirmationToken", token)), HttpResponse.BodyHandlers.ofString());
            assertAll(
                () -> assertEquals(400, response.statusCode(), id + " token=" + token + ": " + response.body()),
                () -> assertDoesNotThrow(() -> verifyNoInteractions(dispatcher, pending)));
          }));
        }
      }
    } finally {
      app.stop();
    }
  }

  @Test
  void historyRefusesAdditionalWindowsDevicesBeforeStoreQuery() throws Exception {
    var queries = mock(AgentRunQueries.class);
    var controller = new AgentSessionController(() -> queries, null);
    var app = app();
    try {
      app.get("/history/{batchId}", controller::handleHistoryDetail);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String id : List.of("COM¹", "com².txt", "COM³", "LPT¹", "lpt².txt", "LPT³",
            "CONIN$", "conout$", "NUL .txt", "COM1  .log")) {
          String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
          var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/history/" + encoded)).GET().build();
          var response = client.send(request, HttpResponse.BodyHandlers.ofString());
          assertAll(
              () -> assertEquals(400, response.statusCode(), id + ": " + response.body()),
              () -> assertDoesNotThrow(() -> verifyNoInteractions(queries)));
        }
      }
    } finally {
      app.stop();
    }
  }

  private static Javalin app() {
    var app = Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    app.before(ctx -> ctx.attribute(RequestEngineContext.ATTRIBUTE, TestRequestContexts.browser()));
    return app;
  }

  private static HttpRequest post(Javalin app, String path, Map<String, Object> body) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder().build().writeValueAsString(body))).build();
  }
}
