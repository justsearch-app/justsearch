/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.DurableGrantStore;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.core.context.EngineContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 655 fix pass — coverage for {@code execute: true} (server-side completion of an
 * approved pending using its own stored args) and {@code handlePeekPending} (the point-to-point
 * decision-content fetch that replaced the broadcast's now-removed argsSummary/rationale).
 *
 * <p>Prior to this pass, {@code execute: true} — the code that lets the server complete a
 * mutation on a human's behalf — had zero automated coverage, verified only by manual browser
 * testing.
 */
class AuthorizationControllerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneId.of("UTC"));

  private ConsentCapsuleService capsuleService;
  private PendingAuthorizationStore pendingStore;
  private List<OperationCatalog> catalogs;

  @BeforeEach
  void setUp() {
    capsuleService = new ConsentCapsuleService(FIXED_CLOCK, Duration.ofMinutes(5));
    pendingStore = new PendingAuthorizationStore(FIXED_CLOCK, Duration.ofMinutes(5));
    catalogs = List.of(new AgentToolsOperationCatalog());
  }

  @Test
  void approvedExecutionReportsLockedPreparationAsUnlockRequired() throws Exception {
    var dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenThrow(new io.justsearch.agent.api.encryption.KeyLockedException());
    String id = createPending("core.ingest-files");
    var controller = new AuthorizationController(capsuleService, pendingStore, null, dispatcher, catalogs);
    var ctx = mockContextWithBody("{\"pendingId\":\"" + id + "\",\"execute\":true}");
    controller.handleApprove(ctx);
    var response = capturedJson(ctx);
    assertEquals(false, response.get("executed")); assertEquals(false, response.get("executeSuccess"));
    assertEquals("STORE_LOCKED", response.get("executeErrorCode"));
    assertEquals("STORE_LOCKED", response.get("executeErrorClass"));
    assertEquals(false, response.get("executeRetryable"));
  }

  @Test
  void preparedApprovalBindsServerNonceAndNeverAuthorizesPublicArgumentsAlone() throws Exception {
    var context = TestRequestContexts.mcp("prepared-approval");
    String key = io.justsearch.app.api.operations.OperationKeys.generate(FIXED_CLOCK);
    var nonce = java.util.UUID.randomUUID();
    String args = "{\"paths\":[\"C:/tmp\"]}";
    String id = pendingStore.create("core.ingest-paths", args, SourceTier.UNTRUSTED, RiskTier.MEDIUM,
        GateBehavior.TYPED_CONFIRM, "approve", null, io.justsearch.agent.api.registry.TransportTag.MCP,
        context, TestRequestContexts.provenance(context, ExecutorTag.AGENT), key, false, nonce);
    var controller = new AuthorizationController(capsuleService, pendingStore);
    var ctx = mockContextWithBody("{\"pendingId\":\"" + id + "\"}");
    controller.handleApprove(ctx);
    var response = capturedJson(ctx);
    String token = (String) response.get("capsule");
    assertFalse(capsuleService.verifyAndConsume(token, "core.ingest-paths", args),
        "An approval for a frozen target cannot authorize an arbitrary preparation of the public input");
    assertFalse(capsuleService.verifyPreparedAndConsume(token, "core.ingest-paths", args, key, java.util.UUID.randomUUID()));
    assertFalse(capsuleService.verifyPreparedAndConsume(token, "core.ingest-paths", args,
        io.justsearch.app.api.operations.OperationKeys.generate(FIXED_CLOCK), nonce));
    assertFalse(capsuleService.verifyPreparedAndConsume(token, "core.ingest-paths", "{}", key, nonce));
    assertFalse(capsuleService.verifyPreparedAndConsume(token, "core.ingest-paths", args, key, null));
    assertFalse(capsuleService.verifyPreparedAndConsume(token, "core.ingest-paths", args, "invalid", nonce));
    assertTrue(capsuleService.verifyPreparedAndConsume(token, "core.ingest-paths",
        " { \"paths\" : [ \"C:/tmp\" ] } ", key, nonce));
    assertFalse(capsuleService.verifyPreparedAndConsume(token, "core.ingest-paths", args, key, nonce));
    assertEquals(key, response.get("operationKey"));
    assertEquals(nonce.toString(), response.get("preparationNonce"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void unsupportedPreparedDispatcherCannotDiscardNonce(boolean undo) {
    var dispatcher = mock(OperationDispatcher.class, CALLS_REAL_METHODS);
    var context = TestRequestContexts.mcp("unsupported-prepared");
    var provenance = TestRequestContexts.provenance(context, ExecutorTag.AGENT);
    String key = io.justsearch.app.api.operations.OperationKeys.generate(FIXED_CLOCK);
    var nonce = java.util.UUID.randomUUID();
    if (undo) {
      assertThrows(UnsupportedOperationException.class,
          () -> dispatcher.undo(null, "exec", provenance, Optional.empty(), context, key, nonce));
      verify(dispatcher, never()).undo(any(), any(), any(), any(), any(), any());
    } else {
      assertThrows(UnsupportedOperationException.class,
          () -> dispatcher.dispatch(null, "{}", provenance, Optional.empty(), context, key, nonce));
      verify(dispatcher, never()).dispatch(any(), any(), any(), any(), any(), any());
    }
  }

  @Test
  void publicCapsuleCannotImpersonatePreparedBindingJson() {
    String key = io.justsearch.app.api.operations.OperationKeys.generate(FIXED_CLOCK);
    var nonce = java.util.UUID.randomUUID();
    String shapedPublicInput = MAPPER.writeValueAsString(Map.of("operationKey", key, "preparationNonce", nonce.toString(),
        "publicDigest", io.justsearch.app.api.operations.CanonicalOperationArguments.digest("{}")));
    String ordinary = capsuleService.mint("core.ingest-files", shapedPublicInput);
    assertFalse(capsuleService.verifyPreparedAndConsume(ordinary, "core.ingest-files", "{}", key, nonce),
        "Caller JSON matching the binding representation must remain ordinary consent");
    assertTrue(capsuleService.verifyAndConsume(ordinary, "core.ingest-files", shapedPublicInput));
  }

  @Test
  void unsupportedCapsuleAuthorityRefusesPreparedConsent() {
    var authority = mock(io.justsearch.agent.api.registry.ConsentCapsuleAuthority.class, CALLS_REAL_METHODS);
    String key = io.justsearch.app.api.operations.OperationKeys.generate(FIXED_CLOCK);
    var nonce = java.util.UUID.randomUUID();
    assertThrows(UnsupportedOperationException.class,
        () -> authority.mintPrepared("core.ingest-files", "{}", SourceTier.UNTRUSTED, key, nonce));
    assertFalse(authority.verifyPreparedAndConsume("token", "core.ingest-files", "{}", key, nonce));
    verify(authority, never()).mint(any(), any(), any());
    verify(authority, never()).verifyAndConsume(any(), any(), any());
  }

  private String createPending(String operationId) {
    var context = TestRequestContexts.mcp("approval-test");
    return pendingStore.create(
        operationId,
        "{\"paths\":[\"C:/tmp\"]}",
        SourceTier.UNTRUSTED,
        RiskTier.MEDIUM,
        GateBehavior.TYPED_CONFIRM,
        "Confirmation required for operation " + operationId,
        context,
        TestRequestContexts.provenance(context, ExecutorTag.AGENT));
  }

  private Context mockContextWithBody(String body) {
    Context ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    Map<String, Object> attributes = new java.util.HashMap<>();
    when(ctx.attribute(anyString()))
        .thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
    doAnswer(
            invocation -> {
              attributes.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(ctx)
        .attribute(anyString(), any());
    return ctx;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> capturedJson(Context ctx) throws Exception {
    ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
    verify(ctx, atLeastOnce()).result(captor.capture());
    byte[] last = captor.getAllValues().get(captor.getAllValues().size() - 1);
    return MAPPER.readValue(last, Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> capturedJsonObject(Context ctx) {
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx, atLeastOnce()).json(captor.capture());
    return captor.getAllValues().get(captor.getAllValues().size() - 1);
  }

  /**
   * The error-body branches of {@code handleApprove} (400/410) write via the {@code
   * result(String)} overload (a hand-built JSON literal), not {@code result(byte[])} (the
   * success path's {@code ObjectMapper}-serialized payload) — a distinct overload {@link
   * #capturedJson} does not match. This captures that overload instead.
   */
  private String capturedErrorResult(Context ctx) {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(ctx, atLeastOnce()).result(captor.capture());
    return captor.getValue();
  }

  @Test
  void approve_withoutExecute_behavesAsBefore_noExecutedKey() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"));
    assertFalse(body.containsKey("executed"), "execute omitted must not attempt server-side dispatch");
    verifyNoInteractions(dispatcher);
  }

  @Test
  void approvedDispatchRetainsTypedKeyFailureWithoutItsPrivateCause() throws Exception {
    var admission = new io.justsearch.app.engine.EngineAdmissionController(2, 2, 1);
    var dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenThrow(new io.justsearch.app.api.operations.OperationStoreException(
            io.justsearch.app.api.operations.OperationStoreException.Code.OPERATION_KEY_REUSED,
            new IllegalStateException("private prepared content")));
    var controller = new AuthorizationController(capsuleService, pendingStore, null, dispatcher, catalogs, admission);
    String pendingId = createPending("core.ingest-files");
    var ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    controller.handleApprove(ctx);
    var body = capturedJson(ctx);
    assertEquals(Boolean.FALSE, body.get("executeSuccess"));
    assertEquals("OPERATION_KEY_REUSED", body.get("executeErrorCode"));
    assertEquals("CONFLICT", body.get("executeErrorClass"));
    assertFalse(body.toString().contains("private prepared content"));
  }

  @Test
  void approve_withExecuteTrue_dispatchesUsingStoredArgsAndReportsSuccess() throws Exception {
    var admission = new io.justsearch.app.engine.EngineAdmissionController(2, 2, 1);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenReturn(OperationResult.success("Indexed 1 item", Map.of()));
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs, admission);
    // The pending was created by an MCP caller; the approving request is a separate browser
    // gesture. Both the origin context and its exact invocation provenance must survive that
    // handoff, so approval cannot silently upgrade the dispatch to the browser's trusted context.
    EngineContext pendingContext =
        new EngineContext(
            EngineContext.ClientKind.MCP_CLIENT,
            "mcp-client-7",
            Optional.of("session-7"),
            Optional.of("grant-7"),
            "UNTRUSTED",
            "MCP",
            EngineContext.Survival.DURABLE,
            EngineContext.Urgency.BACKGROUND);
    try (var original = admission.admit(pendingContext, false)) {
      pendingContext = original.context();
    }
    Instant pendingOccurredAt = Instant.parse("2026-07-02T11:59:30Z");
    var pendingProvenance =
        io.justsearch.agent.api.registry.InvocationProvenance.fromEngineContext(
            pendingContext,
            ExecutorTag.AGENT,
            pendingOccurredAt,
            Optional.of("signed-intent-7"));
    String pendingId =
        pendingStore.create(
            "core.ingest-files",
            "{\"paths\":[\"C:/tmp\"]}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "Confirmation required for operation core.ingest-files",
            "MCP client",
            io.justsearch.agent.api.registry.TransportTag.MCP,
            pendingContext,
            pendingProvenance);

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    EngineContext approvingContext = TestRequestContexts.browser();
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(approvingContext);
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"));
    assertEquals(Boolean.TRUE, body.get("executed"));
    assertEquals(Boolean.TRUE, body.get("executeSuccess"));
    assertEquals("Indexed 1 item", body.get("executeMessage"));

    // Dispatched with the PENDING's own stored args, not anything the caller supplied.
    ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<io.justsearch.agent.api.registry.InvocationProvenance> provenanceCaptor =
        ArgumentCaptor.forClass(io.justsearch.agent.api.registry.InvocationProvenance.class);
    ArgumentCaptor<EngineContext> contextCaptor = ArgumentCaptor.forClass(EngineContext.class);
    verify(dispatcher)
        .dispatch(
            any(),
            argsCaptor.capture(),
            provenanceCaptor.capture(),
            any(),
            contextCaptor.capture());
    assertEquals("{\"paths\":[\"C:/tmp\"]}", argsCaptor.getValue());

    EngineContext dispatchedContext = contextCaptor.getValue();
    assertNotEquals(approvingContext, dispatchedContext);
    assertTrue(dispatchedContext.workId().isPresent());
    assertNotEquals(pendingContext.workId(), dispatchedContext.workId());
    assertEquals(pendingContext.withWorkId(dispatchedContext.workId().orElseThrow()), dispatchedContext);
    assertThrows(io.justsearch.app.api.EngineAdmissionException.class, () -> admission.attach(dispatchedContext),
        "the approval dispatch must release its child after completion");
    assertEquals(EngineContext.ClientKind.MCP_CLIENT, dispatchedContext.clientKind());
    assertEquals("mcp-client-7", dispatchedContext.clientId());
    assertEquals(Optional.of("session-7"), dispatchedContext.sessionId());
    assertEquals(Optional.of("grant-7"), dispatchedContext.grantReference());
    assertEquals("UNTRUSTED", dispatchedContext.sourceTier());
    assertEquals("MCP", dispatchedContext.transport());
    assertEquals(EngineContext.Survival.DURABLE, dispatchedContext.survival());
    assertEquals(EngineContext.Urgency.BACKGROUND, dispatchedContext.urgency());

    var dispatchedProvenance = provenanceCaptor.getValue();
    assertEquals(pendingProvenance, dispatchedProvenance);
    assertEquals(io.justsearch.agent.api.registry.TransportTag.MCP, dispatchedProvenance.transport());
    assertEquals(ExecutorTag.AGENT, dispatchedProvenance.executor());
    assertEquals(Optional.of("mcp-client-7"), dispatchedProvenance.initiator());
    assertEquals(pendingOccurredAt, dispatchedProvenance.occurredAt());
    assertEquals(Optional.of("signed-intent-7"), dispatchedProvenance.signedIntentToken());
    assertEquals(Optional.of("session-7"), dispatchedProvenance.correlationId());
  }

  @Test
  void childAdmissionRefusalLeavesTheSingleUsePendingAvailableForRetry() {
    var admission = new io.justsearch.app.engine.EngineAdmissionController(1, 1, 1);
    var dispatcher = mock(OperationDispatcher.class);
    var controller = new AuthorizationController(capsuleService, pendingStore, null, dispatcher, catalogs, admission);
    String pendingId = createPending("core.ingest-files");
    try (var _ = admission.admit(TestRequestContexts.browser(), false)) {
      var ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
      controller.handleApprove(ctx);
      verify(ctx).status(429);
      verify(ctx).header("Retry-After", "1");
      assertEquals(Boolean.TRUE, capturedJsonObject(ctx).get("retrySafe"));
      assertTrue(pendingStore.peek(pendingId).isPresent());
      verifyNoInteractions(dispatcher);
    }
  }

  @Test
  void approve_lateAdmissionFailureAfterConsume_isUnsafeAndPendingIsConsumed() {
    ConsentCapsuleService failingCapsuleService = mock(ConsentCapsuleService.class);
    when(failingCapsuleService.mint(anyString(), anyString(), any(SourceTier.class)))
        .thenThrow(
            new io.justsearch.app.api.EngineAdmissionException(
                io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT, 1));
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var admission = new io.justsearch.app.engine.EngineAdmissionController(2, 2, 1);
    var controller =
        new AuthorizationController(
            failingCapsuleService, pendingStore, null, dispatcher, catalogs, admission);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    verify(ctx).status(429);
    Map<String, Object> refusal = capturedJsonObject(ctx);
    assertEquals(Boolean.FALSE, refusal.get("retrySafe"));
    assertTrue(
        pendingStore.peek(pendingId).isEmpty(),
        "late failure must not restore the consumed pending");
    verify(failingCapsuleService).mint(anyString(), anyString(), any(SourceTier.class));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void approve_withExecuteTrue_noDispatcherWired_reportsNotExecutedWithoutThrowing() throws Exception {
    // Legacy/test-wiring constructor — no dispatcher.
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"), "approval itself must still succeed");
    assertEquals(Boolean.FALSE, body.get("executed"));
    assertNotNull(body.get("executeMessage"));
  }

  @Test
  void approve_withExecuteTrue_unresolvableOperationId_reportsNotExecuted() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs);
    String pendingId = createPending("core.does-not-exist");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"));
    assertEquals(Boolean.FALSE, body.get("executed"));
    assertTrue(((String) body.get("executeMessage")).contains("core.does-not-exist"));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void approve_withExecuteTrue_dispatchThrows_reportsFailureButApprovalStands() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("disk full"));
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    // The mint/approval already happened before dispatch was attempted — a downstream execution
    // failure doesn't retroactively undo it (there is nothing to "roll back": the pending was
    // already consumed and the capsule already minted).
    assertNotNull(body.get("capsule"));
    assertEquals(Boolean.FALSE, body.get("executed"));
    assertTrue(((String) body.get("executeMessage")).contains("disk full"));
  }

  // ── Smoke-round 2026-07-14 HIGH finding (734): the 410-expired approval branch had no
  // regression coverage on either side. Verified fixed at HEAD (AuthorizationHost.decide
  // closes/advances synchronously; approveByPendingId throws on non-2xx; this 410 is the
  // backend half) — these two tests pin it so a future regression fails loudly here instead
  // of only in a live GUI smoke pass.

  @Test
  void approve_unknownPendingId_returns410WithErrorBody_noCapsuleMintedNoDispatch() throws Exception {
    ConsentCapsuleService mockCapsuleService = mock(ConsentCapsuleService.class);
    DurableGrantStore durableGrantStore = mock(DurableGrantStore.class);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            mockCapsuleService, pendingStore, durableGrantStore, dispatcher, catalogs);

    Context ctx = mockContextWithBody("{\"pendingId\":\"pa-does-not-exist\"}");
    controller.handleApprove(ctx);

    // If handleApprove ever regressed to returning 200 with an empty/placeholder capsule for
    // an id that doesn't resolve, this assertion fails: it pins the actual status code, not
    // just "some error happened".
    verify(ctx).status(410);
    String body = capturedErrorResult(ctx);
    assertTrue(body.contains("\"error\""), "410 body must carry an error message: " + body);
    assertFalse(body.contains("capsule"), "no capsule minted for an unknown pendingId: " + body);
    verifyNoInteractions(mockCapsuleService);
    verifyNoInteractions(dispatcher);
    verifyNoInteractions(durableGrantStore);
  }

  @Test
  void approve_expiredPendingId_returns410WithErrorBody_noCapsuleMintedNoDispatch() throws Exception {
    MutableClock mutableClock = new MutableClock(Instant.parse("2026-07-02T12:00:00Z"), ZoneId.of("UTC"));
    PendingAuthorizationStore expiringStore =
        new PendingAuthorizationStore(mutableClock, Duration.ofMinutes(5));
    String pendingId =
        expiringStore.create(
            "core.ingest-files",
            "{\"paths\":[\"C:/tmp\"]}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "Confirmation required for operation core.ingest-files",
            TestRequestContexts.mcp("approval-expiry"),
            TestRequestContexts.provenance(
                TestRequestContexts.mcp("approval-expiry"), ExecutorTag.AGENT));
    // Advance the SAME clock instance the store consults past its 5-minute TTL — this is the
    // real expiry path (PendingAuthorizationStore#consume's isExpired check), not a stand-in
    // for "unknown id".
    mutableClock.advance(Duration.ofMinutes(6));

    ConsentCapsuleService mockCapsuleService = mock(ConsentCapsuleService.class);
    DurableGrantStore durableGrantStore = mock(DurableGrantStore.class);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            mockCapsuleService, expiringStore, durableGrantStore, dispatcher, catalogs);

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(ctx);

    verify(ctx).status(410);
    String body = capturedErrorResult(ctx);
    assertTrue(body.contains("\"error\""), "410 body must carry an error message: " + body);
    assertFalse(body.contains("capsule"), "no capsule minted for an expired pendingId: " + body);
    verifyNoInteractions(mockCapsuleService);
    verifyNoInteractions(dispatcher);
    verifyNoInteractions(durableGrantStore);

    // A retry with the same id also 410s — the ceremony can't be "revived" by re-clicking
    // Approve on the same expired pendingId (the finding's exact undismissable-modal shape).
    Context retryCtx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(retryCtx);
    verify(retryCtx).status(410);
  }

  /** Mutable {@link Clock} so a test can create a pending, then advance time past its TTL. */
  private static final class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    void advance(Duration d) {
      instant = instant.plus(d);
    }
  }

  @Test
  void peekPending_knownId_returnsDecisionContentWithoutConsuming() throws Exception {
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(pendingId);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertEquals(pendingId, body.get("pendingId"));
    assertEquals("core.ingest-files", body.get("operationId"));
    assertEquals("{\"paths\":[\"C:/tmp\"]}", body.get("argsSummary"));
    assertEquals("UNTRUSTED", body.get("sourceTier"));
    assertEquals("MEDIUM", body.get("riskTier"));
    assertEquals("TYPED_CONFIRM", body.get("gateBehavior"));

    // requestedBy omitted entirely (not present as a key) when the pending has no MCP client.
    assertFalse(body.containsKey("requestedBy"));

    // Non-mutating: the SAME id can still be approved afterward (peek didn't consume it).
    Context approveCtx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(approveCtx);
    Map<String, Object> approveBody = capturedJson(approveCtx);
    assertNotNull(approveBody.get("capsule"));
  }

  @Test
  void peekPending_exposesExpiresAt_soAClientCanSayHowLongTheRequestIsValid() throws Exception {
    // Tempdoc 807 item 3 / sandbox round 13 F3: pendings DO expire (the store's 5-minute TTL) and
    // PendingAuthorization has always carried expiresAt — but no surface put it on the wire. With
    // nothing to read, no client could tell the user how long an approval request is valid, and no
    // round could deterministically induce or verify expiry.
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(pendingId);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    Map<String, Object> body = capturedJson(ctx);
    // FIXED_CLOCK + the store's 5-minute TTL, serialized ISO-8601 UTC.
    assertEquals("2026-07-02T12:05:00Z", body.get("expiresAt"));
  }

  @Test
  void peekPending_withRequestedBy_includesItInResponse() throws Exception {
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId =
        pendingStore.create(
            "core.ingest-files",
            "{\"paths\":[\"C:/tmp\"]}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "Confirmation required",
            "Claude Code",
            io.justsearch.agent.api.registry.TransportTag.MCP,
            TestRequestContexts.mcp("claude-code"),
            TestRequestContexts.provenance(TestRequestContexts.mcp("claude-code"), ExecutorTag.AGENT));

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(pendingId);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertEquals("Claude Code", body.get("requestedBy"));
  }

  @Test
  void peekPending_unknownOrExpiredId_returns404_notArgsContent() throws Exception {
    var controller = new AuthorizationController(capsuleService, pendingStore, null);

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn("pa-does-not-exist");
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    verify(ctx).status(404);
  }

  // ── Tempdoc 875 C.2 — the durable grant's risk ceiling, at the issuance surface ────────────────
  // The gate already refuses to honour a durable grant on a HIGH-risk operation. These pin the
  // complementary property: such a grant is not RECORDED either, so the management surface never
  // lists an active-looking row that can never fire.

  @Test
  void grantOperation_highRiskOperation_isRefusedAndNotRecorded() {
    var store = new DurableGrantStore(FIXED_CLOCK);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, store, dispatcher, catalogs);

    Context ctx =
        mockContextWithBody(
            "{\"kind\":\"OPERATION\",\"target\":\"core.file-operations\",\"sourceTier\":\"UNTRUSTED\"}");
    controller.handleGrant(ctx);

    verify(ctx).status(400);
    assertTrue(store.snapshot().isEmpty(), "a HIGH-risk operation grant must not be recorded");
  }

  @Test
  void grantOperation_mediumRiskOperation_isStillRecorded() {
    var store = new DurableGrantStore(FIXED_CLOCK);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, store, dispatcher, catalogs);

    Context ctx =
        mockContextWithBody(
            "{\"kind\":\"OPERATION\",\"target\":\"core.ingest-files\",\"sourceTier\":\"UNTRUSTED\"}");
    controller.handleGrant(ctx);

    verify(ctx, never()).status(400);
    assertEquals(1, store.snapshot().size(), "the MEDIUM sibling is unaffected by the ceiling");
    assertEquals("core.ingest-files", store.snapshot().get(0).target());
  }

  @Test
  void grantFamily_isStillAcceptedEvenThoughTheFamilyHasAHighMember() {
    // 560 §28's "file-operations" family contains the HIGH core.file-operations. The family grant
    // stays useful for the family's MEDIUM members, so it is accepted; the ceiling bites per
    // operation at the gate, not on the family as a whole.
    var store = new DurableGrantStore(FIXED_CLOCK);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, store, dispatcher, catalogs);

    Context ctx =
        mockContextWithBody(
            "{\"kind\":\"FAMILY\",\"target\":\"file-operations\",\"sourceTier\":\"UNTRUSTED\"}");
    controller.handleGrant(ctx);

    verify(ctx, never()).status(400);
    assertEquals(1, store.snapshot().size());
    assertEquals(
        DurableGrantStore.GrantKind.FAMILY, store.snapshot().get(0).kind());
  }

  @Test
  void approve_allowAlways_isNotHonouredForAHighRiskOperation() throws Exception {
    var store = new DurableGrantStore(FIXED_CLOCK);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, store, dispatcher, catalogs);
    String pendingId = createPending("core.file-operations");

    Context ctx =
        mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"allowAlways\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertEquals(Boolean.FALSE, body.get("allowAlways"), "the response must report what was done");
    assertTrue(store.snapshot().isEmpty(), "no durable grant for a HIGH-risk operation");
  }

  @Test
  void approve_allowAlways_isHonouredForAMediumRiskOperation() throws Exception {
    var store = new DurableGrantStore(FIXED_CLOCK);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, store, dispatcher, catalogs);
    String pendingId = createPending("core.ingest-files");

    Context ctx =
        mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"allowAlways\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertEquals(Boolean.TRUE, body.get("allowAlways"));
    assertEquals(1, store.snapshot().size());
  }
}
