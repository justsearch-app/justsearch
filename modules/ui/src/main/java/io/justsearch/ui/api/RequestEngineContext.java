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
    return get(request, ignored -> Optional.empty());
  }

  /** The protocol owner resolves known server-issued sessions before the admission filters run. */
  public static EngineContext get(Context request,
      java.util.function.Function<String, Optional<String>> mcpClientIdentity) {
    EngineContext existing = request.attribute(ATTRIBUTE);
    if (existing != null) return existing;
    try {
      boolean nativeMcp = request.path().equals("/mcp");
      TransportTag transport = nativeMcp ? TransportTag.MCP : transport(request);
      boolean mcp = transport == TransportTag.MCP;
      EngineContext.ClientKind kind = mcp ? EngineContext.ClientKind.MCP_CLIENT
          : value(request, "X-JustSearch-Client-Kind", EngineContext.ClientKind.class,
              EngineContext.ClientKind.WEBVIEW);
      Optional<String> session = optional(request, nativeMcp ? "Mcp-Session-Id" : "X-JustSearch-Session-Id");
      String clientId = mcp ? session.flatMap(mcpClientIdentity).orElse("mcp-anonymous")
          : optional(request, "X-JustSearch-Client-Id").orElse("local-webview");
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

  /** User input is independent of work scheduling. Polls never extend the idle window. */
  static boolean isUserActivity(Context request) {
    return ActivityRole.isUserActivity(request);
  }

  /** The web UI's presence signal; its body may say how long ago the reported input happened. */
  static final String PRESENCE_PATH = "/api/ui/activity";

  /**
   * How long before arrival the input a request reports happened: the presence signal's
   * {@code inputAgeMs}, which the web UI sets on the report it sends when a presence interval ends.
   * Every other request, and a presence report without a usable age, is input happening now. The
   * activity owner bounds the age, so no client can place input in the future or far in the past.
   */
  static long reportedInputAgeMs(Context request) {
    if (!PRESENCE_PATH.equals(request.path()) || request.method() != io.javalin.http.HandlerType.POST) {
      return 0;
    }
    try {
      String body = request.body();
      if (body.isBlank()) return 0;
      var age = PRESENCE_JSON.readTree(body).path("inputAgeMs");
      return age.isNumber() ? age.asLong() : 0;
    } catch (RuntimeException unreadable) {
      return 0;
    }
  }

  private static final tools.jackson.databind.ObjectMapper PRESENCE_JSON =
      new tools.jackson.databind.ObjectMapper();

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
