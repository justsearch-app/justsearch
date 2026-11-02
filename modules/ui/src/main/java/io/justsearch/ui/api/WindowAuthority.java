/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Per-request proof of the packaged app window, independent of the manifest token. */
final class WindowAuthority {
  static final String HEADER = "X-JustSearch-Window";
  private static final String REQUIRED = "__window_authority_required__";
  private static final String VERIFIED = "__window_authority_verified__";

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
