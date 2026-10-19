package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentRunQueries;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class StoreIdAmendmentsHttpTest {
  @TempDir Path tmp;

  @Test
  void historyRefusesExternalBatchIdsBeforeQuerying() throws Exception {
    Path root = tmp.resolve("data/file-operations");
    var log = new FileOperationLog(root);
    String journal = "{\"batchId\":\"private-sentinel\",\"operations\":[],\"executed\":[]}";
    Path beside = Files.writeString(root.getParent().resolve("sentinel.json"), journal);
    Path above = Files.writeString(tmp.resolve("sentinel.json"), journal);
    var queries = mock(AgentRunQueries.class);
    when(queries.operationDetail(anyString())).thenAnswer(call -> log.readBatch(call.getArgument(0)));
    var sessions = new AgentSessionController(() -> queries, null);
    var app = app();
    try {
      app.get("/history/{batchId}", sessions::handleHistoryDetail);
      app.post("/undo", sessions::handleUndo);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String id : List.of("../sentinel", "../../sentinel", "..\\sentinel", "..\\..\\sentinel",
            "...", "a.", "a ", "CON", "nul.txt", "C:sentinel", "a\u0000b")) {
          String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
          {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/history/" + encoded)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                "history " + id + ": " + response.statusCode() + " " + response.body());
            assertFalse(response.body().contains("private-sentinel"));
            verifyNoInteractions(queries);
            assertEquals(journal, Files.readString(beside));
            assertEquals(journal, Files.readString(above));
          }
        }
      }
    } finally {
      app.stop();
    }
  }

  @Test
  void undoRefusesUnsafeExecutionIdsBeforeQuerying() throws Exception {
    var queries = mock(AgentRunQueries.class);
    when(queries.undoOperation(anyString(), anyString(), any()))
        .thenReturn(io.justsearch.agent.api.registry.OperationResult.success("undone"));
    var controller = new AgentSessionController(() -> queries, null);
    var app = app();
    try {
      app.post("/undo", controller::handleUndo);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String id : List.of("../sentinel", "../../sentinel", "..\\sentinel", "...", "a.", "a ", "CON", "nul.txt")) {
          var response = client.send(post(app, "/undo", Map.of("toolName", "core_file_operations", "executionId", id)),
              HttpResponse.BodyHandlers.ofString());
          assertEquals(400, response.statusCode(), id + ": " + response.body());
          verifyNoInteractions(queries);
        }
      }
    } finally {
      app.stop();
    }
  }

  @Test
  void blankConversationIdRetainsLegacyHttpResponse() throws Exception {
    var engine = mock(ConversationEngine.class);
    var chat = new ChatController(new TestEngineExecutors(), engine, new SseWriter(null), null, mock(ConversationStore.class));
    var app = app();
    try {
      app.post("/dispatch", chat.dynamicHandler("/dispatch"));
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        var absent = client.send(post(app, "/dispatch", Map.of("shapeId", "core.free-chat", "prompt", "hello")),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, absent.statusCode());
        for (String blank : List.of("", " ", "\t\n")) {
          var response = client.send(post(app, "/dispatch",
              Map.of("conversationId", blank, "shapeId", "core.free-chat", "prompt", "hello")), HttpResponse.BodyHandlers.ofString());
          assertEquals(absent.statusCode(), response.statusCode(), response.body());
          assertEquals(absent.body(), response.body());
        }
      }
    } finally {
      app.stop();
      chat.shutdown();
    }
  }

  @Test
  void optionalBlankBodyIdsDispatchAsAbsent() throws Exception {
    var engine = mock(ConversationEngine.class);
    var agent = mock(AgentService.class);
    when(agent.isAvailable()).thenReturn(true);
    when(engine.wouldDiscardWhileLocked(any(), any())).thenAnswer(call -> {
      Map<?, ?> body = call.getArgument(1);
      return body.get("sessionId") != null || body.get("conversationId") != null;
    });
    var chat = new ChatController(new TestEngineExecutors(), engine, new SseWriter(null), null, mock(ConversationStore.class));
    var control = new AgentController(new TestEngineExecutors(), () -> agent, engine,
        new AgentSseWriter(new SseWriter(null), () -> agent, null), null);
    var runs = new RunStreamController(new TestEngineExecutors(),
        new io.justsearch.app.observability.stream.run.RunChannelRegistry(), chat);
    var app = app();
    try {
      app.post("/runs", runs::handleCreate);
      app.post("/dispatch", chat.dynamicHandler("/dispatch"));
      app.post("/fixed", chat.handler(new ConversationShapeRef("core.free-chat"), "/fixed"));
      app.post("/agent", control::handleRunStream);
      app.post("/approve", control::handleApprove);
      app.post("/reject", control::handleReject);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String blank : List.of("", " ", "\t\n")) {
          for (String key : List.of("sessionId", "conversationId")) {
            for (String route : List.of("/dispatch", "/fixed", "/agent", "/runs")) {
              clearInvocations(engine);
              var response = client.send(post(app, route,
                  Map.of(key, blank, "shapeId", route.equals("/agent") ? "core.agent-run" : "core.free-chat", "prompt", "hello")), HttpResponse.BodyHandlers.ofString());
              assertEquals(200, response.statusCode(), route + " " + key + ": " + response.body());
              var bodies = org.mockito.ArgumentCaptor.forClass(Map.class);
              if (route.equals("/agent")) verify(engine).run(any(), bodies.capture(), any(), any(), any());
              else verify(engine).runScoped(any(), bodies.capture(), any(), any(), any(), any());
              assertNull(bodies.getValue().get(key), "blank optional id must not reach the store as an id");
            }
          }
          for (String route : List.of("/approve", "/reject")) {
            clearInvocations(agent);
            var response = client.send(post(app, route, Map.of("sessionId", blank, "callId", "c")), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode(), response.body());
            if (route.equals("/approve")) verify(agent).tryApproveToolCall(null, "c");
            else verify(agent).tryRejectToolCall(null, "c", "User rejected");
          }
        }
      }
    } finally {
      app.stop();
      chat.shutdown();
      control.shutdown();
      runs.shutdown();
    }
  }

  @Test
  void runtimeInstanceIdsRefuseWindowsDevicesBeforeResolvingTheRoot() throws Exception {
    var publisher = mock(io.justsearch.ui.runtime.RuntimeManifestPublisher.class);
    when(publisher.instancesRoot()).thenReturn(tmp);
    var controller = new io.justsearch.ui.api.runtime.RuntimeInstancesController(publisher);
    var app = app();
    try {
      app.get("/instances/{id}", controller::handleGetOne);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        for (String id : List.of("CON", "prn", "AUX", "NUL", "COM1", "com9", "LPT1", "lpt9")) {
          var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/instances/" + id)).GET().build();
          var response = client.send(request, HttpResponse.BodyHandlers.ofString());
          assertEquals(400, response.statusCode(), id + ": " + response.body());
          verifyNoInteractions(publisher);
        }
      }
    } finally {
      app.stop();
    }
  }

  private static Javalin app() {
    return Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
  }

  private static HttpRequest post(Javalin app, String path, Map<String, Object> body) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(JsonMapper.builder().build().writeValueAsString(body))).build();
  }
}
