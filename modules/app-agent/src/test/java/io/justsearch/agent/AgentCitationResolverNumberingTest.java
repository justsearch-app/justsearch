/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.app.api.DocumentService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 822 §3b — the agent tier's half of THE numbering contract.
 *
 * <p>A match's {@code sourceIndex} is the position in the list handed to the matcher, and the
 * resolver builds that list 1:1 from {@code sources}. The retired code re-derived the position by
 * comparing that positional index against each source's DOCUMENT-relative {@code chunkIndex}, then
 * fell back to "the first source of the same document" — a wrong-target deep link whenever a
 * document contributed more than one passage. These fixtures make ordinal and position differ, so
 * the old behavior cannot pass.
 */
class AgentCitationResolverNumberingTest {

  private static DocumentService docsReturning(DocumentService.CitationMatchResult result) {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitations(
          String answerText, List<ContextCitation> citations, double threshold) {
        return CompletableFuture.completedFuture(result);
      }
    };
  }

  /** Two passages of the SAME document, whose chunk ordinals (7, 3) are not their positions. */
  private static List<AgentEvent.AgentSource> twoPassagesOfOneDoc() {
    return List.of(
        new AgentEvent.AgentSource("doc-1", 7, "/tmp/doc-1.md", "Doc One", "first passage", 1, 2, ""),
        new AgentEvent.AgentSource(
            "doc-1", 3, "/tmp/doc-1.md", "Doc One", "second passage", 9, 10, ""));
  }

  @Test
  @DisplayName("a match at position 1 cites SOURCE 1, not the document's first passage")
  void positionAddressesTheSourceList() {
    var result =
        new DocumentService.CitationMatchResult(
            List.of(
                new DocumentService.CitationMatchEntry(
                    0, "A sentence.", 1, 0.9, "doc-1", DocumentService.TextSource.CHUNK_LOOKUP)),
            1,
            1,
            5L,
            1,
            DocumentService.ScorerKind.CROSS_ENCODER,
            List.of());

    var cites =
        new AgentCitationResolver(docsReturning(result))
            .resolve("A sentence.", twoPassagesOfOneDoc())
            .cites();

    assertEquals(1, cites.size());
    assertEquals(
        1,
        cites.get(0).sourceIndex(),
        "the positional index addresses sources directly (old code fell back to source 0)");
  }

  @Test
  @DisplayName("an out-of-range index mints NO cite — never a fallback to another source")
  void outOfRangeDropsTheCite() {
    var result =
        new DocumentService.CitationMatchResult(
            List.of(
                new DocumentService.CitationMatchEntry(
                    0, "A sentence.", 59, 0.9, "doc-1", DocumentService.TextSource.CHUNK_LOOKUP)),
            1,
            1,
            5L,
            1,
            DocumentService.ScorerKind.CROSS_ENCODER,
            List.of());

    var cites =
        new AgentCitationResolver(docsReturning(result))
            .resolve("A sentence.", twoPassagesOfOneDoc())
            .cites();

    assertTrue(cites.isEmpty(), "59 addresses no source in a 2-source answer, so no mark is minted");
  }
}
