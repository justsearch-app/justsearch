/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.*;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class ConversationIndexingContinuationTest {
  @TempDir Path root;

  @ParameterizedTest
  @EnumSource(PersistenceMode.class)
  void continuingAPersistedPausedQuestionSavesOneUserTurnAndInjectsItOnce(PersistenceMode mode) {
    var shape = new ConversationShape(new ConversationShapeRef("core.pause-continuation"),
        new Presentation(new I18nKey("test.label"), new I18nKey("test.description"), Optional.empty(), Optional.empty()),
        Audience.USER, Provenance.core("v1"), ExecutionMode.SUBSTRATE_DRIVEN, IterationMode.ONE_SHOT,
        mode, List.of(), List.of(UserPromptInjector.ID), List.of(), SingleHopController.ID, List.of(), true);
    var store = new FileConversationStore(root);
    store.createSession("conversation", shape.id().value());
    store.appendMessage("conversation", shape.id().value(), Map.of("role", "user", "content", "Earlier question"));
    store.appendMessage("conversation", shape.id().value(), Map.of("role", "assistant", "content", "Earlier answer"));
    var paused = new AtomicBoolean(true);
    var ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(true);
    var inputs = new ArrayList<List<Map<String, Object>>>();
    doAnswer(call -> {
      var request = call.getArgument(0, OnlineAiService.StreamRequest.class);
      if (paused.get()) throw new AnswersPausedException();
      inputs.add(request.messages());
      var sink = call.getArgument(1, OnlineAiService.StreamSink.class);
      sink.onContent().accept("Resumed answer");
      sink.onComplete().accept("stop");
      return null;
    }).when(ai).stream(any(), any());
    var engine = new ConversationEngine(ConversationShapeCatalog.of("core", List.of(shape)), List.of(),
        PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of(UserPromptInjector.INSTANCE)),
        StreamConsumerRegistry.of(List.of()), IterationControllerRegistry.of(List.of()), () -> ai, store);
    var body = Map.<String, Object>of("conversationId", "conversation", "sessionId", "conversation",
        "turnId", "waiting-turn", "prompt", "Waiting question");
    for (int attempt = 0; attempt < 2; attempt++) {
      assertThrows(AnswersPausedException.class, () -> engine.run(shape.id(), body, Audience.USER,
          event -> {}, io.justsearch.app.services.TestEngineContexts.internal()));
      var saved = new FileConversationStore(root).loadHistory("conversation");
      assertEquals(3, saved.size(), "a repeated pause keeps the already-persisted unanswered question once");
      assertEquals("waiting-turn", saved.getLast().get("turnId"));
    }
    paused.set(false);
    var events = new ArrayList<SseEvent>();
    engine.run(shape.id(), body, Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
    assertTrue(events.stream().anyMatch(e -> "done".equals(e.name())), events.toString());
    assertEquals(1, inputs.size());
    assertEquals(1, inputs.getFirst().stream().filter(m -> "Waiting question".equals(m.get("content"))).count());
    assertEquals(mode == PersistenceMode.PERSISTENT ? 3 : 1, inputs.getFirst().size());
    var history = new FileConversationStore(root).loadHistory("conversation");
    assertEquals(List.of("Earlier question", "Earlier answer", "Waiting question", "Resumed answer"),
        history.stream().map(m -> m.get("content")).toList());
    assertEquals("waiting-turn", history.getLast().get("turnId"));
  }
}
