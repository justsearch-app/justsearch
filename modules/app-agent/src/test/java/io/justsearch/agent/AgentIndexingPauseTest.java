/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.AnswersPausedException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AgentIndexingPauseTest {
  @Test
  void aPausedAnswerDoesNotRetryOrHideThePause() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var ai = (OnlineAiService) java.lang.reflect.Proxy.newProxyInstance(
        OnlineAiService.class.getClassLoader(), new Class<?>[] {OnlineAiService.class},
        (proxy, method, args) -> {
          if (method.getName().equals("stream")) {
            calls.incrementAndGet();
            ((OnlineAiService.StreamSink) args[1]).onError().accept(new AnswersPausedException());
          }
          return null;
        });
    var caller = new AgentLlmCaller(ai, AgentTelemetry.noop(), new AgentContextCompressor(false, 200, 1));
    var session = new AgentSession(List.of(Map.of("role", "user", "content", "question")),
        8000, EngineContextTestFixtures.AGENT_LOOP);
    var refusal = assertThrows(AnswersPausedException.class,
        () -> caller.callLlmWithRetries(session, List.of(), event -> {}));
    assertEquals("Answers paused while indexing", refusal.getMessage());
    assertEquals(1, calls.get());
  }
}
