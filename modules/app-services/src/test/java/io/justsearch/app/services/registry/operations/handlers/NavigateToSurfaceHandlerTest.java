package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentDispatchResult;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NavigateToSurfaceHandler} (slice 491 §9.D Phase E E3).
 *
 * <p>Asserts: (a) handler parses surfaceId from args JSON; (b) handler dispatches a
 * {@link ShellAddress.Navigation} envelope through {@link BackendIntentRouter}; (c) the
 * envelopeId from a Forwarded result lands in {@code structuredData}; (d) missing-arg
 * + null-router error paths return failure with the expected errorCode.
 */
final class NavigateToSurfaceHandlerTest {

  @Test
  @DisplayName("Dispatches a Navigation intent and returns Forwarded result")
  void dispatchesNavigationIntent() {
    var captured = new AtomicReference<Intent>();
    BackendIntentRouter router =
        (intent, prov, engineContext) -> {
          captured.set(intent);
          return new IntentDispatchResult.Forwarded("env-123");
        };
    NavigateToSurfaceHandler handler = new NavigateToSurfaceHandler(() -> router);

    OperationResult result = handler.execute("{\"surfaceId\":\"core.library-surface\"}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertNotNull(captured.get());
    assertTrue(captured.get().address() instanceof ShellAddress.Navigation);
    ShellAddress.Navigation nav = (ShellAddress.Navigation) captured.get().address();
    assertEquals("core.library-surface", nav.target().value());
    assertEquals("core.library-surface", result.structuredData().get("surfaceId"));
    assertEquals("env-123", result.structuredData().get("envelopeId"));
    assertEquals("forwarded", result.structuredData().get("outcome"));
  }

  @Test
  @DisplayName("Missing surfaceId returns BAD_REQUEST failure")
  void missingSurfaceIdReturnsBadRequest() {
    NavigateToSurfaceHandler handler =
        new NavigateToSurfaceHandler(() -> (intent, prov, engineContext) -> new IntentDispatchResult.Forwarded("x"));
    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertEquals("BAD_REQUEST", result.errorCode().orElse(null));
  }

  @Test
  @DisplayName("Blank surfaceId returns BAD_REQUEST failure")
  void blankSurfaceIdReturnsBadRequest() {
    NavigateToSurfaceHandler handler =
        new NavigateToSurfaceHandler(() -> (intent, prov, engineContext) -> new IntentDispatchResult.Forwarded("x"));
    OperationResult result = handler.execute("{\"surfaceId\":\"\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertEquals("BAD_REQUEST", result.errorCode().orElse(null));
  }

  @Test
  @DisplayName("Null router returns SERVICE_UNAVAILABLE failure")
  void nullRouterReturnsServiceUnavailable() {
    NavigateToSurfaceHandler handler = new NavigateToSurfaceHandler(() -> null);
    OperationResult result = handler.execute("{\"surfaceId\":\"core.library-surface\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertEquals("SERVICE_UNAVAILABLE", result.errorCode().orElse(null));
  }

  /**
   * F6 helper — build an InvocationProvenance with a specific transport for the
   * context-aware execute() overload.
   */
  private static InvocationProvenance provenance(TransportTag transport) {
    return InvocationProvenance.fromEngineContext(
        io.justsearch.app.services.TestEngineContexts.forTransport(transport),
        ExecutorTag.UI,
        Instant.parse("2026-05-14T00:00:00Z"),
        Optional.empty());
  }

  @Test
  @DisplayName(
      "F6: dispatched intent's transport matches provenance.transport (AGENT_LOOP)")
  void transportPropagatedFromProvenanceAgentLoop() {
    var captured = new AtomicReference<Intent>();
    BackendIntentRouter router =
        (intent, prov, engineContext) -> {
          captured.set(intent);
          return new IntentDispatchResult.Forwarded("env-x");
        };
    NavigateToSurfaceHandler handler = new NavigateToSurfaceHandler(() -> router);

    OperationResult result =
        handler.execute(
            "{\"surfaceId\":\"core.library-surface\"}", provenance(TransportTag.AGENT_LOOP),
            io.justsearch.app.services.TestEngineContexts.forProvenance(
                provenance(TransportTag.AGENT_LOOP)));

    assertTrue(result.success());
    assertEquals(TransportTag.AGENT_LOOP, captured.get().transport());
  }

  @Test
  @DisplayName(
      "F6: dispatched intent's transport matches provenance.transport (PALETTE)")
  void transportPropagatedFromProvenancePalette() {
    var captured = new AtomicReference<Intent>();
    BackendIntentRouter router =
        (intent, prov, engineContext) -> {
          captured.set(intent);
          return new IntentDispatchResult.Forwarded("env-y");
        };
    NavigateToSurfaceHandler handler = new NavigateToSurfaceHandler(() -> router);

    OperationResult result =
        handler.execute(
            "{\"surfaceId\":\"core.library-surface\"}", provenance(TransportTag.PALETTE),
            io.justsearch.app.services.TestEngineContexts.forProvenance(
                provenance(TransportTag.PALETTE)));

    assertTrue(result.success());
    assertEquals(TransportTag.PALETTE, captured.get().transport());
  }

  @Test
  @DisplayName(
      "F6: dispatched intent's transport matches provenance.transport (RAIL)")
  void transportPropagatedFromProvenanceRail() {
    var captured = new AtomicReference<Intent>();
    BackendIntentRouter router =
        (intent, prov, engineContext) -> {
          captured.set(intent);
          return new IntentDispatchResult.Forwarded("env-z");
        };
    NavigateToSurfaceHandler handler = new NavigateToSurfaceHandler(() -> router);

    OperationResult result =
        handler.execute(
            "{\"surfaceId\":\"core.library-surface\"}", provenance(TransportTag.RAIL),
            io.justsearch.app.services.TestEngineContexts.forProvenance(
                provenance(TransportTag.RAIL)));

    assertTrue(result.success());
    assertEquals(TransportTag.RAIL, captured.get().transport());
  }

  @Test
  @DisplayName("Router exception surfaces as DISPATCH_FAILED failure")
  void routerExceptionReturnsDispatchFailed() {
    BackendIntentRouter router =
        (intent, prov, engineContext) -> {
          throw new RuntimeException("boom");
        };
    NavigateToSurfaceHandler handler = new NavigateToSurfaceHandler(() -> router);
    OperationResult result = handler.execute("{\"surfaceId\":\"core.unknown-surface\"}", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertEquals("DISPATCH_FAILED", result.errorCode().orElse(null));
    assertEquals("core.unknown-surface", result.errorDetails().get("surfaceId"));
  }
}
