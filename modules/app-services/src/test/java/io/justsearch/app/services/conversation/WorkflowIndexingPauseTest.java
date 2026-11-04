/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.conversation.*;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.chat.AnswersPausedException;
import io.justsearch.app.services.intent.*;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkflowIndexingPauseTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void llmStepPreservesTypedPauseAndNeverRunsTheNextNode(boolean thrown) {
    var calls = new AtomicInteger();
    ShapeRunner child = new ShapeRunner() {
      public ConversationShapeRef shapeId() { return new ConversationShapeRef("core.free-chat"); }
      public void run(Map<String, Object> body, Audience audience, Consumer<SseEvent> sink,
          EngineContext context) {
        calls.incrementAndGet();
        if (thrown) throw new AnswersPausedException();
        sink.accept(ConversationEngine.refusalEvent(new AnswersPausedException()));
      }
    };
    var shape = new ConversationShape(child.shapeId(),
        Presentation.of(new I18nKey("test.label"), new I18nKey("test.description")),
        Audience.USER, Provenance.core("1"), ExecutionMode.SHAPE_DRIVEN, IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL, List.of(), List.of(), List.of(), null,
        EventDescriptor.namesOnly(List.of("error", "done")), false);
    var engine = new ConversationEngine(ConversationShapeCatalog.of("core", List.of(shape)), List.of(child));
    var runner = new WorkflowShapeRunner(() -> engine, CoreWorkflowCatalog.catalog(),
        () -> OperationCatalog.of("core", List.of()), () -> OperationCatalog.of("core", List.of()),
        new GatedOperationExecutor(() -> (intent, provenance, context) -> {
          throw new AssertionError("an LLM-only workflow must not execute tools");
        }, () -> null, TransportTag.WORKFLOW), new WorkflowGateRegistry(),
        new IntentGateEvaluator(new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog()));
    var events = new ArrayList<SseEvent>();
    assertDoesNotThrow(() -> runner.run(Map.of(), Audience.USER, events::add,
        io.justsearch.app.services.TestEngineContexts.ui()),
        "a paused workflow must return its typed event to the caller");
    var errors = events.stream().filter(e -> e.name().equals("error")).toList();
    assertEquals(1, errors.size());
    assertEquals("CHAT_UNAVAILABLE", errors.getFirst().payload().get("errorCode"));
    assertEquals("INDEXING", errors.getFirst().payload().get("reason"));
    assertEquals("Answers paused while indexing", errors.getFirst().payload().get("error"));
    assertEquals(false, errors.getFirst().payload().get("forceAllowed"));
    assertEquals(1, calls.get(), "a refused first node must not run the second LLM node");
    assertFalse(events.stream().anyMatch(e -> List.of("done", "node_completed", "node_output").contains(e.name())));
  }
}
