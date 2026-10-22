/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConversationDeleteHttpTest {
  @Test
  void lockedDeleteRefusesBeforeTouchingEitherStore() throws Exception {
    var store = mock(ConversationStore.class);
    when(store.isLocked()).thenReturn(true);
    var agent = mock(AgentService.class);
    var chat = new ChatController(new TestEngineExecutors(), null, new SseWriter(null), null,
        store, io.justsearch.app.api.OnlineAiService::unavailable, () -> agent);
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
    try {
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation).start(0);
      try (var client = HttpClient.newHttpClient()) {
        var response = client.send(HttpRequest.newBuilder(URI.create(
            "http://localhost:" + app.port() + "/conversations/conv-locked"))
            .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(423, response.statusCode(), response.body());
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals("STORE_LOCKED", body.get("errorCode").asString());
        assertFalse(body.get("error").asString().isBlank());
        verify(store, never()).deleteSession(anyString());
        verifyNoInteractions(agent);
      }
    } finally {
      app.stop();
      chat.shutdown();
    }
  }
  @Test
  void anotherHttpClientCannotDeleteWhileAnAskIsStreaming(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
    var store = new io.justsearch.app.services.conversation.FileConversationStore(temp);
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    io.justsearch.app.services.conversation.ShapeRunner runner = new io.justsearch.app.services.conversation.ShapeRunner() {
      public io.justsearch.agent.api.registry.ConversationShapeRef shapeId() {
        return io.justsearch.app.services.conversation.AgentRunShape.ID;
      }
      public void run(java.util.Map<String, Object> body, io.justsearch.agent.api.registry.Audience audience,
          java.util.function.Consumer<io.justsearch.agent.api.conversation.SseEvent> sink,
          io.justsearch.core.context.EngineContext context) {
        started.countDown();
        try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        sink.accept(new io.justsearch.agent.api.conversation.SseEvent("done", java.util.Map.of("finalResponse", "saved answer")));
      }
    };
    var engine = new io.justsearch.app.services.conversation.ConversationEngine(
        io.justsearch.app.services.conversation.CoreConversationShapeCatalog.catalog(), java.util.List.of(runner),
        io.justsearch.app.services.conversation.PromptContributorRegistry.of(java.util.List.of()),
        io.justsearch.app.services.conversation.ContextInjectorRegistry.of(java.util.List.of()),
        io.justsearch.app.services.conversation.StreamConsumerRegistry.of(java.util.List.of()),
        io.justsearch.app.services.conversation.IterationControllerRegistry.of(java.util.List.of()),
        io.justsearch.app.api.OnlineAiService::unavailable, store);
    var chat = new ChatController(new TestEngineExecutors(), engine, new SseWriter(null), null, store);
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
    try {
      app.post("/ask", chat.handler(io.justsearch.app.services.conversation.AgentRunShape.ID, "/ask"));
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.start(0);
      try (var client = HttpClient.newHttpClient(); var other = HttpClient.newHttpClient()) {
        var stream = client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ask"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\"conversation\",\"messages\":[{\"role\":\"user\",\"content\":\"question\"}]}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        try {
          assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
          var response = other.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port()
              + "/conversations/conversation")).DELETE().build(), HttpResponse.BodyHandlers.ofString());
          assertEquals(409, response.statusCode(), response.body());
          assertEquals("CONVERSATION_BUSY", JsonMapper.builder().build().readTree(response.body()).get("errorCode").asString());
          assertEquals(1, store.loadHistory("conversation").size());
        } finally { release.countDown(); stream.get(3, java.util.concurrent.TimeUnit.SECONDS); }
        assertEquals(2, store.loadHistory("conversation").size());
      }
    } finally { release.countDown(); app.stop(); chat.shutdown(); }
  }

}
