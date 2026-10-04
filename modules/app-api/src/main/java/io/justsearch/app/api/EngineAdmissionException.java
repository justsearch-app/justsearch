/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/** A typed admission refusal, independent from operation dispatch authorization. */
public final class EngineAdmissionException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public enum Reason { CONTEXT_LIMIT, ENGINE_LIMIT, FROZEN, WORK_FINISHED }

  private final Reason reason;
  private final int retryAfterSeconds;

  public EngineAdmissionException(Reason reason, int retryAfterSeconds) {
    super("Engine work refused: " + reason.name());
    this.reason = reason;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public Reason reason() { return reason; }
  public int retryAfterSeconds() { return retryAfterSeconds; }
}
