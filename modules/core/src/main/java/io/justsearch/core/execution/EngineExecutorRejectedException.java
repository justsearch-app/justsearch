/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Typed refusal from a bounded Engine executor owner. */
public final class EngineExecutorRejectedException extends RejectedExecutionException {
  public enum Reason { QUEUE_LIMIT, TIMER_LIMIT, INSTANCE_LIMIT, CLOSED }

  private final Reason reason;
  private final int retryAfterSeconds;

  public EngineExecutorRejectedException(Reason reason, String executorName, int retryAfterSeconds) {
    super(Objects.requireNonNull(reason, "reason") + ": " + Objects.requireNonNull(executorName, "executorName"));
    this.reason = reason;
    if (retryAfterSeconds < 1) throw new IllegalArgumentException("retryAfterSeconds must be positive");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public Reason reason() {
    return reason;
  }

  public int retryAfterSeconds() { return retryAfterSeconds; }
}
