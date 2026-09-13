/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.PendingToolApproval;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.app.services.conversation.WorkflowGateRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentApprovalPreviewTest {
  private static Context request(String session, String call) {
    var context = mock(Context.class);
    when(context.queryParam("sessionId")).thenReturn(session);
    when(context.queryParam("callId")).thenReturn(call);
    when(context.status(anyInt())).thenReturn(context);
    when(context.path()).thenReturn("/api/chat/approval");
    return context;
  }

  private static Map<?, ?> response(Context context) {
    var payload = ArgumentCaptor.forClass(Object.class);
    verify(context).json(payload.capture());
    return assertInstanceOf(Map.class, payload.getValue());
  }

  private static AgentController controller(AgentService service, WorkflowGateRegistry workflows) {
    var controller = new AgentController(new io.justsearch.core.execution.TestEngineExecutors(),
        () -> service, null, null, null);
    controller.setWorkflowGateRegistry(workflows);
    return controller;
  }

  @Test
  void agentLookupReturnsCompleteFrozenDisplayWithoutPublicBodyOrContinuation() {
    var service = mock(AgentService.class);
    String summary = "Frozen target " + "directory/".repeat(75);
    var detail = new AgentEvent.PendingApproval("call", "core.test", "{\"body\":\"PRIVATE_INPUT\"}",
        "medium", "typed_confirm");
    when(service.pendingToolApproval("session", "call")).thenReturn(Optional.of(
        new PendingToolApproval(detail, Optional.of(new OperationApprovalPreview(summary)))));
    var context = request("session", "call");
    controller(service, new WorkflowGateRegistry()).handleApproval(context);
    var response = response(context);
    assertEquals(summary, response.get("argsSummary"));
    assertEquals("TYPED_CONFIRM", response.get("gateBehavior")); assertEquals("MEDIUM", response.get("riskTier"));
    assertEquals("call", response.get("callId")); assertEquals("core.test", response.get("operationId"));
    assertFalse(response.toString().contains("PRIVATE_INPUT"));
    assertFalse(response.containsKey("operationKey")); assertFalse(response.containsKey("preparationNonce"));
    verify(context).header("Cache-Control", "no-store");
    verify(service, never()).tryApproveToolCall(any(), any());
  }

  @Test
  void workflowLookupUsesItsLiveOwnerAndDisappearsAfterTheReply() {
    var service = mock(AgentService.class);
    when(service.pendingToolApproval(any(), any())).thenReturn(Optional.empty());
    var workflows = new WorkflowGateRegistry();
    var detail = new AgentEvent.PendingApproval("inner", "core.workflow-test", "{}", "medium", "inline_confirm");
    var future = workflows.create("inner", new PendingToolApproval(detail,
        Optional.of(new OperationApprovalPreview("Frozen workflow target"))));
    var controller = controller(service, workflows);
    var context = request("outer-agent", "inner"); controller.handleApproval(context);
    assertEquals("Frozen workflow target", response(context).get("argsSummary"));
    assertFalse(future.isDone(), "Reading approval never answers it");
    assertTrue(controller.resolveApprovalGate("outer-agent", "inner", true, "unused"));
    assertTrue(future.join());
    var gone = request("outer-agent", "inner"); controller.handleApproval(gone);
    verify(gone).status(404);
  }

  @Test
  void missingCallIsRejectedAndRawFallbackUsesTheExistingBoundedSummary() {
    var service = mock(AgentService.class);
    var controller = controller(service, new WorkflowGateRegistry());
    var missing = request("session", null); controller.handleApproval(missing);
    verify(missing).status(400); verifyNoInteractions(service);
    String args = "{\"input\":\"" + "x".repeat(300) + "\"}";
    when(service.pendingToolApproval("session", "call")).thenReturn(Optional.of(new PendingToolApproval(
        new AgentEvent.PendingApproval("call", "core.test", args, "medium", "inline_confirm"), Optional.empty())));
    var context = request("session", "call"); controller.handleApproval(context);
    assertEquals(ArgsSummary.summarize(args), response(context).get("argsSummary"));
  }
}
