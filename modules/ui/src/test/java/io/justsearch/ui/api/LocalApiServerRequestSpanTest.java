/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import io.opentelemetry.api.trace.Span;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocalApiServerRequestSpanTest {

  @Test
  void completionSpanProjectsCachedContextAndDoesNotResolveHeadersAgain() {
    EngineContext cached =
        EngineProvenance.context(
            EngineContext.ClientKind.WEBVIEW,
            "cached-client",
            Optional.of("cached-session"),
            Optional.of("cached-grant"),
            TransportTag.AGENT_LOOP,
            EngineContext.Survival.INTERACTIVE,
            EngineContext.Urgency.FOREGROUND);
    Context request = mock(Context.class);
    Span span = mock(Span.class);
    when(request.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(cached);
    when(request.header(anyString()))
        .thenThrow(new AssertionError("completion projection must not resolve request headers"));

    LocalApiServer.recordEngineProvenance(request, span);

    verify(span).setAttribute("engine.originator", "agent");
    verify(span).setAttribute("engine.transport", "AGENT_LOOP");
    // The span projection is deliberately limited to coarse, low-cardinality attribution.
    verify(span, never()).setAttribute("engine.client_id", "cached-client");
    verify(span, never()).setAttribute("engine.session_id", "cached-session");
    verify(span, never()).setAttribute("engine.grant_reference", "cached-grant");
  }

  @Test
  void completionSpanLeavesUnresolvedRequestsUnattributed() {
    Context request = mock(Context.class);
    Span span = mock(Span.class);

    assertDoesNotThrow(() -> LocalApiServer.recordEngineProvenance(request, span));

    verify(span, never()).setAttribute("engine.originator", "agent");
    verify(span, never()).setAttribute("engine.transport", "AGENT_LOOP");
  }
}
