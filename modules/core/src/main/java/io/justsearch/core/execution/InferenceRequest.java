/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Caller scheduling facts; encoder owners translate these into native acquisition requests.
 *
 * <p>{@code semanticBudget} says whether this request is a query encoder leg running under a
 * search's semantic sub-budget (Spec D §1). {@link SemanticBudget#NONE} is every other caller.
 * {@code mayCreateSessions} independently restricts request paths that have a fallback, including
 * a caller deadline too short for a semantic sub-budget. Narrowing never restores that permission.
 * Native owners may prepare explicitly deferred SPLADE/reranker CPU backups under this same
 * authority; that exception never permits GPU creation/retry or extends the caller deadline.
 */
public record InferenceRequest(
    Urgency urgency, long deadlineNanos, BooleanSupplier cancellationRequested,
    SemanticBudget semanticBudget, boolean mayCreateSessions) {
  public enum Urgency { FOREGROUND, BACKGROUND }

  /** Whether this request is a query leg bounded by a semantic sub-budget, and what a miss means. */
  public enum SemanticBudget {
    /** Not a semantic leg: the request's own deadline, today's behaviour. */
    NONE,
    /** A leg with a keyword fallback: a miss becomes a fallback reason, never a failure. */
    FALLBACK,
    /** A leg the request cannot answer without (VECTOR-only): a miss is a typed deadline. */
    REQUIRED
  }

  public InferenceRequest {
    Objects.requireNonNull(urgency, "urgency");
    Objects.requireNonNull(cancellationRequested, "cancellationRequested");
    Objects.requireNonNull(semanticBudget, "semanticBudget");
    mayCreateSessions = mayCreateSessions && semanticBudget != SemanticBudget.FALLBACK;
  }

  /** Compatibility authority: fallback legs use existing sessions; other work may create them. */
  public InferenceRequest(
      Urgency urgency, long deadlineNanos, BooleanSupplier cancellationRequested,
      SemanticBudget semanticBudget) {
    this(urgency, deadlineNanos, cancellationRequested, semanticBudget,
        semanticBudget != SemanticBudget.FALLBACK);
  }

  public InferenceRequest(
      Urgency urgency, long deadlineNanos, BooleanSupplier cancellationRequested) {
    this(urgency, deadlineNanos, cancellationRequested, SemanticBudget.NONE);
  }

  /** Bounded authority for callers without an admitted Engine request. */
  public static InferenceRequest within(
      Urgency urgency, Duration timeout, BooleanSupplier cancellationRequested) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(365)) > 0) {
      throw new IllegalArgumentException("timeout must be positive and no greater than 365 days");
    }
    return new InferenceRequest(urgency, System.nanoTime() + timeout.toNanos(), cancellationRequested);
  }

  public static InferenceRequest foreground() {
    return within(Urgency.FOREGROUND, Duration.ofMinutes(5), () -> false);
  }

  /** Bulk (indexing and enrichment) work: yields to foreground queries at the device. */
  public static InferenceRequest background() {
    return within(Urgency.BACKGROUND, Duration.ofMinutes(5), () -> false);
  }

  /**
   * A child of {@code parent} for one semantic leg: same urgency and cancellation, and a deadline
   * that is the earlier of the parent's and {@code deadlineNanos}, so a child never outlives its
   * parent. Deadlines are compared by signed difference, which is safe across a wrapping
   * {@link System#nanoTime()}.
   */
  public static InferenceRequest narrowed(
      InferenceRequest parent, long deadlineNanos, SemanticBudget semanticBudget) {
    Objects.requireNonNull(parent, "parent");
    long earlier = deadlineNanos - parent.deadlineNanos() < 0 ? deadlineNanos : parent.deadlineNanos();
    return new InferenceRequest(
        parent.urgency(), earlier, parent.cancellationRequested(), semanticBudget,
        parent.mayCreateSessions());
  }

  /** Request-path authority independent of whether a semantic sub-budget fits the deadline. */
  public InferenceRequest withoutSessionCreation() {
    return new InferenceRequest(urgency, deadlineNanos, cancellationRequested, semanticBudget, false);
  }

  /** Whether the deadline has passed, without the cancellation checks of {@link #remainingNanos}. */
  public boolean deadlinePassed() {
    return deadlineNanos - System.nanoTime() <= 0;
  }

  /** Checks cancellation before deadline expiry, including interruption before lease handoff. */
  public long remainingNanos() {
    if (Thread.currentThread().isInterrupted()) {
      var cancellation = new CancellationException("inference interrupted");
      cancellation.initCause(new InterruptedException("interrupt observed before lease handoff"));
      throw cancellation;
    }
    if (cancellationRequested.getAsBoolean()) {
      throw new CancellationException("inference cancelled");
    }
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) throw new DeadlineExceededException("inference deadline exceeded");
    return remaining;
  }

  /** Neutral outcome shared by caller checks and encoder acquisition failures. */
  public interface DeadlineExceeded {}

  public static final class DeadlineExceededException extends RuntimeException
      implements DeadlineExceeded {
    private static final long serialVersionUID = 1L;

    public DeadlineExceededException(String message) {
      super(message);
    }
  }
}
