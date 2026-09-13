/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Mints and verifies <b>consent capsules</b> — the bound, single-use, expiring proof that
 * a user explicitly approved one specific action. Tempdoc 550 Slice C2 (Authorize face).
 *
 * <p>This interface lives in {@code app-agent-api} — alongside the trust-lattice contract
 * types ({@link TrustEvaluator}, {@link ConfirmStrategy}, {@link
 * ConfirmationRequiredException}) — so every party in the authorization substrate can
 * reach it: the enforcement point (the {@code OperationDispatcher} impl that verifies),
 * the agent loop (which mints a capsule after obtaining user approval via its safety
 * gate), and the FE-facing approval endpoint (which mints on a user gesture). A single
 * implementation instance is shared so its single-use nonce ledger is authoritative
 * across all callers.
 *
 * <p>A capsule is cryptographically bound to {@code operationId} + a hash of the exact
 * {@code argumentsJson}, so it cannot be replayed against a different action or different
 * arguments, and cannot be forged without the implementation's session key.
 */
public interface ConsentCapsuleAuthority {

  /**
   * Mints a single-use capsule approving {@code operationId} with exactly {@code argumentsJson},
   * recording the {@code sourceTier} of the action it authorizes (tempdoc 550 critical-analysis
   * F3). The tier scopes revocation: an emergency Global Hard Stop revokes only non-user
   * ({@code != TRUSTED}) grants, leaving a user's own pending approval intact.
   */
  String mint(String operationId, String argumentsJson, SourceTier sourceTier);

  /**
   * Mints a capsule of unknown provenance — defaults to the strictest, most-revocable posture
   * ({@code UNTRUSTED}), so an unattributed capsule is treated as non-user for revocation. Callers
   * that know the tier (the approval endpoint) should use the 3-arg form.
   */
  default String mint(String operationId, String argumentsJson) {
    return mint(operationId, argumentsJson, SourceTier.UNTRUSTED);
  }

  /**
   * Verifies {@code token} is a valid, unexpired, unconsumed capsule bound to exactly
   * {@code operationId} + {@code argumentsJson}; consumes it (single-use) on success.
   * Never throws on malformed input — returns {@code false}.
   */
  boolean verifyAndConsume(String token, String operationId, String argumentsJson);

  /** Approve one frozen preparation in a domain distinct from ordinary public-argument capsules. */
  default String mintPrepared(String operationId, String argumentsJson, SourceTier sourceTier,
      String operationKey, java.util.UUID preparationNonce) {
    throw new UnsupportedOperationException("Prepared consent is unavailable");
  }

  /** Verify the exact prepared reference; an implementation must never fall back to public-only consent. */
  default boolean verifyPreparedAndConsume(String token, String operationId, String argumentsJson,
      String operationKey, java.util.UUID preparationNonce) {
    return false;
  }
}
