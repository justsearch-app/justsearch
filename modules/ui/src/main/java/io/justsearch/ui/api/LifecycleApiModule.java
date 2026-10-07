/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/**
 * Process-lifecycle control cohort over the existing loopback/session-token transport (tempdoc 805
 * G.1).
 *
 * <p>One route: the shell asks Head to run its own ordered shutdown before quitting, so normal
 * termination traverses the shutdown path instead of being force-killed. The ordered close deletes
 * the runtime manifest, which is what stops crash residue from being the normal on-disk state.
 *
 * <p>This is NOT the upgrade protocol (tempdoc 617): no preparation id, no shutdown nonce, no
 * receipt, no lease freeze. It shares the one ordered-shutdown routine those endpoints drive — one
 * routine, two callers — and nothing else.
 *
 * <p>The body may carry the supervisor's quit deadline ({@code deadlineAtEpochMs}), the incarnation
 * it addressed and a stop detail (Spec F §4.3); an action that is a {@link LifecycleShutdownAction}
 * receives them, so the ordered close finishes before the supervisor's force.
 */
final class LifecycleApiModule implements ApiModule {
  private static final Set<String> ROUTES = Set.of("/api/lifecycle/shutdown");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Runnable orderlyShutdown;

  LifecycleApiModule(Runnable orderlyShutdown) {
    this.orderlyShutdown = orderlyShutdown == null ? () -> {} : orderlyShutdown;
  }

  @Override
  public void register(Javalin app) {
    app.post("/api/lifecycle/shutdown", this::shutdown);
  }

  @Override
  public Set<String> ownedRoutePaths() {
    return ROUTES;
  }

  /**
   * Acknowledge with 202 and flush BEFORE the shutdown runs — the caller is waiting on this
   * response and the ordered close tears down the very server that would write it. Mirrors
   * {@code UpgradeController.commitShutdown}'s response-before-exit ordering.
   */
  private void shutdown(Context ctx) {
    // Read before the response is committed: the ack below flushes, and the body is the request's.
    LifecycleShutdownRequest request = requestOf(ctx);
    byte[] response =
        JSON.writeValueAsBytes(
            Map.of(
                "schemaVersion", 1,
                "shutdownAccepted", true,
                "issuedAtEpochMs", System.currentTimeMillis()));
    ctx.status(202);
    ctx.contentType("application/json");
    ctx.res().setContentLength(response.length);
    try {
      ctx.res().getOutputStream().write(response);
      ctx.res().flushBuffer();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to acknowledge orderly lifecycle shutdown", e);
    }
    Runnable shutdown = orderlyShutdown instanceof LifecycleShutdownAction action
        ? () -> action.shutdown(request)
        : orderlyShutdown;
    Thread.ofPlatform().daemon(true).name("lifecycle-orderly-shutdown").start(shutdown);
  }

  /**
   * The body's quit deadline, incarnation and detail (Spec F §4.3). Every field is optional and an
   * unreadable body names nothing: the endpoint must still quit for today's shell, which posts
   * {@code {}}, and a quit that fails on a malformed body is a quit that is force-killed instead.
   */
  static LifecycleShutdownRequest requestOf(Context ctx) {
    String body;
    try {
      body = ctx.body();
    } catch (RuntimeException unreadable) {
      return LifecycleShutdownRequest.NONE;
    }
    if (body == null || body.isBlank()) return LifecycleShutdownRequest.NONE;
    try {
      var root = JSON.readTree(body);
      if (root == null || !root.isObject()) return LifecycleShutdownRequest.NONE;
      var deadline = root.get("deadlineAtEpochMs");
      return new LifecycleShutdownRequest(
          deadline != null && deadline.isIntegralNumber() ? deadline.asLong() : null,
          text(root.get("incarnation")), text(root.get("detail")));
    } catch (RuntimeException malformed) {
      return LifecycleShutdownRequest.NONE;
    }
  }

  private static String text(tools.jackson.databind.JsonNode node) {
    return node == null || !node.isString() ? null : node.stringValue();
  }
}
