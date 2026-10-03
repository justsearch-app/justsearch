/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

/** Shared Engine work admission. HTTP fairness and the aggregate work envelope stay distinct. */
public interface EngineAdmissionService {
  /** Admit new work. Only upgrade control routing may bypass a frozen boundary. */
  EngineWorkHandle admit(EngineContext context, boolean allowWhileFrozen);

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
