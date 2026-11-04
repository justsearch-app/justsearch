/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexingPauseGenerationHttpTest {
  @TempDir Path root;

  @Test void compactionPreservesAPauseFromGeneration() throws Exception { exercise(false); }
  @Test void titlePreservesAPauseAfterInitialAdmission() throws Exception { exercise(true); }

  private void exercise(boolean title) throws Exception {
    Files.createDirectories(root.resolve("target"));
    Files.writeString(root.resolve("target/meta.json"),
        "{\"schemaVersion\":1,\"shapeId\":\"core.free-chat\",\"messageCount\":2}");
    Files.writeString(root.resolve("target/messages.jsonl"),
        "{\"id\":\"q\",\"role\":\"user\",\"content\":\"question\"}\n"
        + "{\"id\":\"a\",\"role\":\"assistant\",\"content\":\"answer\"}\n");
    var store = new FileConversationStore(root);
    var ai = mock(OnlineAiService.class);
    if (title) when(ai.chatCompletion(anyList(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new AnswersPausedException()));
    else when(ai.summarize(anyString(), anyInt(), any()))
        .thenThrow(new AnswersPausedException());
    try (var executors = new TestEngineExecutors(); var client = HttpClient.newHttpClient()) {
      var chat = new ChatController(executors, null, new SseWriter(null), null, store, () -> ai);
      var app = Javalin.create(c -> {
        c.showJavalinBanner = false;
        c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      try {
        app.before(ctx -> ctx.attribute(RequestEngineContext.ATTRIBUTE, TestRequestContexts.browser()));
        app.post("/api/chat/conversations/{sessionId}/auto-title", chat::handleAutoTitle);
        app.post("/api/chat/conversations/{sessionId}/compact", chat::handleCompact);
        app.start(0);
        String route = title ? "auto-title" : "compact";
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port()
                + "/api/chat/conversations/target/" + route))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"floorMessageId\":\"a\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode(), response.body());
        var body = new tools.jackson.databind.ObjectMapper().readTree(response.body());
        assertEquals("CHAT_UNAVAILABLE", body.path("errorCode").asString());
        assertEquals("INDEXING", body.path("reason").asString());
        assertEquals("Answers paused while indexing", body.path("error").asString());
        assertFalse(body.path("forceAllowed").asBoolean(true));
        assertEquals(2, store.loadHistory("target").size());
        assertNull(store.getSessionMeta("target").orElseThrow().contextFloorSummary());
        assertNull(store.getSessionMeta("target").orElseThrow().title());
        if (title) verify(ai).chatCompletion(anyList(), anyInt(), any(), any());
        else verify(ai).summarize(anyString(), anyInt(), any());
      } finally { app.stop(); chat.shutdown(); }
    }
  }
}
