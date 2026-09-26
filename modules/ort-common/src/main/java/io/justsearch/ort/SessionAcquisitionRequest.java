/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable scheduling authority for one native-session acquisition attempt. */
public record SessionAcquisitionRequest(
    Urgency urgency, long deadlineNanos, BooleanSupplier cancellationRequested) {

  /** Keeps signed {@link System#nanoTime()} subtraction unambiguous and rejects accidental hangs. */
  public static final Duration MAX_TIMEOUT = Duration.ofDays(365);

  public SessionAcquisitionRequest {
    Objects.requireNonNull(urgency, "urgency");
    Objects.requireNonNull(cancellationRequested, "cancellationRequested");
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
          "native session acquisition deadline exceeded");
    }
    return remaining;
  }

  /** Scheduling class used by the owning per-handle fairness gate. */
  public enum Urgency {
    FOREGROUND,
    BACKGROUND
  }
}
