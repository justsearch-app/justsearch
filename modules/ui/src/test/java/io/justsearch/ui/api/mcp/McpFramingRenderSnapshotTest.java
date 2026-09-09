/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.QualitySignals;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.MatchSpan;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 789 Phase 2 — the rendered before/after deliveries, produced by the SAME renderers
 * production calls ({@code buildSearchContent}/{@code renderSearchText} and {@code
 * buildAnswerContent}/{@code renderAnswerText}), with the framing {@link
 * McpDeliveryFraming.Settings} threaded explicitly rather than read from a global config store.
 *
 * <p>Each test asserts the OFF rendering is byte-identical to the ON rendering minus the framing —
 * the probe's control arm (F0) must be the untouched delivery. {@code McpTierEquivalenceGoldenTest}
 * makes the same guarantee against the captured 0.3.1 byte-goldens; this test states it locally, per
 * framing, so a regression names which framing broke it.
 *
 * <p>The rendered examples these tests pin are reproduced verbatim in the tempdoc 789 Phase-2
 * implementation log and the PR body.
 */
@DisplayName("MCP delivery framings: rendered before/after (tempdoc 789 Phase 2)")
final class McpFramingRenderSnapshotTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneId.of("UTC"));

  /** 771 item (b): these snapshots pin the 789 framings, so carriage stays off in all of them. */
  private static final McpEntityCarriage.Settings CARRIAGE_OFF = McpEntityCarriage.Settings.OFF;

  private static final McpDeliveryFraming.Settings OFF = McpDeliveryFraming.Settings.OFF;
  private static final McpDeliveryFraming.Settings F1 =
      new McpDeliveryFraming.Settings(true, false, false, 400, 0.40);
  private static final McpDeliveryFraming.Settings F2 =
      new McpDeliveryFraming.Settings(false, true, false, 400, 0.40);
  private static final McpDeliveryFraming.Settings F3 =
      new McpDeliveryFraming.Settings(false, false, true, 400, 0.40);
  private static final McpDeliveryFraming.Settings F1_AND_F3 =
      new McpDeliveryFraming.Settings(true, false, true, 400, 0.40);

  /** A surface with no knowledge lookup — the enrichment hint self-suppresses, keeping renders stable. */
  private static McpToolSurface surface() {
    return new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class),
        () -> null,
        () -> null,
        FIXED_CLOCK);
  }

  private static String renderSearch(
      KnowledgeSearchResponse resp,
      String query,
      McpDeliveryFraming.Settings framing,
      long indexedDocs) {
    McpSearchResponseContent content =
        surface().buildSearchContent(
            resp, Map.of("query", query), framing, indexedDocs, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    return McpToolSurface.renderSearchText(resp, content, false);
  }

  // =========================================================================
  // Fixtures
  // =========================================================================

  /** One hit whose delivered excerpt names an indexed person the query does not. */
  private static KnowledgeSearchResponse hopOneResponse() {
    String regionText =
        "Please route the Q3 hedging memo through Vince Kaminski before the Friday close.";
    ExcerptRegion region =
        new ExcerptRegion(
            regionText,
            0,
            regionText.length(),
            1,
            List.of(new MatchSpan("content_preview", 21, 28, "hedging")));
    Hit hit =
        new Hit(
            "docs/memos/q3-hedging.md",
            0.91d,
            Map.of(
                "title", "Q3 hedging memo",
                "path", "docs/memos/q3-hedging.md",
                "content_preview", regionText),
            List.of("content_preview"),
            List.of(new MatchSpan("content_preview", 21, 28, "hedging")),
            List.of(region),
            null);
    Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
    Map<String, Long> persons = new LinkedHashMap<>();
    persons.put("Vince Kaminski", 12L);
    facets.put("entity_persons_raw", persons);
    return new KnowledgeSearchResponse(
        1L, 1L, 8L, List.of(hit), null, facets, null, null, null, null, null, null, null);
  }

  /** A zero-result response — nothing matched at all. */
  private static KnowledgeSearchResponse zeroResponse() {
    return new KnowledgeSearchResponse(
        0L, 0L, 6L, List.of(), null, Map.of(), null, null, null, null, null, null, null);
  }

  private static ContextResult answerResult() {
    List<ContextCitation> citations =
        List.of(
            new ContextCitation(
                "doc-1", 0, 2, 0, 40, 0.9f, "excerpt-1", 1, 4, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-2", 0, 1, 0, 30, 0.7f, "excerpt-2", 1, 3, "", 0, ContextInclusion.ABSENT));
    return new ContextResult(
        "[From: doc-1]\nThe hedging desk escalated to Vince Kaminski.",
        2,
        2,
        0,
        citations,
        "HYBRID",
        "HYBRID_AVAILABLE",
        false,
        List.of(),
        new QualitySignals(0.9f, 0.1f, 0.5f, 4, 2));
  }

  // =========================================================================
  // F1 — continuation
  // =========================================================================

  @Test
  @DisplayName("F1: OFF delivers the excerpt bare; ON appends exactly one continuation line")
  void f1BeforeAfter() {
    KnowledgeSearchResponse resp = hopOneResponse();
    String before = renderSearch(resp, "what happened to the Q3 hedging memo", OFF, -1L);
    String after = renderSearch(resp, "what happened to the Q3 hedging memo", F1, -1L);

    assertFalse(before.contains("note: this excerpt names"), before);
    assertTrue(
        after.contains(
            "    note: this excerpt names \"Vince Kaminski\" — 12 of the documents matching this"
                + " search also reference it. If that is an intermediate fact rather than your"
                + " answer, a follow-up search for it may locate the final answer."),
        after);
    // The control arm is the untouched delivery: ON minus the framing line == OFF.
    assertEquals(before, stripLinesContaining(after, "note: this excerpt names"));
  }

  @Test
  @DisplayName("F1: a query that already names the entity renders identically to OFF")
  void f1SuppressedWhenQueryNamesEntity() {
    KnowledgeSearchResponse resp = hopOneResponse();
    assertEquals(
        renderSearch(resp, "what did Vince Kaminski do with the hedging memo", OFF, -1L),
        renderSearch(resp, "what did Vince Kaminski do with the hedging memo", F1, -1L));
  }

  @Test
  @DisplayName(
      "F1: concise mode omits the continuation from the TEXT tier — the sentence claims 'this"
          + " excerpt names X' and concise does not render the excerpt — but keeps it on the"
          + " structured tier, which carries excerpts unconditionally")
  void f1SuppressedInConciseTextTier() {
    KnowledgeSearchResponse resp = hopOneResponse();
    McpSearchResponseContent content =
        surface()
            .buildSearchContent(
                resp,
                Map.of("query", "what happened to the Q3 hedging memo"),
                F1,
                -1L,
                CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    // The fact is computed either way — the density decision is the renderer's.
    assertTrue(content.hits().get(0).continuation().contains("Vince Kaminski"));

    String conciseText = McpToolSurface.renderSearchText(resp, content, true);
    assertFalse(conciseText.contains("Preview:"), conciseText);
    assertFalse(conciseText.contains("note: this excerpt names"), conciseText);

    String verboseText = McpToolSurface.renderSearchText(resp, content, false);
    assertTrue(verboseText.contains("note: this excerpt names"), verboseText);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> structured =
        (List<Map<String, Object>>)
            McpEvidenceProjection.searchEvidence(resp, content, false).get("results");
    assertTrue(((String) structured.get(0).get("continuation")).contains("Vince Kaminski"));
  }

  @Test
  @DisplayName("F1: the continuation reaches structuredContent too, not just the text tier")
  void f1ReachesStructuredTier() {
    KnowledgeSearchResponse resp = hopOneResponse();
    McpSearchResponseContent content =
        surface()
            .buildSearchContent(resp, Map.of("query", "hedging memo"), F1, -1L, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    Map<String, Object> structured = McpEvidenceProjection.searchEvidence(resp, content, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
    assertTrue(((String) results.get(0).get("continuation")).contains("Vince Kaminski"));

    McpSearchResponseContent offContent =
        surface().buildSearchContent(resp, Map.of("query", "hedging memo"), OFF, -1L, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    Map<String, Object> offStructured =
        McpEvidenceProjection.searchEvidence(resp, offContent, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> offResults =
        (List<Map<String, Object>>) offStructured.get("results");
    assertFalse(offResults.get(0).containsKey("continuation"));
  }

  // =========================================================================
  // F2 — evidence, not answer
  // =========================================================================

  @Test
  @DisplayName("F2 (search): ON leads with the retrieval-evidence header; OFF has no header")
  void f2SearchBeforeAfter() {
    KnowledgeSearchResponse resp = hopOneResponse();
    String before = renderSearch(resp, "hedging memo", OFF, -1L);
    String after = renderSearch(resp, "hedging memo", F2, -1L);

    assertFalse(before.startsWith("Retrieval evidence"), before);
    assertTrue(
        after.startsWith(
            "Retrieval evidence — 1 document matches on \"hedging\". These are lexical and semantic"
                + " matches to your query, not verified answers to your question — read the excerpts"
                + " and judge for yourself whether they answer it.\n\n"),
        after);
    assertEquals(before, after.substring(after.indexOf("\n\n") + 2));

    // Tier equivalence (735 G3): the header must reach structuredContent too, or a client that
    // delivers the structured tier would silently sit outside the probe arm.
    McpSearchResponseContent onContent =
        surface().buildSearchContent(resp, Map.of("query", "hedging memo"), F2, -1L, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    assertTrue(
        ((String) McpEvidenceProjection.searchEvidence(resp, onContent, false).get("evidenceHeader"))
            .startsWith("Retrieval evidence"));
    McpSearchResponseContent offContent =
        surface().buildSearchContent(resp, Map.of("query", "hedging memo"), OFF, -1L, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    assertFalse(
        McpEvidenceProjection.searchEvidence(resp, offContent, false)
            .containsKey("evidenceHeader"));
  }

  @Test
  @DisplayName("F2 (answer): ON prepends the evidence header above the existing Evidence pack line")
  void f2AnswerBeforeAfter() {
    ContextResult result = answerResult();
    McpAnswerResponseContent offContent =
        surface().buildAnswerContent(result, "who escalated the hedge", OFF, TestRequestContexts.mcp("s1"));
    McpAnswerResponseContent onContent =
        surface().buildAnswerContent(result, "who escalated the hedge", F2, TestRequestContexts.mcp("s1"));
    String before = McpToolSurface.renderAnswerText(result, offContent, false, "q");
    String after = McpToolSurface.renderAnswerText(result, onContent, false, "q");

    assertTrue(before.startsWith("Evidence pack: 2 passages from 2 documents"), before);
    assertTrue(
        after.startsWith(
            "Retrieval evidence — 2 passages from 2 documents, selected by lexical and semantic"
                + " match to your query. This is retrieved evidence, not a verified answer to your"
                + " question — the passages may be relevant without containing the answer.\n\n"),
        after);
    assertEquals(before, after.substring(after.indexOf("\n\n") + 2));

    Map<String, Object> structured = McpEvidenceProjection.answerEvidence(result, onContent);
    assertTrue(((String) structured.get("evidenceHeader")).startsWith("Retrieval evidence"));
    assertFalse(
        McpEvidenceProjection.answerEvidence(result, offContent).containsKey("evidenceHeader"));
  }

  // =========================================================================
  // F3 — calibrated absence
  // =========================================================================

  @Test
  @DisplayName("F3: a zero-result delivery gains coverage + absence-is-not-evidence framing")
  void f3ZeroResultBeforeAfter() {
    KnowledgeSearchResponse resp = zeroResponse();
    String before = renderSearch(resp, "quarterly hedging policy", OFF, 10_432L);
    String after = renderSearch(resp, "quarterly hedging policy", F3, 10_432L);

    assertTrue(before.contains("Found 0 results"), before);
    assertFalse(before.contains("Absence of results"), before);
    assertTrue(
        after.contains(
            "10432 documents are indexed and were searched for \"quarterly hedging policy\". No"
                + " document matched. Absence of results is not evidence of absence: the index may"
                + " phrase the fact differently, the document may not be indexed, or the match may"
                + " sit in a field this query did not reach. Before concluding the information does"
                + " not exist, try alternate phrasings or narrower terms; if you have native file"
                + " tools, reading or grepping the source directory directly will settle it."),
        after);

    // Tier equivalence (735 G3): the absence note reaches structuredContent too.
    McpSearchResponseContent onContent =
        surface().buildSearchContent(
            resp, Map.of("query", "quarterly hedging policy"), F3, 10_432L, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    assertTrue(
        ((String) McpEvidenceProjection.searchEvidence(resp, onContent, false).get("absenceNote"))
            .contains("Absence of results is not evidence of absence"));
    McpSearchResponseContent offContent =
        surface()
            .buildSearchContent(
                resp, Map.of("query", "quarterly hedging policy"), OFF, 10_432L, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
    assertFalse(
        McpEvidenceProjection.searchEvidence(resp, offContent, false).containsKey("absenceNote"));
  }

  @Test
  @DisplayName("F3: a substantive delivery is untouched — the framing is absence-specific")
  void f3DoesNotTouchSubstantiveDeliveries() {
    KnowledgeSearchResponse resp = substantialResponse();
    assertEquals(
        renderSearch(resp, "hedging", OFF, 10_432L), renderSearch(resp, "hedging", F3, 10_432L));
  }

  @Test
  @DisplayName("F3: an unavailable doc count still frames absence, without inventing a number")
  void f3WithoutDocCount() {
    String after = renderSearch(zeroResponse(), "widget torque", F3, -1L);
    assertTrue(after.contains("The index was searched for \"widget torque\""), after);
    assertFalse(after.contains("-1 documents"), after);
  }

  // =========================================================================
  // Composition
  // =========================================================================

  @Test
  @DisplayName("F1+F3 compose: both framings render, neither suppresses the other")
  void f1AndF3Compose() {
    // A hit whose delivered body is under the 400-byte floor AND names an off-query entity.
    KnowledgeSearchResponse resp = hopOneResponse();
    String both = renderSearch(resp, "what happened to the Q3 memo", F1_AND_F3, 10_432L);
    assertTrue(both.contains("note: this excerpt names \"Vince Kaminski\""), both);
    assertTrue(both.contains("Absence of results is not evidence of absence"), both);
    assertTrue(both.contains("under the 400-byte floor"), both);
  }

  @Test
  @DisplayName("all framings OFF renders byte-identically to a surface that never knew about them")
  void allOffIsTheControlArm() {
    KnowledgeSearchResponse resp = hopOneResponse();
    String off = renderSearch(resp, "hedging memo", OFF, -1L);
    assertFalse(off.contains("Retrieval evidence"), off);
    assertFalse(off.contains("note: this excerpt names"), off);
    assertFalse(off.contains("Absence of results"), off);
  }

  /** A response whose delivered body comfortably clears the F3 thin floor. */
  private static KnowledgeSearchResponse substantialResponse() {
    List<Hit> hits = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String text =
          "Section "
              + i
              + ": the hedging desk reconciles positions daily against the counterparty ledger, and"
              + " the reconciliation output is archived for audit review each quarter.";
      hits.add(
          new Hit(
              "docs/ops/hedging-" + i + ".md",
              0.8d,
              Map.of(
                  "title", "Hedging operations " + i,
                  "path", "docs/ops/hedging-" + i + ".md",
                  "content_preview", text),
              List.of("content_preview"),
              List.of(new MatchSpan("content_preview", 13, 20, "hedging")),
              List.of(
                  new ExcerptRegion(
                      text,
                      0,
                      text.length(),
                      1,
                      List.of(new MatchSpan("content_preview", 13, 20, "hedging")))),
              null));
    }
    return new KnowledgeSearchResponse(
        5L, 5L, 9L, hits, null, Map.of(), null, null, null, null, null, null, null);
  }

  private static String stripLinesContaining(String text, String needle) {
    StringBuilder sb = new StringBuilder();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].contains(needle)) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(lines[i]);
    }
    return sb.toString();
  }
}
