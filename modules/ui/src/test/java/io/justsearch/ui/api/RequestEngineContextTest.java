/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.justsearch.core.context.EngineContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class RequestEngineContextTest {
  @Test
  void requestResolvesOnceAndHeadersCannotChooseWorkAxes() {
    Context request = request("/api/knowledge/search", Map.of(
        "X-JustSearch-Client-Id", "desktop-a",
        "X-JustSearch-Session-Id", "session-a",
        "X-JustSearch-Grant-Reference", "grant-a",
        "X-JustSearch-Survival", "DURABLE",
        "X-JustSearch-Urgency", "BACKGROUND"));
    EngineContext context = RequestEngineContext.get(request);
    assertSame(context, RequestEngineContext.get(request));
    assertEquals("desktop-a", context.clientId());
    assertEquals(Optional.of("session-a"), context.sessionId());
    assertEquals(Optional.of("grant-a"), context.grantReference());
    assertEquals("BUTTON", context.transport());
    assertEquals("TRUSTED", context.sourceTier());
    assertEquals(EngineContext.Survival.INTERACTIVE, context.survival());
    assertEquals(EngineContext.Urgency.FOREGROUND, context.urgency());
  }

  @Test
  void mcpCannotSelectTrustedBrowserSourceOrInternalKind() {
    EngineContext context = RequestEngineContext.get(request("/mcp", Map.of(
        "X-JustSearch-Transport", "BUTTON",
        "X-JustSearch-Client-Kind", "INTERNAL",
        "X-JustSearch-Client-Id", "forged-client",
        "Mcp-Session-Id", "mcp-session")), session -> Optional.of("mcp-session"));
    assertEquals(EngineContext.ClientKind.MCP_CLIENT, context.clientKind());
    assertEquals("mcp-session", context.clientId());
    assertEquals(Optional.of("mcp-session"), context.sessionId());
    assertEquals("MCP", context.transport());
    assertEquals("UNTRUSTED", context.sourceTier());
  }

  @Test
  void unknownAndMissingMcpSessionsShareAnonymousIdentityDespiteRotatingHints() {
    for (var headers : java.util.List.of(Map.<String, String>of(), Map.of(
        "Mcp-Session-Id", "invented-a", "X-JustSearch-Client-Id", "client-a"), Map.of(
        "Mcp-Session-Id", "invented-b", "X-JustSearch-Client-Id", "client-b"))) {
      assertEquals("mcp-anonymous", RequestEngineContext.get(request("/mcp", headers)).clientId());
    }
  }

  @Test
  void cooperativeInternalLabelDoesNotTurnAnAgentSourceIntoAuthority() {
    EngineContext context = RequestEngineContext.get(request("/api/operations/dispatch", Map.of(
        "X-JustSearch-Transport", "AGENT_LOOP",
        "X-JustSearch-Client-Kind", "INTERNAL",
        "X-JustSearch-Client-Id", "supervisor")));
    assertEquals(EngineContext.ClientKind.INTERNAL, context.clientKind());
    assertEquals("AGENT_LOOP", context.transport());
    assertEquals("UNTRUSTED", context.sourceTier());
  }

  @Test
  void browserReplayKeepsUntrustedTransportsAndNormalizesSystemTransport() {
    for (String transport : new String[] {"AGENT_LOOP", "WORKFLOW", "LLM_EMISSION", "MCP"}) {
      EngineContext context = RequestEngineContext.get(request("/api/operations/dispatch",
          Map.of("X-JustSearch-Transport", transport)));
      assertEquals(transport, context.transport());
      assertEquals("UNTRUSTED", context.sourceTier());
    }
    assertEquals("BUTTON", RequestEngineContext.get(request("/api/operations/dispatch",
        Map.of("X-JustSearch-Transport", "SYSTEM_INTERNAL"))).transport());
    assertEquals("BUTTON", RequestEngineContext.get(request("/api/operations/dispatch",
        Map.of("X-JustSearch-Transport", "invalid"))).transport());
    for (String invalid : new String[] {"PLUGIN_EMITTED", "SCHEDULED", "RULE_ENGINE"}) {
      assertThrows(BadRequestResponse.class, () -> RequestEngineContext.get(request(
          "/api/operations/dispatch", Map.of("X-JustSearch-Transport", invalid))));
    }
  }

  @Test
  void directAgentActionsRebaseTrustAndRetainCallerMetadata() {
    Context request = request("/api/agent/resume", Map.of(
        "X-JustSearch-Client-Id", "desktop-a",
        "X-JustSearch-Session-Id", "session-a",
        "X-JustSearch-Grant-Reference", "grant-a"));
    EngineContext original = RequestEngineContext.get(request);
    EngineContext agent = RequestEngineContext.agent(request);
    assertEquals("AGENT_LOOP", agent.transport());
    assertEquals("UNTRUSTED", agent.sourceTier());
    assertEquals(original.clientId(), agent.clientId());
    assertEquals(original.sessionId(), agent.sessionId());
    assertEquals(original.grantReference(), agent.grantReference());
    assertEquals(original.survival(), agent.survival());
    assertEquals(original.urgency(), agent.urgency());
    assertSame(original, RequestEngineContext.get(request));
  }

  private static Context request(String path, Map<String, String> headers) {
    Context request = mock(Context.class);
    Map<String, Object> attributes = new HashMap<>();
    when(request.path()).thenReturn(path);
    when(request.header(anyString())).thenAnswer(call -> headers.get(call.getArgument(0)));
    when(request.attribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
    doAnswer(call -> {
      attributes.put(call.getArgument(0), call.getArgument(1));
      return request;
    }).when(request).attribute(anyString(), any());
    return request;
  }
}
