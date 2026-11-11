/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reranker's second passage per leading candidate: title plus the document's opening. */
final class KnowledgeSearchEngineOpeningPassageTest {

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
