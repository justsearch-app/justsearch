/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static io.justsearch.ui.api.mcp.McpSemanticBudgetNoteTest.invokeSearch;
import static io.justsearch.ui.api.mcp.McpSemanticBudgetNoteTest.responseWith;
import static io.justsearch.ui.api.mcp.McpSemanticBudgetNoteTest.structuredOf;
import static io.justsearch.ui.api.mcp.McpSemanticBudgetNoteTest.textOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.SearchTrace;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Spec D step 2b (ADR-0050 decision 1): the MCP search surface carries the per-leg cause of a
 * semantic budget miss, as {@code (cause: <cause>)} in the text note and as a {@code causes} map in
 * the structured degradation summary, and adds nothing when no cause is carried.
 */
final class McpSemanticBudgetCauseTest {

  @Test
  void eachLegCarriesItsOwnCause() {
    SearchTrace.Degradation bothMissed =
        new SearchTrace.Degradation(
            false,
            null,
            true,
            "SEMANTIC_BUDGET_EXCEEDED",
            false,
            "SEMANTIC_BUDGET_EXCEEDED",
            "stuck",
            "contended");

    Map<String, Object> result = invokeSearch(responseWith(bothMissed));

    String text = textOf(result);
    assertTrue(
        text.contains(
            "Note: semantic ranking degraded (SEMANTIC_BUDGET_EXCEEDED (cause: stuck); SPLADE"
                + " SEMANTIC_BUDGET_EXCEEDED (cause: contended)); results may be keyword-ranked"
                + " only."),
        text);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) structuredOf(result).get("degradation");
    assertEquals(Map.of("dense", "stuck", "splade", "contended"), summary.get("causes"));
  }

  @Test
  void aSpladeOnlyMissDoesNotClaimKeywordOnlyRanking() {
    SearchTrace.Degradation spladeMissed =
        new SearchTrace.Degradation(
            false, null, false, null, false, "SEMANTIC_BUDGET_EXCEEDED", null, "loading");

    String text = textOf(invokeSearch(responseWith(spladeMissed)));

    assertTrue(
        text.contains(
            "Note: semantic ranking degraded (SPLADE SEMANTIC_BUDGET_EXCEEDED (cause: loading));"
                + " results are ranked without the SPLADE leg."),
        text);
  }

  @Test
  void noCauseAddsNoCauseText() {
    SearchTrace.Degradation fallback =
        new SearchTrace.Degradation(false, null, true, "NO_EMBEDDING_SERVICE", false, "absent");

    Map<String, Object> result = invokeSearch(responseWith(fallback));

    assertFalse(textOf(result).contains("cause:"), textOf(result));
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) structuredOf(result).get("degradation");
    assertFalse(summary.containsKey("causes"));
  }
}
