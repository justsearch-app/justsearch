/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationCompactionDeleteTest {
  @TempDir Path root;

  @Test void deleteRefusesUntilCompactionHasSavedItsSummary() throws Exception {
    exercise(false);
  }

  @Test void failedCompactionReleasesDeletionExclusion() throws Exception {
    exercise(true);
  }

  private void exercise(boolean failSummary) throws Exception {
    Files.createDirectories(root.resolve("target"));
    Files.writeString(root.resolve("target/meta.json"),
        "{\"schemaVersion\":1,\"shapeId\":\"core.free-chat\",\"messageCount\":2}");
    Files.writeString(root.resolve("target/messages.jsonl"),
        "{\"id\":\"q\",\"role\":\"user\",\"content\":\"private question\"}\n"
        + "{\"id\":\"a\",\"role\":\"assistant\",\"content\":\"answer\"}\n");
    var store = new FileConversationStore(root);
    var summary = new CompletableFuture<String>();
    var entered = new CountDownLatch(1);
    var ai = mock(OnlineAiService.class);
    when(ai.summarize(anyString(), anyInt(), any())).thenAnswer(call -> {
      entered.countDown();
      return summary;
    });
    var chat = new ChatController(new TestEngineExecutors(), null, new SseWriter(null), null,
        store, () -> ai, () -> null);
    var app = Javalin.create(c -> c.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()));
    try {
      app.post("/conversations/{sessionId}/compact", chat::handleCompact);
      app.delete("/conversations/{sessionId}", chat::handleDeleteConversation);
      app.start(0);
      try (var client = HttpClient.newHttpClient(); var other = HttpClient.newHttpClient()) {
        var base = "http://localhost:" + app.port() + "/conversations/target";
        var compact = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/compact"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"floorMessageId\":\"a\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS), "summarizer was not called");
          var refused = other.send(HttpRequest.newBuilder(URI.create(base)).DELETE().build(),
              HttpResponse.BodyHandlers.ofString());
          assertEquals(409, refused.statusCode(), refused.body());
          assertTrue(refused.body().contains("CONVERSATION_BUSY"), refused.body());
          assertEquals(2, store.loadHistory("target").size());
        } finally {
          if (failSummary) summary.completeExceptionally(new IllegalStateException("model unavailable"));
          else summary.complete("private summary");
          var completed = compact.get(5, TimeUnit.SECONDS);
          assertEquals(failSummary ? 503 : 200, completed.statusCode(), completed.body());
        }
        if (!failSummary) {
          assertEquals("private summary", store.getSessionMeta("target").orElseThrow().contextFloorSummary());
        }
        var deleted = other.send(HttpRequest.newBuilder(URI.create(base)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertTrue(new FileConversationStore(root).listSessions(null, 10).isEmpty());
        assertFalse(Files.exists(root.resolve("target")));
      }
    } finally {
      summary.completeExceptionally(new IllegalStateException("test cleanup"));
      app.stop();
      chat.shutdown();
    }
  }
}
