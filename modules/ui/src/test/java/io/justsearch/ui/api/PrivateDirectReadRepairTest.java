/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** HTTP regression for the packaged window's private run and indexed-document read boundary. */
final class PrivateDirectReadRepairTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"/api/chat/sessions/last", "/api/chat/sessions", "/api/chat/sessions/run",
      "/api/chat/sessions/run/transcript", "/api/chat/sessions/run/events", "/api/chat/runs/live",
      "/api/chat/conversations", "/api/chat/conversations/run/history", "/api/chat/agent/history",
      "/api/chat/agent/history/batch", "/api/thread/run", "/api/presence", "/api/chat/approval",
      "/api/chat/runs/run/observe", "/api/chat/agent/run/attach", "/api/chat/agent/run/ag-ui"})
  void everyRunStoreReadRequiresTheWindowWhileHeadlessRemainsOperator(String route) throws Exception {
    boolean observer = route.endsWith("/observe") || route.endsWith("/attach") || route.endsWith("/ag-ui");
    for (String credential : Arrays.asList("window", null)) {
      var app = application();
      try (var client = HttpClient.newHttpClient()) {
        WindowAuthority.install(app, credential);
        WindowAuthority.installMutationGuard(app);
        io.javalin.http.Handler handler = ctx -> ctx.json(Map.of("private", "transcript"));
        if (observer) app.post(route, handler); else app.get(route, handler);
        app.options(route, ctx -> ctx.status(204));
        app.start("127.0.0.1", 0);
        assertEquals(204, client.send(HttpRequest.newBuilder(uri(app, route))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var request = HttpRequest.newBuilder(uri(app, route)).header("X-JustSearch-Client-Kind", "WEBVIEW");
        if (observer) request.POST(HttpRequest.BodyPublishers.ofString("{}")); else request.GET();
        assertEquals(credential == null ? 200 : 403,
            client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode(), route);
        assertEquals(200, client.send(request.header("X-JustSearch-Window", "window").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      } finally { app.stop(); }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"preview", "enumeration", "chunks", "presence", "resolve"})
  void directReadsProtectPathsAndReservedCollections(String operation) throws Exception {
    String privatePath = io.justsearch.configuration.InternalCollections.dataDirectory().resolve("agent-history/run.md").toString();
    String reserved = "tagged.md", ordinary = "/documents/ordinary.md";
    var docs = mock(DocumentService.class, call -> {
      if (call.getMethod().getName().equals("listAllDocumentIds")) {
        boolean publicScope = call.getArguments().length == 4 && Boolean.FALSE.equals(call.getArgument(2));
        var ids = publicScope ? List.of(ordinary) : List.of(privatePath, reserved, ordinary);
        return CompletableFuture.completedFuture(new DocumentService.DocumentIdPage(ids, ids.size(), 1));
      }
      return RETURNS_DEFAULTS.answer(call);
    });
    for (String id : List.of(privatePath, reserved, ordinary)) {
      var metadata = id.equals(reserved) ? Map.<String, Object>of("collection", "agent-history", "path", id) : Map.<String, Object>of("path", id);
      when(docs.fetchSlice(eq(id), anyInt(), anyInt(), any())).thenReturn(CompletableFuture.completedFuture(
          new DocumentService.DocumentSlice(id, "private content", metadata, true, false, 15, 15, "ok")));
      when(docs.fetch(eq(id), any())).thenReturn(CompletableFuture.completedFuture(new DocumentService.DocumentRecord(id, "private content", metadata)));
    }
    when(docs.fetchBatch(anyList(), any())).thenAnswer(call -> {
      Map<String, DocumentService.DocumentRecord> result = new LinkedHashMap<>();
      for (String id : (List<String>)call.getArgument(0)) result.put(id, docs.fetch(id, null).toCompletableFuture().join());
      return CompletableFuture.completedFuture(result);
    });
    when(docs.retrieveContextWithMeta(anyString(), anySet(), anyInt(), any())).thenReturn(CompletableFuture.completedFuture(
        new DocumentService.ContextResult("private content", 1, 1, 1, List.of(), "BM25", "ok", false, List.of())));
    when(docs.checkPresence(anyList(), any())).thenAnswer(call -> {
      Map<String, DocumentService.Presence> states = new LinkedHashMap<>();
      for (String id : (List<String>)call.getArgument(0)) states.put(id, DocumentService.Presence.PRESENT);
      return CompletableFuture.completedFuture(states);
    });
    var controller = new PreviewController(docs, Duration.ofSeconds(5));
    var app = application();
    try (var client = HttpClient.newHttpClient()) {
      WindowAuthority.install(app, "window");
      WindowAuthority.installMutationGuard(app);
      app.get("/api/preview", controller::handlePreview);
      app.get("/api/debug/chunks", new ChunkInfoController(docs, null)::handleGetChunkInfo);
      app.post("/api/document/{id}/resolve-address", new ResolveAddressController(docs)::handle);
      app.post(PreviewController.PRESENCE_PATH, controller::handlePresence);
      app.post(PreviewController.DOCUMENT_IDS_PATH, controller::handleListDocumentIds);
      app.start("127.0.0.1", 0);
      if (operation.equals("preview")) {
        for (String id : List.of(privatePath, reserved, ordinary)) {
          var request = HttpRequest.newBuilder(uri(app, "/api/preview?docId=" + URLEncoder.encode(id, StandardCharsets.UTF_8))).GET();
          assertEquals(id.equals(ordinary) ? 200 : 403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode(), id);
          assertEquals(200, client.send(request.header("X-JustSearch-Window", "window").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
      } else if (operation.equals("chunks") || operation.equals("presence")) {
        for (String id : List.of(privatePath, reserved, ordinary)) {
          var request = operation.equals("chunks")
              ? HttpRequest.newBuilder(uri(app, "/api/debug/chunks?docId=" + URLEncoder.encode(id, StandardCharsets.UTF_8))).GET()
              : HttpRequest.newBuilder(uri(app, PreviewController.PRESENCE_PATH)).POST(HttpRequest.BodyPublishers.ofString(
                  new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("docIds", List.of(id)))));
          assertEquals(id.equals(ordinary) ? 200 : 403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode(), id);
          assertEquals(200, client.send(request.header("X-JustSearch-Window", "window").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
      } else if (operation.equals("resolve")) {
        var address = HttpRequest.newBuilder(uri(app, "/api/document/tagged.md/resolve-address"))
            .POST(HttpRequest.BodyPublishers.ofString("{\"coords\":\"canonical\",\"docId\":\"tagged.md\",\"startChar\":0,\"endChar\":1}"));
        assertEquals(403, client.send(address.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, client.send(address.header("X-JustSearch-Window", "window").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      } else {
        var request = HttpRequest.newBuilder(uri(app, PreviewController.DOCUMENT_IDS_PATH))
            .POST(HttpRequest.BodyPublishers.ofString("{\"offset\":0,\"limit\":100}"));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var json = new tools.jackson.databind.ObjectMapper().readTree(response.body());
        assertEquals(List.of(ordinary), new tools.jackson.databind.ObjectMapper().convertValue(json.get("docIds"), List.class));
        assertEquals(1, json.path("totalCount").asInt());
        verify(docs, never()).fetchBatch(anyList(), any());
        assertTrue(client.send(request.header("X-JustSearch-Window", "window").build(), HttpResponse.BodyHandlers.ofString()).body().contains("tagged.md"));
      }
    } finally { app.stop(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"resolve-hash", "path-label"})
  void pathHashReadsCannotDisclosePrivateSourceIdentifiers(String route) throws Exception {
    var indexing = mock(io.justsearch.app.api.IndexingService.class);
    String history = io.justsearch.configuration.InternalCollections.dataDirectory().resolve("agent-history/run.md").toString();
    when(indexing.resolvePathHash(anyString(), any())).thenReturn(Map.of("found", true, "path", history));
    var controller = new IndexingController(() -> indexing, null, null, null);
    var app = application();
    try (var client = HttpClient.newHttpClient()) {
      WindowAuthority.install(app, "window");
      WindowAuthority.installMutationGuard(app);
      if (route.equals("resolve-hash")) app.post("/api/library/" + route, controller::handleResolvePathHash);
      else app.post("/api/library/" + route, controller::handlePathLabel);
      app.start("127.0.0.1", 0);
      var request = HttpRequest.newBuilder(uri(app, "/api/library/" + route))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"pathHash\":\"" + "a".repeat(64) + "\"}"));
      assertEquals(403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(200, client.send(request.header("X-JustSearch-Window", "window").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    } finally { app.stop(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"core.read-document", "core.resolve-path-hash"})
  void genericDocumentReadNeedsTheWindowBeforeRelativeResolutionOrReceiptCreation(String operation) throws Exception {
    var dispatcher = mock(io.justsearch.agent.api.registry.OperationDispatcher.class);
    when(dispatcher.dispatch(any(), anyString(), any(), any(), any())).thenReturn(
        io.justsearch.agent.api.registry.OperationResult.success("private transcript"));
    var controller = new OperationsController(List.of(new io.justsearch.agent.tools.AgentToolsOperationCatalog(),
        new io.justsearch.app.services.registry.operations.CoreOperationCatalog()), dispatcher);
    var app = application();
    try (var client = HttpClient.newHttpClient()) {
      WindowAuthority.install(app, "window");
      WindowAuthority.installMutationGuard(app);
      app.post("/api/operations/{id}/invoke", controller::handleInvoke);
      app.start("127.0.0.1", 0);
      var request = HttpRequest.newBuilder(uri(app, "/api/operations/" + operation + "/invoke"))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
              "{\"args\":{\"path\":\"documents/app-data/agent-history/run.md\"}}"));
      assertEquals(403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      verifyNoInteractions(dispatcher);
      assertEquals(200, client.send(request.header("X-JustSearch-Window", "window").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    } finally { app.stop(); }
  }

  @org.junit.jupiter.api.Test
  void sessionAuthenticationPrecedesThePrivateReadGuard() throws Exception {
    var app = application();
    try (var client = HttpClient.newHttpClient()) {
      WindowAuthority.install(app, "window");
      app.before(ctx -> {
        if (!"session".equals(ctx.header("X-JustSearch-Session"))) throw new io.javalin.http.UnauthorizedResponse();
      });
      WindowAuthority.installMutationGuard(app);
      app.get("/api/chat/sessions/last", ctx -> ctx.json(Map.of("private", "transcript")));
      app.start("127.0.0.1", 0);
      var request = HttpRequest.newBuilder(uri(app, "/api/chat/sessions/last")).GET();
      assertEquals(401, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      request.header("X-JustSearch-Session", "session");
      assertEquals(403, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      request.header("X-JustSearch-Window", "window");
      assertEquals(200, client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    } finally { app.stop(); }
  }

  private static Javalin application() {
    return Javalin.create(config -> { config.showJavalinBanner = false; config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });
  }
  private static URI uri(Javalin app, String route) { return URI.create("http://127.0.0.1:" + app.port() + route); }
}
