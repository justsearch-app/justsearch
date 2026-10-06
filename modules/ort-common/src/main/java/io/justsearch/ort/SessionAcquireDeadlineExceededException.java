/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.core.execution.InferenceRequest;
import java.util.Objects;

/**
 * The monotonic deadline elapsed before a native call could return to its caller.
 *
 * <p>{@link #phase()} names where the time went, set by the code that observed the miss (Spec D
 * §1.4): a session that was not ready yet, a wait for the device, or a native run that did not
 * return in time.
 */
public final class SessionAcquireDeadlineExceededException extends SessionAcquisitionException
    implements InferenceRequest.DeadlineExceeded {

  /** Where a deadline was missed. */
  public enum Phase {
    /** No usable session yet: models still loading, or a session being created or warmed. */
    LOADING,
    /** Waiting for the device (and tokenising before the wait); never seated. */
    GATE,
    /** Seated and the native run started, but it did not return before the deadline. */
    RUN
  }

  private final Phase phase;

  public SessionAcquireDeadlineExceededException(String message) {
    this(message, Phase.GATE);
  }

  public SessionAcquireDeadlineExceededException(String message, Phase phase) {
    super(message);
    this.phase = Objects.requireNonNull(phase, "phase");
  }

  public Phase phase() {
    return phase;
  }
}
