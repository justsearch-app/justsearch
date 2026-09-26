/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Thrown by the dispatcher when the trust lattice (tempdoc 487 §4.4) computes a
 * non-AUTO {@link GateBehavior} for a dispatch and the caller did not supply a
 * confirmation token.
 *
 * <p>Caught by the dispatch's caller (HTTP endpoint, agent loop, etc.) — the caller
 * is responsible for surfacing the appropriate elicitation UX (per the operation's
 * declared {@link ConfirmStrategy}) and re-dispatching with a confirmation token
 * after consent.
 *
 * <p>Carries the operation reference + the computed gate behavior + the
 * destination's confirm strategy so callers can render trust-aware copy (e.g.,
 * "An LLM is requesting to run {opTitle}; type the operation name to confirm").
 */
public final class ConfirmationRequiredException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OperationRef operationRef;
  private final GateBehavior gateBehavior;
  private final ConfirmStrategy declaredStrategy;
  private final SourceTier sourceTier;
  private final String operationKey;
  private final java.util.UUID preparationNonce;
  private final OperationApprovalPreview approvalPreview;

  public ConfirmationRequiredException(
      OperationRef operationRef,
      GateBehavior gateBehavior,
      ConfirmStrategy declaredStrategy,
      SourceTier sourceTier) {
    this(operationRef, gateBehavior, declaredStrategy, sourceTier, null, null);
  }

  /** The server-selected preparation reference; neither field grants execution authority. */
  public ConfirmationRequiredException(OperationRef operationRef, GateBehavior gateBehavior,
      ConfirmStrategy declaredStrategy, SourceTier sourceTier, String operationKey, java.util.UUID preparationNonce) {
    this(operationRef, gateBehavior, declaredStrategy, sourceTier, operationKey, preparationNonce, null);
  }

  public ConfirmationRequiredException(OperationRef operationRef, GateBehavior gateBehavior,
      ConfirmStrategy declaredStrategy, SourceTier sourceTier, String operationKey, java.util.UUID preparationNonce,
      OperationApprovalPreview approvalPreview) {
    super(
        "Confirmation required for operation "
            + Objects.requireNonNull(operationRef, "operationRef").value()
            + " (gate=" + Objects.requireNonNull(gateBehavior, "gateBehavior")
            + ", sourceTier=" + Objects.requireNonNull(sourceTier, "sourceTier")
            + ")");
    this.operationRef = operationRef;
    this.gateBehavior = gateBehavior;
    this.declaredStrategy = Objects.requireNonNull(declaredStrategy, "declaredStrategy");
    this.sourceTier = sourceTier;
    this.operationKey = preparationNonce == null ? operationKey : Objects.requireNonNull(operationKey, "operationKey");
    this.preparationNonce = preparationNonce;
    if (approvalPreview != null) Objects.requireNonNull(preparationNonce, "preparationNonce");
    this.approvalPreview = approvalPreview;
  }

  public OperationApprovalPreview approvalPreview() { return approvalPreview; }

  public String operationKey() { return operationKey; }

  public java.util.UUID preparationNonce() { return preparationNonce; }

  public OperationRef operationRef() {
    return operationRef;
  }

  public GateBehavior gateBehavior() {
    return gateBehavior;
  }

  public ConfirmStrategy declaredStrategy() {
    return declaredStrategy;
  }

  public SourceTier sourceTier() {
    return sourceTier;
  }
}
