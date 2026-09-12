/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationAdmissionClosedException;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 583 Stage 4: the request-filter security plumbing, lifted out of {@link LocalApiServer}
 * (§B.7 remedy).
 *
 * <p>This is the loopback-CORS / session-token / capability-gate / slow-request-dump policy —
 * ~300 LOC of {@code app.before(...)} filter wiring plus the rate-limited deny/slow-dump bookkeeping
 * it carries. It keeps the loopback bind policy (Hard Invariant #2) single-authority: {@link
 * #resolveAllowedOrigin} is the one CORS-origin decision and is still exercised verbatim by {@code
 * LocalApiCorsPolicyTest} / {@code LocalApiUiTokenPolicyTest}. Behaviour is identical — the filter
 * bodies moved verbatim; LocalApiServer constructs one instance and calls {@link #install} from
 * {@code buildAndStartApp} (and {@link #maybeCaptureSlowRequestDump} from the shared after-hook).
 */
final class ApiSecurityFilters {
  private static final Logger log = LoggerFactory.getLogger(ApiSecurityFilters.class);

  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
  // Newer Tauri/WebView2 versions use an http(s) origin for the bundled app assets.
  // This is not a general DNS-backed host; treat it as a special-case desktop origin.
  private static final String TAURI_WEBVIEW_HOST = "tauri.localhost";
  /** Methods that require the session token in prod mode. */
  private static final Set<String> TOKEN_REQUIRED_METHODS = Set.of("POST", "PUT", "DELETE");

  /** Contract-facing projection of the filters that actually enforce this route. */
  record ContractSecurity(
      boolean loopbackHostRequired, boolean sessionTokenRequired, boolean mcpOriginValidated) {}

  static ContractSecurity contractSecurity(String method, String path) {
    return new ContractSecurity(
        true,
        requiresSessionToken(method, path),
        MCP_ENDPOINT_PATH.equals(path) || MCP_TOKEN_PATH.equals(path));
  }
  /**
   * The MCP Streamable-HTTP endpoint path, shared with the route registration in {@link
   * LocalApiServer} so the guarded path and the routed path cannot drift. The spec's
   * transport-security clause is endpoint-scoped, so the Origin check is registered on this path
   * (all methods) rather than globally — the rest of the API keeps the browser-enforced CORS policy
   * in {@link #setupCors}.
   */
  private static final String MCP_ENDPOINT_PATH = LocalApiServer.MCP_ENDPOINT_PATH;
  /**
   * The MCP session-token bootstrap path, guarded by the same Origin check as {@link
   * #MCP_ENDPOINT_PATH} — see {@link #setupMcpOriginValidation} for why this second path is in
   * scope.
   */
  private static final String MCP_TOKEN_PATH = LocalApiServer.MCP_TOKEN_PATH;
  /** Cap on the attacker-controlled Origin string reproduced in logs and the event buffer. */
  private static final int MAX_LOGGED_ORIGIN_CHARS = 128;
  private static final long SLOW_REQUEST_THRESHOLD_MS = 3000;
  private static final long SLOW_DUMP_RATE_LIMIT_MS = 30_000;
  private static final String MUTATION_LEASE_ATTRIBUTE = "__upgrade_mutation_lease__";

  private final boolean prodMode;
  private final String sessionToken;
  private final EventBuffer eventBuffer;
  private final ExecutorService slowRequestExecutor;
  private final HeadAssembly headAssembly;
  private final OperationLeaseService operationLeases;
  private final io.justsearch.app.api.EngineAdmissionService engineAdmission;

  // Rate-limit bookkeeping for deny / slow-dump logging.
  private final AtomicLong lastCorsDenyUiReadyAtMs = new AtomicLong(0);
  private final AtomicReference<String> lastCorsDenyUiReadyOrigin = new AtomicReference<>("");
  private final AtomicLong lastTokenDenyAtMs = new AtomicLong(0);
  private final AtomicLong lastHostDenyAtMs = new AtomicLong(0);
  private final AtomicLong lastMcpOriginDenyAtMs = new AtomicLong(0);
  private final AtomicLong lastSlowDumpAtMs = new AtomicLong(0);

  ApiSecurityFilters(
      boolean prodMode,
      String sessionToken,
      EventBuffer eventBuffer,
      ExecutorService slowRequestExecutor,
      HeadAssembly headAssembly) {
    this(prodMode, sessionToken, eventBuffer, slowRequestExecutor, headAssembly, null);
  }

  ApiSecurityFilters(
      boolean prodMode,
      String sessionToken,
      EventBuffer eventBuffer,
      ExecutorService slowRequestExecutor,
      HeadAssembly headAssembly,
      OperationLeaseService operationLeases) {
    this(prodMode, sessionToken, eventBuffer, slowRequestExecutor, headAssembly, operationLeases, null);
  }

  ApiSecurityFilters(boolean prodMode, String sessionToken, EventBuffer eventBuffer,
      ExecutorService slowRequestExecutor, HeadAssembly headAssembly,
      OperationLeaseService operationLeases, io.justsearch.app.api.EngineAdmissionService engineAdmission) {
    this.prodMode = prodMode;
    this.sessionToken = sessionToken;
    this.eventBuffer = eventBuffer;
    this.slowRequestExecutor = slowRequestExecutor;
    this.headAssembly = headAssembly;
    this.operationLeases = operationLeases;
    this.engineAdmission = engineAdmission;

    // Tempdoc 884 item 23: FAIL CLOSED. This combination used to disable token enforcement with a
    // WARN and serve the API anyway — fail-open on the one control that gates mutation.
    //
    // The refusal is in the CONSTRUCTOR, not in setupSessionTokenEnforcement, for four reasons:
    //   1. prodMode/sessionToken are final, so the illegal state is a property of the object, not
    //      of whichever Javalin it is later installed onto. Constructing it is the error.
    //   2. It fires two layers before the bind (LocalApiServer constructs this, then
    //      buildAndStartApp, then app.start("127.0.0.1", ...)), so no socket is ever opened.
    //   3. buildAndStartApp runs TWICE on the ephemeral-port fallback path; a throw inside
    //      install() would be evaluated twice, while the constructor runs once.
    //   4. LocalApiServer.isBindFailure matches only BindException, and this construction sits
    //      OUTSIDE the try that guards it, so the throw can never be re-dispatched into the retry.
    if (prodMode && (sessionToken == null || sessionToken.isBlank())) {
      String reasonCode = LifecycleReasonCode.LOCAL_API_SESSION_TOKEN_MISSING.code();
      String message =
          "Refusing to start the local API: prod mode is enabled but no session token was provided."
              + " The session token is the only control gating mutating loopback requests, so"
              + " starting without it would ship the API open to any local process. Reason code: "
              + reasonCode
              + ". Pass a token (HeadlessApp mints one per boot when justsearch.prod=true) or start"
              + " in dev mode.";
      log.error(message);
      // eventBuffer is nullable (SlowRequestStreamExemptionTest constructs without one).
      if (eventBuffer != null) {
        eventBuffer.error(
            "LocalApiServer",
            "TOKEN_ENFORCEMENT_REFUSED",
            Map.of("reason", "no_token_provided", "reasonCode", reasonCode, "prodMode", prodMode));
      }
      throw new IllegalStateException(message);
    }
  }

  /** Installs the Host-allowlist, CORS, session-token, and capability-gate before-filters on the app. */
  void install(Javalin app) {
    install(app, ignored -> java.util.Optional.empty());
  }

  void install(Javalin app, java.util.function.Function<String, java.util.Optional<String>> mcpClientIdentity) {
    setupHostValidation(app);
    setupMcpOriginValidation(app);
    setupCors(app, prodMode);
    setupSessionTokenEnforcement(app);
    app.before(ctx -> RequestEngineContext.get(ctx, mcpClientIdentity));
    setupOperationAdmission(app);
    setupEngineAdmission(app);
    setupCapabilityGates(app);
  }

  private void setupEngineAdmission(Javalin app) {
    if (engineAdmission == null) return;
    app.exception(io.justsearch.app.api.EngineAdmissionException.class,
        (failure, ctx) -> RequestEngineWork.writeRefusal(ctx, failure));
    app.before(ctx -> {
      if ("OPTIONS".equals(ctx.method().name()) || "/api/health".equals(ctx.path())) return;
      if (ctx.attribute(RequestEngineWork.REFUSAL_ATTRIBUTE) != null) return;
      try {
        var work = engineAdmission.admit(RequestEngineContext.get(ctx),
            ctx.path().startsWith("/api/upgrade/"));
        ctx.attribute(RequestEngineWork.ATTRIBUTE, work);
        ctx.attribute(RequestEngineContext.ATTRIBUTE, work.context());
      } catch (io.justsearch.app.api.EngineAdmissionException refused) {
        ctx.attribute(RequestEngineWork.REFUSAL_ATTRIBUTE, refused);
        if ("/mcp".equals(ctx.path()) && "POST".equals(ctx.method().name())) {
          // The protocol owner must parse the JSON-RPC id before shaping its refusal.
        } else {
          throw refused;
        }
      }
    });
    app.after(ctx -> {
      var work = RequestEngineWork.get(ctx);
      if (work != null) work.close();
    });
  }

  private void setupOperationAdmission(Javalin app) {
    if (operationLeases == null) return;
    app.before(
        ctx -> {
          String method = ctx.method().name().toUpperCase(Locale.ROOT);
          if ("GET".equals(method) || "OPTIONS".equals(method)) return;
          if (ctx.path().startsWith("/api/upgrade/")) return;
          try {
            OperationLeaseHandle handle =
                operationLeases.register(
                    "api.mutation",
                    OpCriticality.MUST_COMPLETE,
                    300,
                    Map.of("method", method, "path", ctx.path()));
            ctx.attribute(MUTATION_LEASE_ATTRIBUTE, handle);
          } catch (OperationAdmissionClosedException e) {
            if (engineAdmission != null && "/mcp".equals(ctx.path()) && "POST".equals(method)) {
              // Preserve the earlier lease refusal even if freeze is released before the next
              // filter. The protocol owner must parse the id before emitting a JSON-RPC error.
              ctx.attribute(RequestEngineWork.REFUSAL_ATTRIBUTE,
                  new io.justsearch.app.api.EngineAdmissionException(
                      io.justsearch.app.api.EngineAdmissionException.Reason.FROZEN,
                      engineAdmission.retryAfterSeconds()));
              return;
            }
            ctx.status(503)
                .json(
                    Map.of(
                        "error", "Application upgrade preparation has frozen mutating operations",
                        "errorCode", "UPGRADE_PREPARING",
                        "retrySafe", true,
                        "preparationId", e.preparationId()));
            throw new io.javalin.http.HttpResponseException(503, "Upgrade preparing");
          }
        });
    app.after(
        ctx -> {
          OperationLeaseHandle handle = ctx.attribute(MUTATION_LEASE_ATTRIBUTE);
          if (handle != null) {
            handle.release(
                ctx.statusCode() >= 500 ? OpLeaseOutcome.FAILURE : OpLeaseOutcome.SUCCESS);
          }
        });
  }

  /**
   * Tempdoc 633 §1a: Host-header allowlist — the DNS-rebinding defense. The loopback bind (Hard
   * Invariant #2) and the CORS Origin allowlist are necessary but not sufficient: after a DNS-rebinding
   * attack a malicious page becomes *same-origin* with the loopback service, so CORS no longer applies,
   * and token-exempt GET reads that return runtime data (e.g. {@code /api/status}) would still execute and return
   * data. The canonical defense (MCP security best-practices "Local MCP Server Compromise"; Ollama
   * CVE-2024-28224) is to reject any request whose {@code Host} header is not a loopback host — even when
   * rebinding points the browser at 127.0.0.1, the server still sees the attacker's domain in {@code
   * Host} and returns 403. Applies in dev and prod alike; legitimate webview/dev-server requests target
   * the loopback interface and carry {@code Host: 127.0.0.1:<port>} (or {@code localhost:<port>}).
   */
  private void setupHostValidation(Javalin app) {
    app.before(
        ctx -> {
          String hostHeader = ctx.header("Host");
          if (!isAllowedHost(hostHeader)) {
            maybeRecordHostDeny(ctx, hostHeader);
            ctx.status(403);
            ctx.json(
                Map.of(
                    "error", "Request Host is not a loopback host",
                    "errorCode", "NON_LOOPBACK_HOST"));
            throw new io.javalin.http.HttpResponseException(403, "Forbidden");
          }
        });
  }

  /**
   * MCP Streamable-HTTP transport security. The spec (Transports §Streamable HTTP, Security
   * Warning — identical in 2025-06-18 and 2025-11-25) states: "Servers <b>MUST</b> validate the
   * {@code Origin} header on all incoming connections to prevent DNS rebinding attacks", and
   * 2025-11-25 adds the remedy: "If the {@code Origin} header is present and invalid, servers
   * <b>MUST</b> respond with HTTP 403 Forbidden. The HTTP response body <b>MAY</b> comprise a
   * JSON-RPC <i>error response</i> that has no {@code id}."
   *
   * <p>{@link #setupHostValidation} is the Host-header half of the same defense (tempdoc 633 §1a)
   * and already covers the whole API; this is the endpoint-scoped Origin half the MCP spec names
   * explicitly, and it applies to every method served at {@code /mcp} — POST, DELETE, and the
   * conformance GET — by construction, since the filter is bound to the path, not to a method.
   *
   * <p>An <b>absent</b> Origin is allowed: the spec conditions rejection on the header being
   * "present and invalid", and native MCP hosts (Claude Code, Claude Desktop, Cursor) are HTTP
   * clients, not browsers, so they send none. A browser-driven caller always supplies one — exactly
   * the caller class the clause addresses.
   *
   * <p><b>{@code /api/mcp/token} is guarded on the same terms.</b> That route is the bootstrap for
   * the whole session-token scheme — it hands out, unauthenticated by construction, the credential
   * that gates every mutating call — so it belongs to the MCP transport's attack surface even
   * though it is not the JSON-RPC endpoint. Its callers are all non-browser (the MCPB bridge at
   * {@code packaging/mcpb/server/index.js}, {@code scripts/prod/justsearch-mcp/discovery.mjs}, the
   * sandbox/installer probes) and therefore send no Origin; the desktop shell does not call it at
   * all (it reads {@code head.sessionToken} from the runtime manifest), and no {@code ui-web} code
   * references it. So no legitimate caller supplies an Origin this check could reject — it is
   * defense in depth over the Host allowlist and CORS, not a load-bearing gate, and its cost is a
   * rejection where CORS would otherwise merely withhold the read grant.
   */
  private void setupMcpOriginValidation(Javalin app) {
    app.before(MCP_ENDPOINT_PATH, ctx -> enforceLoopbackOrigin(ctx, true));
    app.before(MCP_TOKEN_PATH, ctx -> enforceLoopbackOrigin(ctx, false));
  }

  /**
   * The shared deny path. {@code jsonRpcBody} selects the envelope: the JSON-RPC error the MCP spec
   * allows for {@code /mcp}, or the repo's plain {@code {error, errorCode}} shape for the token
   * route, which is an ordinary JSON endpoint and would be misdescribed by a JSON-RPC envelope.
   */
  private void enforceLoopbackOrigin(Context ctx, boolean jsonRpcBody) {
    String originHeader = ctx.header("Origin");
    if (isAllowedMcpOrigin(originHeader)) {
      return;
    }
    maybeRecordMcpOriginDeny(ctx, originHeader);
    ctx.status(403);
    ctx.json(
        jsonRpcBody
            ? mcpForbiddenOriginBody()
            : Map.of(
                "error", "Request Origin is not an allowed loopback origin",
                "errorCode", "MCP_ORIGIN_FORBIDDEN"));
    throw new io.javalin.http.HttpResponseException(403, "Forbidden");
  }

  /**
   * The 403 body for a rejected MCP Origin: a JSON-RPC error response with no request id, matching
   * both the spec's allowance above and {@code McpProtocolHandler}'s own error framing. The repo's
   * {@code errorCode} convention rides in the JSON-RPC {@code error.data} slot so the envelope stays
   * a valid JSON-RPC error object.
   */
  private static Map<String, Object> mcpForbiddenOriginBody() {
    var error = new LinkedHashMap<String, Object>();
    error.put("code", -32600);
    error.put("message", "Forbidden: request Origin is not an allowed loopback origin");
    error.put("data", Map.of("errorCode", "MCP_ORIGIN_FORBIDDEN"));
    var body = new LinkedHashMap<String, Object>();
    body.put("jsonrpc", "2.0");
    body.put("id", null);
    body.put("error", error);
    return body;
  }

  /**
   * Returns true iff a request carrying {@code originHeader} may reach the MCP endpoint. Absent or
   * blank → allowed (native, non-browser clients). Otherwise the value is parsed as a URI and its
   * <b>host component</b> is compared for equality against the loopback set — never a substring or
   * prefix match, so {@code http://127.0.0.1.evil.com} and {@code http://127.0.0.1@evil.com} both
   * resolve to a foreign host and are rejected. The opaque {@code null} origin (sandboxed iframe,
   * {@code file://}, some redirect chains) is rejected: it carries no host to verify, so it cannot
   * be shown to be loopback, and no legitimate MCP client produces it.
   *
   * <p>Deliberately independent of {@code prodMode}, unlike {@link #resolveAllowedOrigin}: the MCP
   * endpoint's caller set is local agents and their hosts, not the desktop webview, so a loopback
   * origin is legitimate in both modes. Package-private for {@code McpOriginValidationTest}.
   */
  static boolean isAllowedMcpOrigin(String originHeader) {
    if (originHeader == null || originHeader.isBlank()) {
      return true;
    }
    String trimmed = originHeader.trim();
    if ("null".equalsIgnoreCase(trimmed)) {
      return false;
    }
    try {
      URI origin = URI.create(trimmed);
      String scheme = origin.getScheme();
      String host = origin.getHost();
      if (scheme == null || host == null) {
        return false;
      }
      String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
      String normalizedHost = host.toLowerCase(Locale.ROOT);
      // URI.getHost() keeps the brackets on an IPv6 literal ("[::1]"); LOOPBACK_HOSTS stores the
      // bare address.
      if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
        normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
      }
      if ("tauri".equals(normalizedScheme)) {
        return LOOPBACK_HOSTS.contains(normalizedHost);
      }
      if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
        return false;
      }
      return LOOPBACK_HOSTS.contains(normalizedHost) || TAURI_WEBVIEW_HOST.equals(normalizedHost);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Rate-limited WARN for a rejected MCP Origin. Deliberately a pure 10s time window (the {@link
   * #maybeRecordHostDeny} pattern) rather than per-origin dedup: the origin is attacker-chosen, so
   * keying suppression on its value lets a rotating-Origin caller emit an unbounded stream of log
   * and event-buffer entries. The value is also truncated, since it is reproduced verbatim.
   */
  private void maybeRecordMcpOriginDeny(Context ctx, String originHeader) {
    long now = System.currentTimeMillis();
    long lastAt = lastMcpOriginDenyAtMs.get();
    if ((now - lastAt) < 10_000) {
      return;
    }
    if (lastMcpOriginDenyAtMs.compareAndSet(lastAt, now)) {
      String origin = truncateForLog(originHeader);
      String method = ctx.method().name();
      // ctx.path(), not a constant: the guard now covers two paths, and a deny record that named
      // only one of them would misattribute half the denials.
      String path = ctx.path();
      eventBuffer.warn(
          "LocalApiServer",
          "MCP_ORIGIN_DENY",
          Map.of("path", path, "method", method, "origin", origin));
      log.warn(
          "MCP request rejected: Origin {} is not an allowed loopback origin (method={}, path={})",
          origin,
          method,
          path);
    }
  }

  /**
   * Caps an untrusted header value reproduced in logs. Never called with null: the only caller runs
   * after {@link #isAllowedMcpOrigin} has already admitted every absent/blank Origin.
   * Package-private for its regression test.
   */
  static String truncateForLog(String value) {
    return value.length() <= MAX_LOGGED_ORIGIN_CHARS
        ? value
        : value.substring(0, MAX_LOGGED_ORIGIN_CHARS) + "...[truncated]";
  }

  private void setupCors(Javalin app, boolean prod) {
    app.before(ctx -> {
      String origin = resolveAllowedOrigin(ctx.header("Origin"), prod);
      if (origin == null) {
        return;
      }
      ctx.header("Access-Control-Allow-Origin", origin);
      ctx.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
      ctx.header("Access-Control-Expose-Headers", "Deprecation, Sunset, Link, Retry-After");
      ctx.res().addHeader("Vary", "Origin");
    });

    app.options("/*", ctx -> {
      String originHeader = ctx.header("Origin");
      String origin = resolveAllowedOrigin(originHeader, prod);
      if (origin == null) {
        maybeRecordCorsDenyUiReadyPreflight(ctx, originHeader);
        ctx.status(403);
        return;
      }

      String requestHeaders = ctx.header("Access-Control-Request-Headers");
      ctx.header(
          "Access-Control-Allow-Headers",
          requestHeaders == null || requestHeaders.isBlank() ? "Content-Type" : requestHeaders);
      ctx.header("Access-Control-Max-Age", "3600");
      ctx.status(200);
    });
  }

  /**
   * Whether a request must carry the session token — the ONE authority for that question, so the
   * enforcing filter and any test double read the same rule.
   *
   * <p>Two axes, in this order:
   *
   * <ol>
   *   <li><strong>Method.</strong> {@code OPTIONS} is always exempt (a CORS preflight cannot carry
   *       the header, so requiring it there would break every browser call it precedes). Otherwise
   *       {@link #TOKEN_REQUIRED_METHODS} — the mutating verbs — require it, and {@code GET} does
   *       not, because a read-only local API behind a loopback bind was the original bargain.
   *   <li><strong>Path (tempdoc 834 §1.6/§15.2).</strong> The run family breaks that bargain and so
   *       requires the token for GET too. Loopback-only (Hard Invariant #2) is not a trust boundary
   *       here — the session token exists precisely because other local processes are not trusted —
   *       and the run journal carries prompts, answers, retrieved passage text and tool arguments,
   *       while {@code GET /api/chat/runs/live} dispenses the very runIds needed to fetch them. A
   *       GET-exempt enumeration would therefore ship that unauthenticated to any local process.
   * </ol>
   *
   * <p>The path check is deliberately a PREFIX over {@link RunRoutes#PATH_PREFIX} rather than an
   * exact match on the one known route: a future read route under the family inherits the
   * requirement instead of silently shipping open.
   */
  static boolean requiresSessionToken(String method, String path) {
    if ("OPTIONS".equals(method)) {
      return false;
    }
    if (path != null && path.startsWith(RunRoutes.PATH_PREFIX)) {
      return true;
    }
    return TOKEN_REQUIRED_METHODS.contains(method);
  }

  /**
   * Installs the session-token filter. The token is minted per boot and delivered to the desktop
   * UI via the Tauri bridge; {@link #requiresSessionToken} is the one authority for which requests
   * must carry it.
   *
   * <p>Dev mode is the ONLY reason to skip installation. The (prod, no-token) combination cannot
   * reach here — the constructor refuses it (tempdoc 884 item 23) — so this is not a re-statement
   * of that rule in a second place where it could drift.
   */
  private void setupSessionTokenEnforcement(Javalin app) {
    if (!prodMode) {
      return;
    }

    log.info("Session token enforcement enabled for non-GET requests");
    eventBuffer.info("LocalApiServer", "Session token enforcement enabled");

    app.before(ctx -> {
      String method = ctx.method().name().toUpperCase(Locale.ROOT);

      if (!requiresSessionToken(method, ctx.path())) {
        return;
      }

      // Validate token
      String providedToken = ctx.header(LocalApiServer.SESSION_TOKEN_HEADER);
      if (providedToken == null || !sessionToken.equals(providedToken)) {
        maybeRecordTokenDeny(ctx, providedToken);
        ctx.status(401);
        ctx.json(Map.of(
            "error", "Missing or invalid session token",
            "errorCode", "UI_TOKEN_REQUIRED"));
        // Halt further processing (Javalin 5+ style)
        throw new io.javalin.http.HttpResponseException(401, "Unauthorized");
      }
    });
  }

  /**
   * Tempdoc 502 §4.2.1: path-aware before-handlers that check capability health and
   * return structured 503 responses when a required capability is unavailable. This
   * replaces the scattered inline checks (knowledgeServer.state() != READY, sentinel
   * catch patterns) with one uniform mechanism.
   */
  private void setupCapabilityGates(Javalin app) {
    if (headAssembly == null) return;

    var workerCap = headAssembly.capabilities().worker();
    var inferenceCap = headAssembly.capabilities().inference();

    // Tempdoc 583 §D.3a: the {path → required-capability} rules live in RouteCapabilityPolicy (the
    // single authority the route manifest also reads, so enforced and advertised can't drift).
    // Behaviour is identical to the prior hard-coded gates: get-exempt rules (knowledge/indexing)
    // skip GET; each rule's caps are checked in 503-precedence order.
    for (RouteCapabilityPolicy.Rule rule : RouteCapabilityPolicy.RULES) {
      app.before(rule.pathPattern(), ctx -> {
        if (rule.getExempt() && "GET".equals(ctx.method().name())) {
          return;
        }
        for (RouteCapabilityPolicy.Capability required : rule.required()) {
          io.justsearch.app.api.lifecycle.Capability cap =
              required == RouteCapabilityPolicy.Capability.WORKER ? workerCap : inferenceCap;
          if (!cap.available()) {
            ctx.status(503);
            // Tempdoc 837 §0.2: `reason` is the machine-readable LifecycleReasonCode; the human
            // sentence that used to occupy it (an exception message, the corrupt-index remedy)
            // rides alongside in `detail` — a debug body is exactly where prose belongs, so the
            // sweep moves it rather than deleting it.
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", required.errorLabel);
            body.put("unavailable", cap.name());
            body.put("health", cap.health().name());
            body.put("reason", cap.pendingReason() != null ? cap.pendingReason() : "");
            body.put("detail", cap.pendingDetail() != null ? cap.pendingDetail() : "");
            ctx.json(body);
            throw new io.javalin.http.HttpResponseException(503, required.haltMessage);
          }
        }
      });
    }
  }

  /**
   * Rate-limited logging for token denial events (avoid log spam from repeated failures).
   */
  private void maybeRecordTokenDeny(Context ctx, String providedToken) {
    long now = System.currentTimeMillis();
    long lastAt = lastTokenDenyAtMs.get();

    // Rate limit: max once per 10 seconds
    if ((now - lastAt) < 10_000) {
      return;
    }

    if (lastTokenDenyAtMs.compareAndSet(lastAt, now)) {
      String path = null;
      try {
        path = ctx.path();
      } catch (Exception ignored) {
        // best-effort
      }
      String tokenPresent = providedToken == null ? "absent" : "present_but_invalid";
      eventBuffer.warn("LocalApiServer", "TOKEN_DENY", Map.of(
          "path", path == null ? "<unknown>" : path,
          "method", ctx.method().name(),
          "tokenState", tokenPresent));
      log.debug("Session token denied: path={}, method={}, tokenState={}",
          path, ctx.method().name(), tokenPresent);
    }
  }

  /** Rate-limited logging for Host-allowlist denial events (DNS-rebinding guard). */
  private void maybeRecordHostDeny(Context ctx, String hostHeader) {
    long now = System.currentTimeMillis();
    long lastAt = lastHostDenyAtMs.get();

    // Rate limit: max once per 10 seconds (a rebinding probe could otherwise flood).
    if ((now - lastAt) < 10_000) {
      return;
    }

    if (lastHostDenyAtMs.compareAndSet(lastAt, now)) {
      String path = null;
      try {
        path = ctx.path();
      } catch (Exception ignored) {
        // best-effort
      }
      eventBuffer.warn("LocalApiServer", "HOST_DENY", Map.of(
          "path", path == null ? "<unknown>" : path,
          "method", ctx.method().name(),
          "host", hostHeader == null ? "<absent>" : hostHeader));
      log.debug("Non-loopback Host denied: path={}, method={}, host={}",
          path, ctx.method().name(), hostHeader);
    }
  }

  private void maybeRecordCorsDenyUiReadyPreflight(Context ctx, String originHeader) {
    // Only record for the UI-ready handshake path to avoid spamming /api/status probes.
    String path = null;
    try {
      path = ctx.path();
    } catch (Exception ignored) {
      // best-effort
    }
    if (!"/api/ui/ready".equals(path)) {
      return;
    }

    String normalized = originHeader == null ? "<absent>" : originHeader;
    long now = System.currentTimeMillis();
    long lastAt = lastCorsDenyUiReadyAtMs.get();
    String lastOrigin = lastCorsDenyUiReadyOrigin.get();

    // Rate limit: avoid emitting the same warning repeatedly during a flapping UI.
    if ((now - lastAt) < 10_000 && normalized.equals(lastOrigin)) {
      return;
    }

    lastCorsDenyUiReadyOrigin.set(normalized);
    lastCorsDenyUiReadyAtMs.set(now);
    eventBuffer.warn(
        "LocalApiServer",
        "CORS_DENY_UI_READY_PREFLIGHT",
        Map.of("originHeader", normalized));
  }

  /**
   * Captures a thread dump if the request exceeded the slow-request threshold.
   *
   * <p>Rate-limited to max 1 dump per 30 seconds to avoid flooding during degradation.
   */
  void maybeCaptureSlowRequestDump(Context ctx) {
    Long startNs = ctx.attribute("__request_start_ns__");
    if (startNs == null) {
      return;
    }

    // A long-lived stream is not a slow handler. The after-hook does not run until the connection
    // CLOSES, so the elapsed wall-clock measures how long the client stayed subscribed — a stream
    // held open for minutes reports a multi-minute "duration" and trips the dump on every
    // disconnect. Exempt before the duration check so the dumper is never called.
    //
    // Detected from the RESPONSE content-type rather than the `/stream` path convention: the
    // convention holds for every SSE route today, but the content-type is what actually makes a
    // response a stream, so this stays correct if a future SSE route is named differently.
    if (isStreamingResponse(ctx)) {
      return;
    }

    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
    if (durationMs < SLOW_REQUEST_THRESHOLD_MS) {
      return;
    }

    // Rate limit: max 1 dump per 30 seconds
    long now = System.currentTimeMillis();
    long lastAt = lastSlowDumpAtMs.get();
    if ((now - lastAt) < SLOW_DUMP_RATE_LIMIT_MS) {
      return;
    }
    if (!lastSlowDumpAtMs.compareAndSet(lastAt, now)) {
      return;
    }

    // Capture async to avoid adding latency to the response
    String route = ctx.path();
    String method = ctx.method().name();
    int status = ctx.res() != null ? ctx.res().getStatus() : 0;
    // Tempdoc 518 Wave A defect Fix-9: extract the active trace ID for cross-correlation
    // with the trace stream. When tracing is off the span context is invalid → traceId is
    // null and the dumper omits the field. Composes with the Wave A.3 X-Trace-Id response
    // header so a slow-request bug report can be matched to its full span tree.
    String traceId = null;
    Object spanAttr = ctx.attribute("__otel_span__");
    if (spanAttr instanceof io.opentelemetry.api.trace.Span span
        && span.getSpanContext().isValid()) {
      traceId = span.getSpanContext().getTraceId();
    }
    final String capturedTraceId = traceId;
    slowRequestExecutor.execute(
        () ->
            SlowRequestDumper.captureDump(
                route, method, status, durationMs, SLOW_REQUEST_THRESHOLD_MS, capturedTraceId));
  }

  /**
   * Returns true iff the response is a Server-Sent-Events stream, i.e. its content-type is {@code
   * text/event-stream} (media type only; charset and other parameters are ignored, and the match is
   * case-insensitive per RFC 9110 §8.3). Javalin sets it for a real {@code EventSource} client and
   * {@code SseEnvelopeWriter.forceSseHeaders} force-sets it for ad-hoc clients, so it is present on
   * every stream by the time the after-hook runs. Package-private for {@code
   * SlowRequestStreamExemptionTest}.
   */
  static boolean isStreamingResponse(Context ctx) {
    return ctx != null && ctx.res() != null && isStreamingContentType(ctx.res().getContentType());
  }

  /** The media-type half of {@link #isStreamingResponse}, split out so it is directly testable. */
  static boolean isStreamingContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    int semi = contentType.indexOf(';');
    String mediaType = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    return mediaType.equalsIgnoreCase("text/event-stream");
  }

  /**
   * Returns true iff the {@code Host} header names a loopback host (port ignored). The DNS-rebinding
   * guard ({@link #setupHostValidation}). A missing/blank Host is rejected — HTTP/1.1 mandates Host and
   * its absence is anomalous. Package-private for {@code LocalApiHostValidationTest}.
   */
  static boolean isAllowedHost(String hostHeader) {
    if (hostHeader == null || hostHeader.isBlank()) {
      return false;
    }
    String host = hostHeader.trim();
    if (host.startsWith("[")) {
      // IPv6 literal with optional port: "[::1]:8080" -> "::1"
      int close = host.indexOf(']');
      if (close < 0) {
        return false;
      }
      host = host.substring(1, close);
    } else {
      // Strip ":port" for IPv4 / hostnames (a bare IPv4/hostname has no colon).
      int colon = host.indexOf(':');
      if (colon >= 0) {
        host = host.substring(0, colon);
      }
    }
    return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
  }

  // Package-private for targeted regression tests (CORS allowlist / loopback safety).
  static String resolveAllowedOrigin(String originHeader, boolean prod) {
    if (originHeader == null || originHeader.isBlank()) {
      return null;
    }
    if ("null".equalsIgnoreCase(originHeader.trim())) {
      return null;
    }

    try {
      URI origin = URI.create(originHeader);
      String scheme = origin.getScheme();
      String host = origin.getHost();
      if (scheme == null || host == null) {
        return null;
      }

      String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
      String normalizedHost = host.toLowerCase(Locale.ROOT);

      // Prod mode: only allow desktop origins (not general browser localhost origins).
      // - Tauri v1:  tauri://localhost
      // - Tauri v2+: http(s)://tauri.localhost (varies by WebView2 / runtime)
      if (prod) {
        if ("tauri".equals(normalizedScheme) && LOOPBACK_HOSTS.contains(normalizedHost)) {
          return originHeader;
        }
        if (("https".equals(normalizedScheme) || "http".equals(normalizedScheme))
            && TAURI_WEBVIEW_HOST.equals(normalizedHost)) {
          return originHeader;
        }
        return null;
      }

      // Dev mode: allow loopback and the Tauri https host for local development and UI smoke tests.
      if (!LOOPBACK_HOSTS.contains(normalizedHost) && !TAURI_WEBVIEW_HOST.equals(normalizedHost)) {
        return null;
      }
      Set<String> allowedSchemes = Set.of("http", "https", "tauri");
      return allowedSchemes.contains(normalizedScheme) ? originHeader : null;
    } catch (Exception e) {
      return null;
    }
  }
}
