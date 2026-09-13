/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import java.util.Optional;

/** Explicit contexts used by app-agent tests when exercising the engine ports. */
public final class EngineContextTestFixtures {

  public static final EngineContext AGENT_LOOP =
      new EngineContext(
          EngineContext.ClientKind.INTERNAL,
          "agent-test-client",
          Optional.of("agent-test-session"),
          Optional.empty(),
          "UNTRUSTED",
          "AGENT_LOOP",
          EngineContext.Survival.INTERACTIVE,
          EngineContext.Urgency.FOREGROUND);

  public static final EngineContext AGENT_LOOP_BACKGROUND =
      new EngineContext(
          EngineContext.ClientKind.INTERNAL,
          "agent-test-client",
          Optional.of("agent-test-session"),
          Optional.empty(),
          "UNTRUSTED",
          "AGENT_LOOP",
          EngineContext.Survival.DURABLE,
          EngineContext.Urgency.BACKGROUND);

  private EngineContextTestFixtures() {}
}
