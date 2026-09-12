/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Expected preparation refusal that is guaranteed to have produced no operation effect.
 *
 * <p>The runner uses the typed unsuccessful result to accept the generic invocation identity and
 * return the refusal without scheduling or admitting work. The result remains the single source of
 * truth for its message, details, error code, and retry classification.
 */
public final class OperationPreparationRefused extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OperationResult refusal;

  public OperationPreparationRefused(OperationResult refusal) {
    super(validate(refusal).message());
    this.refusal = refusal;
  }

  public OperationResult refusal() {
    return refusal;
  }

  private static OperationResult validate(OperationResult refusal) {
    Objects.requireNonNull(refusal, "refusal");
    if (refusal.success()) {
      throw new IllegalArgumentException("Preparation refusal must be unsuccessful");
    }
    if (refusal.errorCode().filter(OperationResult::isDurableOutcomeCode).isEmpty()) {
      throw new IllegalArgumentException("Preparation refusal requires a durable outcome code");
    }
    return refusal;
  }
}
