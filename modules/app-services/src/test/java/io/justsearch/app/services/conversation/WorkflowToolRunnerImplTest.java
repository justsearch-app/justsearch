package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 560 WS5 — the streaming bridge from a projected workflow tool call to {@code
 * WorkflowShapeRunner}. Uses a fake {@link WorkflowToolRunnerImpl.WorkflowExecutor} that emits the
 * runner's SSE vocabulary so the SSE→AgentEvent translation + terminal-result capture are verified
 * without the live engine.
 */
final class WorkflowToolRunnerImplTest {

  private static final OperationRef DEMO_OP = new OperationRef("core.workflow-demo-compose");

  private WorkflowToolRunnerImpl runnerWith(WorkflowToolRunnerImpl.WorkflowExecutor executor) {
    return new WorkflowToolRunnerImpl(CoreWorkflowCatalog.catalog(), executor);
  }

  @Test
  void handlesOnlyProjectedWorkflowRefs() {
    WorkflowToolRunnerImpl runner = runnerWith((body, audience, sink, engineContext) -> {});
    assertTrue(runner.handles(DEMO_OP), "a projected workflow op is handled");
    assertFalse(
        runner.handles(new OperationRef("core.restart-worker")), "a normal op is not handled");
    assertFalse(
        runner.handles(new OperationRef("core.workflow-does-not-exist")),
        "a workflow ref with no catalog entry is not handled");
  }

  @Test
  void streamsNodeProgressAsAgentProgressAndReturnsFinalResponse() {
    // A fake workflow run that emits the real SSE vocabulary, terminating in `done`.
    WorkflowToolRunnerImpl.WorkflowExecutor fake =
        (body, audience, sink, engineContext) -> {
          // The runner sets the body itself — assert it routed the right workflow.
          assertEquals("core.demo-compose", body.get("workflowId"));
          sink.accept(new SseEvent("session_started", Map.of("sessionId", "s1")));
          sink.accept(new SseEvent("workflow_started", Map.of("workflowId", "core.demo-compose", "nodeCount", 2)));
          sink.accept(new SseEvent("node_started", Map.of("nodeId", "n0", "index", 0)));
          sink.accept(new SseEvent("node_completed", Map.of("nodeId", "n0", "index", 0)));
          sink.accept(new SseEvent("done", Map.of("finalResponse", "the composed answer", "nodesExecuted", 2)));
        };

    List<AgentEvent> events = new ArrayList<>();
    OperationResult result = runnerWith(fake).run(DEMO_OP, "{}", events::add, io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success(), "a workflow that reaches `done` succeeds");
    assertEquals("the composed answer", result.message());

    // Every workflow SSE event is forwarded as an AgentProgress — the live streaming contract.
    assertFalse(events.isEmpty());
    assertTrue(events.stream().allMatch(e -> e instanceof AgentEvent.AgentProgress));
    AgentEvent.AgentProgress started =
        events.stream()
            .map(AgentEvent.AgentProgress.class::cast)
            .filter(p -> p.phase().equals("workflow:node_started"))
            .findFirst()
            .orElseThrow();
    assertEquals(2, started.maxIterations(), "nodeCount propagates as the progress denominator");
  }

  @Test
  void workflowErrorBecomesAFailureResultNotAnException() {
    WorkflowToolRunnerImpl.WorkflowExecutor failing =
        (body, audience, sink, engineContext) ->
            sink.accept(new SseEvent("error", Map.of("error", "node n1 blew up")));
    List<AgentEvent> events = new ArrayList<>();
    OperationResult result = runnerWith(failing).run(DEMO_OP, "{}", events::add, io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success(), "a workflow error is a recoverable failure result");
    assertTrue(result.message().contains("node n1 blew up"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"medium", "high"})
  void nestedWorkflowApprovalControlsRemainAnswerable(String risk) {
    WorkflowToolRunnerImpl.WorkflowExecutor fake = (body, audience, sink, context) -> {
      sink.accept(new SseEvent("tool_call_pending", Map.of("callId", "inner-call", "toolName", "core.test",
          "arguments", "{\"public\":true}", "risk", risk)));
      sink.accept(new SseEvent("tool_call_approved", Map.of("callId", "inner-call")));
      sink.accept(new SseEvent("tool_call_rejected", Map.of("callId", "inner-call", "reason", "declined")));
      sink.accept(new SseEvent("done", Map.of("finalResponse", "done")));
    };
    List<AgentEvent> events = new ArrayList<>();
    assertTrue(runnerWith(fake).run(DEMO_OP, "{}", events::add,
        io.justsearch.app.services.TestEngineContexts.internal()).success());
    var pending = org.junit.jupiter.api.Assertions.assertInstanceOf(AgentEvent.ToolCallPendingApproval.class, events.get(0));
    assertEquals("inner-call", pending.callId()); assertEquals("core.test", pending.toolName());
    assertEquals("{\"public\":true}", pending.arguments());
    assertEquals(risk.toUpperCase(java.util.Locale.ROOT), pending.risk().name());
    assertEquals(risk.equals("high") ? io.justsearch.agent.api.registry.GateBehavior.TYPED_CONFIRM
        : io.justsearch.agent.api.registry.GateBehavior.INLINE_CONFIRM, pending.gateBehavior(),
        "An explicit workflow wait cannot become AUTO under the enclosing agent's dial");
    assertEquals("inner-call", org.junit.jupiter.api.Assertions.assertInstanceOf(
        AgentEvent.ToolCallApproved.class, events.get(1)).callId());
    var rejected = org.junit.jupiter.api.Assertions.assertInstanceOf(AgentEvent.ToolCallRejected.class, events.get(2));
    assertEquals("inner-call", rejected.callId()); assertEquals("declined", rejected.reason());
  }

  @Test
  void executorThrowBecomesAFailureResult() {
    WorkflowToolRunnerImpl.WorkflowExecutor throwing =
        (body, audience, sink, engineContext) -> {
          throw new IllegalStateException("engine offline");
        };
    OperationResult result = runnerWith(throwing).run(DEMO_OP, "{}", e -> {}, io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success(), "a runner exception never escapes — it becomes a failure result");
    assertTrue(result.message().contains("engine offline"));
  }
}
