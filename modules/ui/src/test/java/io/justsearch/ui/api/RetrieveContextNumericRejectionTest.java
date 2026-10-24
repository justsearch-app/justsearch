/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** NG6 preservation at the real raw-citation HTTP handler, before browser producer filtering. */
class RetrieveContextNumericRejectionTest {
  @Test
  @SuppressWarnings("unchecked")
  void rawApiReportsRejectionAsScoredWithZeroMatches() {
    for (DocumentService.ScorerKind scorer : List.of(DocumentService.ScorerKind.CROSS_ENCODER,
        DocumentService.ScorerKind.EMBEDDING_COSINE)) {
      var docs = mock(DocumentService.class);
      when(docs.matchCitationsAgainst(anyString(), anyList(), anyDouble(), any()))
          .thenReturn(CompletableFuture.completedFuture(new DocumentService.CitationMatchResult(
              List.of(), 1, 0, 5L, 1, scorer,
              List.of(new DocumentService.SourceCoverage(0, 1, 1)))));
      var controller = new RetrieveContextController(null, () -> docs, mock(OnlineAiService.class), () -> "");
      var ctx = mock(Context.class);
      when(ctx.path()).thenReturn("/api/knowledge/match-citations");
      when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("answer_text", "Opened in 2099.",
          "chunk_refs", List.of(Map.of("parent_doc_id", "doc", "chunk_index", 0,
              "passage_text", "Opened in 1883."))));
      when(ctx.status(anyInt())).thenReturn(ctx);
      AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
      when(ctx.json(any())).thenAnswer(call -> {
        captured.set(call.getArgument(0));
        return ctx;
      });
      controller.handleMatchCitations(ctx);
      var response = captured.get();
      assertNotNull(response);
      assertEquals(List.of(), response.get("matches"));
      assertEquals(1, response.get("sentences_total"));
      assertEquals(0, response.get("sentences_matched"));
      assertEquals(1, response.get("sentences_scored"));
      assertEquals(false, response.get("scoring_incomplete"));
      assertEquals(true, response.get("text_coverage_complete"));
      assertEquals(scorer.name(), response.get("scorer"));
    }
  }
}
