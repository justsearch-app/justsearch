/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.util.Optional;

/** Engine-context fixtures whose attribution agrees with the production source catalog. */
public final class TestEngineContexts {
  private TestEngineContexts() {}

  public static EngineContext internal() {
    return EngineProvenance.internal(
        "app-services-test", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }

  public static EngineContext durableInternal() {
    return EngineProvenance.internal(
        "app-services-test", EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
  }

  public static EngineContext ui() {
    return EngineProvenance.context(
        EngineContext.ClientKind.WEBVIEW, "test-webview", Optional.empty(), Optional.empty(),
        io.justsearch.agent.api.registry.TransportTag.BUTTON,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  }

  public static EngineContext agent() {
    return EngineProvenance.context(
        EngineContext.ClientKind.INTERNAL, "test-agent", Optional.of("test-session"),
        Optional.empty(), io.justsearch.agent.api.registry.TransportTag.AGENT_LOOP,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  }

  public static EngineContext workflow() {
    return EngineProvenance.context(
        EngineContext.ClientKind.INTERNAL, "test-workflow", Optional.empty(), Optional.empty(),
        io.justsearch.agent.api.registry.TransportTag.WORKFLOW,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  }

  public static EngineContext mcp() {
    return EngineProvenance.context(
        EngineContext.ClientKind.MCP_CLIENT, "test-mcp-client", Optional.of("test-mcp-session"),
        Optional.empty(), io.justsearch.agent.api.registry.TransportTag.MCP,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
  }

  /** Deterministic caller identity for a transport-specific provenance fixture. */
  public static EngineContext forTransport(
      io.justsearch.agent.api.registry.TransportTag transport) {
    EngineContext.ClientKind kind = switch (transport) {
      case MCP -> EngineContext.ClientKind.MCP_CLIENT;
      case AGENT_LOOP, LLM_EMISSION, WORKFLOW, SYSTEM_INTERNAL ->
          EngineContext.ClientKind.INTERNAL;
      default -> EngineContext.ClientKind.WEBVIEW;
    };
    String clientId = switch (kind) {
      case MCP_CLIENT -> "test-mcp-client";
      case WEBVIEW -> "test-webview";
      default -> "app-services-test";
    };
    return EngineProvenance.context(
        kind,
        clientId,
        Optional.empty(),
        Optional.empty(),
        transport,
        EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }

  /** Build the required port context from the invocation record a test already chose. */
  public static EngineContext forProvenance(InvocationProvenance provenance) {
    EngineContext transportContext = forTransport(provenance.transport());
    EngineContext.ClientKind kind = transportContext.clientKind();
    String clientId = provenance.initiator().orElse(transportContext.clientId());
    return EngineProvenance.context(
        kind,
        clientId,
        provenance.correlationId(),
        Optional.empty(),
        provenance.transport(),
        EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }
}
