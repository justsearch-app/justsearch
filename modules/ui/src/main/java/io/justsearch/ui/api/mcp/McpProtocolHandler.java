/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.core.context.EngineContext;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.mcp.McpContractVersions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Layer 1: MCP Streamable HTTP protocol transport.
 *
 * <p>Handles JSON-RPC framing, session management, and method dispatch. Delegates tool,
 * prompt, and resource logic to {@link McpToolSurface} (Layer 2). Per tempdoc 500's
 * three-layer architecture, this class has no knowledge of tool definitions, descriptions,
 * or backend dispatch paths.
 */
public final class McpProtocolHandler {

  private static final Logger log = LoggerFactory.getLogger(McpProtocolHandler.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String JSONRPC_VERSION = "2.0";
  // Tempdoc 654: single-sourced from app-api so the manifest's RuntimeContract and this
  // initialize response report identical versions by construction (projection, not fork).
  private static final String MCP_PROTOCOL_VERSION = McpContractVersions.PROTOCOL_VERSION;
  private static final String SERVER_NAME = "JustSearch";
  /**
   * Version reported when the build version is unknown — a browser/dev run started outside the
   * packaged shell, which is the only launcher that passes {@code -Djustsearch.app.version}. Never
   * a real release number, so a host that logs or gates on it cannot mistake a dev run for a build.
   */
  static final String UNKNOWN_BUILD_VERSION = "0.0.0-dev";
  private static final Duration SESSION_TTL = Duration.ofMinutes(30);

  /**
   * MCP {@code serverInfo.version} — the version of the SERVER IMPLEMENTATION, i.e. this build
   * (tempdoc 804 §B9 / round-10 F12: it reported the curated tool-surface version {@code 0.5.0} on a
   * {@code 0.2.0} build, so an MCP host that logs or gates on server version saw a version this
   * release does not have). Read from the one build-version source the packaged shell already sets
   * ({@code EnvRegistry.APP_VERSION}, passed as {@code -Djustsearch.app.version} from the Tauri
   * package version), never a literal. The tool-surface version keeps its own, separately-named slot
   * ({@code serverInfo._meta}, plus the runtime manifest's {@code mcpToolSurfaceVersion}) — two
   * different claims, two fields.
   */
  static String buildVersion() {
    return io.justsearch.configuration.EnvRegistry.APP_VERSION
        .get()
        .filter(v -> !v.isBlank())
        .orElse(UNKNOWN_BUILD_VERSION);
  }

  private final McpToolSurface surface;
  private final List<ResourceCatalog> resourceCatalogs;
  private final Clock clock;
  private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();

  public McpProtocolHandler(McpToolSurface surface, List<ResourceCatalog> resourceCatalogs,
      Clock clock) {
    this.surface = Objects.requireNonNull(surface);
    this.resourceCatalogs = List.copyOf(Objects.requireNonNull(resourceCatalogs));
    this.clock = Objects.requireNonNull(clock);
  }

  public McpProtocolHandler(McpToolSurface surface, List<ResourceCatalog> resourceCatalogs) {
    this(surface, resourceCatalogs, Clock.systemUTC());
  }

  public void handlePost(Context ctx) {
    var engineContext = io.justsearch.ui.api.RequestEngineContext.get(ctx);
    String sessionId = ctx.header("Mcp-Session-Id");
    String body = ctx.body();
    Object requestId = null;

    try {
      var node = MAPPER.readTree(body);
      String method = node.has("method") ? node.get("method").asText() : null;
      // JSON-RPC 2.0 §4.1: a Notification is a Request object WITHOUT an "id" MEMBER. Presence of
      // the member is what matters, not its value — an explicit "id":null is a (malformed, but
      // still a) Request, not a Notification, and must not be conflated with the absent case; it
      // falls through to the normal request path below like any other request.
      boolean isNotification = !node.has("id");
      var id = isNotification ? null : node.get("id");
      requestId = id;
      var params = node.has("params") ? node.get("params") : MAPPER.createObjectNode();

      io.justsearch.app.api.EngineAdmissionException refused =
          ctx.attribute(io.justsearch.ui.api.RequestEngineWork.REFUSAL_ATTRIBUTE);
      if (refused != null) {
        io.justsearch.ui.api.RequestEngineWork.status(ctx, refused);
        if (!isNotification) writeError(ctx, id, -32000, refused.getMessage(),
            io.justsearch.ui.api.RequestEngineWork.errorCode(refused));
        return;
      }

      if (method == null) {
        writeError(ctx, id, -32600, "Invalid Request: missing method");
        return;
      }

      if (isNotification) {
        // "The Server MUST NOT reply to a Notification, including those that are within a batch
        // request" (JSON-RPC 2.0 §4.1) — this holds even for an unrecognized method name, so
        // notifications never flow through the method-dispatch switch/error path below.
        // `notifications/initialized` is the mandatory post-initialize lifecycle notification
        // every MCP client sends (this codebase's own McpClient#initialize sends the identical
        // string); the others are the MCP spec's remaining standard client->server notifications.
        // None currently require server-side state changes, so all are accept-and-discard.
        handleNotification(method);
        // Streamable HTTP: 202 Accepted + empty body is what the MCP spec prescribes for a POST
        // carrying only notifications/responses. The shipped MCPB bridge
        // (packaging/mcpb/server/index.js postToServer) special-cases status 202/204 by resolving
        // immediately without attempting to parse a body, so this is bridge-safe by construction.
        ctx.status(202);
        return;
      }

      Object result = switch (method) {
        case "initialize" -> handleInitialize(ctx, params);
        case "tools/list" -> surface.listTools();
        case "tools/call" -> handleToolsCall(params, sessionId, engineContext);
        case "resources/list" -> surface.listResources(resourceCatalogs);
        case "resources/read" -> handleResourcesRead(params, engineContext);
        case "resources/subscribe" -> handleResourcesSubscribe(params, sessionId);
        case "resources/unsubscribe" -> handleResourcesUnsubscribe(params, sessionId);
        case "prompts/list" -> surface.listPrompts();
        case "prompts/get" -> handlePromptsGet(params, engineContext);
        case "ping" -> Map.of();
        default -> {
          writeError(ctx, id, -32601, "Method not found: " + method);
          yield null;
        }
      };

      if (result != null) {
        writeResult(ctx, id, result);
      }
    } catch (io.justsearch.app.api.EngineAdmissionException refused) {
      io.justsearch.ui.api.RequestEngineWork.status(ctx, refused);
      writeError(ctx, requestId, -32000, refused.getMessage(),
          io.justsearch.ui.api.RequestEngineWork.errorCode(refused));
    } catch (Exception e) {
      var executorRefusal = io.justsearch.ui.api.ApiErrorHandler.executorRefusal(e);
      if (executorRefusal != null) {
        io.justsearch.ui.api.ApiErrorHandler.executorRefusalStatus(ctx, executorRefusal);
        writeError(ctx, requestId, -32000,
            io.justsearch.ui.api.ApiErrorHandler.sanitizeMessage(executorRefusal.getMessage()),
            io.justsearch.ui.api.ApiErrorHandler.resolve(executorRefusal).name(), false);
        return;
      }
      log.warn("MCP protocol error", e);
      writeError(ctx, null, -32603, "Internal error: " + e.getMessage());
    }
  }

  /**
   * Accept-and-discard for client->server notifications. None of the MCP spec's standard
   * notifications currently require server-side state (no in-flight-request cancellation table,
   * no progress tracking, no dynamic roots), so this is deliberately a no-op beyond logging — the
   * correctness requirement is entirely upstream, in {@link #handlePost} never treating a
   * notification as something to reply to.
   */
  private void handleNotification(String method) {
    switch (method) {
      case "notifications/initialized",
          "notifications/cancelled",
          "notifications/progress",
          "notifications/roots/list_changed" -> log.debug("Received MCP notification: {}", method);
      default -> log.debug("Received unrecognized MCP notification (accepted, no-op): {}", method);
    }
  }

  public void handleDelete(Context ctx) {
    String sessionId = ctx.header("Mcp-Session-Id");
    if (sessionId != null) sessions.remove(sessionId);
    ctx.status(204);
  }

  /**
   * MCP Streamable-HTTP requires the single endpoint to answer {@code GET}: the server either opens
   * a server-to-client SSE stream ({@code text/event-stream}) or "MUST return HTTP 405 Method Not
   * Allowed, indicating that the server does not offer an SSE stream at this endpoint" (Transports
   * §Streamable HTTP, identical in 2025-06-18 and 2025-11-25).
   *
   * <p>JustSearch offers no SSE stream at {@code /mcp}: every MCP response is the direct reply to a
   * {@code POST}, and nothing here pushes unsolicited JSON-RPC to a connected client. Serving one
   * would need a per-session push channel plus the spec's resumability machinery
   * ({@code Last-Event-ID}, event ids) — a <b>product</b> decision, not a conformance one. 405 is
   * the spec's own answer for a server without such a stream, and it is what a conforming client
   * probes for; before this it was a 404, which tells the client nothing about the endpoint.
   *
   * <p>Static because the answer carries no session or protocol state — which also lets the
   * conformance test bind the production handler rather than a mirror of it.
   */
  public static void handleGet(Context ctx) {
    // OPTIONS included because the CORS preflight catch-all (app.options("/*"),
    // ApiSecurityFilters#setupCors) genuinely serves it on this path too — Allow must name every
    // method the resource answers, not just the ones bound here.
    ctx.header("Allow", "POST, DELETE, OPTIONS");
    ctx.status(405);
    writeError(
        ctx,
        null,
        -32600,
        "Method Not Allowed: this endpoint offers no server-initiated SSE stream; use POST",
        "MCP_METHOD_NOT_ALLOWED");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleInitialize(Context ctx, Object paramsObj) {
    cleanStaleSessions();
    String sessionId = UUID.randomUUID().toString();
    // Tempdoc 655: capture the caller's self-reported clientInfo.name (part of the MCP spec's own
    // initialize request) for DISPLAY ONLY — surfaced later in the approval ceremony so a human
    // can see which agent is asking. Never used for any trust decision (ADR-0030: a handshake-
    // declared identity is a hint, not enforced policy — same line the ADR already draws for MCP
    // tool annotations).
    String clientName = null;
    try {
      var params = MAPPER.convertValue(paramsObj, Map.class);
      Object clientInfo = params != null ? params.get("clientInfo") : null;
      if (clientInfo instanceof Map<?, ?> ci && ci.get("name") instanceof String name
          && !name.isBlank()) {
        clientName = name;
      }
    } catch (Exception e) {
      log.debug("Failed to parse clientInfo from initialize params: {}", e.getMessage());
    }
    sessions.put(sessionId, new McpSession(clock.instant(), clientName));
    ctx.header("Mcp-Session-Id", sessionId);

    return Map.of(
        "protocolVersion", MCP_PROTOCOL_VERSION,
        // Tempdoc 655 fix: tools.listChanged/resources.listChanged were previously declared
        // `true` with no code path that ever emits the corresponding notifications/*/list_changed
        // message — an over-declared capability. Both the 6-tool list (McpToolSurface#listTools)
        // and the advisory-resource list (AdvisoryResourceCatalog#DEFINITIONS) are fixed at
        // compile time with no runtime-mutable path, so `false` is the honest declaration, not a
        // deferred notification mechanism. resources.subscribe stays true — that capability (live
        // updates within an already-known resource's own stream) is real and unrelated to whether
        // the resource LIST can change.
        "capabilities", Map.of(
            "tools", Map.of("listChanged", false),
            "resources", Map.of("subscribe", true, "listChanged", false),
            "prompts", Map.of("listChanged", false),
            "experimental", surface.experimentalCapabilities()),
        // Tempdoc 804 §B9 (F12): `version` is THIS BUILD (the claim MCP's serverInfo makes); the
        // curated tool-surface version rides `_meta` under a namespaced key, so both stay reachable
        // and neither is reported as the other.
        "serverInfo", Map.of(
            "name", SERVER_NAME,
            "version", buildVersion(),
            "_meta", Map.of(
                "io.justsearch/toolSurfaceVersion", McpContractVersions.TOOL_SURFACE_VERSION)),
        // Tempdoc 655 (agent-legibility layer): the MCP spec's optional connect-time steering slot.
        // Clients (confirmed: Claude Code) inject it into the model's system context, so it is the
        // one server-level surface that reaches an autonomous agent at tool-selection time. Content
        // is the comparative tool-selection guidance owned by Layer 2 (McpToolSurface) — Layer 1
        // holds no tool knowledge, so it asks the surface for the string rather than authoring it.
        "instructions", surface.instructions());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleToolsCall(Object paramsObj, String sessionId, EngineContext engineContext) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    if (params == null) return McpToolSurface.errorContent("Invalid params", ApiErrorCode.INVALID_REQUEST);
    String toolName = (String) params.get("name");
    Map<String, Object> arguments =
        (Map<String, Object>) params.getOrDefault("arguments", Map.of());
    if (toolName == null) return McpToolSurface.errorContent("Tool name is required", ApiErrorCode.INVALID_REQUEST);
    touchSession(sessionId);
    String requestedBy = sessionId != null && sessions.get(sessionId) != null
        ? sessions.get(sessionId).clientName
        : null;
    return surface.callTool(toolName, arguments, sessionId, requestedBy, engineContext);
  }

  private Map<String, Object> handleResourcesRead(Object paramsObj, EngineContext engineContext) {
    @SuppressWarnings("unchecked")
    var params = MAPPER.convertValue(paramsObj, Map.class);
    String uri = params != null ? (String) params.get("uri") : null;
    return surface.readResource(uri, engineContext);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handlePromptsGet(Object paramsObj, EngineContext engineContext) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    if (params == null) return Map.of("messages", List.of());
    String name = (String) params.get("name");
    Map<String, String> arguments =
        (Map<String, String>) params.getOrDefault("arguments", Map.of());
    return surface.getPrompt(name, arguments, engineContext);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleResourcesSubscribe(Object paramsObj, String sessionId) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    String uri = params != null ? (String) params.get("uri") : null;
    if (sessionId != null && uri != null) {
      McpSession session = sessions.get(sessionId);
      if (session != null) session.subscriptions.add(uri);
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> handleResourcesUnsubscribe(Object paramsObj, String sessionId) {
    var params = MAPPER.convertValue(paramsObj, Map.class);
    String uri = params != null ? (String) params.get("uri") : null;
    if (sessionId != null && uri != null) {
      McpSession session = sessions.get(sessionId);
      if (session != null) session.subscriptions.remove(uri);
    }
    return Map.of();
  }

  private void touchSession(String sessionId) {
    if (sessionId != null) {
      McpSession session = sessions.get(sessionId);
      if (session != null) session.lastActivity = clock.instant();
    }
  }

  private void cleanStaleSessions() {
    Instant cutoff = clock.instant().minus(SESSION_TTL);
    Iterator<Map.Entry<String, McpSession>> it = sessions.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().lastActivity.isBefore(cutoff)) {
        log.info("Evicting stale MCP session (idle > {})", SESSION_TTL);
        it.remove();
      }
    }
  }

  private void writeResult(Context ctx, Object id, Object result) {
    try {
      var response = new LinkedHashMap<String, Object>();
      response.put("jsonrpc", JSONRPC_VERSION);
      response.put("id", id);
      response.put("result", result);
      ctx.contentType("application/json");
      ctx.result(MAPPER.writeValueAsString(response));
    } catch (Exception e) {
      log.error("Failed to write MCP result", e);
      ctx.status(500);
    }
  }

  private static void writeError(Context ctx, Object id, int code, String message) {
    writeError(ctx, id, code, message, null);
  }

  /**
   * JSON-RPC error envelope. {@code errorCode} is optional and rides in the {@code error.data} slot
   * — the repo's {@code errorCode} convention expressed without breaking the JSON-RPC error object
   * (the same placement {@code ApiSecurityFilters.mcpForbiddenOriginBody} uses for the 403).
   */
  private static void writeError(
      Context ctx, Object id, int code, String message, String errorCode) {
    writeError(ctx, id, code, message, errorCode, null);
  }

  private static void writeError(
      Context ctx, Object id, int code, String message, String errorCode, Boolean retrySafe) {
    try {
      var error = new LinkedHashMap<String, Object>();
      error.put("code", code);
      error.put("message", message);
      if (errorCode != null) {
        var data = new LinkedHashMap<String, Object>();
        data.put("errorCode", errorCode);
        if (retrySafe != null) data.put("retrySafe", retrySafe);
        error.put("data", data);
      }
      var response = new LinkedHashMap<String, Object>();
      response.put("jsonrpc", JSONRPC_VERSION);
      response.put("id", id);
      response.put("error", error);
      ctx.contentType("application/json");
      ctx.result(MAPPER.writeValueAsString(response));
    } catch (Exception e) {
      log.error("Failed to write MCP error", e);
      ctx.status(500);
    }
  }

  private static final class McpSession {
    volatile Instant lastActivity;
    final java.util.Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    // Tempdoc 655: the client's self-reported name from `initialize`'s `clientInfo` — display
    // only, never a trust input. Nullable — absent for clients that omit clientInfo.
    final String clientName;

    McpSession(Instant createdAt, String clientName) {
      this.lastActivity = createdAt;
      this.clientName = clientName;
    }
  }
}
