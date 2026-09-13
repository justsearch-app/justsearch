/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.context.EngineContext;

import io.javalin.http.Context;
import io.justsearch.app.api.inference.EncoderRuntimeResponse;
import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.services.observability.EncoderRuntimeExplainer;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.ort.EncoderRole;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implements {@code GET /api/inference/encoders} (tempdoc 422).
 *
 * <p>Derives a structured "why is encoder X on CPU/GPU/unavailable?" answer per encoder by
 * correlating Worker's authoritative {@code PolicySnapshot} (via
 * {@link KnowledgeClient#getSessionPolicies()}) with Worker's runtime OrtCuda probe
 * snapshot (via {@link KnowledgeClient#getEncoderOrtCudaViews()}). Folds those two
 * surfaces plus tempdoc 414's metric labels into a single read-only JSON view backing the
 * Brain/Health UI panel + a future MCP tool wrapper.
 *
 * <p>Late-bind pattern mirrors {@link SessionPoliciesController}: constructed with {@code null}
 * client by {@link LocalApiServer}, then {@link #setClient} flipped from
 * {@code lateBindKnowledgeServer} once the Worker is reachable.
 */
@io.justsearch.contracts.AdvisoryContract(
    description =
        "Per-encoder runtime accelerator explainer. Iterates the active PolicySnapshot keys "
            + "and emits one EncoderRuntimeView per active encoder. KnowledgeClient is "
            + "null at LocalApiServer construction in eval mode and late-bound once Worker "
            + "boot completes — pre-late-bind requests must return snapshotStatus="
            + "worker-unreachable, never throw.",
    tempdoc = "422",
    signal = "encoder.runtime.worker_unreachable")
public final class EncoderRuntimeController {

  /**
   * Volatile so {@link #setClient(KnowledgeClient)} late-bind from the
   * {@link LocalApiServer#lateBindKnowledgeServer} path is visible to any subsequent
   * {@link #handle} invocation on the Javalin thread pool.
   */
  private volatile KnowledgeClient client;

  public EncoderRuntimeController(KnowledgeClient client) {
    this.client = client;
  }

  /** Late-binds the Worker RPC client after Worker boot completes. */
  public void setClient(KnowledgeClient client) {
    this.client = client;
  }

  /** Handler for {@code GET /api/inference/encoders}. */
  public void handle(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    ctx.contentType("application/json");
    ctx.json(buildResponse(engineContext));
  }

  /** Package-private for tests. Returns the typed response body (Jackson serialises). */
  EncoderRuntimeResponse buildResponse(EngineContext engineContext) {
    KnowledgeClient current = this.client;
    if (current == null) {
      return new EncoderRuntimeResponse(Map.of(), "worker-unreachable");
    }

    Map<String, Object> policies = current.getSessionPolicies(engineContext);
    Object configStatusNode = policies.get("configStatus");
    if ("worker-unreachable".equals(configStatusNode)) {
      return new EncoderRuntimeResponse(Map.of(), "worker-unreachable");
    }

    Object modelsNode = policies.get("models");
    if (!(modelsNode instanceof Map<?, ?> modelsMap) || modelsMap.isEmpty()) {
      return new EncoderRuntimeResponse(Map.of(), "policy-unavailable");
    }

    // Tempdoc 805 G.3: the policy × probe correlation moved into the explainer so
    // /api/ai/runtime/status's observed-EP fields project the SAME derivation instead of
    // re-implementing it. This controller keeps only its own reachability reporting.
    Map<EncoderRole, EncoderRuntimeView> derived =
        EncoderRuntimeExplainer.explainAll(policies, current.getEncoderOrtCudaViews(engineContext));
    Map<String, EncoderRuntimeView> encoders = new LinkedHashMap<>();
    for (Map.Entry<EncoderRole, EncoderRuntimeView> entry : derived.entrySet()) {
      encoders.put(entry.getKey().consumerName(), entry.getValue());
    }

    return new EncoderRuntimeResponse(encoders, "ok");
  }
}
