/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.conversation.shapes.FreeChatShape;
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DispatchCorrelationTest {
  @TempDir Path dir;

  @Test void currentPromptAndItsOutcomeShareDispatchWhileForwardedHistoryAndLlmInputDoNot() {
    var store = new FileConversationStore(dir);
    var request = new AtomicReference<OnlineAiService.StreamRequest>();
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    doAnswer(call -> {
      request.set(call.getArgument(0));
      OnlineAiService.StreamSink sink = call.getArgument(1);
      sink.onContent().accept("saved answer");
      sink.onComplete().accept("stop");
      return null;
    }).when(ai).stream(any(), any());
    engine(ai, store).run(FreeChatShape.ID, Map.of("sessionId", "chat", "prompt", "current prompt", "turnId", "dispatch-A",
        "context", List.of(Map.of("role", "user", "content", "prior question"), Map.of("role", "assistant", "content", "prior answer"))),
        Audience.USER, event -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    var history = new FileConversationStore(dir).loadHistory("chat");
    assertEquals(4, history.size());
    assertFalse(history.get(0).containsKey("turnId"));
    assertFalse(history.get(1).containsKey("turnId"));
    assertEquals("current prompt", history.get(2).get("content"));
    assertEquals("dispatch-A", history.get(2).get("turnId"));
    assertEquals("dispatch-A", history.get(3).get("turnId"));
    assertEquals("saved answer", history.get(3).get("content"));
    assertNotNull(request.get());
    assertTrue(request.get().messages().stream().noneMatch(m -> m.containsKey("turnId")), "correlation is transcript metadata, never model input");
    var hash = history.get(2).get("hash");
    store.branchFrom("chat", String.valueOf(history.get(3).get("id")), "child");
    assertEquals(history, new FileConversationStore(dir).loadHistory("child"), "inherited IDs and stamps remain historical identity");
    assertEquals(hash, new FileConversationStore(dir).loadHistory("child").get(2).get("hash"));
  }

  @Test void savedGenerationFailureNamesItsDispatch() {
    var store = new FileConversationStore(dir);
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(false);
    engine(ai, store).run(FreeChatShape.ID, Map.of("sessionId", "chat", "prompt", "current prompt", "turnId", "dispatch-error"),
        Audience.USER, event -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    var history = new FileConversationStore(dir).loadHistory("chat");
    assertEquals(2, history.size());
    assertEquals(true, history.get(1).get("terminalError"));
    assertEquals("dispatch-error", history.get(0).get("turnId"));
    assertEquals("dispatch-error", history.get(1).get("turnId"));
  }

  @Test void aLiveExtractionSnapshotCarriesItsQuestionButOnlyAcceptedJsonIsASavedOutcome() {
    var store = new FileConversationStore(dir);
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> {
      OnlineAiService.StreamSink sink = call.getArgument(1);
      var snapshot = new FileConversationStore(dir).loadHistory("extract");
      assertEquals("extract-A", snapshot.get(0).get("turnId"));
      assertEquals("INCOMPLETE_TURN", snapshot.get(1).get("errorCode"));
      assertEquals("extract-A", snapshot.get(1).get("turnId"));
      sink.onContent().accept(attempts.getAndIncrement() == 0 ? "{oops}" : "{\"city\":\"Paris\"}");
      sink.onComplete().accept("stop");
      return null;
    }).when(ai).stream(any(), any());
    var engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(), List.of(), PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.of(new ExternalContextInjector(() -> ai), UserPromptInjector.INSTANCE,
            new io.justsearch.app.services.conversation.spi.SelectionContextInjector(mock(io.justsearch.app.api.DocumentService.class)))),
        StreamConsumerRegistry.of(List.of(new io.justsearch.app.services.conversation.spi.ValidationConsumer())),
        IterationControllerRegistry.of(List.of(new io.justsearch.app.services.conversation.spi.ValidatingController())), () -> ai, store);
    engine.run(io.justsearch.app.services.conversation.shapes.ExtractShape.ID,
        Map.of("conversationId", "extract", "prompt", "extract city", "turnId", "extract-A", "schema", "{\"type\":\"object\"}"),
        Audience.USER, event -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    var saved = new FileConversationStore(dir).loadHistory("extract");
    assertEquals(2, attempts.get());
    assertEquals(2, saved.size());
    assertEquals("extract-A", saved.get(1).get("turnId"));
    assertEquals("{\"city\":\"Paris\"}", saved.get(1).get("content"));
    assertFalse(saved.toString().contains("{oops}"));
    assertFalse(store.isTurnOpen("extract"));
  }

  private ConversationEngine engine(OnlineAiService ai, FileConversationStore store) {
    return new ConversationEngine(CoreConversationShapeCatalog.catalog(), List.of(), PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.of(new ExternalContextInjector(() -> ai), UserPromptInjector.INSTANCE)),
        StreamConsumerRegistry.of(List.of(new io.justsearch.app.services.conversation.spi.MemoryExtractionConsumer(mock(io.justsearch.agent.api.memory.MemoryStore.class)))),
        IterationControllerRegistry.of(List.of(SingleHopController.INSTANCE)), () -> ai, store);
  }
}
