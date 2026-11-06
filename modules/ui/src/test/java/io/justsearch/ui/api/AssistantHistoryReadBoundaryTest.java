/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.javalin.Javalin;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.InternalCollections;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP requests exercise the packaged-window boundary, independent of caller hints. */
final class AssistantHistoryReadBoundaryTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
  private io.justsearch.configuration.resolved.ConfigStore previous;
  private io.justsearch.configuration.resolved.ConfigStore config;

  @org.junit.jupiter.api.BeforeEach void configure() {
    previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    config = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.configuration.resolved.TestResolvedConfigHelper.fromEntries(
            Map.of("justsearch.data.dir", directory.resolve("data").toString(),
                "justsearch.qu.enabled", "false", "justsearch.filter_norm.enabled", "false")));
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(config);
  }

  @org.junit.jupiter.api.AfterEach void restore() {
    io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(config, previous);
  }

  @Test void everyTokenOnlyReadRefusesHistoryScopesAndFolderPathsRegardlessOfHints() throws Exception {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    var indexed = mock(io.justsearch.app.services.worker.KnowledgeClient.class);
    var lease = mock(KnowledgeServerBootstrap.ClientLease.class);
    when(bootstrap.publicationLock()).thenReturn(config.publicationLock());
    when(bootstrap.acquireClientLease()).thenReturn(lease);
    when(bootstrap.captureClient()).thenReturn(lease);
    when(lease.client()).thenReturn(indexed);
    when(lease.withClient(any())).thenAnswer(invocation ->
        ((java.util.function.Function<io.justsearch.app.services.worker.KnowledgeClient, ?>) invocation.getArgument(0)).apply(indexed));
    when(indexed.search(any(io.justsearch.ipc.SearchRequest.class), any())).thenReturn(io.justsearch.ipc.SearchResponse.getDefaultInstance());
    var docs = mock(DocumentService.class);
    doCallRealMethod().when(docs).retrieveContext(any(), anyBoolean(), any());
    when(docs.retrieveContext(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        new DocumentService.ContextResult("private assistant history", 1, 1, 1, List.of(), "BM25", "ok", false, List.of())));
    var search = new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class));
    var retrieval = new RetrieveContextController(null, () -> docs, OnlineAiService.unavailable(), () -> "");
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try {
      WindowAuthority.install(app, "window-secret");
      app.post("/api/knowledge/search", search::handleSearch);
      app.post("/api/knowledge/retrieve-context", retrieval::handleRetrieveContext);
      app.post("/api/knowledge/match-citations", retrieval::handleMatchCitations);
      app.post("/api/knowledge/collection-facet", search::handleCollectionFacet);
      app.post("/api/knowledge/folders", search::handleListFolders);
      app.post("/api/knowledge/folder-files", search::handleListFolderFiles);
      app.start("127.0.0.1", 0);
      clearInvocations(bootstrap, docs, indexed);
      try (var client = HttpClient.newHttpClient()) {
        for (String route : List.of("search", "retrieve-context", "match-citations", "collection-facet", "folders", "folder-files")) {
          for (List<String> scope : List.of(List.of("agent-history"), List.of("personal", "agent-history"))) {
            var body = Map.of("query", "history", "filters", Map.of("collection", scope), "maxDocsScanned", 100);
            var response = send(client, app.port(), route, body, "spoofed-window-secret");
            assertEquals(403, response.statusCode(), route + ": " + response.body());
            assertEquals("ASSISTANT_HISTORY_PRIVATE", JSON.readTree(response.body()).path("errorCode").asString());
          }
        }
        for (String route : List.of("folders", "folder-files")) {
          for (List<String> projection : List.of(List.of("title"), List.of("content"))) {
            var response = send(client, app.port(), route,
                Map.of("folderPath", directory.resolve("data").resolve("agent-history").toString(),
                    "parentPath", directory.resolve("data").toString(), "projection", projection), null);
            assertEquals(403, response.statusCode(), response.body());
          }
        }
      }
      verifyNoInteractions(bootstrap, docs, indexed);
    } finally { app.stop(); }
  }

  @Test void theWindowCredentialAndHeadlessModeRetainHistoryScopeAuthority() throws Exception {
    for (String credential : Arrays.asList("window-secret", null)) {
      var app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      try {
        WindowAuthority.install(app, credential);
        app.post("/read", ctx -> {
          if (allow(ctx)) ctx.json(Map.of("allowed", true));
        });
        app.start("127.0.0.1", 0);
        try (var client = HttpClient.newHttpClient()) {
          var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/read"))
              .header("Content-Type", "application/json").header("X-JustSearch-Window", "window-secret")
              .POST(HttpRequest.BodyPublishers.ofString("{\"filters\":{\"collection\":[\"agent-history\"]}}")).build();
          assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
      } finally { app.stop(); }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void selectedOpaqueHistoryIdsAreRefusedBeforeRetrievalAndCitationReads(boolean missingText) throws Exception {
    var documents = mock(DocumentService.class);
    doCallRealMethod().when(documents).retrieveContext(any(), anyBoolean(), any());
    when(documents.fetchBatch(anyList(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        missingText ? Map.of() : Map.of("opaque-history", new DocumentService.DocumentRecord("opaque-history", "privatemarker",
            Map.of("collection", "agent-history")))));
    when(documents.fetchSlice(eq("opaque-history"), anyInt(), anyInt(), any())).thenReturn(
        java.util.concurrent.CompletableFuture.completedFuture(new DocumentService.DocumentSlice("opaque-history", "",
            Map.of("collection", "agent-history"), false, false, 0, 0, "missing text")));
    when(documents.retrieveContext(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        new DocumentService.ContextResult("privatemarker", 1, 1, 1, List.of(), "BM25", "ok", false, List.of())));
    var retrieval = new RetrieveContextController(null, () -> documents, OnlineAiService.unavailable(), () -> "");
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try {
      WindowAuthority.install(app, "window-secret");
      app.post("/api/knowledge/retrieve-context", retrieval::handleRetrieveContext);
      app.post("/api/knowledge/match-citations", retrieval::handleMatchCitations);
      app.post(PreviewController.PRESENCE_PATH, new PreviewController(documents, java.time.Duration.ofSeconds(5))::handlePresence);
      app.start("127.0.0.1", 0);
      try (var client = HttpClient.newHttpClient()) {
        var response = send(client, app.port(), "retrieve-context", Map.of("query", "private",
            "doc_ids", List.of("opaque-history")), null);
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("ASSISTANT_HISTORY_PRIVATE"));
        var citations = send(client, app.port(), "match-citations", Map.of("answer_text", "private",
            "chunk_refs", List.of(Map.of("parent_doc_id", "opaque-history", "chunk_index", 0))), null);
        assertEquals(403, citations.statusCode(), citations.body());
        var presence = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + PreviewController.PRESENCE_PATH))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                "{\"docIds\":[\"opaque-history\"]}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, presence.statusCode(), presence.body());
        verify(documents, never()).retrieveContext(any(), any());
        verify(documents, never()).matchCitationsAgainst(anyString(), anyList(), anyDouble(), any());
        verify(documents, never()).checkPresence(anyList(), any());
      }
    } finally { app.stop(); }
  }

  @Test void offlineOrdinaryIdsUseStoredCollectionBeforeAnyOwnershipDecision() throws Exception {
    String id = directory.resolve("offline/agent-history/ordinary.md").toString();
    var documents = mock(DocumentService.class);
    doCallRealMethod().when(documents).retrieveContext(any(), anyBoolean(), any());
    when(documents.fetchBatch(anyList(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        Map.of(id, new DocumentService.DocumentRecord(id, "ordinarymarker", Map.of("collection", "personal")))));
    when(documents.retrieveContext(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        new DocumentService.ContextResult("ordinarymarker", 1, 1, 1, List.of(), "BM25", "ok", false, List.of())));
    var retrieval = new RetrieveContextController(null, () -> documents, OnlineAiService.unavailable(), () -> "");
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try {
      WindowAuthority.install(app, "window-secret");
      app.post("/api/knowledge/retrieve-context", retrieval::handleRetrieveContext);
      app.start("127.0.0.1", 0);
      try (var client = HttpClient.newHttpClient()) {
        var response = send(client, app.port(), "retrieve-context", Map.of("query", "ordinary", "doc_ids", List.of(id)), null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("ordinarymarker"), response.body());
      }
    } finally { app.stop(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"address", "preview-without-text"})
  void everyDirectReadChecksItsReturnedOwnership(String arm) throws Exception {
    var documents = mock(DocumentService.class);
    doCallRealMethod().when(documents).retrieveContext(any(), anyBoolean(), any());
    when(documents.fetchSlice(eq("changed.md"), anyInt(), anyInt(), any())).thenReturn(
        java.util.concurrent.CompletableFuture.completedFuture(new DocumentService.DocumentSlice(
            "changed.md", "public", Map.of("collection", "personal"), true, false, 6, 6, "ok")));
    when(documents.fetch(eq("changed.md"), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        new DocumentService.DocumentRecord("changed.md", "private\nhistory", Map.of("collection", "agent-history"))));
    when(documents.fetchSlice(eq("missing-text"), anyInt(), anyInt(), any())).thenReturn(
        java.util.concurrent.CompletableFuture.completedFuture(new DocumentService.DocumentSlice(
            "missing-text", "", Map.of("collection", "agent-history"), false, false, 0, 0, "missing text")));
    when(documents.checkPresence(anyList(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
        Map.of("missing-text", DocumentService.Presence.PRESENT)));
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    try {
      WindowAuthority.install(app, "window-secret");
      app.post("/api/document/{id}/resolve-address", new ResolveAddressController(documents)::handle);
      app.get("/api/preview", new PreviewController(documents, java.time.Duration.ofSeconds(5))::handlePreview);
      app.start("127.0.0.1", 0);
      try (var client = HttpClient.newHttpClient()) {
        var request = arm.equals("address")
            ? HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/document/changed.md/resolve-address"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                    "{\"coords\":\"lines\",\"docId\":\"changed.md\",\"startLine\":0,\"endLine\":1}"))
            : HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/preview?docId=missing-text")).GET();
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("ASSISTANT_HISTORY_PRIVATE"), response.body());
      }
    } finally { app.stop(); }
  }

  private static boolean allow(io.javalin.http.Context ctx) throws Exception {
    try {
      return (boolean) WindowAuthority.class.getDeclaredMethod("allowKnowledgeRead",
          io.javalin.http.Context.class, Map.class).invoke(null, ctx, ctx.bodyAsClass(Map.class));
    } catch (NoSuchMethodException baselineHasNoHistoryGuard) { return true; }
  }

  private static HttpResponse<String> send(HttpClient client, int port, String route,
      Map<String, ?> body, String suppliedCredential) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/knowledge/" + route))
        .header("Content-Type", "application/json").header("X-JustSearch-Token", "manifest-token")
        .header("X-JustSearch-Client-Kind", "WEBVIEW").header("X-JustSearch-Transport", "BUTTON")
        .header("X-JustSearch-Client-Id", "local-webview");
    if (suppliedCredential != null) request.header("X-JustSearch-Window", suppliedCredential);
    return client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
