package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.justsearch.agent.api.AgentRunQueries;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationSafeIdsHttpTest {
  record Route(String method, String path, Handler handler) {}

  @Test
  void rejectsEncodedIdsOnEveryAddressedRouteWithoutTouchingStores() throws Exception {
    var store = mock(ConversationStore.class);
    var agent = mock(AgentService.class);
    var queries = mock(AgentRunQueries.class);
    try (var executors = new DefaultEngineExecutorRegistry()) {
      var chat = new ChatController(new TestEngineExecutors(), null, new SseWriter(null), null, store);
      var control = new AgentController(new TestEngineExecutors(), () -> agent, null, new AgentSseWriter(new SseWriter(null), () -> agent, null), null);
      var sessions = new AgentSessionController(() -> queries, null);
      var thread = new InteractionThreadController(
          mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), store, agent, executors);
      var app = Javalin.create(c -> {
        c.showJavalinBanner = false;
        c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      try {
        List<Route> routes = List.of(
            new Route("GET", "/conversations/{sessionId}", chat::handleLoadHistory),
            new Route("DELETE", "/conversations/{sessionId}", chat::handleDeleteConversation),
            new Route("POST", "/conversations/{sessionId}/branch", chat::handleBranchConversation),
            new Route("POST", "/conversations/{sessionId}/context-floor", chat::handleSetContextFloor),
            new Route("DELETE", "/conversations/{sessionId}/context-floor", chat::handleClearContextFloor),
            new Route("POST", "/conversations/{sessionId}/title", chat::handleSetTitle),
            new Route("DELETE", "/conversations/{sessionId}/title", chat::handleClearTitle),
            new Route("POST", "/conversations/{sessionId}/auto-title", chat::handleAutoTitle),
            new Route("POST", "/conversations/{sessionId}/compact", chat::handleCompact),
            new Route("POST", "/conversations/{sessionId}/summary", chat::handleEditContextFloorSummary),
            new Route("POST", "/conversations/{sessionId}/messages/m/exclude", chat::handleToggleMessageExcluded),
            new Route("POST", "/conversations/{sessionId}/sources/exclude", chat::handleToggleSourceExcluded),
            new Route("GET", "/sessions/{id}", sessions::handleSessionDetail),
            new Route("GET", "/sessions/{id}/events", sessions::handleSessionEvents),
            new Route("GET", "/sessions/{id}/transcript", sessions::handleSessionTranscript),
            new Route("POST", "/sessions/{id}/resume", control::handleResumeSessionStream),
            new Route("POST", "/sessions/{id}/fork", control::handleForkSessionStream),
            new Route("DELETE", "/sessions/{id}", control::handleCancelSession),
            new Route("POST", "/agent/{sessionId}/attach", control::handleAttachStream),
            new Route("POST", "/agent/{sessionId}/ag-ui", control::handleAgUiAttachStream),
            new Route("GET", "/thread/{id}", thread::handleGet),
            new Route("POST", "/thread/{id}/events", thread::handlePostEvent));
        for (Route route : routes) {
          switch (route.method()) {
            case "GET" -> app.get(route.path(), route.handler());
            case "POST" -> app.post(route.path(), route.handler());
            case "DELETE" -> app.delete(route.path(), route.handler());
            default -> throw new AssertionError(route.method());
          }
        }
        app.start(0);
        try (var client = HttpClient.newHttpClient()) {
          for (Route route : routes) {
            for (String id : List.of("a%2Fb", "a%5Cb", "%2E%2E", "..%5C..", "a%00b", "a%2F..%5Cb", "C%3Aoutside", "%20", "%2E", "%2Fabsolute", "%5C%5Chost%5Cshare", "a%0Ab")) {
              String path = route.path().replace("{sessionId}", id).replace("{id}", id);
              var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                  .header("Content-Type", "application/json")
                  .method(route.method(), HttpRequest.BodyPublishers.ofString(
                      "{\"title\":\"x\",\"floorMessageId\":\"m\",\"summary\":\"x\"}")).build();
              var response = client.send(request, HttpResponse.BodyHandlers.ofString());
              assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                  route.method() + " " + path + ": " + response.statusCode() + " " + response.body());
              // Jetty may reject an ambiguous URI before dispatch; application refusals retain the API shape.
              if (response.body().startsWith("{")) {
                assertTrue(JsonMapper.builder().build().readTree(response.body()).has("error"));
              }
              verifyNoInteractions(store, agent, queries);
            }
          }
        }
      } finally {
        app.stop();
        chat.shutdown();
        control.shutdown();
        thread.shutdown();
      }
    }
  }

  @Test
  void refusesUnsafeBodyAndQueryIdsBeforeStartingWork() throws Exception {
    var store = mock(ConversationStore.class);
    var agent = mock(AgentService.class);
    var engine = mock(io.justsearch.app.services.conversation.ConversationEngine.class);
    var registry = mock(io.justsearch.app.observability.stream.run.RunChannelRegistry.class);
    var chat = new ChatController(new TestEngineExecutors(), engine, new SseWriter(null), null, store);
    var control = new AgentController(new TestEngineExecutors(), () -> agent, engine,
        new AgentSseWriter(new SseWriter(null), () -> agent, null), null);
    var tools = new AgentToolsController(() -> agent, null);
    var runs = new RunStreamController(new TestEngineExecutors(), registry, chat);
    var thread = new InteractionThreadController(
        mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), store, agent, new TestEngineExecutors());
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
      try {
      app.post("/dispatch", chat.handler(new io.justsearch.agent.api.registry.ConversationShapeRef("core.free-chat"), "/dispatch"));
      app.post("/dynamic", chat.dynamicHandler("/dynamic"));
      app.post("/presence", thread::handlePresenceRun);
      app.post("/runs", runs::handleCreate);
      app.post("/agent", control::handleRunStream);
      app.post("/approve", control::handleApprove);
      app.post("/reject", control::handleReject);
      app.get("/approval", control::handleApproval);
      app.post("/autonomy", control::handleAutonomy);
      app.post("/budget", control::handleRaiseBudget);
      app.post("/budget-decision", control::handleBudgetDecision);
      app.post("/context-decision", control::handleContextDecision);
      app.post("/steer", control::handleSteeringDirective);
      app.post("/tool-result", tools::handleVirtualToolResult);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String id : List.of("", " ", "..", "../outside", "..\\..", "C:outside", "x\u0000y", "a/b", "a\\b")) {
          String body = JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
              "sessionId", id, "conversationId", id, "shapeId", "core.free-chat",
              "callId", "c", "prompt", "hello"));
          for (String route : List.of("dispatch", "dynamic", "presence", "runs", "agent", "approve", "reject", "autonomy",
              "budget", "budget-decision", "context-decision", "steer", "tool-result", "approval")) {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/" + route
                + (route.equals("approval") ? "?callId=c&sessionId=" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8) : "")));
            var request = route.equals("approval") ? builder.GET().build()
                : builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode(), route + ": " + response.body());
            assertEquals("INVALID_REQUEST", JsonMapper.builder().build().readTree(response.body()).get("errorCode").asString());
            verifyNoInteractions(store, agent, engine, registry);
          }
        }
      }
    } finally {
      app.stop();
      chat.shutdown();
      control.shutdown();
      runs.shutdown();
      thread.shutdown();
    }
  }

  @Test
  void realStoreSentinelsSurviveEncodedReadWriteBranchAndDelete(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
    var parent = java.nio.file.Files.createDirectory(tmp.resolve("parent"));
    var root = java.nio.file.Files.createDirectory(parent.resolve("conversations"));
    var beside = java.nio.file.Files.createDirectory(parent.resolve("beside"));
    var above = java.nio.file.Files.createDirectory(tmp.resolve("above"));
    for (var dir : List.of(beside, above)) {
      java.nio.file.Files.writeString(dir.resolve("sentinel"), "keep");
      java.nio.file.Files.writeString(dir.resolve("messages.jsonl"), "{\"role\":\"user\",\"content\":\"private\"}\n");
    }
    var chat = new ChatController(new TestEngineExecutors(), null, new SseWriter(null), null,
        new io.justsearch.app.services.conversation.FileConversationStore(root));
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
      try {
      app.get("/conversations/{sessionId}", chat::handleLoadHistory);
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.post("/conversations/{sessionId}/context-floor", chat::handleSetContextFloor);
      app.post("/conversations/{sessionId}/branch", chat::handleBranchConversation);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String id : List.of("..%2Fbeside", "..%5Cbeside", "..%2F..%2Fabove", "..%5C..%5Cabove")) {
          for (String action : List.of("DELETE", "GET", "context-floor", "branch")) {
            String suffix = action.equals("branch") ? "/branch?fromMsgId=m"
                : action.equals("context-floor") ? "/context-floor" : "";
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/conversations/" + id + suffix))
                .header("Content-Type", "application/json")
                .method(action.equals("GET") || action.equals("DELETE") ? action : "POST",
                    HttpRequest.BodyPublishers.ofString("{\"floorMessageId\":\"m\"}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            for (var dir : List.of(beside, above)) {
              assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("sentinel")), "sentinel removed: " + action + " " + id);
              assertEquals("keep", java.nio.file.Files.readString(dir.resolve("sentinel")));
              assertEquals("{\"role\":\"user\",\"content\":\"private\"}\n", java.nio.file.Files.readString(dir.resolve("messages.jsonl")));
              assertFalse(java.nio.file.Files.exists(dir.resolve("meta.json")));
            }
            assertTrue(java.nio.file.Files.isDirectory(root));
            try (var children = java.nio.file.Files.list(root)) {
              assertEquals(0, children.count(), "refusal must not create a conversation");
            }
            assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, action + " " + id);
          }
        }
      }
    } finally {
      app.stop();
      chat.shutdown();
    }
  }

  @Test
  void validDeleteAndReadKeepTheirResponses() throws Exception {
    var store = mock(ConversationStore.class);
    var chat = new ChatController(new TestEngineExecutors(), null, new SseWriter(null), null, store);
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
      try {
      app.get("/conversations/{sessionId}", chat::handleLoadHistory);
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        String url = "http://localhost:" + app.port() + "/conversations/uc-123";
        var read = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode());
        assertTrue(JsonMapper.builder().build().readTree(read.body()).get("messages").isArray());
        var deleted = client.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode());
        var body = JsonMapper.builder().build().readTree(deleted.body());
        assertTrue(body.get("ok").asBoolean());
        assertEquals(0, body.get("agentRunsDeleted").asInt());
        verify(store).deleteSession("uc-123");
      }
    } finally {
      app.stop();
      chat.shutdown();
    }
  }
}
