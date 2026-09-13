/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.core.context.EngineContext;
import java.util.Optional;

/** Fixed attribution contexts for app-agent-api contract tests. */
public final class TestEngineContexts {
  private TestEngineContexts() {}

  /** An autonomous agent-loop call belongs to the untrusted source tier. */
  public static EngineContext agentLoop() {
    return agentLoop(Optional.empty());
  }

  /** An autonomous agent-loop call carrying its session correlation id. */
  public static EngineContext agentLoop(String sessionId) {
    return agentLoop(Optional.of(sessionId));
  }

  private static EngineContext agentLoop(Optional<String> sessionId) {
    return new EngineContext(
        EngineContext.ClientKind.INTERNAL,
        "test-agent",
        sessionId,
        Optional.empty(),
        SourceTier.UNTRUSTED.name(),
        TransportTag.AGENT_LOOP.name(),
        EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }
}
