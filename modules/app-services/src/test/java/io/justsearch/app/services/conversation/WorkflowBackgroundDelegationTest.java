/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.agent.api.*;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.services.intent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WorkflowBackgroundDelegationTest {
  private static final class Fixture {
    final AgentService agent = mock(AgentService.class);
    final List<Boolean> observedPostures = new ArrayList<>();
    final Workflow workflow = new Workflow(new WorkflowRef("core.background-fixture"),
        Presentation.of(new I18nKey("test.label"), new I18nKey("test.description")), Provenance.core("1"),
        Audience.USER, List.of(new WorkflowNode.LlmStep("delegate", AgentRunShape.ID, "do work")),
        List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
    final ConversationEngine engine;
    Fixture() {
      when(agent.isAvailable()).thenReturn(true);
      when(agent.availableOperations()).thenReturn(List.of());
      doAnswer(invocation -> {
        observedPostures.add(invocation.getArgument(2));
        Consumer<AgentEvent> sink = invocation.getArgument(1);
        sink.accept(new AgentEvent.AgentDone("done", 1, 0, 1, TraceContext.none()));
        return null;
      }).when(agent).runAgent(any(), any(), anyBoolean(), any());
      var holder = new AtomicReference<ConversationEngine>();
      var workflowRunner = new WorkflowShapeRunner(holder::get, WorkflowCatalog.of("core", List.of(workflow)),
          () -> OperationCatalog.of("core", List.of()), () -> OperationCatalog.of("core", List.of()),
          mock(GatedOperationExecutor.class), new WorkflowGateRegistry(),
          new IntentGateEvaluator(new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog()));
      engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(),
          List.of(new ToolIteratingShapeRunner(() -> agent), workflowRunner));
      holder.set(engine);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void serverPostureSurvivesWorkflowAndLlmDelegationDespiteOppositeCallerInput(boolean background) {
    var f = new Fixture(); var events = new ArrayList<SseEvent>();
    f.engine.run(WorkflowRunShape.ID,
        Map.of("workflowId", f.workflow.id().value(), "backgroundRun", !background),
        Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.internal(), background);
    assertEquals(List.of(background), f.observedPostures);
    assertFalse(events.stream().anyMatch(e -> e.name().equals("error")), events.toString());
    assertTrue(events.stream().anyMatch(e -> e.name().equals("done")));
    verify(f.agent, never()).runAgent(any(), any(), any());
  }

  @Test void ordinaryIngressOverwritesACallerClaimToBeABackgroundRun() {
    var f = new Fixture();
    f.engine.run(AgentRunShape.ID,
        Map.of("messages", List.of(Map.of("role", "user", "content", "hi")), "backgroundRun", true),
        Audience.USER, event -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals(List.of(false), f.observedPostures);
  }
}
