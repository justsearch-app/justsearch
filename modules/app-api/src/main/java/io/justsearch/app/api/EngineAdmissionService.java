/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.context.EngineContext;

/** Shared Engine work admission. HTTP fairness and the aggregate work envelope stay distinct. */
public interface EngineAdmissionService {
  /** Admit new work. Only upgrade control routing may bypass a frozen boundary. */
  EngineWorkHandle admit(EngineContext context, boolean allowWhileFrozen);

  /** Attach a port call to existing exact work, or admit an unattached library call. */
  EngineWorkHandle attach(EngineContext context);

  void cancelInteractive(String reason);

  int retryAfterSeconds();

  /** Immutable limits of this running owner, not defaults read from a configuration file. */
  Limits limits();

  /** All currently retained work, including internal producers and the inspecting request. */
  int activeWorkCount();

  record Limits(int perContextLimit, int aggregateLimit, int retryAfterSeconds) {}
}
