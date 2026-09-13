/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.agent.api.*;
import io.justsearch.agent.api.registry.*;
import io.justsearch.core.context.EngineContext;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentPreparedContinuationTest {
  private static Operation operation(RiskTier risk) {
    var id = new OperationRef("core.prepared-fixture");
    return new Operation(id, Presentation.of(new I18nKey("test.label"), new I18nKey("test.description")),
        Interface.of("{}", "{}"), new OperationPolicy(risk, ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE, RetryPolicy.autoRetry(1, id.value()), Set.of(), false),
        OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id), Provenance.core("1"),
        Set.of(ExecutorTag.AGENT));
  }

  private static final class Fixture implements BackendIntentRouter, ConsentCapsuleAuthority {
    final OperationDispatchPlan.Ready ready = new OperationDispatchPlan.Ready("key", UUID.randomUUID(),
        Optional.of(new OperationApprovalPreview("Frozen target " + "segment/".repeat(80))));
    final AgentSession session = new AgentSession(List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    final ToolCallRequest call = new ToolCallRequest("call", "core_prepared_fixture", "{}");
    int preparations;
    int dispatches;
    int mints;
    int policyReads;
    boolean recorded;
    boolean failFirst;
    GateBehavior behavior = GateBehavior.INLINE_CONFIRM;
    final AgentToolDispatcher subject = new AgentToolDispatcher(null, AgentTelemetry.noop(), () -> this,
        () -> this, () -> (risk, autonomy, reversible, confirm) -> { policyReads++; return behavior; });

    @Override public OperationDispatchPlan prepare(Intent intent, InvocationProvenance provenance,
        EngineContext context, String key, boolean includePreview) {
      preparations++;
      assertEquals(0, dispatches); assertEquals(0, mints);
      assertTrue(includePreview);
      return recorded ? new OperationDispatchPlan.Recorded("key", OperationResult.success("old receipt")) : ready;
    }
    @Override public IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance,
        EngineContext context) { throw new AssertionError("Unkeyed route must never serve a prepared continuation"); }
    @Override public IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance,
        EngineContext context, String key, UUID nonce) {
      dispatches++; assertEquals("key", key);
      assertEquals(recorded ? null : ready.preparationNonce(), nonce);
      assertEquals(call.arguments(), ((ShellAddress.Invocation) intent.address()).argsJson());
      if (failFirst && dispatches == 1) throw new IllegalStateException("transient fixture failure");
      return new IntentDispatchResult.Dispatched(OperationResult.success(recorded ? "fresh receipt" : "executed"));
    }
    @Override public String mint(String operation, String args, SourceTier source) {
      throw new AssertionError("Prepared route must not mint public-only consent");
    }
    @Override public String mintPrepared(String operation, String args, SourceTier source, String key, UUID nonce) {
      mints++; assertEquals("key", key); assertEquals(ready.preparationNonce(), nonce); return "prepared-token";
    }
    @Override public boolean verifyAndConsume(String token, String operation, String args) { return false; }
  }

  @Test void watchLowPreparesBeforeApprovalAndKeepsOnePlanAcrossRetries() {
    var f = new Fixture(); f.failFirst = true;
    var op = operation(RiskTier.LOW);
    f.session.setAutonomyLevel(AutonomyLevel.WATCH);
    var approval = f.subject.prepareAndApprove(f.session, f.call, op, event -> {
      assertInstanceOf(AgentEvent.ToolCallPendingApproval.class, event);
      assertEquals(1, f.preparations); assertEquals(0, f.dispatches); assertEquals(0, f.mints);
      var pending = f.session.pendingToolApproval("call").orElseThrow();
      assertEquals(f.ready.approvalPreview(), pending.preview());
      assertFalse(AgentEventPayloads.base(event).toString().contains("Frozen target"));
      f.session.approve("call");
    }, false);
    assertTrue(approval.approved()); assertSame(f.ready, approval.plan());
    var result = f.subject.executeOperationWithPolicy(op, f.call, "agent-test-session",
        f.session.engineContext(), approval.plan());
    assertTrue(result.success()); assertEquals(2, f.dispatches); assertEquals(2, f.mints);
    assertEquals(1, f.preparations); assertEquals(1, f.policyReads);
    assertTrue(f.session.pendingToolApproval("call").isEmpty());
  }

  @Test void recordedOutcomeSkipsAnotherPromptAndQueriesTheCurrentReceiptWithoutConsent() {
    var f = new Fixture(); f.recorded = true;
    var op = operation(RiskTier.MEDIUM);
    var approval = f.subject.prepareAndApprove(f.session, f.call, op,
        event -> fail("Recorded receipt must not announce a new prompt"), false);
    var result = f.subject.executeOperationWithPolicy(op, f.call, "agent-test-session",
        f.session.engineContext(), approval.plan());
    assertEquals("fresh receipt", result.message()); assertEquals(0, f.mints); assertEquals(1, f.dispatches);
  }

  @Test void rejectionLeavesNoDispatchOrConsent() {
    var f = new Fixture();
    var approval = f.subject.prepareAndApprove(f.session, f.call, operation(RiskTier.MEDIUM),
        event -> f.session.reject("call"), false);
    assertFalse(approval.approved()); assertEquals(1, f.preparations);
    assertEquals(0, f.dispatches); assertEquals(0, f.mints);
  }

  @Test void backgroundRefusesBeforePreparationAndNeverCreatesAHumanWait() {
    var f = new Fixture(); f.session.markBackground();
    var approval = f.subject.prepareAndApprove(f.session, f.call, operation(RiskTier.MEDIUM),
        event -> assertInstanceOf(AgentEvent.ToolCallRejected.class, event), false);
    assertFalse(approval.approved()); assertEquals(0, f.preparations);
    assertTrue(f.session.pendingApprovals().isEmpty());
  }

  @Test void projectedWorkflowDoesNotEnterTheOperationHandlerPreparationPath() {
    var f = new Fixture(); f.behavior = GateBehavior.AUTO;
    var approval = f.subject.prepareAndApprove(f.session, f.call, operation(RiskTier.LOW),
        event -> fail("Auto workflow wrapper must not open a gate"), true);
    assertTrue(approval.approved()); assertNull(approval.plan()); assertEquals(0, f.preparations);
  }
}
