/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import io.justsearch.core.context.EngineContext;

/**
 * SPI for trust-tier-aware Operation dispatch.
 *
 * <p>Per tempdoc 429 §C decision A: substrate types live in {@code app-agent-api},
 * behavior lives in {@code app-services}. This interface is the substrate contract
 * consumed by {@code AgentLoopService} (in {@code app-agent}) and other agents that
 * dispatch Operations; the concrete trust-tier-aware implementation
 * ({@code OperationExecutorImpl}) lives in {@code app-services} alongside the
 * emitter framework.
 *
 * <p>Per §A.5 + §B.D + §C.A.5 implementations branch on
 * {@link Provenance#tier()}:
 * <ul>
 *   <li>{@code CORE}: dispatch directly via the handler resolved by {@code Binding.handlerId()}.
 *   <li>{@code TRUSTED_PLUGIN}: dispatch equivalently to CORE in V1 — V1's trust model
 *       is "you wrote it, or you know who did," so trusted plugins behave as core code.
 *       V1.5+ extends this branch additively with a policy floor.
 *   <li>{@code UNTRUSTED_PLUGIN}: throw — V1 has no sandbox infrastructure. V1.5+ adds
 *       iframe sandbox dispatch.
 * </ul>
 *
 * <p>Per §E.3: {@link #undo(Operation, String, EngineContext)} checks
 * {@code op.policy().undoSupported()} before delegating; operations without undo
 * support fail fast with a typed denial, never reaching the handler.
 */
public interface OperationDispatcher {

  /**
   * Execute with provenance projected from the required context and the implementation's clock.
   * Callers with an explicit executor or signed intent use the provenance overload.
   */
  OperationResult dispatch(Operation op, String argumentsJson, EngineContext engineContext);

  /**
   * Execute the operation against the parsed argument JSON, recording the supplied
   * invocation-side provenance on the resulting history entry.
   *
   * <p>Per slice 490 §4.B: invocation-side provenance describes how today's invocation
   * arrived (transport + executor + initiator). The implementation threads
   * {@code provenance} into the emitted {@link io.justsearch.app.observability.operations.OperationHistoryEntry}
   * so audit / replay / chat-receipt consumers can answer "who triggered this?" via a
   * typed record rather than an opaque string.
   *
   * <p>The default supplies only an empty confirmation token; it preserves both attribution records.
   */
  default OperationResult dispatch(
      Operation op, String argumentsJson, InvocationProvenance provenance, EngineContext engineContext) {
    return dispatch(op, argumentsJson, provenance, java.util.Optional.empty(), engineContext);
  }

  /**
   * Execute the operation with both invocation-side provenance and an optional
   * confirmation token (slice 487 §4.4).
   *
   * <p>The implementation runs the {@code TrustEvaluator} lattice between
   * {@code validateProvenance} and {@code inputValidator.validate}. When the
   * lattice computes a non-AUTO {@link GateBehavior}, the caller must supply a
   * validated consent capsule or an applicable live durable grant. Without either, the dispatcher throws
   * {@link ConfirmationRequiredException} carrying the gate behavior + the
   * destination's declared {@link ConfirmStrategy} so the caller can render the
   * trust-aware elicitation UX. On {@link GateBehavior#DENY} the dispatcher
   * throws {@link TrustGateDeniedException}.
   *
   * <p>Implementations validate that provenance agrees with the Engine context before dispatch.
   */
  OperationResult dispatch(
      Operation op,
      String argumentsJson,
      InvocationProvenance provenance,
      java.util.Optional<String> confirmationToken, EngineContext engineContext);

  /** Canonical keyed ingress. Implementations must never silently discard a supplied key. */
  default OperationResult dispatch(Operation op, String argumentsJson, InvocationProvenance provenance,
      java.util.Optional<String> confirmationToken, EngineContext engineContext, String operationKey) {
    if (operationKey != null) throw new UnsupportedOperationException("Keyed dispatch is unavailable");
    return dispatch(op, argumentsJson, provenance, confirmationToken, engineContext);
  }

  /**
   * Undo a previous execution identified by {@code executionId}. Returns a typed
   * failure (not throwing) when the operation's policy does not support undo.
   *
   * <p>Projects the required context with no confirmation token. Explicit provenance and consent
   * travel through the canonical overload below.
   */
  OperationResult undo(Operation op, String executionId, EngineContext engineContext);

  /**
   * Undo a previous execution, carrying the invocation-side provenance and an optional
   * confirmation token so the reversal meets the SAME trust lattice its forward form did
   * (tempdoc 875 §C.7: <em>the reversal of an operation is an operation and inherits its
   * risk class</em>).
   *
   * <p>The implementation evaluates {@code (SourceTier × RiskTier)} with the operation's
   * declared risk — the forward operation's risk, unchanged — and the caller's transport.
   * A non-AUTO gate is satisfied by exactly what satisfies it on the forward path: a
   * durable grant within its risk ceiling AND argument scope, or a consent capsule bound
   * to ({@code op.id()}, {@link #undoArguments(String)}). Absent either, the same
   * {@link ConfirmationRequiredException} the forward dispatch throws; on
   * {@link GateBehavior#DENY}, the same {@link TrustGateDeniedException}.
   *
   * <p>Implementations preserve both attribution records and evaluate current grant state.
   */
  OperationResult undo(
      Operation op,
      String executionId,
      InvocationProvenance provenance,
      java.util.Optional<String> confirmationToken, EngineContext engineContext);

  /** Undo uses the same key contract; its canonical identity includes the target execution id. */
  default OperationResult undo(Operation op, String executionId, InvocationProvenance provenance,
      java.util.Optional<String> confirmationToken, EngineContext engineContext, String operationKey) {
    if (operationKey != null) throw new UnsupportedOperationException("Keyed undo is unavailable");
    return undo(op, executionId, provenance, confirmationToken, engineContext);
  }

  /**
   * The canonical arguments JSON of an undo invocation: {@code {"executionId":"<id>"}}.
   *
   * <p>One authority, because three parties must agree on the exact string — the gate's
   * argument-scope check, the consent capsule minted at the user's approval gesture, and
   * the capsule verification inside the dispatcher. A reversal carries no arguments of its
   * own, so this identifies WHICH execution is being reversed and nothing else; an
   * argument-scope implementation that cannot find its governed key in it must fail closed
   * (which costs a confirmation, never a silent action).
   */
  static String undoArguments(String executionId) {
    StringBuilder sb = new StringBuilder("{\"executionId\":\"");
    String id = executionId == null ? "" : executionId;
    for (int i = 0; i < id.length(); i++) {
      char c = id.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append("\"}").toString();
  }
}
