/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

/** Per-request proof of the packaged app window, independent of the manifest token. */
final class WindowAuthority {
  static final String HEADER = "X-JustSearch-Window";
  private static final String REQUIRED = "__window_authority_required__";
  private static final String VERIFIED = "__window_authority_verified__";

  // The complete token-only mutation-method surface. New /api routes require the window by
  // default. Keep each exception narrow and explain why it cannot confer operator authority.
  private static final Map<String, String> TOKEN_ONLY_ROUTES = Map.ofEntries(
      Map.entry("POST /mcp", "MCP applies the intent lattice to every tool invocation"),
      Map.entry("DELETE /mcp", "Closes only the caller's MCP session"),
      Map.entry("POST /api/operations/{id}/invoke", "The intent lattice classes outside callers as untrusted"),
      Map.entry("POST /api/knowledge/search", "Reads search results"),
      Map.entry("POST /api/knowledge/retrieve-context", "Reads indexed context"),
      Map.entry("POST /api/knowledge/match-citations", "Matches citations against indexed content"),
      Map.entry("POST /api/knowledge/collection-facet", "Reads collection facet counts"),
      Map.entry("POST /api/knowledge/folders", "Lists indexed folders"),
      Map.entry("POST /api/knowledge/folder-files", "Lists indexed files in a folder"),
      Map.entry("POST /api/indexing-roots/preview", "Inspects a candidate folder without adding it"),
      Map.entry("POST /api/library/resolve-hash", "Resolves an existing path hash"),
      Map.entry("POST /api/library/path-label", "Reads the label for an existing path hash"),
      Map.entry("POST /api/documents/presence", "Reads bounded index membership"),
      Map.entry("POST /api/debug/eval/document-ids", "Reads a bounded document ID snapshot for release evaluation"),
      Map.entry("POST /api/ai/packs/preflight", "Reads pack metadata and digest without installing or writing"),
      Map.entry("POST /api/plugins/verify", "Reads existing plugin trust without adding an allowlist entry"),
      Map.entry("POST /api/document/{id}/resolve-address", "Converts document coordinates using indexed content"),
      Map.entry("POST /api/chat/runs/{runId}/observe", "Attaches an observer to an existing run without starting it"),
      Map.entry("POST /api/chat/agent/{sessionId}/attach", "Attaches an observer to an existing agent run"),
      Map.entry("POST /api/chat/agent/{sessionId}/ag-ui", "Projects an existing agent run to an AG-UI observer"),
      Map.entry("DELETE /api/authorizations/grants", "Revokes a grant, reducing authority"),
      Map.entry("POST /api/chat/reject", "Rejects a pending agent or workflow gate, reducing authority"));
  private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private WindowAuthority() {}

  static void install(Javalin app, String credential) {
    if (credential == null) return; // Headless and development retain token-as-operator.
    if (credential.isBlank()) throw new IllegalArgumentException("Empty window credential");
    byte[] expected = credential.getBytes(StandardCharsets.UTF_8);
    app.before(ctx -> {
      String supplied = ctx.header(HEADER);
      ctx.attribute(REQUIRED, true);
      ctx.attribute(VERIFIED, supplied != null
          && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8)));
    });
  }

  static boolean hasAuthority(Context ctx) {
    return !Boolean.TRUE.equals(ctx.attribute(REQUIRED))
        || Boolean.TRUE.equals(ctx.attribute(VERIFIED));
  }

  static void installMutationGuard(Javalin app) {
    // Use the router's path matching, just as the controllers do (including case/encoding rules).
    app.before("/api/*", ctx -> {
      if (MUTATION_METHODS.contains(ctx.method().name())
          && !tokenOnlyRoute(ctx.method().name(), ctx.path()) && !require(ctx)) {
        // The production mapper preserves require()'s body; Javalin's default mapper replaces it.
        // Carry the error code through that mapper too, without skipping request cleanup hooks.
        throw new io.javalin.http.HttpResponseException(403, "Forbidden",
            Map.of("errorCode", "WINDOW_CREDENTIAL_REQUIRED"));
      }
    });
  }

  private static boolean tokenOnlyRoute(String method, String path) {
    String route = method + " " + path;
    if (TOKEN_ONLY_ROUTES.containsKey(route)) return true;
    // Templates match exactly one addressed segment, never an entire route family.
    return TOKEN_ONLY_ROUTES.keySet().stream().filter(pattern -> pattern.contains("{"))
        .anyMatch(pattern -> route.matches(pattern.replaceAll("\\{[^/}]+}", "[^/]+")));
  }

  /** Refuse before parsing, admitting, consuming consent or changing state. */
  static boolean require(Context ctx) {
    if (hasAuthority(ctx)) return true;
    ctx.status(403).json(Map.of("errorCode", "WINDOW_CREDENTIAL_REQUIRED",
        "error", "Approve this action in the JustSearch app window"));
    return false;
  }

  /** An outside request may lower autonomy, but cannot increase it above the default. */
  static Map<String, Object> limitAutonomy(Context ctx, Map<String, Object> body) {
    if (!hasAuthority(ctx) && io.justsearch.agent.api.registry.AutonomyLevel.fromWire(
        java.util.Objects.toString(body.get("autonomyLevel"), null))
        == io.justsearch.agent.api.registry.AutonomyLevel.AUTO) {
      body = new java.util.LinkedHashMap<>(body);
      body.put("autonomyLevel", io.justsearch.agent.api.registry.AutonomyLevel.DEFAULT.wire());
    }
    return body;
  }
}
