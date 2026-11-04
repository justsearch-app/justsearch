/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.core.execution.TestEngineExecutors;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

final class IndexingPauseGenerationRoutesTest {
  @ParameterizedTest
  @ValueSource(strings = {"legacy-stream", "unified-stream", "completion"})
  void queueRefusalReachesTheCallerWithoutAnUpstreamRequest(String route) throws Exception {
    var tracker = new GenerationTracker(ignored -> () -> {}, Runnable::run);
    tracker.setAnswersPaused(() -> true);
    var terminal = new CompletableFuture<Throwable>();
    var endpoints = new java.util.concurrent.atomic.AtomicInteger();
    try (var registry = new TestEngineExecutors(); var http = HttpClient.newHttpClient()) {
      var ops = new OnlineModeOps(new InferenceExecutorRegistrations(registry), http,
          new ObjectMapper(), () -> Mode.ONLINE, () -> {
            endpoints.incrementAndGet();
            throw new AssertionError("a refused answer must not contact llama-server");
          }, () -> "test-model", () -> "test-model", null, tracker);
      try {
        var messages = List.<Map<String, Object>>of(Map.of("role", "user", "content", "question"));
        if (route.equals("legacy-stream")) {
          ops.streamChat(messages, 32, ignored -> fail("refused answer emitted content"),
              ignored -> fail("refused answer completed"), terminal::complete);
        } else if (route.equals("unified-stream")) {
          ops.stream(messages, null, 32, ignored -> fail("refused answer emitted content"),
              null, null, null, ignored -> fail("refused answer completed"), terminal::complete,
              null, true, null);
        } else {
          ops.chatCompletion(messages, 32).whenComplete((answer, error) -> terminal.complete(error));
        }
        // An absent terminal callback is itself the legacy defect; keep its failure an assertion.
        assertTrue(terminal.handle((error, failure) -> true).completeOnTimeout(false, 5, TimeUnit.SECONDS)
            .get(6, TimeUnit.SECONDS), "every refused route must notify its caller");
        assertTrue(AnswersPausedException.causedByPause(terminal.getNow(null)));
        assertEquals(0, endpoints.get());
        assertEquals(0, tracker.queued());
        assertEquals(0, tracker.inFlight());
      } finally {
        ops.shutdown();
      }
    }
  }
}
