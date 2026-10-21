/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationDeleteWorkTest {
  @TempDir Path temp;

  @Test
  void refusesDeleteThroughoutShapeRunIncludingTerminalDelivery() {
    var store = new FileConversationStore(temp);
    var refused = new AtomicBoolean();
    ShapeRunner runner = new ShapeRunner() {
      public io.justsearch.agent.api.registry.ConversationShapeRef shapeId() { return AgentRunShape.ID; }
      public void run(Map<String, Object> body, Audience audience,
          java.util.function.Consumer<SseEvent> sink, EngineContext context) {
        try { store.deleteSession("conversation"); }
        catch (IllegalStateException busy) { refused.set(true); }
        sink.accept(new SseEvent("done", Map.of("finalAnswer", "kept answer")));
      }
    };
    var engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(), List.of(runner),
        PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of()),
        StreamConsumerRegistry.of(List.of()), IterationControllerRegistry.of(List.of()),
        OnlineAiService::unavailable, store);
    engine.run(AgentRunShape.ID, Map.of("conversationId", "conversation", "messages",
        List.of(Map.of("role", "user", "content", "question"))), Audience.USER, e -> {},
        io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(refused.get(), "a running conversation must refuse deletion before losing any messages");
    assertEquals(2, store.loadHistory("conversation").size());
    store.deleteSession("conversation");
    assertTrue(store.listSessions(null, 20).isEmpty());
  }
}
