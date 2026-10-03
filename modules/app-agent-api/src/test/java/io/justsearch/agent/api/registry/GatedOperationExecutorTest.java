package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.agent.api.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Verifies the shared gate→consent→route primitive (tempdoc 560 Phase 2). */
class GatedOperationExecutorTest {

  private static Operation op(String id, RiskTier risk) {
    OperationRef ref = new OperationRef(id);
    return new Operation(
        ref,
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        Interface.of("{}", "{}"),
        new OperationPolicy(
            risk,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ref),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static BackendIntentRouter routerReturning(
      OperationResult result, List<Intent> captured) {
    return (intent, provenance, context) -> {
      captured.add(intent);
      return new IntentDispatchResult.Dispatched(result);
    };
  }

  /** Captures the dispatched {@link InvocationProvenance} (the prior stub discarded it). */
  private static BackendIntentRouter routerCapturingProvenance(
      OperationResult result, List<InvocationProvenance> captured) {
    return (intent, provenance, context) -> {
      captured.add(provenance);
      return new IntentDispatchResult.Dispatched(result);
    };
  }

  @Test
  void routeApprovedDispatchesThroughTheIntentRouter() {
    List<Intent> captured = new ArrayList<>();
    GatedOperationExecutor ex =
        new GatedOperationExecutor(
            () -> routerReturning(OperationResult.success("42"), captured), () -> null);

    OperationResult result =
        ex.routeApproved(
            op("vendor.x.add", RiskTier.LOW),
            "{\"a\":2}",
            TestEngineContexts.agentLoop());

    assertTrue(result.success());
    assertEquals("42", result.message());
    assertEquals(1, captured.size(), "the call must be routed through the intent layer");
    assertEquals(
        TransportTag.AGENT_LOOP,
        captured.get(0).transport(),
        "a workflow/agent tool call reuses the AGENT_LOOP transport — no new mechanism");
  }

  @Test
  void executeAutoApprovesLowRiskWithoutPromptingTheGate() {
    boolean[] prompted = {false};
    GatedOperationExecutor ex =
        new GatedOperationExecutor(
            () -> routerReturning(OperationResult.success("ok"), new ArrayList<>()), () -> null);

    OperationResult result =
        ex.execute(
            op("vendor.x.read", RiskTier.LOW),
            "c1",
            "read",
            "{}",
            (callId, tool, args, risk) -> prompted[0] = true,
            callId -> {
              fail("LOW-risk op must not request approval");
              return CompletableFuture.completedFuture(false);
            },
            TestEngineContexts.agentLoop());

    assertFalse(prompted[0], "LOW-risk op must not surface a pending prompt");
    assertTrue(result.success());
  }

  @Test
  void executeGatesHigherRiskAndRoutesOnlyWhenApproved() {
    List<Intent> captured = new ArrayList<>();
    GatedOperationExecutor ex =
        new GatedOperationExecutor(
            () -> routerReturning(OperationResult.success("done"), captured), () -> null);

    OperationResult approved =
        ex.execute(
            op("vendor.x.write", RiskTier.MEDIUM),
            "c2",
            "write",
            "{}",
            (callId, tool, args, risk) -> {},
            callId -> CompletableFuture.completedFuture(true),
            TestEngineContexts.agentLoop());
    assertTrue(approved.success());
    assertEquals(1, captured.size(), "approved MEDIUM-risk op routes");
  }

  @Test
  void executeReturnsFailureWhenTheGateDeclines() {
    List<Intent> captured = new ArrayList<>();
    GatedOperationExecutor ex =
        new GatedOperationExecutor(
            () -> routerReturning(OperationResult.success("done"), captured), () -> null);

    OperationResult declined =
        ex.execute(
            op("vendor.x.write", RiskTier.HIGH),
            "c3",
            "write",
            "{}",
            (callId, tool, args, risk) -> {},
            callId -> CompletableFuture.completedFuture(false),
            TestEngineContexts.agentLoop());

    assertFalse(declined.success());
    assertTrue(declined.message().toLowerCase().contains("declined"));
    assertTrue(captured.isEmpty(), "a declined op must never be routed");
  }

  @Test
  void routeApprovedStampsCorrelationIdAsTheJoinKeyNotInitiator() {
    // Tempdoc 561 P-A1 regression (merge fix): the agent sessionId carried by the routeApproved
    // must land in the provenance's correlationId — the cross-domain History join key (P-B1) —
    // NOT the initiator slot. The merge had wired it into the 4-arg ctor's initiator, leaving
    // correlationId empty; no test asserted the provenance, so it shipped green.
    List<InvocationProvenance> captured = new ArrayList<>();
    GatedOperationExecutor ex =
        new GatedOperationExecutor(
            () -> routerCapturingProvenance(OperationResult.success("ok"), captured), () -> null);

    EngineContext context = TestEngineContexts.agentLoop("sess-123");
    ex.routeApproved(op("vendor.x.add", RiskTier.LOW), "{}", context);

    assertEquals(1, captured.size());
    InvocationProvenance p = captured.get(0);
    assertEquals(
        InvocationProvenance.fromEngineContext(
            context, ExecutorTag.AGENT, p.occurredAt(), Optional.empty()),
        p,
        "dispatch provenance must be projected from the supplied engine context");
    assertEquals(
        Optional.of("sess-123"),
        p.correlationId(),
        "the agent sessionId must be the cross-domain correlationId (P-A1 join key)");
    assertEquals(
        Optional.of(context.clientId()),
        p.initiator(),
        "the engine context clientId, rather than the sessionId, supplies the initiator");
  }

  @Test
  void routeApprovedWithoutSessionCarriesNoCorrelationId() {
    // The context without a session has no correlation id — the workflow/no-session case.
    List<InvocationProvenance> captured = new ArrayList<>();
    GatedOperationExecutor ex =
        new GatedOperationExecutor(
            () -> routerCapturingProvenance(OperationResult.success("ok"), captured), () -> null);

    EngineContext context = TestEngineContexts.agentLoop();
    ex.routeApproved(op("vendor.x.add", RiskTier.LOW), "{}", context);

    InvocationProvenance p = captured.get(0);
    assertEquals(
        InvocationProvenance.fromEngineContext(
            context, ExecutorTag.AGENT, p.occurredAt(), Optional.empty()),
        p,
        "dispatch provenance must be projected from the supplied engine context");
    assertEquals(Optional.empty(), p.correlationId());
  }

  private static final class PlanningRouter implements BackendIntentRouter {
    OperationDispatchPlan nextPlan;
    OperationResult nextReceipt;
    Intent plannedIntent;
    InvocationProvenance plannedProvenance;
    Intent dispatchedIntent;
    String dispatchedKey;
    java.util.UUID dispatchedNonce;
    int preparations;
    int dispatches;

    @Override public OperationDispatchPlan prepare(Intent intent, InvocationProvenance provenance,
        EngineContext context, String key, boolean includePreview) {
      preparations++; plannedIntent = intent; plannedProvenance = provenance;
      assertTrue(includePreview); org.junit.jupiter.api.Assertions.assertNull(key);
      return nextPlan;
    }
    @Override public IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance, EngineContext context) {
      throw new AssertionError("A planned call must retain its key through the router");
    }
    @Override public IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance,
        EngineContext context, String key, java.util.UUID nonce) {
      dispatches++; dispatchedIntent = intent; dispatchedKey = key; dispatchedNonce = nonce;
      return new IntentDispatchResult.Dispatched(nextReceipt);
    }
  }

