/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.Optional;

/** Fixed attribution values for direct UI API unit-test calls. */
public final class TestRequestContexts {

  private TestRequestContexts() {}

  public static EngineContext browser() {
    return new EngineContext(
        EngineContext.ClientKind.WEBVIEW,
        "local-webview",
        Optional.empty(),
        Optional.empty(),
        "TRUSTED",
        "BUTTON",
        EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }

  /** Context used by the internal status sampler when it polls the Worker. */
  public static EngineContext internal() {
    return new EngineContext(
        EngineContext.ClientKind.INTERNAL,
        "status-sampler",
        Optional.empty(),
        Optional.empty(),
        "TRUSTED",
        "SYSTEM_INTERNAL",
        EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.BACKGROUND);
  }

  public static EngineContext mcp(String sessionId) {
    String clientId = sessionId == null || sessionId.isBlank() ? "mcp-anonymous" : sessionId;
    return new EngineContext(
        EngineContext.ClientKind.MCP_CLIENT,
        clientId,
        sessionId == null || sessionId.isBlank() ? Optional.empty() : Optional.of(sessionId),
        Optional.empty(),
        "UNTRUSTED",
        "MCP",
        EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }

  public static InvocationProvenance provenance(EngineContext context, ExecutorTag executor) {
    return InvocationProvenance.fromEngineContext(context, executor, Instant.EPOCH, Optional.empty());
  }
}
