/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.interaction.ThreadProjection;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class DispatchCorrelationHttpTest {
  @TempDir Path dir;

  @Test void realThreadReadPreservesCorrelationAndSyntheticIncompleteIdentity() throws Exception {
    var store = new FileConversationStore(dir);
    store.createSession("saved", "core.extract");
    store.appendMessage("saved", "core.extract", Map.of("role", "user", "content", "extract", "turnId", "dispatch-A"));
    store.setTurnOpen("saved", true);
    var agent = mock(AgentService.class);
    when(agent.threadProjection(eq("saved"), any())).thenReturn(ThreadProjection.of(List.of()));
    try (var executors = new TestEngineExecutors()) {
      var controller = new InteractionThreadController(mock(io.justsearch.app.api.operations.OperationAttemptRunner.class), store, agent, executors);
      var app = Javalin.create(config -> config.showJavalinBanner = false);
      try {
        app.get("/api/thread/{id}", controller::handleGet);
        app.start("127.0.0.1", 0);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/thread/saved"))
            .timeout(Duration.ofSeconds(5)).GET().build();
        try (var client = HttpClient.newHttpClient()) {
          var response = client.send(request, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          var events = JsonMapper.builder().build().readTree(response.body()).get("events");
          assertEquals(2, events.size());
          assertEquals("dispatch-A", events.get(0).get("attributes").path("turnId").asString());
          assertEquals("dispatch-A", events.get(1).get("attributes").path("turnId").asString());
          assertEquals("ERROR", events.get(1).get("kind").asString());
          assertEquals("INCOMPLETE_TURN", events.get(1).get("attributes").path("errorCode").asString());
        }
      } finally {
        app.stop();
        controller.shutdown();
      }
    }
  }
}
