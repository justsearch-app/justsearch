package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.MatchSpan;
import io.justsearch.ipc.RerankResponse;
import java.util.List;
import java.util.Map;
import io.justsearch.ipc.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 774 Stage 2 (Item 2): unit coverage for the Head cross-encoder's per-candidate input
 * assembly ({@link KnowledgeSearchEngine#buildCrossEncoderDocText}), the previously untested
 * {@code extractQueryFocusedSnippet} usage (§J.2 test gap). Documents both the healthy path (a
 * content_preview-bearing hit → title + query-focused snippet) and the judge-blindness defect (a
 * chunk-only hit with no content_preview → title-only text, §F.1-5).
 */
final class KnowledgeSearchEngineCrossEncoderDocTextTest {

  @Test
  @DisplayName("(a) hit with content_preview → title + query-focused snippet")
  void withContentPreviewYieldsTitlePlusSnippet() {
    SearchResult sr =
        SearchResult.newBuilder()
            .setId("doc-1")
            .putFields("title", "Doc Title")
            .putFields("content_preview", "short preview text")
            .build();

    // No spans → snippet falls back to the preview start (whole preview here, under 1500 chars).
    assertEquals("Doc Title short preview text", KnowledgeSearchEngine.buildCrossEncoderDocText(sr));
  }

  @Test
  @DisplayName("(b) hit without content_preview → title-only text (documents judge-blindness)")
  void withoutContentPreviewYieldsTitleOnly() {
    SearchResult sr =
        SearchResult.newBuilder().setId("chunk-only").putFields("title", "Only Title").build();

    // The §F.1-5 defect: a chunk-only hit reaches the CE as title + empty snippet — no evidence.
    assertEquals("Only Title ", KnowledgeSearchEngine.buildCrossEncoderDocText(sr));
  }

  @Test
  @DisplayName("(c) snippet centers on the first match span, not the document start")
  void snippetCentersOnFirstMatchSpan() {
    // The match lives PAST the RERANK_SNIPPET_LENGTH (1500) window, so a start-anchored snippet
    // would miss it entirely — only a match-centered snippet surfaces "MATCH".
    String preview = "A".repeat(3000) + " MATCH " + "B".repeat(100);
    int matchStart = preview.indexOf("MATCH");
    SearchResult sr =
        SearchResult.newBuilder()
            .setId("doc-long")
            .putFields("title", "Long Doc")
            .putFields("content_preview", preview)
            .addMatchSpans(
                MatchSpan.newBuilder()
                    .setField("content_preview")
                    .setStartChar(matchStart)
                    .setEndChar(matchStart + "MATCH".length())
                    .setTerm("MATCH")
                    .build())
            .build();

    String docText = KnowledgeSearchEngine.buildCrossEncoderDocText(sr);

    assertTrue(docText.startsWith("Long Doc "), "docText leads with the title");
    assertTrue(
        docText.contains("MATCH"),
        "a match-centered snippet surfaces the match even though it sits past the 1500-char window");
    assertFalse(
        docText.contains("A".repeat(1500)),
        "the snippet is centered on the match, not a start-anchored first-1500-chars cut");
  }

  @Test
  @DisplayName("(d) chunk hit: opening passage is title + the Worker-sent document opening")
  void chunkHitOpeningPassageUsesParentOpening() {
    SearchResult sr =
        SearchResult.newBuilder()
            .setId("doc-2")
            .putFields("title", "Kleine Anfrage")
            .putFields("chunk_index", "3")
            .putFields("content_preview", "deep chunk text about camps")
            .putFields(KnowledgeSearchEngine.PARENT_OPENING_FIELD, "Zur Situation im Lager Machmur")
            .build();

    assertEquals(
        "Kleine Anfrage Zur Situation im Lager Machmur",
        KnowledgeSearchEngine.buildCrossEncoderOpeningText(sr));
  }

  @Test
  @DisplayName("(e) no opening known, or opening equal to the match passage -> no second pair")
  void noSecondPassageWhenUnknownOrIdentical() {
    SearchResult chunkWithoutOpening =
        SearchResult.newBuilder()
            .setId("c")
            .putFields("title", "T")
            .putFields("chunk_index", "2")
            .putFields("content_preview", "chunk")
            .build();
    SearchResult shortWholeDoc =
        SearchResult.newBuilder()
            .setId("w")
            .putFields("title", "T")
            .putFields("content_preview", "short whole document")
            .build();

    assertNull(KnowledgeSearchEngine.buildCrossEncoderOpeningText(chunkWithoutOpening));
    assertNull(KnowledgeSearchEngine.buildCrossEncoderOpeningText(shortWholeDoc));
  }

  @Test
  @DisplayName("(f) each candidate keeps its better passage score; order is recomputed")
  void keepBetterPassageFoldsOpeningScores() {
    // Window of 3; candidate 2 has an opening passage at position 3 that scores best.
    RerankResponse worker =
        RerankResponse.newBuilder()
            .addAllScores(List.of(1.0f, 0.5f, 0.2f, 3.0f))
            .addAllSortedIndices(List.of(3, 0, 1, 2))
            .build();

    RerankResponse folded =
        KnowledgeSearchEngine.keepBetterPassage(worker, new int[] {-1, -1, 3}, 3);

    assertEquals(List.of(1.0f, 0.5f, 3.0f), folded.getScoresList());
    assertEquals(List.of(2, 0, 1), folded.getSortedIndicesList());
  }

  @Test
  @DisplayName("(g) the Worker's opening field never leaves the search API")
  void openingFieldIsDroppedFromApiFields() {
    Map<String, String> fields =
        Map.of("title", "T", KnowledgeSearchEngine.PARENT_OPENING_FIELD, "opening");

    assertEquals(Map.of("title", "T"), SearchResultMapper.withoutWireOnlyFields(fields));
  }
}
