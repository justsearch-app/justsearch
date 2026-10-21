/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.AgentLoopService;
import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.encryption.StoreDescriptor;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.EncryptionKeystore;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SavedStoreIdsHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path tmp;

  @ParameterizedTest(name = "branchWithInvalidSavedParentOpensLikeMissingParentOverHttp({0})")
  @ValueSource(strings = {"history", "thread"})
  void branchWithInvalidSavedParentOpensLikeMissingParentOverHttp(String view) throws Exception {
    Path root = tmp.resolve("conversations");
    var store = new FileConversationStore(root);
    for (String id : List.of("branch", "missing-parent-branch")) {
      store.appendMessage(id, "core.free-chat", Map.of("id", "own-turn", "role", "user", "content", "keep",
          "ts", "2026-10-20T00:00:00Z"));
    }
    setParent(root, "missing-parent-branch", "absent");
    var agent = mock(AgentService.class);
    when(agent.threadProjection(anyString(), anySet()))
        .thenReturn(new io.justsearch.agent.api.interaction.ThreadProjection(List.of(), Map.of()));
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var chat = new ChatController(executors, null, new SseWriter(null), null, store);
      var thread = new InteractionThreadController(
          mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), store, agent, executors);
      var app = server();
      try {
        app.get("/api/chat/conversations/{sessionId}/history", chat::handleLoadHistory);
        app.get("/api/thread/{id}", thread::handleGet);
        app.start(0);
        boolean historyView = "history".equals(view);
        String missingPath = historyView ? "/api/chat/conversations/missing-parent-branch/history"
            : "/api/thread/missing-parent-branch";
        JsonNode missing = get(client, app, missingPath);
        for (String parent : List.of(" ", "a.", "CON", "x:y", "../outside")) {
          setParent(root, "branch", parent);
          byte[] savedMeta = Files.readAllBytes(root.resolve("branch/meta.json"));
          if (historyView) {
            JsonNode history = get(client, app, "/api/chat/conversations/branch/history");
            assertEquals(missing.get("messages"), history.get("messages"), parent);
            assertFalse(history.has("parentFirstUserMessage"), parent);
          } else {
            JsonNode events = get(client, app, "/api/thread/branch").get("events");
            assertEquals(missing.get("events").size(), events.size(), parent);
            assertTrue(events.toString().contains("keep"), parent);
          }
          assertArrayEquals(savedMeta, Files.readAllBytes(root.resolve("branch/meta.json")), parent);
        }
      } finally {
        app.stop();
        chat.shutdown();
        thread.shutdown();
      }
    }
  }

  @Test
  void conversationListIgnoresSavedRunsWithInvalidConversationIdsOverHttp() throws Exception {
    var store = new FileConversationStore(tmp.resolve("conversations"));
    store.appendMessage("valid", "core.free-chat", Map.of("role", "user", "content", "keep"));
    var runs = new AgentRunStore(tmp.resolve("runs"));
    for (String id : List.of("a.", "CON", "x:y", " ")) {
      String runId = "run-" + id.hashCode();
      runs.runEvents().writeRunMeta(runId, Map.of(
          "sessionId", runId, "conversationId", id, "shapeId", "core.agent-run",
          "state", "COMPLETED", "startedAt", "2026-10-20T00:00:00Z", "updatedAt", "2026-10-20T00:01:00Z"));
    }
    var agent = new AgentLoopService(null, OperationCatalog.of("core", List.of()),
        mock(OperationDispatcher.class), mock(AgentToolEmitter.class), null, null, runs, null);
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var chat = new ChatController(executors, null, new SseWriter(null), null, store, () -> null, () -> agent);
      var app = server();
      try {
        app.get("/api/chat/conversations", chat::handleListSessions);
        app.start(0);
        JsonNode sessions = get(client, app, "/api/chat/conversations").get("sessions");
        assertEquals(1, sessions.size(), sessions.toString());
        assertEquals("valid", sessions.get(0).get("sessionId").asString());
        assertEquals(4, runs.listSessions(100).size(), "the runs themselves stay available");
      } finally {
        app.stop();
        chat.shutdown();
      }
    }
  }

  @Test
  void legacyFormatBackupRestoresValidConversationsAndReportsInvalidEntriesOverHttp() throws Exception {
    var head = mock(HeadAssembly.class, RETURNS_DEEP_STUBS);
    when(head.storeCipher(any())).thenReturn(StoreCipher.disabled());
    var descriptors = new ArrayList<StoreDescriptor>();
    doAnswer(call -> { descriptors.add(call.getArgument(0)); return null; })
        .when(head).registerAuthoredStore(any());
    when(head.authoredStores()).thenAnswer(call -> List.copyOf(descriptors));
    var keys = new DataKeyManager(new EncryptionKeystore(tmp.resolve("keys")));
    keys.setup("local-passphrase".toCharArray());
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var builder = LocalApiServer.builder(executors, null, tmp.resolve("index"))
          .HeadAssembly(head).documentService(mock(io.justsearch.app.api.DocumentService.class))
          .onlineAiService(io.justsearch.app.api.OnlineAiService.unavailable());
      var assembly = ConversationApiAssembly.assemble(builder, null, HeadApiMetricCatalog.noop(), false, () -> null);
      var controller = new ConversationBackupController(head, keys);
      // v1's bundle is a map of store names to entry arrays, sealed with the bundled keystore's DEK.
      // These are the same entries the old dispatch bug exported, including blank session names.
      var entries = new ArrayList<Map<String, Object>>();
      for (String id : List.of(" ", "valid-one", "\t", "a.", "CON", "valid-two", "x:y")) {
        entries.add(Map.of("sessionId", id, "shapeId", "core.free-chat", "firstUserMessage", "keep " + id,
            "messages", List.of(Map.of("role", "user", "content", "keep " + id))));
      }
      var backupKeys = new DataKeyManager(new EncryptionKeystore(tmp.resolve("backup-keys")));
      backupKeys.setup("backup-passphrase".toCharArray());
      var container = Map.of("format", "justsearch-backup/v1", "keystore", backupKeys.keystoreRecord(),
          "data", new StoreCipher(backupKeys).seal(JSON.writeValueAsString(Map.of("conversations", entries))));
      var app = server();
      try {
        app.post("/api/conversations/encryption/import", controller::handleImport);
        app.start(0);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/conversations/encryption/import"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                "container", container, "passphrase", "backup-passphrase")))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = JSON.readTree(response.body());
        assertEquals(2, result.path("restored").path("conversations").asInt());
        assertEquals(5, result.path("skipped").asInt());
        assertEquals(List.of("valid-one", "valid-two"), assembly.conversationStore().listSessions(null, 100).stream()
            .map(io.justsearch.agent.api.conversation.ConversationStore.SessionSummary::sessionId).sorted().toList());
        assertEquals("keep valid-two", assembly.conversationStore().loadHistory("valid-two").getFirst().get("content"));
      } finally {
        app.stop();
        assembly.chatController().shutdown();
      }
    }
  }

  private static void setParent(Path root, String id, String parent) throws Exception {
    Path file = root.resolve(id).resolve("meta.json");
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = new LinkedHashMap<>(JSON.readValue(Files.readString(file), Map.class));
    meta.put("parentSessionId", parent);
    Files.writeString(file, JSON.writeValueAsString(meta));
  }

  private static Javalin server() {
    return Javalin.create(c -> {
      c.showJavalinBanner = false;
      c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
  }

  private static JsonNode get(HttpClient client, Javalin app, String path) throws Exception {
    var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }
}
