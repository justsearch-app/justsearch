/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Implements {@code GET /api/debug/session-policies} — diagnostic endpoint for tempdoc 397 §7.3.
 *
 * <p>Post-§14.28 U4: reads Worker's authoritative {@code PolicySnapshot} via the
 * {@code GetSessionPolicies} gRPC rpc. Worker built the snapshot at boot via
 * {@code InferenceCompositionRoot.compose} — the endpoint reports what Worker actually
 * observed when constructing ORT sessions, not what Head's re-resolve would produce from its
 * own {@code ConfigStore} + detected hardware.
 *
 * <p>Proto-type handling lives in {@link KnowledgeClient#getSessionPolicies} per
 * {@code UiApiGuardrailsTest} (ui.api must not depend on ipc proto types). This controller
 * is a thin HTTP adapter over the typed Map response.
 *
 * <p>Response shape (unchanged bytewise from the pre-U4 REST response):
 *
 * <pre>{@code
 * {
 *   "configStatus": "ok" | "config-unavailable" | "surface-unavailable" | "worker-unreachable",
 *   "runtime": { ... RuntimePolicy JSON as an embedded object ... },
 *   "models": { "EMBEDDING": {...}, "SPLADE": {...}, ... }
 * }
 * }</pre>
 */
@io.justsearch.contracts.AdvisoryContract(
    description =
        "Endpoint returns PolicySnapshot built at Worker boot (397 §14.28 U4). In"
            + " runHeadlessEval mode the KnowledgeClient reference is null at Head-side"
            + " LocalApiServer construction and late-bound via setClient once Worker is ready"
            + " (400 Phase 2.1). Pre-400 Phase 2.1 the late-bind was wired for 4 other"
            + " controllers but missed here, producing a permanent worker-unreachable response"
            + " in eval mode.",
    tempdoc = "397 §14.28 U4 / 400 Phase 2.1",
    signal = "session.policies.worker_unreachable")
public final class SessionPoliciesController {

  /**
   * Volatile so {@link #setClient(KnowledgeClient)} late-bind from the
   * {@link io.justsearch.ui.api.LocalApiServer#lateBindKnowledgeServer} path is visible to any
   * subsequent {@link #handle} invocation on the Javalin thread pool. Single-writer (LocalApiServer
   * boot orchestration), multi-reader (request threads).
   */
  private volatile KnowledgeClient client;

  public SessionPoliciesController(KnowledgeClient client) {
    this.client = client;
  }

  /**
   * Late-binds the Worker RPC client after Worker boot completes.
   *
   * <p>Head-side {@code LocalApiServer} constructs this controller with {@code null} and then
   * invokes this setter from {@code lateBindKnowledgeServer} once {@code knowledgeServer.client()}
   * is reachable. Pre-400 Phase 2.1 the late-bind was missing, so {@link #buildResponse} returned
   * {@code worker-unreachable} forever in eval mode despite the Worker being READY.
   */
  public void setClient(KnowledgeClient client) {
    this.client = client;
  }

  /** Handler for {@code GET /api/debug/session-policies}. */
  public void handle(Context ctx) {
    ctx.contentType("application/json");
    ctx.json(buildResponse());
  }

  /** Package-private for tests. Returns the typed response body (Jackson serialises). */
  Map<String, Object> buildResponse() {
    KnowledgeClient current = this.client;
    if (current == null) {
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("configStatus", "worker-unreachable");
      response.put("runtime", new LinkedHashMap<>());
      response.put("models", new TreeMap<>());
      return response;
    }
    return current.getSessionPolicies();
  }
}
