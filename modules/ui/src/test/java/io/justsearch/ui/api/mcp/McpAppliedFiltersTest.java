/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code appliedFilters} — the filter echo (366 §1b) — on the MCP evidence tier.
 *
 * <p>The engine sets it, REST carries it, and the MCP delivery governor preserves it through
 * truncation, but {@link McpEvidenceProjection} never emitted it: an agent that scoped a search
 * could not distinguish "the scope was honoured" from "the scope was silently dropped" without
 * inferring it from the returned rows.
 *
 * <p>Driven through the real {@code tools/call} entry point, and the canned response's echo is
 * built from the request the surface actually produced (the same {@code AppliedFilters.of} call the
 * engine makes) — so this asserts the whole MCP arg → request → echo → projection path, not a
 * hand-made map.
 */
@DisplayName("MCP justsearch_search — appliedFilters echo")
final class McpAppliedFiltersTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneId.of("UTC"));

  /** Mirrors {@code KnowledgeSearchEngine}'s response build: the echo is of the REQUEST. */
  private static KnowledgeSearchResponse respondingTo(KnowledgeSearchRequest req) {
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            "multi_leg",
            null,
            null,
            List.of(
                new SearchTrace.TraceStage(
                    SearchTrace.StageId.SPARSE_RETRIEVAL,
                    SearchTrace.StageStatus.EXECUTED,
                    null,
                    5L,
                    null,
                    null)));
    KnowledgeSearchResponse.Hit hit =
        new KnowledgeSearchResponse.Hit(
            "doc-1",
            0.9d,
            Map.of("title", "Troubleshooting", "path", "help/troubleshooting.md"),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    return new KnowledgeSearchResponse(
        1L,
        1L,
        5L,
        List.of(hit),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        trace,
        KnowledgeSearchResponse.AppliedFilters.of(req.filters(), req.boostFilters()));
  }

  private McpProtocolHandler handler(ArgumentCaptor<KnowledgeSearchRequest> captor) {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(captor.capture(), any(EngineContext.class)))
        .thenAnswer(inv -> respondingTo(inv.getArgument(0)));
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);
    return new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);
  }

  private static String callSearch(McpProtocolHandler h, int id, String argumentsJson)
      throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/mcp");
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"justsearch_search\","
                + "\"arguments\":"
                + argumentsJson
                + "}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    h.handlePost(ctx);
    return resultCaptor.getValue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structuredOf(String raw) throws Exception {
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertEquals(Boolean.FALSE, result.get("isError"), "tool call must succeed: " + raw);
    return (Map<String, Object>) result.get("structuredContent");
  }

  @Test
  @DisplayName("a filtered call's evidence echoes the filters it requested")
  @SuppressWarnings("unchecked")
  void filteredCallCarriesAppliedFilters() throws Exception {
    ArgumentCaptor<KnowledgeSearchRequest> captor =
        ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
    McpProtocolHandler h = handler(captor);

    Map<String, Object> structured =
        structuredOf(
            callSearch(
                h,
                1,
                "{\"query\":\"troubleshoot\",\"filters\":{\"path_prefix\":\"C:/docs\","
                    + "\"meta_author\":[\"ada\"]}}"));

    // Precondition: the surface really did build a scoped request (otherwise the echo below would
    // be vacuously absent-for-the-wrong-reason).
    KnowledgeSearchRequest sent = captor.getValue();
    assertEquals("C:/docs", sent.filters().pathPrefix());
    assertEquals(List.of("ada"), sent.filters().metaAuthor());

    Map<String, Object> applied = (Map<String, Object>) structured.get("appliedFilters");
    assertNotNull(applied, "a scoped search must say so on the agent-facing tier");
    Map<String, Object> filters = (Map<String, Object>) applied.get("filters");
    assertNotNull(filters);
    assertEquals("C:/docs", filters.get("pathPrefix"));
    assertEquals(List.of("ada"), filters.get("metaAuthor"));
    // Empty filter members are omitted — an agent reads what was scoped, not a wall of empties.
    assertNull(filters.get("mime"));
    assertNull(filters.get("collection"));
  }

  @Test
  @DisplayName("an unfiltered call carries no appliedFilters at all")
  void unfilteredCallCarriesNone() throws Exception {
    ArgumentCaptor<KnowledgeSearchRequest> captor =
        ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
    McpProtocolHandler h = handler(captor);

    Map<String, Object> structured =
        structuredOf(callSearch(h, 2, "{\"query\":\"troubleshoot\"}"));

    assertNotNull(structured.get("results"), "positive control: the call did produce evidence");
    assertNull(
        structured.get("appliedFilters"),
        "an unscoped search must not claim a scope — the echo is present only when filters were sent");
  }
}
