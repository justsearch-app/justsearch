package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@link OperationsController} (slice 3a-1-2 Phase 2).
 *
 * <p>Covers happy-path dispatch, operation-not-found, handler-returned failure, handler
 * threw, and request body parsing edge cases.
 */
@DisplayName("OperationsController")
final class OperationsControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OperationDispatcher dispatcher;
  private OperationsController controller;

  @BeforeEach
  void setUp() {
    OperationCatalog catalog = new CoreOperationCatalog();
    dispatcher = mock(OperationDispatcher.class);
    controller = new OperationsController(List.of(catalog), dispatcher);
  }

  private Context mockContext(String idPathParam, String body) {
    Context ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/api/operations/" + idPathParam + "/invoke");
    when(ctx.pathParam("id")).thenReturn(idPathParam);
    when(ctx.body()).thenReturn(body);
    when(ctx.contentType(any(String.class))).thenReturn(ctx);
    when(ctx.status(any(Integer.class))).thenReturn(ctx);
    return ctx;
  }

  private JsonNode capture(Context ctx) throws Exception {
    ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(body.capture());
    return MAPPER.readTree(body.getValue());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void invocationAndUndoForwardTheSuppliedKey(boolean undo) throws Exception {
    String key = io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC());
    var result = OperationResult.success("recorded", Map.of("operationKey", key, "operationRecordId", 7));
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("key was dropped"));
    when(dispatcher.undo(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("key was dropped"));
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class), eq(key)))
        .thenReturn(result);
    when(dispatcher.undo(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class), eq(key)))
        .thenReturn(result);
    String input = undo ? "\"executionId\":\"exec-1\"" : "\"args\":{}";
    Context ctx = mockContext("core.ping-backend", "{" + input + ",\"idempotencyKey\":\"" + key + "\"}");
    if (undo) {
      controller.handleUndo(ctx);
      verify(dispatcher).undo(any(), eq("exec-1"), any(InvocationProvenance.class), any(), any(EngineContext.class), eq(key));
    } else {
      controller.handleInvoke(ctx);
      verify(dispatcher).dispatch(any(), eq("{}"), any(InvocationProvenance.class), any(), any(EngineContext.class), eq(key));
    }
    verify(ctx).status(200);
    assertEquals(key, capture(ctx).path("structuredData").path("operationKey").asText());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "INVALID_OPERATION_KEY,400,BAD_REQUEST,OPERATION_KEY_INVALID,false",
      "OPERATION_KEY_REUSED,409,CONFLICT,OPERATION_KEY_REUSED,false",
      "OPERATION_EXPIRED,409,CONFLICT,OPERATION_KEY_EXPIRED,false",
      "OPERATIONS_CAPACITY,503,UNAVAILABLE,OPERATIONS_CAPACITY,true",
      "STORAGE_FAILED,500,HANDLER_ERROR,OPERATION_STORAGE_FAILED,false"
  })
  void keyFailuresKeepTheirPublicCodeAndDoNotExposeNativeCauses(
      io.justsearch.app.api.operations.OperationStoreException.Code code,
      int status, String errorClass, String publicCode, boolean retryable) throws Exception {
    String key = io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC());
    var failure = new io.justsearch.app.api.operations.OperationStoreException(code,
        new IllegalStateException("private SQL and invocation contents"));
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class), eq(key)))
        .thenThrow(failure);
    when(dispatcher.undo(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class), eq(key)))
        .thenThrow(failure);
    for (boolean undo : new boolean[] {false, true}) {
      String input = undo ? "\"executionId\":\"exec-1\"" : "\"args\":{}";
      Context ctx = mockContext("core.ping-backend", "{" + input + ",\"idempotencyKey\":\"" + key + "\"}");
      if (undo) controller.handleUndo(ctx); else controller.handleInvoke(ctx);
      verify(ctx).status(status);
      var response = capture(ctx);
      assertEquals(errorClass, response.path("errorClass").asText());
      assertEquals(publicCode, response.path("errorCode").asText());
      assertEquals(retryable, response.path("retryable").asBoolean());
      org.junit.jupiter.api.Assertions.assertFalse(response.toString().contains("private SQL"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void approvalRetainsTheOriginalKeyAndInvokeOrUndoMode(boolean undo) throws Exception {
    String key = io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC());
    var catalog = new CoreOperationCatalog();
    var pending = new io.justsearch.app.services.intent.PendingAuthorizationStore();
    controller = new OperationsController(List.of(catalog), dispatcher, java.time.Clock.systemUTC(), pending);
    var refusal = new io.justsearch.agent.api.registry.ConfirmationRequiredException(
        new io.justsearch.agent.api.registry.OperationRef("core.ping-backend"),
        io.justsearch.agent.api.registry.GateBehavior.TYPED_CONFIRM,
        io.justsearch.agent.api.registry.ConfirmStrategy.None.INSTANCE,
        io.justsearch.agent.api.registry.SourceTier.TRUSTED);
    var success = OperationResult.success("approved once", Map.of("operationKey", key, "operationRecordId", 7));
    if (undo) {
      when(dispatcher.undo(any(), eq("exec-1"), any(), any(), any(EngineContext.class), eq(key)))
          .thenThrow(refusal).thenReturn(success);
    } else {
      when(dispatcher.dispatch(any(), eq("{}"), any(), any(), any(EngineContext.class), eq(key)))
          .thenThrow(refusal).thenReturn(success);
    }
    String input = undo ? "\"executionId\":\"exec-1\"" : "\"args\":{}";
    Context original = mockContext("core.ping-backend", "{" + input + ",\"idempotencyKey\":\"" + key + "\"}");
    if (undo) controller.handleUndo(original); else controller.handleInvoke(original);
    String pendingId = capture(original).path("pendingId").asText();
    var recorded = pending.peek(pendingId).orElseThrow();
    assertEquals(key, recorded.operationKey());
    assertEquals(undo, recorded.undo());
    var admission = new io.justsearch.app.engine.EngineAdmissionController(2, 2, 1);
    var approval = new AuthorizationController(new io.justsearch.app.services.intent.ConsentCapsuleService(),
        pending, null, dispatcher, List.of(catalog), admission);
    Context approve = mockContext("unused", "{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    when(approve.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    approval.handleApprove(approve);
    var approved = capture(approve);
    assertTrue(approved.path("executeSuccess").asBoolean());
    assertEquals(key, approved.path("operationKey").asText());
    assertEquals(7, approved.path("operationRecordId").asLong());
    if (undo) {
      verify(dispatcher, org.mockito.Mockito.times(2)).undo(any(), eq("exec-1"), any(), any(), any(EngineContext.class), eq(key));
      verify(dispatcher, org.mockito.Mockito.never()).dispatch(any(), any(), any(), any(), any(EngineContext.class), any());
    } else {
      verify(dispatcher, org.mockito.Mockito.times(2)).dispatch(any(), eq("{}"), any(), any(), any(EngineContext.class), eq(key));
    }
    assertTrue(pending.peek(pendingId).isEmpty());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void preparedApprovalCarriesServerMintedReferenceThroughInvokeAndUndo(boolean undo) throws Exception {
    String key = io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC());
    var nonce = java.util.UUID.randomUUID();
    var catalog = new CoreOperationCatalog();
    var pending = new io.justsearch.app.services.intent.PendingAuthorizationStore();
    controller = new OperationsController(List.of(catalog), dispatcher, java.time.Clock.systemUTC(), pending);
    var refusal = new io.justsearch.agent.api.registry.ConfirmationRequiredException(
        new io.justsearch.agent.api.registry.OperationRef("core.ping-backend"),
        io.justsearch.agent.api.registry.GateBehavior.TYPED_CONFIRM,
        io.justsearch.agent.api.registry.ConfirmStrategy.None.INSTANCE,
        io.justsearch.agent.api.registry.SourceTier.TRUSTED, key, nonce);
    var success = OperationResult.success("approved frozen target", Map.of("operationKey", key, "operationRecordId", 7));
    when(dispatcher.undo(any(), any(), any(), any(), any(EngineContext.class))).thenThrow(refusal);
    when(dispatcher.dispatch(any(), any(), any(), any(), any(EngineContext.class))).thenThrow(refusal);
    when(dispatcher.undo(any(), any(), any(), any(), any(EngineContext.class), eq(key), eq(nonce))).thenReturn(success);
    when(dispatcher.dispatch(any(), any(), any(), any(), any(EngineContext.class), eq(key), eq(nonce))).thenReturn(success);
    String input = undo ? "\"executionId\":\"exec-1\"" : "\"args\":{}";
    Context original = mockContext("core.ping-backend", "{" + input + "}");
    if (undo) controller.handleUndo(original); else controller.handleInvoke(original);
    var gated = capture(original);
    assertEquals(key, gated.path("operationKey").asText());
    assertEquals(nonce.toString(), gated.path("preparationNonce").asText());
    String pendingId = gated.path("pendingId").asText();
    assertEquals(nonce, pending.peek(pendingId).orElseThrow().preparationNonce());
    var approval = new AuthorizationController(new io.justsearch.app.services.intent.ConsentCapsuleService(),
        pending, null, dispatcher, List.of(catalog), new io.justsearch.app.engine.EngineAdmissionController(2, 2, 1));
    Context approve = mockContext("unused", "{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    when(approve.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    approval.handleApprove(approve);
    var approved = capture(approve);
    assertTrue(approved.path("executeSuccess").asBoolean());
    assertEquals(nonce.toString(), approved.path("preparationNonce").asText());
    if (undo) verify(dispatcher).undo(any(), eq("exec-1"), any(), any(), any(EngineContext.class), eq(key), eq(nonce));
    else verify(dispatcher).dispatch(any(), eq("{}"), any(), any(), any(EngineContext.class), eq(key), eq(nonce));
    verify(dispatcher, org.mockito.Mockito.never()).dispatch(any(), any(), any(), any(), any(EngineContext.class), any());
    verify(dispatcher, org.mockito.Mockito.never()).undo(any(), any(), any(), any(), any(EngineContext.class), any());
  }

  @Test
  @DisplayName("happy path — known operation, dispatcher returns success")
  void happyPath() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContext("core.ping-backend", "{\"args\":{}}");
    controller.handleInvoke(ctx);

    verify(ctx).status(200);
    JsonNode response = capture(ctx);
    assertTrue(response.get("success").asBoolean());
    assertEquals("ok", response.get("message").asText());
    assertNull(response.get("errorClass"), "no errorClass on success");
  }

  @Test
  @DisplayName("operation not found — 422 with OPERATION_NOT_FOUND errorClass")
  void operationNotFound() throws Exception {
    // Status 422 (not 404) — LocalApiServer's global app.error(404) handler
    // overwrites response bodies on 404 with the canonical ApiErrorHandler shape,
    // swallowing our errorClass tag. 422 (Unprocessable Entity) is semantically
    // correct and isn't intercepted. Surfaced via live-stack smoke 2026-05-06.
    Context ctx = mockContext("core.does-not-exist", "{\"args\":{}}");
    controller.handleInvoke(ctx);

    verify(ctx).status(422);
    JsonNode response = capture(ctx);
    assertEquals(false, response.get("success").asBoolean());
    assertEquals("OPERATION_NOT_FOUND", response.get("errorClass").asText());
  }

  @Test
  @DisplayName("undo resolves an agent wire-name (Fix F) — not 422")
  void undoResolvesAgentWireName() throws Exception {
    // Tempdoc 875 §C.7: the route now calls the provenance-carrying overload, because a reversal
    // must meet the same trust lattice its forward form met and the 2-arg form carries neither the
    // caller's transport nor a confirmation token. Stubbing the 2-arg form here would stub a method
    // the controller no longer calls (the mock would return null) — the arity moved with
    // production; this test's subject, wire-name resolution, did not.
    when(dispatcher.undo(any(), eq("exec-1"), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("undone"));

    // The "undo the AI" affordance journals the agent TOOL wire-name (dots/dashes ->
    // underscores), e.g. "core.ping-backend" -> "core_ping_backend". Resolving by op-id
    // only returned 422 (Operation not found); findByWireName must match it.
    Context ctx = mockContext("core_ping_backend", "{\"executionId\":\"exec-1\"}");
    controller.handleUndo(ctx);

    verify(ctx).status(200);
    JsonNode response = capture(ctx);
    assertTrue(response.get("success").asBoolean());

    // The provenance the gate will judge must actually reach the dispatcher — a null or a
    // hardcoded system-internal here would make the reversal un-gateable at the HTTP edge, which
    // is the exact defect §C.7 closes.
    ArgumentCaptor<InvocationProvenance> prov =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).undo(any(), eq("exec-1"), prov.capture(), any(), any(EngineContext.class));
    assertEquals(
        TransportTag.BUTTON,
        prov.getValue().transport(),
        "no transport header ⇒ the same BUTTON default the invoke path uses");
  }

  /**
   * Tempdoc 875 §C.7: the reversal's transport is the CALLER's, not a constant. If this route
   * laundered every undo into BUTTON (TRUSTED), an agent-loop reversal would be judged in the
   * strongest lattice row there is — the gate would be present but reading the wrong cell.
   */
  @Test
  @DisplayName("undo declares the caller's transport, so the lattice judges the right source tier")
  void undoCarriesTheDeclaredTransport() throws Exception {
    when(dispatcher.undo(any(), eq("exec-1"), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("undone"));
    Context ctx = mockContext("core.ping-backend", "{\"executionId\":\"exec-1\"}");
    when(ctx.header("X-JustSearch-Transport")).thenReturn("AGENT_LOOP");

    controller.handleUndo(ctx);

    ArgumentCaptor<InvocationProvenance> prov =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).undo(any(), eq("exec-1"), prov.capture(), any(), any(EngineContext.class));
    assertEquals(TransportTag.AGENT_LOOP, prov.getValue().transport());
  }

  @Test
  @DisplayName("handler returns failure — 200 with HANDLER_FAILURE errorClass")
  void handlerReturnsFailure() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.failure("worker not running"));

    Context ctx = mockContext("core.ping-backend", "{\"args\":{}}");
    controller.handleInvoke(ctx);

    verify(ctx).status(200);
    JsonNode response = capture(ctx);
    assertEquals(false, response.get("success").asBoolean());
    assertEquals("worker not running", response.get("message").asText());
    assertEquals("HANDLER_FAILURE", response.get("errorClass").asText());
  }

  @Test
  @DisplayName("handler throws — 500 with HANDLER_ERROR errorClass")
  void handlerThrows() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenThrow(new RuntimeException("boom"));

    Context ctx = mockContext("core.ping-backend", "{\"args\":{}}");
    controller.handleInvoke(ctx);

    verify(ctx).status(500);
    JsonNode response = capture(ctx);
    assertEquals(false, response.get("success").asBoolean());
    assertEquals("HANDLER_ERROR", response.get("errorClass").asText());
    assertTrue(response.get("message").asText().contains("boom"));
  }

  @Test
  @DisplayName("empty body is treated as zero-args invocation")
  void emptyBody() throws Exception {
    when(dispatcher.dispatch(any(), eq("{}"), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContext("core.ping-backend", "");
    controller.handleInvoke(ctx);

    verify(ctx).status(200);
    JsonNode response = capture(ctx);
    assertTrue(response.get("success").asBoolean());
  }

  @Test
  @DisplayName("invalid JSON body — 400 with BAD_REQUEST errorClass")
  void invalidJsonBody() throws Exception {
    Context ctx = mockContext("core.ping-backend", "not-json");
    controller.handleInvoke(ctx);

    verify(ctx).status(400);
    JsonNode response = capture(ctx);
    assertEquals("BAD_REQUEST", response.get("errorClass").asText());
  }

  @Test
  @DisplayName("dispatcher receives serialized args JSON")
  void dispatcherReceivesArgsJson() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContext("core.ping-backend", "{\"args\":{\"foo\":\"bar\",\"n\":42}}");
    controller.handleInvoke(ctx);

    ArgumentCaptor<String> argsJson = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).dispatch(any(), argsJson.capture(), any(InvocationProvenance.class), any(), any(EngineContext.class));
    JsonNode parsed = MAPPER.readTree(argsJson.getValue());
    assertEquals("bar", parsed.get("foo").asText());
    assertEquals(42, parsed.get("n").asInt());
  }

  @Test
  @DisplayName("OperationResult with structuredData is mapped to wire response")
  void structuredDataPassThrough() throws Exception {
    OperationResult result =
        OperationResult.success(
            "ok", Map.of("port", 9001, "elapsedMs", 123L));
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class))).thenReturn(result);

    Context ctx = mockContext("core.ping-backend", "{}");
    controller.handleInvoke(ctx);

    JsonNode response = capture(ctx);
    JsonNode structured = response.get("structuredData");
    assertNotNull(structured);
    assertEquals(9001, structured.get("port").asInt());
    assertEquals(123L, structured.get("elapsedMs").asLong());
  }

  @Test
  @DisplayName("OperationResult with executionId is mapped to wire response")
  void executionIdPassThrough() throws Exception {
    OperationResult result = OperationResult.success("ok", "550e8400-uuid");
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class))).thenReturn(result);

    Context ctx = mockContext("core.ping-backend", "{}");
    controller.handleInvoke(ctx);

    JsonNode response = capture(ctx);
    assertEquals("550e8400-uuid", response.get("executionId").asText());
  }

  @Test
  @DisplayName("dispatcher receives typed InvocationProvenance (TransportTag.BUTTON, ExecutorTag.UI)")
  void dispatcherReceivesTypedProvenance() throws Exception {
    // Slice 490 §4.B: the HTTP entry point constructs an InvocationProvenance with
    // TransportTag.BUTTON (FE ActionButton is the dominant caller) and ExecutorTag.UI,
    // and passes it to the dispatcher. Without this stub, the mocked dispatcher
    // returned null for the 3-arg overload and tests NPE'd.
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContext("core.ping-backend", "{}");
    controller.handleInvoke(ctx);

    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).dispatch(any(), any(), provenance.capture(), any(), any(EngineContext.class));
    InvocationProvenance captured = provenance.getValue();
    assertEquals(
        TransportTag.BUTTON, captured.transport());
    assertEquals(io.justsearch.agent.api.registry.ExecutorTag.UI, captured.executor());
    assertEquals(
        Optional.of("local-webview"),
        captured.initiator(),
        "v1 HTTP endpoint carries the resolved client identity as the initiator projection");
    assertNotNull(captured.occurredAt());
  }

  @Test
  @DisplayName("blank id path param — 400 with BAD_REQUEST")
  void blankIdParam() throws Exception {
    Context ctx = mockContext("", "{}");
    controller.handleInvoke(ctx);

    verify(ctx).status(400);
    JsonNode response = capture(ctx);
    assertEquals("BAD_REQUEST", response.get("errorClass").asText());
  }

  // ----- Slice 489 §17.5 — FE→backend transport stamping via X-JustSearch-Transport -----

  private Context mockContextWithTransportHeader(String idPath, String body, String transport) {
    Context ctx = mockContext(idPath, body);
    when(ctx.header("X-JustSearch-Transport")).thenReturn(transport);
    return ctx;
  }

  @Test
  @DisplayName("X-JustSearch-Transport=URL_BAR stamps URL_BAR provenance")
  void transportHeaderUrlBarStampsUrlBar() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContextWithTransportHeader("core.ping-backend", "{}", "URL_BAR");
    controller.handleInvoke(ctx);

    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).dispatch(any(), any(), provenance.capture(), any(), any(EngineContext.class));
    assertEquals(
        TransportTag.URL_BAR, provenance.getValue().transport());
  }

  @Test
  @DisplayName("X-JustSearch-Transport=PALETTE stamps PALETTE provenance")
  void transportHeaderPaletteStampsPalette() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContextWithTransportHeader("core.ping-backend", "{}", "PALETTE");
    controller.handleInvoke(ctx);

    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).dispatch(any(), any(), provenance.capture(), any(), any(EngineContext.class));
    assertEquals(
        TransportTag.PALETTE, provenance.getValue().transport());
  }

  @Test
  @DisplayName("X-JustSearch-Transport=URL_DEEPLINK stamps URL_DEEPLINK provenance")
  void transportHeaderDeeplinkStampsDeeplink() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContextWithTransportHeader("core.ping-backend", "{}", "URL_DEEPLINK");
    controller.handleInvoke(ctx);

    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).dispatch(any(), any(), provenance.capture(), any(), any(EngineContext.class));
    assertEquals(
        TransportTag.URL_DEEPLINK,
        provenance.getValue().transport());
  }

  @Test
  @DisplayName("transport header is case-insensitive (url_bar → URL_BAR)")
  void transportHeaderIsCaseInsensitive() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContextWithTransportHeader("core.ping-backend", "{}", "url_bar");
    controller.handleInvoke(ctx);

    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).dispatch(any(), any(), provenance.capture(), any(), any(EngineContext.class));
    assertEquals(
        TransportTag.URL_BAR, provenance.getValue().transport());
  }

  @Test
  @DisplayName("unknown transport header falls back to BUTTON (no privilege escalation)")
  void unknownTransportFallsBackToButton() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContextWithTransportHeader("core.ping-backend", "{}", "TIME_TRAVELLER");
    controller.handleInvoke(ctx);

    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).dispatch(any(), any(), provenance.capture(), any(), any(EngineContext.class));
    assertEquals(
        TransportTag.BUTTON, provenance.getValue().transport());
  }

  @Test
  @DisplayName("blank transport header falls back to BUTTON (preserves prior behavior)")
  void blankTransportFallsBackToButton() throws Exception {
    when(dispatcher.dispatch(any(), any(), any(InvocationProvenance.class), any(), any(EngineContext.class)))
        .thenReturn(OperationResult.success("ok"));

    Context ctx = mockContextWithTransportHeader("core.ping-backend", "{}", "   ");
    controller.handleInvoke(ctx);

    ArgumentCaptor<InvocationProvenance> provenance =
        ArgumentCaptor.forClass(InvocationProvenance.class);
    verify(dispatcher).dispatch(any(), any(), provenance.capture(), any(), any(EngineContext.class));
    assertEquals(
        TransportTag.BUTTON, provenance.getValue().transport());
  }
}
