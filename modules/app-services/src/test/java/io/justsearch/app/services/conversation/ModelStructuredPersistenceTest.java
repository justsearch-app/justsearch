/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.conversation.spi.SelectionContextInjector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ModelStructuredPersistenceTest {
  @Test
  void extractionAndD5RulesEachSaveOneQuestionAndErrorEvenWhenBothRulesApply() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.exists(root.resolve("settings.gradle.kts"))) {
      root = root.getParent();
      if (root == null) throw new IllegalStateException("Repository root not found");
    }
    Path directory = Files.createTempDirectory(Files.createDirectories(root.resolve("tmp/no-evidence-tests")), "combined-");
    try {
      var store = new FileConversationStore(directory);
      var ai = mock(OnlineAiService.class);
      when(ai.isAvailable()).thenReturn(true);
      int count = 0;
      for (var scenario : List.of(List.of("core.extract", "test.selection"),
          List.of("core.rag-ask", RAGContext.ID), List.of("core.extract", RAGContext.ID))) {
        String shapeId = scenario.get(0);
        ContextInjector injector = new ContextInjector() {
          @Override public String id() { return scenario.get(1); }
          @Override public InjectorResult inject(ConversationContext context) {
            return InjectorResult.terminalError(new SseEvent("error", Map.of("error", "input failed", "errorCode", "FETCH_FAILED")));
          }
        };
        var shape = new ConversationShape(new ConversationShapeRef(shapeId),
            new Presentation(new I18nKey("test.label"), new I18nKey("test.description"), Optional.empty(), Optional.empty()),
            Audience.USER, Provenance.core("v1"), ExecutionMode.SUBSTRATE_DRIVEN, IterationMode.ONE_SHOT,
            PersistenceMode.EPHEMERAL, List.of(), List.of(injector.id()), List.of(), SingleHopController.ID, List.of(), true);
        var engine = new ConversationEngine(ConversationShapeCatalog.of("core", List.of(shape)), List.of(),
            PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of(injector,
                new SelectionContextInjector(mock(DocumentService.class), () -> ai))),
            StreamConsumerRegistry.of(List.of()), IterationControllerRegistry.of(List.of()), () -> ai, store);
        var events = new ArrayList<SseEvent>();
        engine.run(shape.id(), Map.of("conversationId", "mixed", "prompt", "question " + count, "question", "question " + count),
            Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal());
        count++;
        var history = new FileConversationStore(directory).loadHistory("mixed");
        assertEquals(count * 2, history.size(), "union of persistence rules saves exactly one exchange per attempt: " + scenario);
        assertEquals(shapeId, history.getLast().get("shapeId"));
        assertEquals(true, history.getLast().get("terminalError"));
        assertEquals("FETCH_FAILED", history.getLast().get("errorCode"));
        assertEquals("error", events.getLast().name());
        assertEquals(history.size(), history.stream().map(m -> m.get("id")).distinct().count());
      }
    } finally {
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
}
