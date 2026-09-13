/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.agent.api.*;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.*;
import io.justsearch.agent.RunEventStore;
import io.justsearch.app.services.intent.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WorkflowBackgroundDelegationTest {
  private static final class Fixture {
    final AgentService agent = mock(AgentService.class);
    final List<Boolean> observedPostures = new ArrayList<>();
    final Workflow workflow;
    final ConversationEngine engine;
    final WorkflowShapeRunner workflowRunner;
    final WorkflowGateRegistry gates = new WorkflowGateRegistry();
    Fixture() { this(RunEventStore.noop(), false); }
    Fixture(RunEventStore store, boolean gateOnly) {
      workflow = new Workflow(new WorkflowRef("core.background-fixture"),
          Presentation.of(new I18nKey("test.label"), new I18nKey("test.description")), Provenance.core("1"),
          Audience.USER, List.of(gateOnly ? new WorkflowNode.GateStep("auto", ConfirmStrategy.None.INSTANCE)
              : new WorkflowNode.LlmStep("delegate", AgentRunShape.ID, "do work")),
          List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
      when(agent.isAvailable()).thenReturn(true);
      when(agent.availableOperations()).thenReturn(List.of());
      doAnswer(invocation -> {
        observedPostures.add(invocation.getArgument(2));
        Consumer<AgentEvent> sink = invocation.getArgument(1);
        sink.accept(new AgentEvent.AgentDone("done", 1, 0, 1, TraceContext.none()));
        return null;
      }).when(agent).runAgent(any(), any(), anyBoolean(), any());
      var holder = new AtomicReference<ConversationEngine>();
      workflowRunner = new WorkflowShapeRunner(holder::get, WorkflowCatalog.of("core", List.of(workflow)),
          () -> OperationCatalog.of("core", List.of()), () -> OperationCatalog.of("core", List.of()),
          mock(GatedOperationExecutor.class), gates, store,
          new IntentGateEvaluator(new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog()));
      engine = new ConversationEngine(CoreConversationShapeCatalog.catalog(),
          List.of(new ToolIteratingShapeRunner(() -> agent), workflowRunner));
      holder.set(engine);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void persistedWorkflowPostureMatchesItsServerOwnerAtStartAndCompletion(
      boolean background, boolean nested, @org.junit.jupiter.api.io.TempDir Path directory) {
    var store = new RunEventStore(directory.resolve("runs"));
    var initial = new HashMap<String, tools.jackson.databind.JsonNode>();
    store.addEventListener((sessionId, event) -> {
      if ("session_started".equals(event.get("eventType"))) initial.put(sessionId, readMeta(store, sessionId));
    });
    var f = new Fixture(store, nested);
    var context = io.justsearch.app.services.TestEngineContexts.internal();
    if (nested) {
      var bridge = new WorkflowToolRunnerImpl(WorkflowCatalog.of("core", List.of(f.workflow)),
          f.workflowRunner::run, f.gates);
      var op = WorkflowOperationProjection.toOperation(f.workflow, ref -> Optional.empty()).orElseThrow();
      var result = bridge.run(op.id(), "{}", event -> {}, context, background);
      assertTrue(result.success(), result.toString());
    } else {
      f.engine.run(WorkflowRunShape.ID, Map.of("workflowId", f.workflow.id().value(), "backgroundRun", !background),
          Audience.USER, event -> {}, context, background);
    }
    assertEquals(nested ? List.of() : List.of(background), f.observedPostures);
    assertEquals(1, initial.size(), "The real store must publish its opening record");
    var opening = initial.entrySet().iterator().next();
    assertEquals("RUNNING", opening.getValue().get("state").asText());
    assertEquals(background, opening.getValue().get("background").asBoolean());
    var completed = readMeta(store, opening.getKey());
    assertEquals("DONE", completed.get("state").asText());
    assertEquals(background, completed.get("background").asBoolean());
  }

  private static tools.jackson.databind.JsonNode readMeta(RunEventStore store, String sessionId) {
    try {
      return new tools.jackson.databind.ObjectMapper().readTree(Files.readString(store.metaPath(sessionId)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
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
