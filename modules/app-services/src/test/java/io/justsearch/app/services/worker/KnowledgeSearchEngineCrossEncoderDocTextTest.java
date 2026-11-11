package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.MatchSpan;
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
}
