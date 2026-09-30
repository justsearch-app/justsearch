/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.MatchSpan;
import io.justsearch.app.api.knowledge.SearchTrace.HitStage;
import io.justsearch.app.api.knowledge.SearchTrace.StageId;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 775 §E/§C — the delivery governor: deterministic degradation of the WHOLE assembled
 * {@code justsearch_search} tool result at the 770 §E.3 client truncation cap. Extends the 770
 * golden/totality guards (see {@link McpEvidenceProjectionTest},
 * {@link McpTierEquivalenceGoldenTest}) to the governor path: the assertions here pin the governed
 * quantity (the full result — text block + structuredContent + envelope, NOT the structured tier
 * alone), the degradation order (numeric provenance first, then whole tail results, never
 * mid-payload / mid-span), the budget-boundary behaviour, the 0-disables escape hatch, byte-stable
 * determinism, and the explicit machine-readable notice.
 */
@DisplayName("McpDeliveryGovernor: deterministic full-result degradation at the delivery cap (775 §E/§C)")
final class McpDeliveryGovernorTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /**
   * A synthetic {@link McpDeliveryGovernor.ResultView} whose full result carries, per surviving
   * result, {@code textPerHit} bytes in the {@code content[0].text} block and {@code structPerHit}
   * bytes in {@code structuredContent} (plus {@code provPerHit} bytes of per-hit provenance when
   * requested) — so a test can independently size the text tier and the structured tier and
   * reproduce the live composition where structuredContent is under budget but the full result is
   * not.
   */
  private static McpDeliveryGovernor.ResultView view(int textPerHit, int structPerHit, int provPerHit) {
    return (keep, includeProvenance) -> {
      StringBuilder text = new StringBuilder();
      List<Object> results = new ArrayList<>();
      for (int i = 1; i <= keep; i++) {
        text.append("t".repeat(textPerHit));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("path", "docs/doc-" + i + ".md");
        h.put("excerpt", "x".repeat(structPerHit));
        if (includeProvenance) {
          h.put("prov", "p".repeat(provPerHit));
        }
        results.add(h);
      }
      Map<String, Object> structured = new LinkedHashMap<>();
      structured.put("results", results);
      return Map.of(
          "content", List.of(Map.of("type", "text", "text", text.toString())),
          "structuredContent", structured,
          "isError", false);
    };
  }

  private static int fullBytes(Map<String, Object> result) {
    return MAPPER.writeValueAsString(result).getBytes(StandardCharsets.UTF_8).length;
  }

  private static int structuredBytes(Map<String, Object> result) {
    return MAPPER.writeValueAsString(result.get("structuredContent")).getBytes(StandardCharsets.UTF_8)
        .length;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structured(Map<String, Object> result) {
    return (Map<String, Object>) result.get("structuredContent");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> results(Map<String, Object> result) {
    return (List<Object>) structured(result).get("results");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> governor(Map<String, Object> result) {
    return (Map<String, Object>) structured(result).get("governor");
  }

  private static String excerptText(Object hit) {
    return (String) ((Map<?, ?>) hit).get("excerpt");
  }

  // ---- boundary: just-under is untouched ----

  @Test
  @DisplayName("just-under budget: full result untouched, no notice, provenance retained")
  void justUnderBudgetUntouched() {
    McpDeliveryGovernor.ResultView v = view(50, 50, 20);
    int budget = fullBytes(v.render(3, true)) + 1_000;
    Map<String, Object> out = McpDeliveryGovernor.govern(3, true, budget, MAPPER, v);
    assertEquals(3, results(out).size(), "no results dropped under budget");
    assertFalse(structured(out).containsKey("governor"), "no notice when nothing was degraded");
    assertTrue(((Map<?, ?>) results(out).get(0)).containsKey("prov"), "provenance retained");
  }

  // ---- boundary: just-over → stripping provenance alone suffices ----

  @Test
  @DisplayName("just-over budget: stripping numeric provenance alone brings the full result under")
  void provenanceStripSuffices() {
    McpDeliveryGovernor.ResultView v = view(10, 100, 400);
    int sizeWith = fullBytes(v.render(4, true));
    int sizeWithout = fullBytes(v.render(4, false));
    int budget = sizeWithout + (sizeWith - sizeWithout) / 2;
    assertTrue(sizeWithout <= budget && budget < sizeWith, "fixture must straddle the strip");

    Map<String, Object> out = McpDeliveryGovernor.govern(4, true, budget, MAPPER, v);

    assertEquals(4, results(out).size(), "no result dropped — provenance strip was enough");
    for (Object h : results(out)) {
      assertFalse(((Map<?, ?>) h).containsKey("prov"), "provenance stripped");
    }
    Map<String, Object> g = governor(out);
    assertEquals(true, g.get("provenanceStripped"));
    assertEquals(0, g.get("resultsDropped"));
    assertEquals(4, g.get("originalResultCount"));
    assertTrue(fullBytes(out) <= budget, "governed full result fits the budget");
  }

  // ---- far-over → drop whole tail results, never mid-span ----

  @Test
  @DisplayName("far-over budget: whole tail results dropped lowest-ranked-first; surviving spans intact")
  void farOverDropsTailNeverMidSpan() {
    int structPerHit = 100;
    McpDeliveryGovernor.ResultView v = view(100, structPerHit, 50);
    int budget = fullBytes(v.render(20, false)) / 2; // even after stripping, ~half must go

    Map<String, Object> out = McpDeliveryGovernor.govern(20, true, budget, MAPPER, v);

    int delivered = results(out).size();
    assertTrue(delivered >= 1 && delivered < 20, "some but not all results survive: " + delivered);
    assertTrue(fullBytes(out) <= budget, "governed full result fits the budget");

    Map<String, Object> g = governor(out);
    assertEquals(true, g.get("provenanceStripped"));
    assertEquals(20 - delivered, g.get("resultsDropped"));
    assertEquals(20, g.get("originalResultCount"));
    assertEquals(delivered, g.get("deliveredResultCount"));

    // Never truncate mid-span: every surviving excerpt is delivered whole.
    for (Object h : results(out)) {
      assertEquals(structPerHit, excerptText(h).length(), "surviving excerpt must not be truncated");
    }
    // Tail dropped, not head: survivors are the top ranks (doc-1..doc-delivered).
    assertEquals("docs/doc-1.md", ((Map<?, ?>) results(out).get(0)).get("path"));
    assertEquals(
        "docs/doc-" + delivered + ".md",
        ((Map<?, ?>) results(out).get(delivered - 1)).get("path"),
        "the dropped results are the lowest-ranked tail");
  }

  // ---- floor: never below one result, span never split ----

  @Test
  @DisplayName("single oversized result: delivered whole (never split), provenance stripped, notice fires")
  void singleOversizedResultDeliveredWhole() {
    int structPerHit = 60_000;
    McpDeliveryGovernor.ResultView v = view(0, structPerHit, 100);
    Map<String, Object> out = McpDeliveryGovernor.govern(1, true, 45_000, MAPPER, v);

    assertEquals(1, results(out).size(), "never drop below one result");
    assertEquals(structPerHit, excerptText(results(out).get(0)).length(), "span never split");
    Map<String, Object> g = governor(out);
    assertEquals(true, g.get("provenanceStripped"));
    assertEquals(0, g.get("resultsDropped"));
  }

  // ---- escape hatch: 0 disables ----

  @Test
  @DisplayName("budget 0 disables the governor entirely — huge full result delivered untouched")
  void zeroDisablesGovernor() {
    McpDeliveryGovernor.ResultView v = view(2_000, 2_000, 500);
    Map<String, Object> out = McpDeliveryGovernor.govern(30, true, 0, MAPPER, v);
    assertEquals(30, results(out).size(), "disabled: nothing dropped");
    assertFalse(structured(out).containsKey("governor"), "disabled: no notice");
    assertTrue(((Map<?, ?>) results(out).get(0)).containsKey("prov"), "disabled: provenance retained");
  }

  // ---- determinism: same input → byte-stable governed output ----

  @Test
  @DisplayName("determinism: identical inputs governed to byte-identical serialized full result")
  void deterministicByteStable() {
    int budget = fullBytes(view(80, 80, 40).render(15, false)) / 2;
    Map<String, Object> a = McpDeliveryGovernor.govern(15, true, budget, MAPPER, view(80, 80, 40));
    Map<String, Object> b = McpDeliveryGovernor.govern(15, true, budget, MAPPER, view(80, 80, 40));
    assertEquals(MAPPER.writeValueAsString(a), MAPPER.writeValueAsString(b));
  }

  // ---- notice shape: machine-readable + budget + counts ----

  @Test
  @DisplayName("notice names budget, pre-degradation count, resultsDropped, provenanceStripped")
  void noticeShapeIsMachineReadable() {
    int budget = fullBytes(view(100, 100, 50).render(12, false)) / 2;
    Map<String, Object> out = McpDeliveryGovernor.govern(12, true, budget, MAPPER, view(100, 100, 50));
    Map<String, Object> g = governor(out);
    assertEquals(budget, g.get("budgetBytes"));
    assertEquals(12, g.get("originalResultCount"));
    assertTrue(((Integer) g.get("resultsDropped")) > 0);
    assertEquals(true, g.get("provenanceStripped"));
    assertTrue(g.get("notice") instanceof String s && s.contains(Integer.toString(budget)));
  }

  // ---- the live-composition regression: structuredContent UNDER budget, full result OVER ----

  @Test
  @DisplayName(
      "live composition (fat text block): structuredContent under budget but full result over →"
          + " governor keeps dropping tails until the FULL result fits (a structured-only budget"
          + " would have wrongly declared success)")
  void structuredUnderButFullOverStillDegrades() {
    int budget = 45_000;
    // Small structured tier, large text tier — the exact shape that fooled the structured-only
    // budget: at 30 results structuredContent stays well under 45 KB while the text block pushes the
    // full wire payload past the cliff.
    McpDeliveryGovernor.ResultView v = view(/*textPerHit=*/ 1_600, /*structPerHit=*/ 200, /*provPerHit=*/ 50);

    // Precondition the regression depends on: structuredContent alone is under budget at full 30 —
    // so a governor that budgeted only structuredContent would have delivered all 30 and shipped an
    // over-cliff wire payload.
    assertTrue(
        structuredBytes(v.render(30, true)) < budget,
        "structuredContent alone must be under budget at 30 (the condition that fooled the old code)");
    assertTrue(
        fullBytes(v.render(30, true)) > budget, "the full result at 30 must exceed the budget");

    Map<String, Object> out = McpDeliveryGovernor.govern(30, true, budget, MAPPER, v);

    int delivered = results(out).size();
    assertTrue(delivered < 30, "the governor must degrade even though structuredContent was under budget");
    assertTrue(fullBytes(out) <= budget, "the delivered FULL result must fit the budget");
    Map<String, Object> g = governor(out);
    assertNotNull(g, "notice present");
    assertTrue(((Integer) g.get("resultsDropped")) > 0, "at least one tail result dropped");
    assertEquals(30, g.get("originalResultCount"));
  }

  // ---- integration through McpToolSurface#callSearch at limit 30, detail:true ----

  @Test
  @DisplayName(
      "integration (770 §C): detail:true limit 30 oversized results → the delivered FULL result"
          + " (text + structuredContent + envelope) is under budget, with count reduction + notice,"
          + " never mid-payload truncation")
  void integrationCallSearchDetailLimit30() {
    List<Hit> hits = new ArrayList<>();
    int excerptLen = 1_800;
    String body = "y".repeat(excerptLen);
    for (int i = 1; i <= 30; i++) {
      MatchSpan span = new MatchSpan("content", 0, 9, "diagnostic");
      ExcerptRegion region = new ExcerptRegion(body, 0, excerptLen, 1, List.of(span));
      hits.add(
          new Hit(
              "docs/doc-" + i + ".md",
              1.0d - i * 0.001d,
              Map.of("title", "Doc " + i, "path", "docs/doc-" + i + ".md"),
              List.of("content"),
              List.of(span),
              List.of(region),
              List.of(new HitStage(StageId.FUSION, i, 0.9f, Map.of("cc", 0.9f)))));
    }
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            30L, 30L, 12L, hits, null, null, null, null, null, null, null, null, null);

    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any(), any(EngineContext.class))).thenReturn(canned);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneId.of("UTC")));

    Map<String, Object> result =
        surface.callTool(
            "justsearch_search", Map.of("query", "diagnostic", "limit", 30, "detail", true), "s1", TestRequestContexts.mcp("s1"));

    // The governed quantity is the WHOLE tool result — it must be under the 45 KB default budget.
    assertTrue(
        fullBytes(result) <= 45_000,
        "delivered full result must fit the budget, was " + fullBytes(result));
    int delivered = results(result).size();
    assertTrue(delivered < 30, "governor reduced the result count from 30 to " + delivered);
    Map<String, Object> g = governor(result);
    assertNotNull(g, "governor notice present");
    assertTrue(((Integer) g.get("resultsDropped")) > 0, "at least one tail result dropped");
    assertEquals(30, g.get("originalResultCount"));
    // Never mid-payload / mid-span: every delivered excerpt is intact.
    for (Object h : results(result)) {
      @SuppressWarnings("unchecked")
      Map<String, Object> hm = (Map<String, Object>) h;
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> excerpts = (List<Map<String, Object>>) hm.get("excerpts");
      assertEquals(
          excerptLen, ((String) excerpts.get(0).get("text")).length(),
          "delivered excerpt must not be truncated");
    }
  }
}
