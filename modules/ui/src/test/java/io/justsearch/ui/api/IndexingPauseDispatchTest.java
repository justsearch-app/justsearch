/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

final class IndexingPauseDispatchTest {
  @Test
  void wrappedGenerationPauseStillHasItsTypedStreamOutcome() {
    var engine = mock(ConversationEngine.class);
    doThrow(new CompletionException(new AnswersPausedException())).when(engine)
        .runScoped(any(), any(), any(), any(), any(), any());
    try (var executors = new TestEngineExecutors()) {
      var controller = new ChatController(executors, engine, null, null);
      var events = new ArrayList<SseEvent>();
      controller.runToSink(new ConversationShapeRef("core.free-chat"), Map.of(), Audience.USER,
          events::add, TestRequestContexts.browser());
      assertEquals(1, events.size());
      assertEquals("error", events.getFirst().name());
      assertEquals("CHAT_UNAVAILABLE", events.getFirst().payload().get("errorCode"));
      assertEquals("INDEXING", events.getFirst().payload().get("reason"));
      assertEquals("Answers paused while indexing", events.getFirst().payload().get("error"));
      assertEquals(false, events.getFirst().payload().get("forceAllowed"));
    }
  }
}
