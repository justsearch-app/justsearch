/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec D test M1 (S2, MCP): a search whose SPLADE leg missed the semantic sub-budget while the
 * dense leg answered (test B3's condition) says so in the {@code justsearch_search} text note and in
 * the structured degradation summary, naming {@code SEMANTIC_BUDGET_EXCEEDED} for the SPLADE leg.
 * Uses only API that exists before the change.
 */
final class McpSemanticBudgetNoteTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneId.of("UTC"));

  @Test
  void spladeLegBudgetMissIsNamedInTheNoteAndTheSummary() {
    SearchTrace.Degradation spladeMissed =
        new SearchTrace.Degradation(false, null, false, null, false, "SEMANTIC_BUDGET_EXCEEDED");

    Map<String, Object> result = invokeSearch(responseWith(spladeMissed));

    String text = textOf(result);
    assertTrue(text.contains("Note: semantic ranking degraded ("), text);
    assertTrue(text.contains("SPLADE SEMANTIC_BUDGET_EXCEEDED"), text);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary =
        (Map<String, Object>) structuredOf(result).get("degradation");
    assertEquals(List.of("SEMANTIC_BUDGET_EXCEEDED"), summary.get("reasons"));
  }

  static KnowledgeSearchResponse responseWith(SearchTrace.Degradation degradation) {
    SearchTrace trace =
        new SearchTrace(SearchTrace.SCHEMA_VERSION, "HYBRID", null, null, degradation, List.of());
    return new KnowledgeSearchResponse(
        0L, 0L, 3L, List.of(), null, null, null, null, null, null, null, trace, null);
  }

  static Map<String, Object> invokeSearch(KnowledgeSearchResponse canned) {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    McpSearchSessionFixture.stub(adapter, canned);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);
    return surface.callTool(
        "justsearch_search", Map.of("query", "widget"), "s1", TestRequestContexts.mcp("s1"));
  }

  static String textOf(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> structuredOf(Map<String, Object> result) {
    return (Map<String, Object>) result.get("structuredContent");
  }
}
