/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Caller scheduling facts; encoder owners translate these into native acquisition requests. */
public record InferenceRequest(
    Urgency urgency, long deadlineNanos, BooleanSupplier cancellationRequested) {
  public enum Urgency { FOREGROUND, BACKGROUND }

  public InferenceRequest {
    Objects.requireNonNull(urgency, "urgency");
    Objects.requireNonNull(cancellationRequested, "cancellationRequested");
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
