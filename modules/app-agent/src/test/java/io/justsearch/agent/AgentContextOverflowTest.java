/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.ContextExceededException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentContextOverflowTest {
  @Test void oversizedExchangeIsNeverRetriedEvenAfterPartialOutput() {
    for (boolean partial : new boolean[] {false, true}) {
      var attempts = new AtomicInteger();
      OnlineAiService ai = new OnlineAiService() {
        @Override public void stream(StreamRequest request, StreamSink sink) {
          attempts.incrementAndGet();
          if (partial) sink.onContent().accept("partial");
          sink.onError().accept(new ContextExceededException());
        }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean isStartingUp() { return false; }
        @Override public java.util.concurrent.CompletableFuture<String> summarize(String content) {
          throw new UnsupportedOperationException();
        }
        @Override public java.util.concurrent.CompletableFuture<String> askQuestion(String question, String context) {
          throw new UnsupportedOperationException();
        }
      };
      var session = new AgentSession(new ArrayList<>(List.of(Map.of("role", "user", "content", "q"))),
          8000, EngineContextTestFixtures.AGENT_LOOP);
      var caller = new AgentLlmCaller(ai, AgentTelemetry.noop(), new AgentContextCompressor(false, 200, 1));
      RuntimeException failure = assertThrows(RuntimeException.class, () ->
          caller.callLlmWithRetries(session, List.of(), ignored -> {}));
      assertTrue(ContextExceededException.causedBy(failure));
      assertEquals(1, attempts.get(), "an individually oversized exchange must not be replayed");
    }
  }
}
