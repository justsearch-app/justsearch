/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;

/**
 * Route registration for the run family (tempdoc 834 §1.6, §5.1).
 *
 * <p>The two streaming routes are POST managed SSE — {@code new SseHandler(consumer)} behind a
 * validating {@link io.javalin.http.Handler} — and are therefore already covered by {@code
 * ApiSecurityFilters}' session-token requirement with NO filter change (probe D1, §14.1d, confirmed
 * end-to-end in prod mode). §5's {@code GET /api/chat/runs/live} enumeration is the one that DOES
 * need a path-scoped filter change, because the filter returns early for GET — see {@link
 * ApiSecurityFilters#requiresSessionToken}, which reads {@link #PATH_PREFIX} so the guarded path and
 * the routed path cannot drift.
 *
 * <p>Under {@code /api/chat/*} like every other conversational route; the removed {@code
 * /api/agent/*} namespace is not resurrected (Hard Invariant #3).
 */
public final class RunRoutes {
  private RunRoutes() {}

  /**
   * The run family's path prefix. Every route under it demands the session token regardless of
   * method — the journal carries prompts, answers, retrieved passage text and tool arguments, and
   * the enumeration dispenses the very runIds needed to fetch them (§1.6). Shared with {@link
   * ApiSecurityFilters} so the guarded prefix and the registered routes are one string.
   */
  public static final String PATH_PREFIX = "/api/chat/runs";

  /** {@code GET} — every run executing right now (§5.1). The FE's run-discovery authority. */
  public static final String LIVE_PATH = PATH_PREFIX + "/live";

  public static void register(Javalin app, RunStreamController runs, AgentSessionController session) {
    if (session != null) {
      // Registered before the {runId} routes. Nothing today can capture `live` as a run id (the
      // parameterised routes are POST), but a future GET /api/chat/runs/{runId} would make the
      // literal-vs-parameter question live, and declaring the literal first is the cheap side.
      app.get(LIVE_PATH, session::handleListLiveRuns, ActivityRole.OBSERVATION);
    }
    if (runs == null) {
      return;
    }
    // Creates the run AND observes it: with the creating call BEING the first stream, execution
    // stays synchronous on the handler thread (§1.7) and no executor move is needed.
    app.post(PATH_PREFIX, runs::handleCreate, ActivityRole.USER_ACTION);
    // An additional / reattaching observer. Unknown or long-retired ⇒ typed 404, never a 200 with
    // an empty stream.
    app.post(PATH_PREFIX + "/{runId}/observe", runs::handleObserve, ActivityRole.OBSERVATION);
  }
}
