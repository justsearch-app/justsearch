/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CD3: retained clients cannot recreate deleted content through the real HTTP interface. */
class LateConversationWriteHttpTest {
  @TempDir Path temp;

  @Test
  void lateSearchIsRefusedAndCannotRecreateRunOrThread() throws Exception {
    var runs = new AgentRunStore(temp.resolve("runs"));
    var store = new FileConversationStore(temp.resolve("conversations"));
    store.useConversationWork(runs.conversationWork());
    store.createSession("conversation", "core.free-chat");
    var agent = mock(AgentService.class);
    when(agent.appendSearchEvent(anyString(), any())).thenAnswer(call ->
        runs.appendSearchEvent(call.getArgument(0), call.getArgument(1)));
    when(agent.deleteConversationRuns(anyString())).thenAnswer(call ->
        runs.deleteConversationRuns(call.getArgument(0)));
    var executors = new TestEngineExecutors();
    var chat = new ChatController(executors, null, new SseWriter(null), null,
        store, io.justsearch.app.api.OnlineAiService::unavailable, () -> agent);
    var thread = new InteractionThreadController(mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), store, agent, executors);
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
    try {
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.get("/conversations", chat::handleListSessions);
      app.post("/thread/{id}/events", thread::handlePostEvent);
      app.start(0);
      try (var client = HttpClient.newHttpClient()) {
        String base = "http://localhost:" + app.port();
        var deleted = client.send(HttpRequest.newBuilder(URI.create(base + "/conversations/conversation"))
            .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode(), deleted.body());
        var late = client.send(HttpRequest.newBuilder(URI.create(base + "/thread/conversation/events"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""
                {"kind":"SEARCH","query":"private query","mode":"hybrid","matchCount":1,"resultCount":1,"docIds":["private-document"]}
                """)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, late.statusCode(), late.body());
        assertTrue(late.body().contains("CONVERSATION_DELETED"), late.body());
        assertTrue(runs.listRunIdsByConversation("conversation").isEmpty());
        assertTrue(store.loadHistory("conversation").isEmpty());
      }
    } finally { app.stop(); thread.shutdown(); chat.shutdown(); executors.close(); }
  }

  @Test
  void refreshedListIdentifiesConfirmedDeletionsBeyondItsRecentWindow() throws Exception {
    var store = new FileConversationStore(temp.resolve("conversations"));
    store.createSession("conversation", "core.free-chat");
    store.deleteSession("conversation");
    store.conversationWork().completed("conversation");
    var chat = new ChatController(new TestEngineExecutors(), null, new SseWriter(null), null, store);
    var ctx = mock(io.javalin.http.Context.class);
    when(ctx.queryParam("limit")).thenReturn("20");
    chat.handleListSessions(ctx);
    var payload = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(ctx).json(payload.capture());
    assertEquals(java.util.List.of("conversation"), payload.getValue().get("deletedIds"));
    chat.shutdown();
  }
  @Test
  void staleManagedStreamIsRefusedWithAReasonBeforeOpeningARun() {
    var store = new FileConversationStore(temp.resolve("conversations"));
    store.createSession("conversation", "core.free-chat");
    store.deleteSession("conversation");
    store.conversationWork().completed("conversation");
    var executors = new TestEngineExecutors();
    var chat = new ChatController(executors, null, new SseWriter(null), null, store);
    var registry = new io.justsearch.app.observability.stream.run.RunChannelRegistry();
    var runs = new RunStreamController(executors, registry, chat);
    var ctx = mock(io.javalin.http.Context.class);
    when(ctx.path()).thenReturn("/api/chat/runs");
    when(ctx.body()).thenReturn("{\"shapeId\":\"core.free-chat\",\"conversationId\":\"conversation\",\"prompt\":\"late\"}");
    var client = mock(io.javalin.http.sse.SseClient.class);
    when(client.ctx()).thenReturn(ctx);
    var sent = new java.util.ArrayList<String>();
    doAnswer(call -> { sent.add(call.getArgument(1)); return null; })
        .when(client).sendEvent(anyString(), anyString());
    try {
      assertDoesNotThrow(() -> runs.streamNewRun(client));
      assertTrue(sent.stream().anyMatch(frame -> frame.contains("CONVERSATION_DELETED")), sent.toString());
      assertTrue(registry.live().isEmpty());
      assertTrue(store.listSessions(null, 20).isEmpty());
    } finally { runs.shutdown(); chat.shutdown(); executors.close(); }
  }

}
