/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.core.execution.InferenceRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Immutable scheduling authority for one native-session acquisition attempt.
 *
 * <p>{@code mayCreateGpuSession} is false for a query leg with a keyword fallback: such a leg must
 * not create or retry a GPU session. It uses a ready session or reports {@code LOADING} (Spec
 * D §4), with one deliberate exception: explicitly deferred SPLADE/reranker CPU backups may
 * prepare under the caller's original deadline/cancellation and the existing native-work bound.
 * Lifecycle warmup clears {@code mayPrepareDeferredCpu}, so GPU release/failure alone cannot
 * create a CPU backup. {@code releaseCallerAtDeadline} asks the owner to return to the caller at
 * the deadline
 * even when a native run has not finished (Spec D §1.5).
 */
public record SessionAcquisitionRequest(
    Urgency urgency,
    long deadlineNanos,
    BooleanSupplier cancellationRequested,
    boolean mayCreateGpuSession,
    boolean releaseCallerAtDeadline,
    boolean mayPrepareDeferredCpu) {

  /** Keeps signed {@link System#nanoTime()} subtraction unambiguous and rejects accidental hangs. */
  public static final Duration MAX_TIMEOUT = Duration.ofDays(365);

  public SessionAcquisitionRequest {
    Objects.requireNonNull(urgency, "urgency");
    Objects.requireNonNull(cancellationRequested, "cancellationRequested");
  }

  /** Ordinary acquisitions may demand a deliberately deferred CPU backup. */
  public SessionAcquisitionRequest(
      Urgency urgency, long deadlineNanos, BooleanSupplier cancellationRequested,
      boolean mayCreateGpuSession, boolean releaseCallerAtDeadline) {
    this(urgency, deadlineNanos, cancellationRequested, mayCreateGpuSession,
        releaseCallerAtDeadline, true);
  }

  /** Lifecycle serving warmup may create GPUs, but cannot turn into cold CPU-backup demand. */
  public SessionAcquisitionRequest withoutDeferredCpuPreparation() {
    return new SessionAcquisitionRequest(urgency, deadlineNanos, cancellationRequested,
        mayCreateGpuSession, releaseCallerAtDeadline, false);
  }

  /** A request that may create sessions and waits for its native run, as before Spec D. */
  public SessionAcquisitionRequest(
      Urgency urgency, long deadlineNanos, BooleanSupplier cancellationRequested) {
    this(urgency, deadlineNanos, cancellationRequested, true, false);
  }

  /** Maps caller facts at the native owner boundary, retaining the live cancellation signal. */
  public static SessionAcquisitionRequest from(InferenceRequest request) {
    InferenceRequest.SemanticBudget semantic = request.semanticBudget();
    return new SessionAcquisitionRequest(
        request.urgency() == InferenceRequest.Urgency.FOREGROUND
            ? Urgency.FOREGROUND : Urgency.BACKGROUND,
        request.deadlineNanos(),
        request.cancellationRequested(),
        request.mayCreateSessions(),
        semantic != InferenceRequest.SemanticBudget.NONE);
  }

  /**
   * Creates a request whose deadline is relative to the monotonic clock.
   *
   * <p>The timeout must be positive and no greater than {@link #MAX_TIMEOUT}. The bounded horizon
   * makes {@code deadline - System.nanoTime()} safe even when the monotonic counter wraps.
   */
  public static SessionAcquisitionRequest within(
      Urgency urgency, Duration timeout, BooleanSupplier cancellationRequested) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "timeout must be positive and no greater than " + MAX_TIMEOUT);
    }
    return new SessionAcquisitionRequest(
        urgency, System.nanoTime() + timeout.toNanos(), cancellationRequested);
  }

  /** Convenience overload for work that has no separate cancellation authority. */
  public static SessionAcquisitionRequest within(Urgency urgency, Duration timeout) {
    return within(urgency, timeout, () -> false);
  }

  /** Returns remaining monotonic time, or throws the typed cancellation/deadline outcome. */
  public long remainingNanos() {
    return remainingNanos(SessionAcquireDeadlineExceededException.Phase.GATE);
  }

  /**
   * As {@link #remainingNanos()}, naming the phase a deadline miss is reported in (Spec D §1.4).
   * Waits before a seat (including tokenisation) are {@code GATE}; waits for a session that is
   * being created or is not ready are {@code LOADING}.
   */
  public long remainingNanos(SessionAcquireDeadlineExceededException.Phase phase) {
    if (Thread.currentThread().isInterrupted()) {
      CancellationException cancellation =
          new CancellationException("native session acquisition interrupted");
      cancellation.initCause(new InterruptedException("interrupt observed before lease handoff"));
      throw cancellation;
    }
    if (cancellationRequested.getAsBoolean()) {
      throw new CancellationException("native session acquisition cancelled");
    }
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new SessionAcquireDeadlineExceededException(
          "native session acquisition deadline exceeded", phase);
    }
    return remaining;
  }

  /** Scheduling class used by the owning per-handle fairness gate. */
  public enum Urgency {
    FOREGROUND,
    BACKGROUND
  }
}
