/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.*;
import io.justsearch.agent.api.registry.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentApprovalPreviewTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void liveLookupRetainsFrozenDisplayWithoutPuttingItInThePublicSnapshot(boolean approve) {
    var session = new AgentSession(List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    var registry = new AgentSessionRegistry(); registry.register("session", session);
    var detail = new AgentEvent.PendingApproval("call", "core.test", "{}", "medium", "typed_confirm");
    var preview = new OperationApprovalPreview("Frozen private target " + "segment/".repeat(80));
    var future = session.createApprovalGate("call", detail, Optional.of(preview));
    var pending = registry.pendingToolApproval("session", "call").orElseThrow();
    assertEquals(preview, pending.preview().orElseThrow()); assertEquals(detail, pending.detail());
    assertEquals(List.of(detail), session.pendingApprovals());
    var snapshot = new AgentEvent.StateSnapshot(0, 1000, 0, 0, "primary", session.pendingApprovals(),
        "ASSIST", session.parkSnapshot(), TraceContext.none());
    assertFalse(AgentEventPayloads.base(snapshot).toString().contains("Frozen private target"));
    assertFalse(pending.toString().contains("Frozen private target"));
    assertTrue(registry.pendingToolApproval("other-session", "call").isEmpty());
    assertTrue(registry.pendingToolApproval(null, "call").isEmpty());
    assertTrue(registry.pendingToolApproval("session", null).isEmpty());
    assertTrue(approve ? registry.tryApproveToolCall("session", "call")
        : registry.tryRejectToolCall("session", "call", "declined"));
    assertEquals(approve, future.join());
    assertTrue(registry.pendingToolApproval("session", "call").isEmpty());
    assertTrue(session.pendingApprovals().isEmpty());
  }

  @Test
  void cancellationHidesThePreviewBeforeTheWaiterFinishesCleanup() {
    var session = new AgentSession(List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    var detail = new AgentEvent.PendingApproval("call", "core.test", "{}", "medium", "inline_confirm");
    var future = session.createApprovalGate("call", detail,
        Optional.of(new OperationApprovalPreview("Frozen private target")));
    session.cancel();
    assertFalse(future.join());
    assertTrue(session.pendingToolApproval("call").isEmpty());
  }

  @Test
  void failedAnnouncementRetiresTheGateBeforeAnotherLookup() {
    var session = new AgentSession(List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    var id = new OperationRef("core.gate-fixture");
    var op = new Operation(id, Presentation.of(new I18nKey("test.label"), new I18nKey("test.description")),
        Interface.of("{}", "{}"), new OperationPolicy(RiskTier.MEDIUM, ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE, RetryPolicy.noRetry(), Set.of(), false), OperationAvailability.empty(),
        OperationLineage.empty(), Binding.of(id), Provenance.core("1"), Set.of(ExecutorTag.AGENT));
    var dispatcher = new AgentToolDispatcher(null, AgentTelemetry.noop(), () -> null, () -> null, () -> null);
    var failure = assertThrows(IllegalStateException.class, () -> dispatcher.prepareAndApprove(session,
        new ToolCallRequest("call", "core_gate_fixture", "{}"), op, event -> {
          throw new IllegalStateException("fixture announcement failed");
        }, false));
    assertEquals("fixture announcement failed", failure.getMessage());
    assertTrue(session.pendingToolApproval("call").isEmpty(), "An abandoned gate must not remain readable or answerable");
    assertTrue(session.pendingApprovals().isEmpty());
  }
}