  private static ConsentCapsuleAuthority unusedAuthority() {
    return new ConsentCapsuleAuthority() {
      @Override public String mint(String op, String args, SourceTier tier) {
        throw new AssertionError("This path must not mint consent");
      }
      @Override public boolean verifyAndConsume(String token, String op, String args) {
        throw new AssertionError("This path must not consume consent");
      }
    };
  }

  @Test
  void preparationDoesNotMintConsentOrDispatch() {
    var router = new PlanningRouter();
    var context = TestEngineContexts.agentLoop("session");
    var op = op("core.prepared", RiskTier.MEDIUM);
    var plan = new OperationDispatchPlan.Ready("key", java.util.UUID.randomUUID(),
        Optional.of(new OperationApprovalPreview("Frozen target")));
    router.nextPlan = plan;
    var executor = new GatedOperationExecutor(() -> router, GatedOperationExecutorTest::unusedAuthority);
    assertEquals(plan, executor.prepare(op, " ", context, null, true));
    assertEquals(ShellAddress.Invocation.of(op.id(), "{}"), router.plannedIntent.address());
    assertEquals(context.sessionId(), router.plannedProvenance.correlationId());
    assertEquals(1, router.preparations); assertEquals(0, router.dispatches);
  }

  @Test
  void recordedPlanQueriesCurrentReceiptWithoutMintingAnotherCapsule() {
    var router = new PlanningRouter();
    var context = TestEngineContexts.agentLoop();
    var op = op("core.prepared", RiskTier.MEDIUM);
    var current = OperationResult.success("current receipt"); router.nextReceipt = current;
    var plan = new OperationDispatchPlan.Recorded("key", OperationResult.success("old receipt"));
    var executor = new GatedOperationExecutor(() -> router, GatedOperationExecutorTest::unusedAuthority);
    assertEquals(current, executor.routePrepared(op, "{}", plan, context));
    assertTrue(((ShellAddress.Invocation) router.dispatchedIntent.address()).confirmationToken().isEmpty());
    assertEquals("key", router.dispatchedKey); org.junit.jupiter.api.Assertions.assertNull(router.dispatchedNonce);
    assertEquals(1, router.dispatches); assertEquals(0, router.preparations);
  }

  @Test
  void preparedConsentCannotFallBackToTheLegacySentinel() {
    var router = new PlanningRouter();
    var executor = new GatedOperationExecutor(() -> router, () -> null);
    var plan = new OperationDispatchPlan.Ready("key", java.util.UUID.randomUUID(), Optional.empty());
    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
        () -> executor.routePrepared(op("core.prepared", RiskTier.MEDIUM), "{}", plan, TestEngineContexts.agentLoop()));
    assertEquals(0, router.dispatches); assertEquals(0, router.preparations);
  }
}
