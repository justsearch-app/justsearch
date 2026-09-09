/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.util.Locale;
import java.util.Optional;

/** Front-owned attribution. Cooperative headers never grant authority or exempt admission. */
public final class RequestEngineContext {
  public static final String ATTRIBUTE = "__engine_context__";

  private RequestEngineContext() {}

  /** Resolve once, including when a controller is mounted without the full API filter stack. */
  public static EngineContext get(Context request) {
    EngineContext existing = request.attribute(ATTRIBUTE);
    if (existing != null) return existing;
    try {
      boolean mcp = request.path().equals("/mcp");
      TransportTag transport = mcp ? TransportTag.MCP : transport(request);
      EngineContext.ClientKind kind = mcp ? EngineContext.ClientKind.MCP_CLIENT
          : value(request, "X-JustSearch-Client-Kind", EngineContext.ClientKind.class,
              EngineContext.ClientKind.WEBVIEW);
      Optional<String> session = optional(request, mcp ? "Mcp-Session-Id" : "X-JustSearch-Session-Id");
      String clientId = optional(request, "X-JustSearch-Client-Id")
          .orElseGet(() -> mcp ? session.orElse("mcp-anonymous") : "local-webview");
      EngineContext resolved = EngineProvenance.context(kind, clientId, session,
          optional(request, "X-JustSearch-Grant-Reference"), transport,
          EngineContext.Survival.INTERACTIVE, urgency(request));
      request.attribute(ATTRIBUTE, resolved);
      return resolved;
    } catch (IllegalArgumentException e) {
      throw new BadRequestResponse("Invalid Engine context: " + e.getMessage());
    }
  }

  private static EngineContext.Urgency urgency(Context request) {
    if (request.method() != io.javalin.http.HandlerType.GET
        && request.method() != io.javalin.http.HandlerType.HEAD) {
      return EngineContext.Urgency.FOREGROUND;
    }
    String path = request.path();
    boolean observer = path.equals("/api/knowledge/status") || path.equals("/api/status")
        || path.equals("/api/health") || path.startsWith("/api/health/")
        || path.equals("/api/debug/state") || path.equals("/api/diagnostics")
        || path.startsWith("/api/diagnostics/");
    return observer ? EngineContext.Urgency.BACKGROUND : EngineContext.Urgency.FOREGROUND;
  }

  /** Direct resume/fork/undo enters the same agent trust boundary as the shape runner. */
  public static EngineContext agent(Context request) {
    EngineContext incoming = get(request);
    return EngineProvenance.rebase(incoming, incoming.sessionId(),
        TransportTag.AGENT_LOOP, incoming.survival(), incoming.urgency());
  }

  /** Existing browser transport fallback; catalog resolution remains the trust authority. */
  private static TransportTag transport(Context request) {
    String header = request.header("X-JustSearch-Transport");
    if (header == null || header.isBlank()) return TransportTag.BUTTON;
    TransportTag declared;
    try {
      declared = TransportTag.valueOf(header.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // Unknown cooperative hints have no authority and preserve the browser default. Registered
      // backend-only transports are handled below and remain explicit rejections.
      return TransportTag.BUTTON;
    }
    return switch (declared) {
      // The shell also forwards untrusted provenance when approving/replaying an AI action.
      case BUTTON, RAIL, PALETTE, URL_BAR, URL_DEEPLINK,
          LLM_EMISSION, AGENT_LOOP, WORKFLOW, MCP -> declared;
      case SYSTEM_INTERNAL -> TransportTag.BUTTON;
      case PLUGIN_EMITTED, SCHEDULED, RULE_ENGINE ->
          throw new BadRequestResponse("Backend transport cannot be declared by HTTP header");
    };
  }

  private static Optional<String> optional(Context request, String name) {
    String value = request.header(name);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }

  private static <E extends Enum<E>> E value(Context request, String name, Class<E> type, E fallback) {
    return optional(request, name).map(v -> Enum.valueOf(type, v.toUpperCase(Locale.ROOT))).orElse(fallback);
  }
}
