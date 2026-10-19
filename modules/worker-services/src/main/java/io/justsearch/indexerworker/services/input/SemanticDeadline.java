/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.core.execution.InferenceRequest;

/**
 * The semantic sub-budget of one search (Spec D §1.1, §1.3): the absolute deadline its query
 * encoder legs share, what a miss means, and whether the models-ready wait already used it up.
 *
 * <p>{@link #unbounded()} is a search with no sub-budget (lexical-only requests, the boot warm-up):
 * its legs, if any, run on the request's own deadline as before.
 *
 * @param bounded whether a sub-budget applies
 * @param deadlineNanos the absolute {@link System#nanoTime()} deadline, when bounded
 * @param kind {@link InferenceRequest.SemanticBudget#FALLBACK} for a leg with a keyword fallback,
 *     {@link InferenceRequest.SemanticBudget#REQUIRED} for VECTOR-only
 * @param modelsTimedOut whether the models-ready latch was still closed at the deadline
 */
public record SemanticDeadline(
    boolean bounded,
    long deadlineNanos,
    InferenceRequest.SemanticBudget kind,
    boolean modelsTimedOut) {

  private static final SemanticDeadline UNBOUNDED =
      new SemanticDeadline(false, 0L, InferenceRequest.SemanticBudget.NONE, false);

  public SemanticDeadline {
    java.util.Objects.requireNonNull(kind, "kind");
    if (bounded == (kind == InferenceRequest.SemanticBudget.NONE)) {
      throw new IllegalArgumentException("a bounded sub-budget names its kind, and only then");
    }
  }

  public static SemanticDeadline unbounded() {
    return UNBOUNDED;
  }

  public static SemanticDeadline of(
      long deadlineNanos, InferenceRequest.SemanticBudget kind, boolean modelsTimedOut) {
    return new SemanticDeadline(true, deadlineNanos, kind, modelsTimedOut);
  }

  /** Whether a sub-budget applies and its deadline has passed. */
  public boolean passed() {
    return bounded && deadlineNanos - System.nanoTime() <= 0;
  }

  /** The request a leg runs under: the parent narrowed to this deadline, or the parent itself. */
  public InferenceRequest narrow(InferenceRequest request) {
    return bounded ? InferenceRequest.narrowed(request, deadlineNanos, kind) : request;
  }
}
