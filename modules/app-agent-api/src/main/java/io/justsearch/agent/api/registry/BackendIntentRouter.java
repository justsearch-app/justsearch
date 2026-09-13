/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.core.context.EngineContext;

/**
 * Backend half of the dual-{@code IntentRouter} topology (tempdoc 487 §4.3).
 *
 * <p>Single per-process consumer of {@link Intent} envelopes that originate
 * backend-side (LLM emission from a chat shape's {@code StreamConsumer},
 * agent-loop tool calls, future MCP requests, future scheduled triggers).
 * Dispatches {@link ShellAddress.Invocation} envelopes via
 * {@link OperationDispatcher#dispatch(Operation, String, InvocationProvenance, EngineContext)};
 * emits {@link ShellAddress.Navigation} envelopes onto the always-on
 * {@code /api/intent/stream} SSE channel for the FE
 * {@code IntentRouter} to consume.
 *
 * <p>The handoff rule (tempdoc §4.3): <strong>surfaces are FE-resolved;
 * operations are backend-resolved.</strong> The backend router never
 * dispatches navigation directly because the backend has no surface
 * authority — surface activation is a FE concern.
 *
 * <p>Trust evaluation (the {@code (SourceTier × RiskTier) → GateBehavior}
 * lattice, §4.4) runs inside the dispatcher implementation, not at this
 * boundary. The router treats every well-formed envelope as admittable;
 * the lattice decides whether it auto-fires, surfaces a confirm gate, or
 * is denied.
 */
public interface BackendIntentRouter {

  /** Backend-only preparation. Never forward a navigation or execute an invocation to plan it. */
  default OperationDispatchPlan prepare(Intent intent, InvocationProvenance provenance,
      EngineContext engineContext, String operationKey, boolean includeApprovalPreview) {
    throw new UnsupportedOperationException("Intent preparation is unavailable");
  }

  /** Carry the server continuation outside ShellAddress and public operation arguments. */
  default IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance,
      EngineContext engineContext, String operationKey, java.util.UUID preparationNonce) {
    if (operationKey != null || preparationNonce != null) {
      throw new UnsupportedOperationException("Keyed intent dispatch is unavailable");
    }
    return dispatch(intent, provenance, engineContext);
  }

  /**
   * Dispatch an intent envelope.
   *
   * <p>For {@link ShellAddress.Invocation}, the implementation looks up the
   * Operation in the {@link OperationCatalog}, then calls
   * {@link OperationDispatcher#dispatch(Operation, String, InvocationProvenance, EngineContext)}
   * and wraps the resulting {@link OperationResult} in
   * {@link IntentDispatchResult.Dispatched}. The lattice runs inside the
   * dispatcher.
   *
   * <p>For {@link ShellAddress.Navigation}, the implementation constructs the
   * intent-envelope payload (per tempdoc §4.3 wire spec) and publishes it
   * onto the {@code /api/intent/stream} channel. Returns
   * {@link IntentDispatchResult.Forwarded} with the stable {@code payload.id}.
   *
   * @param intent the envelope to dispatch; the {@code transport} field is
   *     correlated with the {@link IntentSourceCatalog} to determine
   *     {@link SourceTier} for the lattice
   * @param provenance the per-dispatch provenance record threaded into the
   *     {@code OperationHistoryEntry} (for Invocation) or the wire envelope
   *     (for Navigation)
   * @return the dispatch outcome (sealed over Dispatched | Forwarded)
   */
  IntentDispatchResult dispatch(Intent intent, InvocationProvenance provenance, EngineContext engineContext);
}
