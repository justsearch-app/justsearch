/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 725 (design #2, increment W1) — pins the {@code justsearch_search} text block's
 * legibility rendering (informative-term-anchored preview, "Matched:" / "Match basis:" lines, the
 * coverage-extension summary, the once-per-response degradation note) plus the corresponding
 * {@code structuredContent} additions ({@code matchedTerms}/{@code matchedFields}/{@code excerpts}
 * per hit, response-level {@code degradation}). This surface had ZERO pinning tests before this
 * increment. Also unit-tests the pure {@link McpSearchResultFormatter} helpers directly.
 *
 * <p>Registered as the {@code mcp-search-text-degradation} surface in {@code
 * governance/execution-surfaces.v1.json} (guards McpToolSurface's direct read of {@link
 * SearchTrace.Degradation}).
 */
@DisplayName("McpToolSurface justsearch_search: response legibility (tempdoc 725 W1)")
final class McpSearchTraceLegibilityTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  // ---------------------------------------------------------------------
  // Fixtures / helpers
  // ---------------------------------------------------------------------

  private static Hit minimalHit(String id) {
    return new Hit(id, 0.5d, Map.of(), List.of(), List.of(), List.of(), null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invokeSearch(KnowledgeSearchResponse canned) {
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
    return surface.callTool("justsearch_search", Map.of("query", "widget"), "s1", TestRequestContexts.mcp("s1"));
  }

  private static String textOf(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structuredOf(Map<String, Object> result) {
    return (Map<String, Object>) result.get("structuredContent");
  }

  // ---------------------------------------------------------------------
  // (a) lexical hit: excerpt-region-anchored preview + Matched line
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(a) excerpt-region hit renders windowed preview containing the payload + Matched line")
  void lexicalHitRendersExcerptPreviewAndMatchedLine() {
    String regionText = "The cavby8 widget assembly guide explains torque settings.";
    MatchSpan nestedSpan = new MatchSpan("content_preview", 4, 10, "cavby8");
    ExcerptRegion region = new ExcerptRegion(regionText, 0, regionText.length(), 1, List.of(nestedSpan));
    Hit hit =
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
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 12L, List.of(hit), null, null, null, null, null, null, null, null, null);

    Map<String, Object> result = invokeSearch(canned);
    String text = textOf(result);

    assertTrue(text.contains("[1] Widget Assembly (score: 0.87)"), text);
    assertTrue(text.contains("    Path: docs/widgets/assembly.md"), text);
    assertTrue(text.contains("    Preview: " + regionText), text);
    assertTrue(text.contains("    Matched: \"cavby8\" in content_preview"), text);
    assertFalse(text.contains("Match basis: semantic similarity"), text);
    assertFalse(text.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), text);

    Map<String, Object> structured = structuredOf(result);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
    Map<String, Object> h = results.get(0);
    assertEquals(List.of("cavby8"), h.get("matchedTerms"));
    assertEquals(List.of("content_preview"), h.get("matchedFields"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> excerpts = (List<Map<String, Object>>) h.get("excerpts");
    assertEquals(1, excerpts.size());
    assertEquals(regionText, excerpts.get(0).get("text"));
    assertEquals(0, excerpts.get(0).get("startChar"));
    assertEquals(regionText.length(), excerpts.get(0).get("endChar"));
  }

  // ---------------------------------------------------------------------
  // (b) 20x "the" noisy fixture -> filters to empty -> semantic fallback
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(b) 20x noisy stopword spans filter to empty: semantic-fallback line + head-truncated preview")
  void noisyStopwordSpansFilterToEmptyAndFallBack() {
    List<MatchSpan> noisySpans = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      noisySpans.add(new MatchSpan("content_preview", i, i + 3, "the"));
    }
    String longPreview =
        "abcdefghij".repeat(25); // 250 chars, > 200, exercises the pre-existing head-truncation path
    Hit hit =
        new Hit(
            "druker7",
            0.42d,
            Map.of(
                "title", "Paraphrase Result",
                "path", "docs/druker7.md",
                "content_preview", longPreview),
            List.of("content_preview"),
            noisySpans,
            List.of(),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 9L, List.of(hit), null, null, null, null, null, null, null, null, null);

    Map<String, Object> result = invokeSearch(canned);
    String text = textOf(result);

    assertTrue(
        text.contains("    Match basis: semantic similarity (no distinctive term overlap)"), text);
    assertFalse(text.contains("Matched:"), text);
    String expectedPreview = longPreview.substring(0, 200) + "...";
    assertTrue(text.contains("    Preview: " + expectedPreview), text);
    assertFalse(text.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), text);

    Map<String, Object> structured = structuredOf(result);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
    assertFalse(results.get(0).containsKey("matchedTerms"));
  }

  // ---------------------------------------------------------------------
  // (c) degradation line present ONLY when degraded, both directions
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(c) degradation note appears when vectorBlocked is true")
  void degradationNoteWhenVectorBlocked() {
    SearchTrace.Degradation degradation =
        new SearchTrace.Degradation(true, "FINGERPRINT_MISMATCH", false, null, true, null);
    SearchTrace trace = new SearchTrace(SearchTrace.SCHEMA_VERSION, "HYBRID", null, null, degradation, List.of());
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(0L, 0L, 3L, List.of(), null, null, null, null, null, null, null, trace, null);

    String text = textOf(invokeSearch(canned));

    assertTrue(
        text.contains(
            "Note: semantic ranking degraded (FINGERPRINT_MISMATCH); results may be"
                + " keyword-ranked only."),
        text);

    Map<String, Object> structured = structuredOf(invokeSearch(canned));
    @SuppressWarnings("unchecked")
    Map<String, Object> deg = (Map<String, Object>) structured.get("degradation");
    assertEquals(Boolean.TRUE, deg.get("vectorBlocked"));
    assertEquals(Boolean.FALSE, deg.get("hybridFallback"));
    assertEquals(List.of("FINGERPRINT_MISMATCH"), deg.get("reasons"));
  }

  @Test
  @DisplayName("(c) degradation note appears when hybridFallback is true")
  void degradationNoteWhenHybridFallback() {
    SearchTrace.Degradation degradation =
        new SearchTrace.Degradation(false, null, true, "NO_EMBEDDING_SERVICE", false, "absent");
    SearchTrace trace = new SearchTrace(SearchTrace.SCHEMA_VERSION, "HYBRID", null, null, degradation, List.of());
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(0L, 0L, 3L, List.of(), null, null, null, null, null, null, null, trace, null);

    String text = textOf(invokeSearch(canned));

    assertTrue(
        text.contains(
            "Note: semantic ranking degraded (NO_EMBEDDING_SERVICE); results may be"
                + " keyword-ranked only."),
        text);
  }

  @Test
  @DisplayName("(c) no degradation note when neither flag is true, even with a present Degradation")
  void noDegradationNoteWhenFlagsFalse() {
    SearchTrace.Degradation degradation = new SearchTrace.Degradation(false, null, false, null, true, null);
    SearchTrace trace = new SearchTrace(SearchTrace.SCHEMA_VERSION, "HYBRID", null, null, degradation, List.of());
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(0L, 0L, 3L, List.of(), null, null, null, null, null, null, null, trace, null);

    String text = textOf(invokeSearch(canned));

    assertFalse(text.contains("semantic ranking degraded"), text);
  }

  @Test
  @DisplayName("(c) no degradation note when trace is null (null-safe)")
  void noDegradationNoteWhenTraceNull() {
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(0L, 0L, 3L, List.of(), null, null, null, null, null, null, null, null, null);

    String text = textOf(invokeSearch(canned));

    assertFalse(text.contains("semantic ranking degraded"), text);
    Map<String, Object> structured = structuredOf(invokeSearch(canned));
    assertNull(structured.get("degradation"));
  }

  // ---------------------------------------------------------------------
  // (d) coverage extension only when totalHits > shown
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(d) summary extends with 'showing N' only when totalHits exceeds the shown count")
  void coverageExtensionOnlyWhenTruncated() {
    List<Hit> tenHits = new ArrayList<>();
    for (int i = 0; i < 10; i++) tenHits.add(minimalHit("doc-" + i));
    KnowledgeSearchResponse truncated =
        new KnowledgeSearchResponse(37L, 37L, 12L, tenHits, null, null, null, null, null, null, null, null, null);
    String truncatedText = textOf(invokeSearch(truncated));
    assertTrue(truncatedText.contains("Found 37 results (took 12ms); showing 10."), truncatedText);

    List<Hit> fiveHits = new ArrayList<>();
    for (int i = 0; i < 5; i++) fiveHits.add(minimalHit("doc-" + i));
    KnowledgeSearchResponse exact =
        new KnowledgeSearchResponse(5L, 5L, 8L, fiveHits, null, null, null, null, null, null, null, null, null);
    String exactText = textOf(invokeSearch(exact));
    assertTrue(exactText.contains("Found 5 results (took 8ms)."), exactText);
    assertFalse(exactText.contains("showing"), exactText);
  }

  // ---------------------------------------------------------------------
  // (e) matched terms with embedded control chars are sanitized (echo-injection shape)
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(e) matched terms with embedded newline/control chars are sanitized before echo")
  void matchedTermsAreSanitized() {
    String dirtyTerm = "cav\r\nby8"; // embedded CR/LF control chars
    Hit hit =
        new Hit(
            "doc-9",
            0.6d,
            Map.of("title", "Sanitize Target", "path", "docs/sanitize.md", "content_preview", ""),
            List.of("content_preview"),
            List.of(new MatchSpan("content_preview", 0, dirtyTerm.length(), dirtyTerm)),
            List.of(),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(1L, 1L, 4L, List.of(hit), null, null, null, null, null, null, null, null, null);

    Map<String, Object> result = invokeSearch(canned);
    String text = textOf(result);

    assertTrue(text.contains("    Matched: \"cavby8\" in content_preview"), text);
    assertFalse(text.contains("\r"), text);
    assertFalse(text.contains("cav\r\nby8"), text);

    Map<String, Object> structured = structuredOf(result);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
    assertEquals(List.of("cavby8"), results.get(0).get("matchedTerms"));
  }

  // ---------------------------------------------------------------------
  // (f) truncation remedy present when window is cut, absent when it is not
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(f) truncation remedy appears only when the excerpt-region window is actually cut")
  void truncationRemedyOnlyWhenWindowCut() {
    String filler = "x".repeat(150);
    String longFiller = "y".repeat(400);
    // Window = [termStart, termStart + 300): the leading filler is cut on the left, and the tail
    // marker sits well beyond termStart + 300 so it is cut on the right too.
    String longRegionText = filler + "zyxq99" + longFiller + "tail-marker-not-in-window";
    int termStart = filler.length();
    MatchSpan nestedSpan = new MatchSpan("content_preview", termStart, termStart + 6, "zyxq99");
    ExcerptRegion region =
        new ExcerptRegion(longRegionText, 0, longRegionText.length(), 1, List.of(nestedSpan));
    Hit cutHit =
        new Hit(
            "doc-long",
            0.5d,
            Map.of("title", "Long Region", "path", "docs/long.md"),
            List.of("content_preview"),
            List.of(nestedSpan),
            List.of(region),
            null);
    KnowledgeSearchResponse cutResp =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(cutHit), null, null, null, null, null, null, null, null, null);
    String cutText = textOf(invokeSearch(cutResp));
    assertTrue(cutText.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), cutText);
    assertFalse(cutText.contains("tail-marker-not-in-window"), cutText);

    String shortRegionText = "zyxq99 short region, well under the window cap.";
    MatchSpan shortSpan = new MatchSpan("content_preview", 0, 6, "zyxq99");
    ExcerptRegion shortRegion =
        new ExcerptRegion(shortRegionText, 0, shortRegionText.length(), 1, List.of(shortSpan));
    Hit shortHit =
        new Hit(
            "doc-short",
            0.5d,
            Map.of("title", "Short Region", "path", "docs/short.md"),
            List.of("content_preview"),
            List.of(shortSpan),
            List.of(shortRegion),
            null);
    KnowledgeSearchResponse shortResp =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(shortHit), null, null, null, null, null, null, null, null, null);
    String shortText = textOf(invokeSearch(shortResp));
    assertTrue(shortText.contains("    Preview: " + shortRegionText), shortText);
    assertFalse(shortText.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), shortText);
  }

  // ---------------------------------------------------------------------
  // Pure McpSearchResultFormatter unit coverage
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("filterInformative: dedups by lowercase term, drops short/stopword terms, caps at 4")
  void filterInformativeDedupsDropsAndCaps() {
    List<MatchSpan> spans =
        List.of(
            new MatchSpan("content_preview", 0, 3, "the"), // stopword — dropped
            new MatchSpan("content_preview", 4, 6, "of"), // length <= 3 — dropped
            new MatchSpan("content_preview", 8, 14, "widget"),
            new MatchSpan("title", 0, 6, "Widget"), // dup of "widget" (lowercase) — dropped
            new MatchSpan("content_preview", 20, 26, "torque"),
            new MatchSpan("content_preview", 30, 36, "flange"),
            new MatchSpan("content_preview", 40, 46, "socket"),
            new MatchSpan("content_preview", 50, 56, "wrench")); // 5th distinct — capped out

    List<MatchSpan> informative = McpSearchResultFormatter.filterInformative(spans);
    List<String> terms = McpSearchResultFormatter.informativeTerms(informative);

    assertEquals(List.of("widget", "torque", "flange", "socket"), terms);
  }

  @Test
  @DisplayName("selectBestRegion: picks the region with the most informative nested spans, ties go first")
  void selectBestRegionPicksMostInformativeTiesFirst() {
    ExcerptRegion noInformative =
        new ExcerptRegion("the of at", 0, 9, 1, List.of(new MatchSpan("f", 0, 3, "the")));
    ExcerptRegion oneInformative =
        new ExcerptRegion("widget guide", 0, 12, 2, List.of(new MatchSpan("f", 0, 6, "widget")));
    ExcerptRegion tiedFirst =
        new ExcerptRegion("torque flange", 0, 13, 3, List.of(new MatchSpan("f", 0, 6, "torque")));
    ExcerptRegion tiedSecond =
        new ExcerptRegion("socket wrench", 0, 13, 4, List.of(new MatchSpan("f", 0, 6, "socket")));

    assertEquals(oneInformative, McpSearchResultFormatter.selectBestRegion(List.of(noInformative, oneInformative)));
    // tiedFirst and tiedSecond both carry exactly 1 informative span — first wins.
    assertEquals(tiedFirst, McpSearchResultFormatter.selectBestRegion(List.of(tiedFirst, tiedSecond)));
    assertNull(McpSearchResultFormatter.selectBestRegion(List.of()));
    assertNull(McpSearchResultFormatter.selectBestRegion(null));
  }

  @Test
  @DisplayName("windowStartingAt / windowCentered: no truncation when text already fits")
  void windowHelpersNoTruncationWhenTextFits() {
    String text = "short text under any cap";
    McpSearchResultFormatter.Window startWindow =
        McpSearchResultFormatter.windowStartingAt(text, 5, 300);
    assertEquals(text, startWindow.text());
    assertFalse(startWindow.truncated());

    McpSearchResultFormatter.Window centerWindow =
        McpSearchResultFormatter.windowCentered(text, 5, 300);
    assertEquals(text, centerWindow.text());
    assertFalse(centerWindow.truncated());
  }

  @Test
  @DisplayName("bestWindow: prefers the window covering the most spans, ties resolve to the later span")
  void bestWindowPrefersHighestCoverageTiesLater() {
    String text = "z".repeat(1000);
    MatchSpan early = new MatchSpan("content_preview", 10, 16, "aaaaaa");
    MatchSpan midA = new MatchSpan("content_preview", 500, 506, "bbbbbb");
    MatchSpan midB = new MatchSpan("content_preview", 520, 526, "cccccc");

    // early's centered window [0,100) covers only itself (count 1). midA's centered window
    // [450,550) covers midA + midB (count 2). midB's centered window [470,570) also covers
    // midA + midB (count 2) — a tie with midA's window, broken toward the LATER span (midB).
    McpSearchResultFormatter.Window window =
        McpSearchResultFormatter.bestWindow(text, List.of(early, midA, midB), 100);

    assertEquals(text.substring(470, 570), window.text());
    assertTrue(window.truncated());
  }

  @Test
  @DisplayName("bestWindow: null/empty span list returns null so callers fall back to a head window")
  void bestWindowReturnsNullForEmptySpans() {
    assertNull(McpSearchResultFormatter.bestWindow("some text", List.of(), 100));
    assertNull(McpSearchResultFormatter.bestWindow("some text", null, 100));
  }

  @Test
  @DisplayName("sanitize: strips control characters including CR/LF, leaves ordinary text untouched")
  void sanitizeStripsControlChars() {
    assertEquals("cavby8", McpSearchResultFormatter.sanitize("cav\r\nby8"));
    assertEquals("plain term", McpSearchResultFormatter.sanitize("plain term"));
    assertEquals("", McpSearchResultFormatter.sanitize(null));
  }

  @Test
  @DisplayName("sanitize: strips embedded double quotes and backslashes (tempdoc 725 review fix)")
  void sanitizeStripsQuotesAndBackslashes() {
    assertEquals("hello", McpSearchResultFormatter.sanitize("he\"llo"));
    assertEquals("hello", McpSearchResultFormatter.sanitize("he\\llo"));
    assertEquals("hello", McpSearchResultFormatter.sanitize("h\"e\\l\"lo"));
  }

  // ---------------------------------------------------------------------
  // (g) a quote-bearing corpus term cannot escape its quotes in the Matched: line
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(g) matched term containing a double quote cannot break out of the Matched: \"...\" line")
  void matchedTermWithQuoteCannotEscapeQuotedSpan() {
    String quoteBearingTerm = "he\"llo"; // corpus term embedding a literal double quote
    Hit hit =
        new Hit(
            "doc-quote",
            0.6d,
            Map.of("title", "Quote Target", "path", "docs/quote.md", "content_preview", ""),
            List.of("content_preview"),
            List.of(new MatchSpan("content_preview", 0, quoteBearingTerm.length(), quoteBearingTerm)),
            List.of(),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(1L, 1L, 4L, List.of(hit), null, null, null, null, null, null, null, null, null);

    Map<String, Object> result = invokeSearch(canned);
    String text = textOf(result);

    assertTrue(text.contains("    Matched: \"hello\" in content_preview"), text);
    assertFalse(text.contains("\"he\"llo\""), text);
    assertFalse(text.contains(quoteBearingTerm), text);

    Map<String, Object> structured = structuredOf(result);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
    assertEquals(List.of("hello"), results.get(0).get("matchedTerms"));
  }

  // ---------------------------------------------------------------------
  // (h) live-validated cavby8 shape: a title-echo occurrence must not starve the payload
  // occurrence of the same term out of the preview window (tempdoc 725 W2)
  // ---------------------------------------------------------------------

  @Test
  @DisplayName(
      "(h) live cavby8 shape: preview window covers the payload occurrence, not just the title echo")
  void previewWindowCoversPayloadOccurrenceOverTitleEcho() {
    String titleEcho = "The cavby8 widget assembly guide.";
    String filler = "Unrelated filler content padding out the region text. ".repeat(7);
    String payloadSentence = "Cavby8 is associated with azure vellum 0008.";
    String regionText = titleEcho + " " + filler + payloadSentence;

    int titleSpanStart = regionText.indexOf("cavby8");
    int payloadSpanStart = regionText.indexOf("Cavby8 is associated");
    assertTrue(titleSpanStart >= 0, regionText);
    assertTrue(payloadSpanStart > titleSpanStart, regionText);
    // Sanity: the payload sits past the old windowStartingAt(titleSpanStart, 300) reach — this is
    // exactly the shape that cut the payload off before this fix.
    assertTrue(
        payloadSpanStart > titleSpanStart + McpSearchResultFormatter.REGION_WINDOW_CHARS, regionText);

    MatchSpan titleSpan = new MatchSpan("content_preview", titleSpanStart, titleSpanStart + 6, "cavby8");
    MatchSpan payloadSpan =
        new MatchSpan("content_preview", payloadSpanStart, payloadSpanStart + 6, "cavby8");
    ExcerptRegion region =
        new ExcerptRegion(regionText, 0, regionText.length(), 1, List.of(titleSpan, payloadSpan));
    Hit hit =
        new Hit(
            "cavby8",
            0.91d,
            Map.of("title", "Cavby8 Doc", "path", "docs/cavby8.md"),
            List.of("content_preview"),
            List.of(titleSpan, payloadSpan),
            List.of(region),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 10L, List.of(hit), null, null, null, null, null, null, null, null, null);

    String text = textOf(invokeSearch(canned));

    assertTrue(text.contains(payloadSentence), text);
    assertTrue(text.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), text);
  }

  // ---------------------------------------------------------------------
  // (i) tempdoc 732 issue 7: buildHitPreview strips control chars but must NOT reuse the
  // quote/backslash-stripping sanitize() -- excerpt/preview text is not quote-delimited, so a
  // literal quote or backslash (routine in legal-text corpora) must survive verbatim. Control
  // char and backslash characters below are built via char-code concatenation, not escape
  // literals, to keep the corpus-fidelity fixtures unambiguous.
  // ---------------------------------------------------------------------

  @Test
  @DisplayName(
      "(i) buildHitPreview strips an embedded control char from the excerpt-region preview")
  void previewStripsControlCharFromExcerptRegion() {
    char controlChar = (char) 7;
    String cleanText = "The cavby8 widget assembly guide explains torque settings.";
    String regionText =
        "The cavby8 widget assembly guide explains torque" + controlChar + " settings.";
    MatchSpan nestedSpan = new MatchSpan("content_preview", 4, 10, "cavby8");
    ExcerptRegion region =
        new ExcerptRegion(regionText, 0, regionText.length(), 1, List.of(nestedSpan));
    Hit hit =
        new Hit(
            "doc-ctrl",
            0.8d,
            Map.of("title", "Widget Assembly", "path", "docs/widgets/assembly.md"),
            List.of("content_preview"),
            List.of(nestedSpan),
            List.of(region),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 12L, List.of(hit), null, null, null, null, null, null, null, null, null);

    String text = textOf(invokeSearch(canned));

    assertFalse(text.contains(String.valueOf(controlChar)), text);
    assertTrue(text.contains("    Preview: " + cleanText), text);
  }

  @Test
  @DisplayName(
      "(i) buildHitPreview preserves literal quotes and backslashes in the excerpt-region"
          + " preview (corpus fidelity -- must NOT reuse the quote-stripping sanitize())")
  void previewPreservesQuotesAndBackslashesInExcerptRegion() {
    char backslash = (char) 92;
    String regionText =
        "The court cited \"Roe v. Wade\" and the C:" + backslash + "legal" + backslash
            + "path reference.";
    MatchSpan nestedSpan = new MatchSpan("content_preview", 4, 9, "court");
    ExcerptRegion region =
        new ExcerptRegion(regionText, 0, regionText.length(), 1, List.of(nestedSpan));
    Hit hit =
        new Hit(
            "doc-quote-preview",
            0.8d,
            Map.of("title", "Legal Citation", "path", "docs/legal/citation.md"),
            List.of("content_preview"),
            List.of(nestedSpan),
            List.of(region),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 12L, List.of(hit), null, null, null, null, null, null, null, null, null);

    String text = textOf(invokeSearch(canned));

    assertTrue(text.contains("    Preview: " + regionText), text);
  }

  @Test
  @DisplayName(
      "(i) buildHitPreview strips control chars from the content_preview-field fallback path"
          + " (no excerptRegions) and preserves quotes/backslashes there too")
  void previewFieldFallbackStripsControlCharsAndPreservesQuotes() {
    char controlChar = (char) 7;
    char backslash = (char) 92;
    String cleanText = "A \"quoted\" C:" + backslash + "path value with a control char.";
    String fieldValue =
        "A \"quoted\" C:" + backslash + "path value" + controlChar + " with a control char.";
    Hit hit =
        new Hit(
            "doc-field-fallback",
            0.7d,
            Map.of(
                "title", "Field Fallback",
                "path", "docs/field-fallback.md",
                "content_preview", fieldValue),
            List.of(),
            List.of(),
            List.of(),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 4L, List.of(hit), null, null, null, null, null, null, null, null, null);

    String text = textOf(invokeSearch(canned));

    assertFalse(text.contains(String.valueOf(controlChar)), text);
    assertTrue(text.contains("    Preview: " + cleanText), text);
  }

  // ---------------------------------------------------------------------
  // (j) refute-first review of the tempdoc 732 delta: 2 of the 7 stripControlChars wrap sites in
  // buildHitPreview were still unpinned -- the content_preview informative-window branch (bestWindow
  // over previewOccurrences, no excerptRegions) and the >200-char head-substring fallback (no
  // informative spans at all). Both use the same char-code-concatenation fixture style as (i) above
  // so a literal quote/backslash is unambiguous in source.
  // ---------------------------------------------------------------------

  @Test
  @DisplayName(
      "(j) buildHitPreview strips a control char from the content_preview informative-window"
          + " path (no excerptRegions, informative matchSpans) and preserves quotes/backslashes")
  void previewFieldWindowStripsControlCharsAndPreservesQuotes() {
    char controlChar = (char) 7;
    char backslash = (char) 92;
    String filler = "x".repeat(250);
    String term = "cavby8";
    // afterTermClean is the content immediately following the matched term, with no control char;
    // afterTermRaw is the same content prefixed by the control char that must be stripped.
    String afterTermClean =
        " \"Roe v. Wade\" at C:" + backslash + "legal" + backslash + "path." + "y".repeat(200);
    String afterTermRaw =
        controlChar + " \"Roe v. Wade\" at C:" + backslash + "legal" + backslash + "path."
            + "y".repeat(200);
    String fieldValue = filler + term + afterTermRaw;
    int termStart = filler.length();
    Hit hit =
        new Hit(
            "doc-field-window",
            0.75d,
            Map.of(
                "title", "Field Window",
                "path", "docs/field-window.md",
                "content_preview", fieldValue),
            List.of("content_preview"),
            List.of(new MatchSpan("content_preview", termStart, termStart + term.length(), term)),
            List.of(),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 4L, List.of(hit), null, null, null, null, null, null, null, null, null);

    String text = textOf(invokeSearch(canned));

    assertFalse(text.contains(String.valueOf(controlChar)), text);
    // Branch pin: bestWindow/windowCentered (PREVIEW_WINDOW_CHARS=200, half=100) centers the
    // window on the term, so it starts 100 chars before it -- i.e. skipping the first 150 of the
    // 250 leading filler chars. This distinguishes it from the head-substring fallback (which
    // would start at field offset 0) and from the plain stripControlChars-only return (which would
    // include the whole 250-char filler run).
    String expectedWindow = "x".repeat(100) + term + afterTermClean.substring(0, 93);
    assertTrue(
        text.contains(
            "    Preview: " + expectedWindow + McpSearchResultFormatter.TRUNCATION_REMEDY),
        text);
  }

  @Test
  @DisplayName(
      "(j) buildHitPreview strips a control char from the >200-char content_preview"
          + " head-substring fallback (no excerptRegions, no informative matchSpans) and preserves"
          + " quotes/backslashes")
  void previewFieldHeadSubstringFallbackStripsControlCharsAndPreservesQuotes() {
    char controlChar = (char) 7;
    char backslash = (char) 92;
    String cleanPrefix =
        "The court cited \"Roe v. Wade\" at C:" + backslash + "legal" + backslash + "path. ";
    String rawPrefix =
        "The court cited \"Roe v. Wade\"" + controlChar + " at C:" + backslash + "legal"
            + backslash + "path. ";
    String fieldValue = rawPrefix + "z".repeat(300); // total > 200; rawPrefix stays under 200
    Hit hit =
        new Hit(
            "doc-field-headcut",
            0.65d,
            Map.of(
                "title", "Field Head Cut",
                "path", "docs/field-headcut.md",
                "content_preview", fieldValue),
            List.of(),
            List.of(),
            List.of(),
            null);
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 4L, List.of(hit), null, null, null, null, null, null, null, null, null);

    String text = textOf(invokeSearch(canned));

    assertFalse(text.contains(String.valueOf(controlChar)), text);
    // Branch pin: the trailing "..." (not the TRUNCATION_REMEDY suffix the window branches use)
    // marks the plain fieldValue.substring(0, 200) head-cut fallback.
    String expectedPreview = cleanPrefix + "z".repeat(200 - rawPrefix.length()) + "...";
    assertTrue(text.contains("    Preview: " + expectedPreview), text);
    assertFalse(text.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), text);
  }
}
