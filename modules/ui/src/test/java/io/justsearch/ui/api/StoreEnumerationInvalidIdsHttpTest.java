/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.justsearch.agent.AgentLoopService;
import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StoreEnumerationInvalidIdsHttpTest {
  @TempDir Path tmp;

  @Test
  void validConversationCanBranchReadAndDeleteBesideInvalidFolders() throws Exception {
    Path root = Files.createDirectory(tmp.resolve("conversations"));
    var store = new FileConversationStore(root);
    store.createSession("valid", "core.free-chat");
    store.appendMessage("valid", "core.free-chat", Map.of("role", "user", "content", "keep"));
    String messageId = (String) store.loadHistory("valid").getFirst().get("id");
    var invalidIds = plantInvalidFolders(root);
    var chat = new ChatController(new TestEngineExecutors(), null, new SseWriter(null), null, store);
    var app = server();
    try (var client = HttpClient.newHttpClient()) {
      app.get("/conversations", chat::handleListSessions);
      app.get("/conversations/{sessionId}", chat::handleLoadHistory);
      app.post("/conversations/{sessionId}/branch", chat::handleBranchConversation);
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.start(0);
      assertEquals("valid", send(client, app, "GET", "/conversations", 200).get("sessions").get(0).get("sessionId").asString());
      assertEquals("keep", send(client, app, "GET", "/conversations/valid", 200).get("messages").get(0).get("content").asString());
      String child = send(client, app, "POST", "/conversations/valid/branch?fromMsgId=" + messageId, 200).get("sessionId").asString();
      assertEquals("keep", send(client, app, "GET", "/conversations/" + child, 200).get("messages").get(0).get("content").asString());
      // A real child still prevents deletion; invalid folders are not children.
      assertAll(
          () -> assertEquals(child, send(client, app, "DELETE", "/conversations/valid", 409).get("childSessionIds").get(0).asString()),
          () -> assertTrue(Files.exists(root.resolve("valid").resolve("meta.json"))));
      assertTrue(send(client, app, "DELETE", "/conversations/" + child, 200).get("ok").asBoolean());
      assertTrue(send(client, app, "DELETE", "/conversations/valid", 200).get("ok").asBoolean());
      assertFalse(Files.exists(root.resolve("valid")));
      assertFalse(Files.exists(root.resolve(child)));
      assertEquals(0, send(client, app, "GET", "/conversations", 200).get("sessions").size());
      assertInvalidFilesUnchanged(root, invalidIds);
    } finally {
      app.stop();
      chat.shutdown();
    }
  }

  @Test
  void runListsPresenceThreadAndDeleteWorkBesideInvalidRunFolders() throws Exception {
    Path root = tmp.resolve("runs");
    var runs = new AgentRunStore(root);
    runs.runEvents().writeRunMeta("run-valid", Map.of(
        "sessionId", "run-valid", "conversationId", "valid", "shapeId", "core.agent-run",
        "background", true, "state", "COMPLETED", "startedAt", "2026-10-20T00:00:00Z",
        "updatedAt", "2026-10-20T00:01:00Z", "messages", List.of(Map.of("role", "user", "content", "keep"))));
    var invalidIds = plantInvalidFolders(root);
    var agent = new AgentLoopService(null, OperationCatalog.of("core", List.of()),
        mock(OperationDispatcher.class), mock(AgentToolEmitter.class), null, null, runs, null);
    var store = new FileConversationStore(tmp.resolve("conversations"));
    var executors = new TestEngineExecutors();
    var chat = new ChatController(executors, null, new SseWriter(null), null, store, () -> null, () -> agent);
    var sessions = new AgentSessionController(() -> agent, null);
    var thread = new InteractionThreadController(
        mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), store, agent, executors);
    var app = server();
    try (var client = HttpClient.newHttpClient()) {
      app.get("/conversations", chat::handleListSessions);
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.get("/sessions", sessions::handleListSessions);
      app.get("/presence", thread::handlePresence);
      app.get("/thread/{id}", thread::handleGet);
      app.start(0);
      assertAll(
          () -> assertEquals("valid", send(client, app, "GET", "/conversations", 200).get("sessions").get(0).get("sessionId").asString()),
          () -> assertEquals("run-valid", send(client, app, "GET", "/sessions", 200).get("sessions").get(0).get("sessionId").asString()),
          () -> assertEquals(1, send(client, app, "GET", "/presence", 200).get("runs").size()),
          () -> assertTrue(send(client, app, "GET", "/thread/valid", 200).get("events").toString().contains("keep")));
      assertEquals(1, send(client, app, "DELETE", "/conversations/valid", 200).get("agentRunsDeleted").asInt());
      assertFalse(Files.exists(root.resolve("run-valid")));
      assertEquals(0, send(client, app, "GET", "/thread/valid", 200).get("events").size());
      assertInvalidFilesUnchanged(root, invalidIds);
    } finally {
      app.stop();
      chat.shutdown();
      thread.shutdown();
    }
  }

  private static Javalin server() {
    return Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
  }

  private static JsonNode send(HttpClient client, Javalin app, String method, String path, int expected) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
        .method(method, HttpRequest.BodyPublishers.noBody()).build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(expected, response.statusCode(), method + " " + path + ": " + response.body());
    return JsonMapper.builder().build().readTree(response.body());
  }

  private static List<String> plantInvalidFolders(Path root) throws Exception {
    var ids = List.of(" ", "\t", "\u2028", "CON", "NUL.txt", "bad\\id", "bad:stream", "trailing.");
    for (String id : ids) {
      Path dir = Files.createDirectory(root.resolve(id));
      Files.writeString(dir.resolve("meta.json"), "not metadata");
      Files.writeString(dir.resolve("sentinel"), "keep");
    }
    return ids;
  }

  private static void assertInvalidFilesUnchanged(Path root, List<String> ids) throws Exception {
    for (String id : ids) {
      assertEquals("not metadata", Files.readString(root.resolve(id).resolve("meta.json")));
      assertEquals("keep", Files.readString(root.resolve(id).resolve("sentinel")));
    }
  }
}
