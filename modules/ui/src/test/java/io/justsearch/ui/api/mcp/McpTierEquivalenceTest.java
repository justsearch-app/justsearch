/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.QualitySignals;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.MatchSpan;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 735 W6 — the STANDING tier-equivalence guard. Where {@link McpTierEquivalenceGoldenTest}
 * proves the text tier did not regress, this test proves the structured tier does not fall behind
 * it: every fact the {@code justsearch_search}/{@code justsearch_answer} text renderer draws from
 * its content model ({@link McpSearchResponseContent} / {@link McpAnswerResponseContent}) must
 * also be reachable on {@code structuredContent}.
 *
 * <p>Two-layer mechanism:
 *
 * <ol>
 *   <li><b>Reflective completeness</b> ({@link #searchContentModelFieldsAreAllMapped()} / {@link
 *       #answerContentModelFieldsAreAllMapped()}) — every {@link RecordComponent} of the content
 *       model must have a corresponding {@code Fact} enum entry. Adding a new field to a content
 *       model without adding a matching {@code Fact} entry fails the build immediately: this is
 *       what makes a future text-only addition to the content model impossible to ship silently —
 *       the moment a new fact is added to the model that both renderers already consume, this test
 *       forces the author to also declare it here.
 *   <li><b>Dual-tier presence</b> ({@link #searchFactsAppearInBothTiers()} / {@link
 *       #answerFactsAppearInBothTiers()}) — a "kitchen sink" fixture that triggers every {@code
 *       Fact} at once, asserting each one's text-tier substring AND its structuredContent
 *       counterpart are both present. A structured field silently dropped while the text line
 *       stays (or vice versa) fails here.
 * </ol>
 *
 * <p>Scope note: only {@code justsearch_search} and {@code justsearch_answer} carry a structured
 * tier at all (the other four tools — browse/ingest/status/runtime_manifest — are single-tier,
 * text-only, so there is no gap to guard for them; tempdoc 735 design, "govern-then-remove
 * re-anchored on the structured tier").
 */
@DisplayName("MCP tier equivalence: every content-model fact reaches BOTH tiers (tempdoc 735 W6)")
final class McpTierEquivalenceTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  /** One entry per {@link McpSearchResponseContent} record component (name-matched, reflectively guarded). */
  private enum SearchFact {
    TOTAL_HITS("totalHits"),
    TOOK_MS("tookMs"),
    SHOWN_COUNT("shownCount"),
    TRUNCATED("truncated"),
    HITS("hits"),
    FACETS("facets"),
    // Tempdoc 821 §L.3: the facet-scan truncation flag — distinct from TRUNCATED (result-list
    // truncation) above. Exercised by the kitchen-sink fixture with facetsTruncated=TRUE.
    FACETS_TRUNCATED("facetsTruncated"),
    HINTS("hints"),
    // Tempdoc 789 Phase 2 — flag-gated framings, off by default, so (like ENRICHMENT_HINT and
    // ZERO_RESULT_HINT) they have their own triggering condition and their own dual-tier coverage:
    // McpFramingRenderSnapshotTest asserts each one in BOTH the text tier and structuredContent,
    // with the framing flag on. They cannot be exercised from this class's fixtures, which run the
    // real callTool path and therefore resolve the shipped default (all framings OFF).
    EVIDENCE_HEADER("evidenceHeader"),
    ABSENCE_NOTE("absenceNote");

    final String contentComponent;

    SearchFact(String contentComponent) {
      this.contentComponent = contentComponent;
    }
  }

  /** One entry per {@link McpAnswerResponseContent} record component (name-matched, reflectively guarded). */
  private enum AnswerFact {
    PASSAGES("passages"),
    DISTINCT_DOCS("distinctDocs"),
    CONTEXT_TRUNCATED("contextTruncated"),
    COMPARATIVE_HINT("comparativeHint"),
    ENRICHMENT_HINT("enrichmentHint"),
    ZERO_RESULT_HINT("zeroResultHint"),
    HINTS("hints"),
    // Tempdoc 789 Phase 2 (F2) — flag-gated, off by default; dual-tier coverage lives in
    // McpFramingRenderSnapshotTest#f2AnswerBeforeAfter, which asserts the header in BOTH tiers.
    EVIDENCE_HEADER("evidenceHeader");

    final String contentComponent;

    AnswerFact(String contentComponent) {
      this.contentComponent = contentComponent;
    }
  }

  // ---------------------------------------------------------------------
  // Layer 1: reflective completeness
  // ---------------------------------------------------------------------

  @Test
  @DisplayName(
      "reflective: every McpSearchResponseContent field is mapped to a SearchFact key — a new"
          + " field with no mapping fails here, before it can ship text-only")
  void searchContentModelFieldsAreAllMapped() {
    Set<String> mapped =
        Arrays.stream(SearchFact.values()).map(f -> f.contentComponent).collect(Collectors.toSet());
    for (RecordComponent rc : McpSearchResponseContent.class.getRecordComponents()) {
      assertTrue(
          mapped.contains(rc.getName()),
          "McpSearchResponseContent field '"
              + rc.getName()
              + "' has no SearchFact mapping in McpTierEquivalenceTest — every response-level fact"
              + " must be wired into BOTH the text renderer and structuredContent (tempdoc 735 W6);"
              + " add a SearchFact entry and extend searchFactsAppearInBothTiers().");
    }
  }

  @Test
  @DisplayName(
      "reflective: every McpAnswerResponseContent field is mapped to an AnswerFact key — a new"
          + " field with no mapping fails here, before it can ship text-only")
  void answerContentModelFieldsAreAllMapped() {
    Set<String> mapped =
        Arrays.stream(AnswerFact.values()).map(f -> f.contentComponent).collect(Collectors.toSet());
    for (RecordComponent rc : McpAnswerResponseContent.class.getRecordComponents()) {
      assertTrue(
          mapped.contains(rc.getName()),
          "McpAnswerResponseContent field '"
              + rc.getName()
              + "' has no AnswerFact mapping in McpTierEquivalenceTest — every response-level fact"
              + " must be wired into BOTH the text renderer and structuredContent (tempdoc 735 W6);"
              + " add an AnswerFact entry and extend answerFactsAppearInBothTiers().");
    }
  }

  // ---------------------------------------------------------------------
  // Layer 2: dual-tier presence, one kitchen-sink fixture per tool
  // ---------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static String textOf(Map<String, Object> result) {
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structuredOf(Map<String, Object> result) {
    return (Map<String, Object>) result.get("structuredContent");
  }

  @Test
  @DisplayName(
      "search: every SearchFact is present in BOTH the text tier and structuredContent"
          + " (kitchen-sink fixture: matched terms, facets, hints, truncated)")
  void searchFactsAppearInBothTiers() {
    // 10 shown of 37 total (SHOWN_COUNT / TOTAL_HITS / TRUNCATED), tookMs=15 (TOOK_MS), one hit
    // with a matched term via an excerpt region (HITS), facets present (FACETS), and a productive
    // search always yields at least one hint (HINTS).
    String regionText = "The cavby8 widget assembly guide explains torque settings.";
    ExcerptRegion region =
        new ExcerptRegion(
            regionText, 0, regionText.length(), 1,
            List.of(new MatchSpan("content_preview", 4, 10, "cavby8")));
    Hit matchedHit =
        new Hit(
            "doc-1",
            0.87d,
            Map.of(
                "title", "Widget Assembly",
                "path", "docs/widgets/assembly.md",
                "content_preview", regionText),
            List.of("content_preview"),
            List.of(new MatchSpan("content_preview", 4, 10, "cavby8")),
            List.of(region),
            null);
    List<Hit> hits = new ArrayList<>();
    hits.add(matchedHit);
    for (int i = 0; i < 9; i++) {
      hits.add(
          new Hit(
              "doc-" + i, 0.5d, Map.of("title", "Doc " + i, "path", "docs/doc-" + i + ".md"),
              List.of(), List.of(), List.of(), null));
    }
    Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
    Map<String, Long> sourceFacet = new LinkedHashMap<>();
    sourceFacet.put("docs", 3L);
    facets.put("meta_source", sourceFacet);
    // facetsTruncated=TRUE (7th positional arg) exercises FACETS_TRUNCATED below, alongside FACETS.
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            37L, 37L, 15L, hits, null, facets, Boolean.TRUE, null, null, null, null, null, null);

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
            FIXED_CLOCK);
    Map<String, Object> result = surface.callTool("justsearch_search", Map.of("query", "widget"), "s1", TestRequestContexts.mcp("s1"));

    String text = textOf(result);
    Map<String, Object> structured = structuredOf(result);
    Map<String, Object> coverage = (Map<String, Object>) structured.get("coverage");

    // TOTAL_HITS
    assertTrue(text.contains("Found 37 results"), text);
    assertEquals(37L, coverage.get("totalHits"));
    // TOOK_MS
    assertTrue(text.contains("took 15ms"), text);
    assertEquals(15L, coverage.get("tookMs"));
    // SHOWN_COUNT
    assertTrue(text.contains("showing 10."), text);
    assertEquals(10, coverage.get("shown"));
    // TRUNCATED
    assertEquals(Boolean.TRUE, structured.get("truncated"));
    // HITS (matched terms projected per-hit)
    assertTrue(text.contains("Matched: \"cavby8\" in content_preview"), text);
    List<Map<String, Object>> structuredResults = (List<Map<String, Object>>) structured.get("results");
    assertEquals(List.of("cavby8"), structuredResults.get(0).get("matchedTerms"));
    // FACETS
    assertTrue(
        text.contains("Facets (use as filter values; counts are partial — the scan did not cover"
            + " every match):"),
        text);
    assertTrue(text.contains("meta_source: docs (3)"), text);
    Map<String, Object> structuredFacets = (Map<String, Object>) structured.get("facets");
    assertFalse(structuredFacets.isEmpty());
    assertEquals(Map.of("docs", 3L), structuredFacets.get("meta_source"));
    // FACETS_TRUNCATED
    assertEquals(Boolean.TRUE, structured.get("facetsTruncated"));
    // HINTS
    assertTrue(text.contains("Hints:"), text);
    assertTrue(text.contains("Searched the index in one call."), text);
    List<String> structuredHints = (List<String>) structured.get("hints");
    assertFalse(structuredHints.isEmpty());
    assertTrue(structuredHints.get(0).startsWith("Searched the index in one call."));
  }

  @Test
  @DisplayName(
      "search: facetsTruncated=false adds no qualifier to either tier — same facets block as"
          + " before the tempdoc 821 §L.3 relay, on both the text tier and structuredContent")
  void searchFacetsTruncatedFalseAddsNothing() {
    Hit hit =
        new Hit(
            "doc-1", 0.5d, Map.of("title", "Doc 1", "path", "docs/doc-1.md"),
            List.of(), List.of(), List.of(), null);
    Map<String, Map<String, Long>> facets = new LinkedHashMap<>();
    Map<String, Long> sourceFacet = new LinkedHashMap<>();
    sourceFacet.put("docs", 3L);
    facets.put("meta_source", sourceFacet);
    // facetsTruncated=FALSE (7th positional arg) — the untruncated case.
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(hit), null, facets, Boolean.FALSE, null, null, null, null, null,
            null);

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
            FIXED_CLOCK);
    Map<String, Object> result = surface.callTool("justsearch_search", Map.of("query", "widget"), "s1", TestRequestContexts.mcp("s1"));

    String text = textOf(result);
    Map<String, Object> structured = structuredOf(result);

    // Facets block renders WITHOUT the truncation qualifier.
    assertTrue(text.contains("Facets (use as filter values):"), text);
    assertFalse(text.contains("counts are partial — the scan did not cover every match"), text);
    assertEquals(Boolean.FALSE, structured.get("facetsTruncated"));
  }

  @Test
  @DisplayName(
      "answer: every AnswerFact is present in BOTH the text tier and structuredContent"
          + " (kitchen-sink fixture: multi-doc citations, truncated context)")
  void answerFactsAppearInBothTiers() {
    List<ContextCitation> citations =
        List.of(
            new ContextCitation(
                "doc-1", 0, 2, 0, 40, 0.9f, "excerpt-1", 1, 4, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-1", 1, 2, 40, 80, 0.8f, "excerpt-2", 5, 8, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-2", 0, 1, 0, 30, 0.7f, "excerpt-3", 1, 3, "", 0, ContextInclusion.ABSENT));
    ContextResult result =
        new ContextResult(
            "[From: doc-1]\nexcerpt-1\n\n---\n\n[From: doc-2]\nexcerpt-3",
            3,
            3,
            0,
            citations,
            "HYBRID",
            "HYBRID_AVAILABLE",
            true, // PASSAGES/DISTINCT_DOCS come from citations; CONTEXT_TRUNCATED forced true here
            List.of(),
            new QualitySignals(0.9f, 0.1f, 0.5f, 5, 3));

    DocumentService documents = mock(DocumentService.class);
    when(documents.retrieveContext(any(), any(EngineContext.class))).thenReturn(CompletableFuture.completedFuture(result));
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);
    // Tempdoc 770 §F.5: a non-null knowledgeLookup is wired so the removed facet sidecar WOULD
    // have fired here — the never() verification below is the regression guard against the second
    // full hybrid search returning.
    KnowledgeHttpApiAdapter facetAdapter = mock(KnowledgeHttpApiAdapter.class);
    KnowledgeSearchController facetCtrl = mock(KnowledgeSearchController.class);
    when(facetCtrl.getAdapter()).thenReturn(facetAdapter);

    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> facetCtrl,
            () -> facade,
            FIXED_CLOCK);
    Map<String, Object> callResult =
        surface.callTool("justsearch_answer", Map.of("query", "widget torque"), "s1", TestRequestContexts.mcp("s1"));

    String text = textOf(callResult);
    Map<String, Object> structured = structuredOf(callResult);
    Map<String, Object> coverage = (Map<String, Object>) structured.get("coverage");

    // PASSAGES / DISTINCT_DOCS
    assertTrue(text.contains("Evidence pack: 3 passages from 2 documents"), text);
    assertEquals(3L, coverage.get("passages"));
    assertEquals(2L, coverage.get("documents"));
    // CONTEXT_TRUNCATED
    assertTrue(text.contains("Context was truncated to fit limits."), text);
    assertEquals(Boolean.TRUE, structured.get("truncated"));
    // COMPARATIVE_HINT (part of HINTS too)
    assertTrue(
        text.contains("Assembled evidence from 2 documents in a single retrieval call"), text);
    // Tempdoc 770 §F.5: the answer path fires NO second search — the facet sidecar (a full hybrid
    // search at limit 0, per call, for a 3-field block rendered into the undelivered text tier) is
    // gone, and with it the `facets` fact on both tiers.
    verify(facetAdapter, never()).search(any(), any(EngineContext.class));
    assertFalse(text.contains("--- Top sources & entities ---"), text);
    assertFalse(structured.containsKey("facets"), structured.toString());
    // HINTS (flattened list carries the comparative hint at minimum)
    List<String> structuredHints = (List<String>) structured.get("hints");
    assertFalse(structuredHints.isEmpty());
    assertTrue(structuredHints.get(0).startsWith("Assembled evidence from 2 documents"));
  }

  @Test
  @DisplayName(
      "answer: ENRICHMENT_HINT and ZERO_RESULT_HINT are present in BOTH tiers (not exercised by"
          + " the kitchen-sink fixture above, since low-coverage + zero-chunks are their own"
          + " triggering conditions)")
  void answerEnrichmentAndZeroResultHintsAppearInBothTiers() {
    ContextResult zeroResult =
        new ContextResult(
            "", 0, 0, 0, List.of(), "HYBRID", "NO_CHUNKS_FOUND", false, List.of());

    DocumentService documents = mock(DocumentService.class);
    when(documents.retrieveContext(any(), any(EngineContext.class))).thenReturn(CompletableFuture.completedFuture(zeroResult));
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);

    // Low embedding coverage (< 100) is the enrichment-hint trigger (McpToolSurface#enrichmentHint).
    KnowledgeStatus lowCoverageStatus =
        new KnowledgeStatus(
            "READY", true, 0, 10, 10, 0, "", "", 0, 0, 0, 0, 0, 0, 0, false, "", 0, true, "READY",
            Map.of("embeddingCoveragePercent", 50.0, "spladeCoveragePercent", 100.0));
    KnowledgeHttpApiAdapter facetAdapter = mock(KnowledgeHttpApiAdapter.class);
    when(facetAdapter.status(any(EngineContext.class))).thenReturn(lowCoverageStatus);
    KnowledgeSearchController facetCtrl = mock(KnowledgeSearchController.class);
    when(facetCtrl.getAdapter()).thenReturn(facetAdapter);

    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> facetCtrl,
            () -> facade,
            FIXED_CLOCK);
    Map<String, Object> callResult =
        surface.callTool("justsearch_answer", Map.of("query", "no matches here"), "s1", TestRequestContexts.mcp("s1"));

    String text = textOf(callResult);
    Map<String, Object> structured = structuredOf(callResult);
    List<String> structuredHints = (List<String>) structured.get("hints");

    // ENRICHMENT_HINT
    assertTrue(
        text.contains(
            "Hint: Enrichment in progress — semantic search and entity filters may be limited"
                + " until complete. Check justsearch_status."),
        text);
    assertTrue(
        structuredHints.stream().anyMatch(h -> h.startsWith("Enrichment in progress")),
        structuredHints.toString());
    // ZERO_RESULT_HINT
    assertTrue(
        text.contains("Hint: No results. Try different terms or check justsearch_status."), text);
    assertTrue(
        structuredHints.contains("No results. Try different terms or check justsearch_status."),
        structuredHints.toString());
  }
}
