/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task t-20261005-b6d536, FP4 and FP11: an MCP search refused because the Engine's inference
 * threads and their queue are full answers JSON-RPC -32000 with errorCode ADMISSION_INFERENCE_BUSY,
 * a 429 with Retry-After, and no safe replay; never ADMISSION_ENGINE_LIMIT.
 *
 * <p>The reason is looked up by name so the check fails by assertion where it is absent.
 */
final class McpInferenceBusyRefusalTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-16T12:00:00Z"), ZoneId.of("UTC"));

  @Test
  @SuppressWarnings("unchecked")
  void inferenceBusyRefusalKeepsItsOwnCodeOverMcp() throws Exception {
    var reason = Arrays.stream(EngineAdmissionException.Reason.values())
        .filter(r -> r.name().equals("INFERENCE_BUSY")).findFirst();
    assertTrue(reason.isPresent(), "no INFERENCE_BUSY admission reason");
    var refusal = new EngineAdmissionException(reason.orElseThrow(), 7);
    var adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.openSearch(any(), any(EngineContext.class)))
        .thenThrow(new CompletionException(refusal));
    var ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/mcp");
    when(ctx.body()).thenReturn("""
        {"jsonrpc":"2.0","id":"busy-7","method":"tools/call",
         "params":{"name":"justsearch_search","arguments":{"query":"test"}}}
        """);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    var response = ArgumentCaptor.forClass(String.class);
    when(ctx.result(response.capture())).thenReturn(ctx);

    handlerOver(adapter).handlePost(ctx);

    var wire = JsonMapper.builder().build().readValue(response.getValue(), Map.class);
    assertEquals("busy-7", wire.get("id"));
    var error = (Map<String, Object>) wire.get("error");
    assertEquals(-32000, error.get("code"));
    var data = (Map<String, Object>) error.get("data");
    assertEquals("ADMISSION_INFERENCE_BUSY", data.get("errorCode"));
    assertEquals(false, data.get("retrySafe"));
    verify(ctx).status(429);
    verify(ctx).header("Retry-After", "7");
  }

  private static McpProtocolHandler handlerOver(KnowledgeHttpApiAdapter adapter) {
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    var surface = new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class),
        () -> ctrl,
        () -> null,
        FIXED_CLOCK);
    return new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);
  }
}
