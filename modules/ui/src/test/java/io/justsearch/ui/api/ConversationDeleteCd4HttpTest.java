/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.AgentService;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** CD4 wire contracts on a private HTTP server and private stores. */
class ConversationDeleteCd4HttpTest {
  @TempDir Path temp;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void everyLateControlWriteHasTheDeletedClientError() throws Exception {
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY), temp.resolve("index")).build();
      try {
        String base = "http://127.0.0.1:" + server.getPort();
        assertEquals(200, send(client, base, "/api/chat/conversations/gone", "DELETE", "").statusCode());
        var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for (var spec : List.of(
            List.of("/title", "POST", "{\"title\":\"late\"}"),
            List.of("/title", "DELETE", ""),
            List.of("/auto-title", "POST", "{}"),
            List.of("/branch?fromMsgId=message", "POST", "{}"),
            List.of("/context-floor", "POST", "{\"floorMessageId\":\"message\"}"),
            List.of("/context-floor", "DELETE", ""),
            List.of("/compact", "POST", "{\"floorMessageId\":\"message\"}"),
            List.of("/context-floor/summary", "POST", "{\"summaryText\":\"edited\"}"),
            List.of("/messages/message/exclude", "POST", "{\"excluded\":true}"),
            List.of("/sources/exclude", "POST", "{\"sourceId\":\"document\",\"excluded\":true}"))) {
          var response = send(client, base, "/api/chat/conversations/gone" + spec.get(0), spec.get(1), spec.get(2));
          checks.add(() -> assertAll(spec.get(0),
              () -> assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.body()),
              () -> assertEquals("CONVERSATION_DELETED", JSON.readTree(response.body()).path("errorCode").asText(), response.body())));
        }
        assertAll(checks);
      } finally { server.stop(); }
    }
  }

  @Test
  void lateAgentStreamHasTheDeletedReason() throws Exception {
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var agent = mock(AgentService.class);
      when(agent.isAvailable()).thenReturn(true);
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY), temp.resolve("index"))
          .agentService(agent).build();
      try {
        String base = "http://127.0.0.1:" + server.getPort();
        assertEquals(200, send(client, base, "/api/chat/conversations/gone", "DELETE", "").statusCode());
        var response = send(client, base, "/api/chat/agent", "POST",
            "{\"prompt\":\"late\",\"conversationId\":\"gone\",\"sessionId\":\"gone\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("CONVERSATION_DELETED"), response.body());
        assertFalse(response.body().contains("BAD_REQUEST"), response.body());
      } finally { server.stop(); }
    }
  }

  @Test
  void deletionMarkersKeepOnlyTheNewestBoundedWireWindow() throws Exception {
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY), temp.resolve("index")).build();
      try {
        String base = "http://127.0.0.1:" + server.getPort();
        for (int i = 0; i < 200; i++) {
          assertEquals(200, send(client, base, "/api/chat/conversations/gone-" + i, "DELETE", "").statusCode());
        }
        var response = send(client, base, "/api/chat/conversations?limit=1", "GET", "");
        var ids = JSON.readTree(response.body()).path("deletedIds");
        assertEquals(100, ids.size(), response.body());
        assertEquals("gone-100", ids.get(0).asText());
        assertEquals("gone-199", ids.get(99).asText());
        var late = send(client, base, "/api/chat/conversations/gone-0/context-floor", "POST", "{\"floorMessageId\":\"message\"}");
        assertEquals("CONVERSATION_DELETED", JSON.readTree(late.body()).path("errorCode").asText());
      } finally { server.stop(); }
    }
  }

  @Test
  void missingIdentityReadDistinguishesAbsentFromEmptyAndOlderConversations() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    store.createSession("empty", "core.free-chat");
    for (int i = 0; i < 25; i++) store.createSession("other-" + i, "core.free-chat");
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var server = LocalApiServer.builder(executors,
          new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY), temp.resolve("index")).build();
      try {
        String base = "http://127.0.0.1:" + server.getPort();
        for (var spec : Map.of("empty", true, "absent", false).entrySet()) {
          var response = send(client, base, "/api/chat/conversations/" + spec.getKey() + "/history?existsOnly=true", "GET", "");
          assertEquals(200, response.statusCode(), response.body());
          assertEquals(spec.getValue(), JSON.readTree(response.body()).path("exists").asBoolean(), response.body());
          assertTrue(JSON.readTree(response.body()).has("exists"), response.body());
        }
      } finally { server.stop(); }
    }
  }

  @Test
  void missingIdentityReadRecognizesRunOnlyAndRefusesLockedStores() throws Exception {
    var keys = new io.justsearch.app.services.encryption.DataKeyManager(
        new io.justsearch.app.services.encryption.EncryptionKeystore(temp.resolve("keys")));
    keys.setup("private-test-passphrase".toCharArray());
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(keys);
    var store = new FileConversationStore(temp.resolve("conversations"), cipher);
    var runs = new io.justsearch.agent.AgentRunStore(temp.resolve("runs"), cipher);
    runs.runEvents().writeRunMeta("run", Map.of("sessionId", "run", "conversationId", "run-only",
        "shapeId", "core.agent-run", "startedAt", "2026-10-19T00:00:00Z"));
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var chat = new ChatController(executors, null, new SseWriter(null), null, store);
      chat.setConversationDeletion(new io.justsearch.app.services.conversation.ConversationDeletion(
          store, runs, mock(io.justsearch.app.services.agenthistory.AgentHistoryIndexer.class),
          new io.justsearch.app.observability.stream.run.RunChannelRegistry(), keys));
      var app = io.javalin.Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
      try {
        app.get("/conversations/{sessionId}/history", chat::handleLoadHistory);
        app.exception(io.justsearch.agent.api.encryption.KeyLockedException.class,
            (error, ctx) -> ctx.status(423).json(Map.of("errorCode", "STORE_LOCKED")));
        app.start("127.0.0.1", 0);
        String base = "http://127.0.0.1:" + app.port();
        var existing = send(client, base, "/conversations/run-only/history?existsOnly=true", "GET", "");
        assertEquals(200, existing.statusCode(), existing.body());
        assertTrue(JSON.readTree(existing.body()).path("exists").asBoolean(), existing.body());
        keys.lock();
        for (String id : List.of("run-only", "absent")) {
          var locked = send(client, base, "/conversations/" + id + "/history?existsOnly=true", "GET", "");
          assertEquals(423, locked.statusCode(), locked.body());
          assertFalse(JSON.readTree(locked.body()).has("exists"), locked.body());
        }
      } finally { app.stop(); chat.shutdown(); }
    }
  }

  @Test
  void inaccessibleMetadataIsNotReportedAsAnAbsentConversation() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        temp.getFileSystem().supportedFileAttributeViews().contains("posix"));
    var store = new FileConversationStore(temp.resolve("conversations"));
    var runs = new io.justsearch.agent.AgentRunStore(temp.resolve("runs"));
    store.createSession("unreadable", "core.free-chat");
    runs.runEvents().writeRunMeta("run", Map.of("sessionId", "run", "conversationId", "run-only",
        "shapeId", "core.agent-run", "startedAt", "2026-10-19T00:00:00Z"));
    var directories = List.of(temp.resolve("conversations/unreadable"), temp.resolve("runs/run"));
    var permissions = new java.util.HashMap<Path, java.util.Set<java.nio.file.attribute.PosixFilePermission>>();
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var chat = new ChatController(executors, null, new SseWriter(null), null, store);
      chat.setConversationDeletion(new io.justsearch.app.services.conversation.ConversationDeletion(
          store, runs, mock(io.justsearch.app.services.agenthistory.AgentHistoryIndexer.class),
          new io.justsearch.app.observability.stream.run.RunChannelRegistry(),
          io.justsearch.app.services.encryption.DataKeyManager.disabled()));
      var app = io.javalin.Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
      try {
        app.get("/conversations/{sessionId}/history", chat::handleLoadHistory);
        app.exception(Exception.class, (error, ctx) -> ctx.status(500).json(Map.of("error", "unavailable")));
        app.start("127.0.0.1", 0);
        for (Path directory : directories) {
          permissions.put(directory, java.nio.file.Files.getPosixFilePermissions(directory));
          java.nio.file.Files.setPosixFilePermissions(directory, java.util.Set.of(
              java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        }
        var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for (String id : List.of("unreadable", "run-only")) {
          var response = send(client, "http://127.0.0.1:" + app.port(),
              "/conversations/" + id + "/history?existsOnly=true", "GET", "");
          checks.add(() -> assertAll(id,
              () -> assertTrue(response.statusCode() >= 500, response.body()),
              () -> assertFalse(JSON.readTree(response.body()).has("exists"), response.body())));
        }
        assertAll(checks);
      } finally {
        for (var entry : permissions.entrySet()) java.nio.file.Files.setPosixFilePermissions(entry.getKey(), entry.getValue());
        app.stop(); chat.shutdown();
      }
    }
  }

  private static HttpResponse<String> send(HttpClient client, String base, String path, String method, String body) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
        .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
