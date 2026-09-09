package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.app.services.conversation.WorkflowGateRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 565 §15.C enforcement — the unified approval dispatch is ONE backend authority over BOTH
 * gate types ("a run is a run" all the way down). Before this, the FE branched the URL by run shape to
 * two endpoints ({@code /api/chat/agent/*} via the {@code AgentSession} gate; {@code
 * /api/chat/workflow/*} via {@link WorkflowGateRegistry}); now a single {@code /api/chat/{approve,reject}}
 * dispatches agent-gate → workflow-gate → not-found. This pins that dispatch decision
 * ({@link AgentController#resolveApprovalGate}) Context-free.
 */
final class AgentControllerApprovalDispatchTest {

  /** A stub agent service whose try-approve/reject succeeds only for one known (session, callId). */
  private static final class GateStubService implements AgentService {
    private final String knownSession;
    private final String knownCallId;
    private String lastApprovedCallId;
    private String lastRejectedCallId;

    GateStubService(String session, String callId) {
      this.knownSession = session;
      this.knownCallId = callId;
    }

    @Override
    public boolean tryApproveToolCall(String sessionId, String callId) {
      if (knownSession.equals(sessionId) && knownCallId.equals(callId)) {
        lastApprovedCallId = callId;
        return true;
      }
      return false;
    }

    @Override
    public boolean tryRejectToolCall(String sessionId, String callId, String reason) {
      if (knownSession.equals(sessionId) && knownCallId.equals(callId)) {
        lastRejectedCallId = callId;
        return true;
      }
      return false;
    }

    @Override
    public void runAgent(AgentRequest request, Consumer<AgentEvent> eventConsumer, EngineContext engineContext) {}

    @Override
    public void approveToolCall(String sessionId, String callId) {
      tryApproveToolCall(sessionId, callId);
    }

    @Override
    public void rejectToolCall(String sessionId, String callId, String reason) {
      tryRejectToolCall(sessionId, callId, reason);
    }

    @Override
    public void cancelSession(String sessionId) {}

    @Override
    public List<Operation> availableOperations() {
      return List.of();
    }

    /** No catalog to filter, so the offering is the (empty) available set. */
    @Override
    public List<Operation> offeredOperations() {
      return availableOperations();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }

  private static AgentController controller(AgentService svc, WorkflowGateRegistry reg) {
    AgentController ctrl = new AgentController(new io.justsearch.core.execution.TestEngineExecutors(), () -> svc, null, null, null);
    if (reg != null) {
      ctrl.setWorkflowGateRegistry(reg);
    }
    return ctrl;
  }

  @Test
  void approvesAnAgentGateByCallId() {
    GateStubService svc = new GateStubService("sess-a", "call-agent");
    AgentController ctrl = controller(svc, new WorkflowGateRegistry());

    assertTrue(ctrl.resolveApprovalGate("sess-a", "call-agent", true, "User rejected"));
    assertEquals("call-agent", svc.lastApprovedCallId);
  }

  @Test
  void rejectsAnAgentGateByCallId() {
    GateStubService svc = new GateStubService("sess-a", "call-agent");
    AgentController ctrl = controller(svc, new WorkflowGateRegistry());

    assertTrue(ctrl.resolveApprovalGate("sess-a", "call-agent", false, "User rejected"));
    assertEquals("call-agent", svc.lastRejectedCallId);
  }

  @Test
  void fallsThroughToTheWorkflowGateWhenNoAgentGateMatches() throws Exception {
    GateStubService svc = new GateStubService("sess-a", "call-agent"); // won't match the workflow callId
    WorkflowGateRegistry reg = new WorkflowGateRegistry();
    CompletableFuture<Boolean> gate = reg.create("call-wf");
    AgentController ctrl = controller(svc, reg);

    // sessionId null (a workflow run has no session); the agent gate returns false, so the dispatch
    // falls through to the workflow registry — the ONE endpoint resolves BOTH gate types.
    assertTrue(ctrl.resolveApprovalGate(null, "call-wf", true, "User rejected"));
    assertTrue(gate.isDone());
    assertTrue(gate.get()); // approved=true was delivered to the runner
    assertNull(svc.lastApprovedCallId); // the agent gate was NOT used
  }

  @Test
  void rejectsAWorkflowGate() throws Exception {
    GateStubService svc = new GateStubService("sess-a", "call-agent");
    WorkflowGateRegistry reg = new WorkflowGateRegistry();
    CompletableFuture<Boolean> gate = reg.create("call-wf");
    AgentController ctrl = controller(svc, reg);

    assertTrue(ctrl.resolveApprovalGate(null, "call-wf", false, "no thanks"));
    assertTrue(gate.isDone());
    assertFalse(gate.get()); // rejected=false delivered
  }

  @Test
  void returnsFalseForAnUnknownCallId() {
    GateStubService svc = new GateStubService("sess-a", "call-agent");
    AgentController ctrl = controller(svc, new WorkflowGateRegistry());

    // Neither the agent gate nor the workflow registry knows this callId → the dispatch returns false
    // (the handler maps that to 404).
    assertFalse(ctrl.resolveApprovalGate("sess-a", "nope", true, "User rejected"));
  }
}
