/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

/** Shared Engine work admission. HTTP fairness and the aggregate work envelope stay distinct. */
public interface EngineAdmissionService {
  /** Admit new work. Only upgrade control routing may bypass a frozen boundary. */
  EngineWorkHandle admit(EngineContext context, boolean allowWhileFrozen);

  /** Bounded status observation; search/generation capacity cannot refuse a status read. */
  default EngineWorkHandle admitObservation(EngineContext context) {
    return admit(context, false);
  }

  /**
   * Reserve a status request atomically without waiting for the work owner's lock, in the same
   * bounded observation pool as {@link #admitObservation}.
   */
  default StatusReservation reserveStatusAdmission(EngineContext context) {
    throw new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, retryAfterSeconds());
  }

  /** A status reservation lives through serialization and is released once by the HTTP filter. */
  interface StatusReservation extends AutoCloseable {
    @Override void close();
  }

  /** Compatibility read of admission state; callers that do work must reserve it instead. */
  default void checkStatusAdmission(EngineContext context) {
    try (var reservation = reserveStatusAdmission(context)) { /* inspection only */ }
  }

  /**
   * Admit a front's request, recording activity only after acceptance. Activity describes the
   * requester, independently of the selected operation's urgency and survival policy. Observer
   * polls and protocol housekeeping pass false.
   */
  default EngineWorkHandle admitRequest(
      EngineContext context, boolean allowWhileFrozen, boolean userActivity) {
    return admit(context, allowWhileFrozen);
  }

  /**
   * {@link #admitObservation} for a front's request: the read takes a bounded observation slot,
   * and a deliberate read still records activity after acceptance, as {@link #admitRequest} does.
   */
  default EngineWorkHandle admitObservationRequest(EngineContext context, boolean userActivity) {
    return admitObservation(context);
  }

  /**
   * Admit a front's report of input a person made {@code inputAgeMs} before it arrived (the web UI's
   * end-of-interval presence report). The activity is placed at that time, bounded by the activity
   * owner, so a report of input made before background work was accepted does not count as acting
   * during it.
   */
  default EngineWorkHandle admitRequest(
      EngineContext context, boolean allowWhileFrozen, boolean userActivity, long inputAgeMs) {
    return admitRequest(context, allowWhileFrozen, userActivity);
  }

  /** Attach a port call to existing exact work, or admit an unattached library call. */
  EngineWorkHandle attach(EngineContext context);

  /**
   * Attach inference-capable work while reserving aggregate and per-context capacity for text work.
   * Providers without this reservation must refuse inference rather than consume that capacity.
   */
  default EngineWorkHandle attachInference(EngineContext context) {
    throw new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, retryAfterSeconds());
  }

  /** Permanently close new work admission for process shutdown. Existing retained work may finish. */
  void beginClosing();

  /** Whether the monotonic process-closing boundary has been crossed. */
  boolean isClosing();

  void cancelInteractive(String reason);

  int retryAfterSeconds();

  /** Immutable limits of this running owner, not defaults read from a configuration file. */
  Limits limits();

  /** All currently retained work, including internal producers and the inspecting request. */
  int activeWorkCount();

  /** Waits for exact admitted-work references to leave after process closing begins. */
  boolean awaitDrained(java.time.Duration timeout);

  record Limits(int perContextLimit, int aggregateLimit, int retryAfterSeconds) {}
}
