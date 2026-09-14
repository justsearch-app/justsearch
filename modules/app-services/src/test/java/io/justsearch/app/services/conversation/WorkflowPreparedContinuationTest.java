/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.agent.api.*;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.services.intent.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowPreparedContinuationTest {
  private static final class Fixture {
    final OperationRef opId = new OperationRef("core.prepared-node");
    final Operation op = new Operation(opId, Presentation.of(new I18nKey("test.label"), new I18nKey("test.description")),
        Interface.of("{}", "{}"), new OperationPolicy(RiskTier.LOW, new ConfirmStrategy.Typed(new I18nKey("confirm")),
            AuditPolicy.NONE, RetryPolicy.noRetry(), Set.of(), false), OperationAvailability.empty(),
        OperationLineage.empty(), Binding.of(opId), Provenance.core("1"), Set.of(ExecutorTag.AGENT));
    final Workflow workflow;
    final WorkflowCatalog catalog;
    final WorkflowGateRegistry gates = new WorkflowGateRegistry();
    final GatedOperationExecutor executor = mock(GatedOperationExecutor.class);
    final OperationDispatchPlan.Ready plan = new OperationDispatchPlan.Ready("workflow-key", UUID.randomUUID(),
        Optional.of(new OperationApprovalPreview("Frozen target " + "segment/".repeat(80))));
    final WorkflowShapeRunner runner;
    Fixture(boolean explicitGate) {
      workflow = new Workflow(new WorkflowRef("core.prepared-workflow"), op.presentation(), Provenance.core("1"),
          Audience.USER, List.of(explicitGate
              ? new WorkflowNode.GateStep("gate", ConfirmStrategy.Inline.INSTANCE)
              : new WorkflowNode.ToolStep("tool", opId, "{}")),
          List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
      catalog = WorkflowCatalog.of("core", List.of(workflow));
      var engine = new ConversationEngine(ConversationShapeCatalog.of("core", List.of()), List.of());
      runner = new WorkflowShapeRunner(() -> engine, catalog,
          () -> OperationCatalog.of("core", List.of(op)), () -> OperationCatalog.of("core", List.of()),
          executor, gates, new IntentGateEvaluator(new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog()));
      when(executor.prepare(eq(op), eq("{}"), any(), isNull(), eq(true))).thenReturn(plan);
      when(executor.routePrepared(eq(op), eq("{}"), any(), any())).thenReturn(OperationResult.success("done"));
    }
  }

  @Test void nestedLowTypedGateKeepsFrozenDisplayPrivateAndProjectsBothSessionAliases() {
    var f = new Fixture(false);
    var context = io.justsearch.app.services.TestEngineContexts.internal();
    // The enclosing run alias must be explicit in the incoming context.
    context = EngineProvenance.rebase(context, Optional.of("outer-agent"), TransportTag.AGENT_LOOP,
        context.survival(), context.urgency());
    var bridge = new WorkflowToolRunnerImpl(f.catalog, f.runner::run, f.gates);
    var pending = new ArrayList<AgentEvent.ToolCallPendingApproval>();
    var result = bridge.run(WorkflowOperationProjection.toOperation(f.workflow, ref -> Optional.of(f.op)).orElseThrow().id(), "{}", event -> {
      if (event instanceof AgentEvent.ToolCallPendingApproval gate) {
        pending.add(gate);
        assertEquals(GateBehavior.TYPED_CONFIRM, gate.gateBehavior());
        assertEquals(RiskTier.LOW, gate.risk());
        assertEquals(f.plan.approvalPreview(), f.gates.pendingToolApproval(gate.callId()).orElseThrow().preview());
        var preparedContext = org.mockito.ArgumentCaptor.forClass(io.justsearch.core.context.EngineContext.class);
        verify(f.executor).prepare(eq(f.op), eq("{}"), preparedContext.capture(), isNull(), eq(true));
        assertEquals(1, bridge.pendingApprovals(preparedContext.getValue().sessionId().orElseThrow()).size());
        var publicGates = bridge.pendingApprovals("outer-agent");
        assertEquals(1, publicGates.size()); assertEquals(gate.callId(), publicGates.getFirst().callId());
        assertTrue(bridge.pendingApprovals("unrelated").isEmpty());
        assertFalse(AgentEventPayloads.base(event).toString().contains("Frozen target"));
        verify(f.executor, never()).routePrepared(any(), any(), any(), any());
        assertTrue(f.gates.complete(gate.callId(), true));
      }
    }, context, false);
    assertTrue(result.success()); assertEquals(1, pending.size());
    assertTrue(bridge.pendingApprovals("outer-agent").isEmpty());
    verify(f.executor).prepare(eq(f.op), eq("{}"), any(), isNull(), eq(true));
    verify(f.executor).routePrepared(eq(f.op), eq("{}"), same(f.plan), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void backgroundRefusesToolAndExplicitGateWithoutPreparationOrHumanWait(boolean explicitGate) {
    var f = new Fixture(explicitGate); var events = new ArrayList<SseEvent>();
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> f.runner.run(
        Map.of("workflowId", f.workflow.id().value()), Audience.USER, events::add,
        io.justsearch.app.services.TestEngineContexts.internal(), true));
    assertTrue(events.stream().anyMatch(e -> e.name().equals("tool_call_rejected")));
    assertFalse(events.stream().anyMatch(e -> e.name().equals("tool_call_pending")));
    verifyNoInteractions(f.executor);
  }

  @Test void nestedBackgroundRefusalIsAFailureResultAndCarriesTheServerPosture() {
    var f = new Fixture(false);
    var bridge = new WorkflowToolRunnerImpl(f.catalog, f.runner::run, f.gates);
    var events = new ArrayList<AgentEvent>();
    var result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> bridge.run(
        WorkflowOperationProjection.toOperation(f.workflow, ref -> Optional.of(f.op)).orElseThrow().id(),
        "{}", events::add, io.justsearch.app.services.TestEngineContexts.internal(), true));
    assertFalse(result.success());
    assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallRejected));
    assertFalse(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallPendingApproval));
    verifyNoInteractions(f.executor);
  }

  @Test void recordedRowAnswersWithoutAnotherGateAndRetainsTheRecordedReference() {
    var f = new Fixture(false);
    var recorded = new OperationDispatchPlan.Recorded("workflow-key", OperationResult.success("old receipt"));
    when(f.executor.prepare(eq(f.op), eq("{}"), any(), isNull(), eq(true))).thenReturn(recorded);
    var events = new ArrayList<SseEvent>();
    f.runner.run(Map.of("workflowId", f.workflow.id().value()), Audience.USER, events::add,
        io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(events.stream().anyMatch(e -> e.name().equals("tool_call_pending")));
    verify(f.executor).routePrepared(eq(f.op), eq("{}"), same(recorded), any());
  }
}
