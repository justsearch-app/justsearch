/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.conversation.FileConversationStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WP4.5 NG6 / GN3: new rejections stay scored; persisted numeric verdicts remain history. */
class NumericCitationHistoryTest {
  private static final String ANSWER = "Vespera Observatory opened in 2099.";
  @TempDir Path storeRoot;

  @Test
  void rejectedSentenceStaysScoredInLiveAndColdReloadedAskPayload() {
    for (DocumentService.ScorerKind scorer : List.of(DocumentService.ScorerKind.CROSS_ENCODER,
        DocumentService.ScorerKind.EMBEDDING_COSINE)) {
      var docs = mock(DocumentService.class);
      when(docs.matchCitationsAgainst(anyString(), anyList(), anyDouble(), any()))
          .thenReturn(CompletableFuture.completedFuture(new DocumentService.CitationMatchResult(
              List.of(), 1, 0, 5L, 1, scorer,
              List.of(new DocumentService.SourceCoverage(0, 1, 1)))));
      var citation = new DocumentService.ContextCitation("doc", 0, 1, 0, 0, 1f,
          "Opened in 1883.", 0, 1, "", 0, DocumentService.ContextInclusion.ABSENT);
      var context = mock(ConversationContext.class);
      when(context.engineContext()).thenReturn(TestEngineContexts.internal());
      when(context.attributes()).thenReturn(Map.of(RAGContext.ATTR_CITATIONS, List.of(citation)));
      var done = new StreamingCitationMatcher(docs).onDone(ANSWER, context);
      assertEquals(1, done.events().size());
      Map<String, Object> live = done.events().getFirst().payload();
      assertEquals(live, done.donePayloadEntries().get("claimMatches"));
      assertEquals(List.of(), live.get("matches"));
      assertEquals(1, live.get("sentencesTotal"));
      assertEquals(0, live.get("sentencesMatched"));
      assertEquals(1, live.get("sentencesScored"));
      assertEquals(live.get("sentencesTotal"), live.get("sentencesScored"));
      assertEquals(scorer.name(), live.get("scorer"));
      String session = scorer.name();
      new FileConversationStore(storeRoot).appendMessage(session, "rag-ask",
          Map.of("role", "assistant", "content", ANSWER, "claimMatches", live));
      var reloaded = new FileConversationStore(storeRoot).loadHistory(session).getFirst();
      assertInstanceOf(Map.class, reloaded.get("claimMatches"));
      Map<?, ?> stored = (Map<?, ?>) reloaded.get("claimMatches");
      assertEquals(live.keySet(), stored.keySet());
      for (String key : live.keySet()) {
        if (key.equals("tookMs")) {
          assertEquals(((Number) live.get(key)).longValue(), ((Number) stored.get(key)).longValue());
        } else {
          assertEquals(live.get(key), stored.get(key), key);
        }
      }
      verify(docs, times(1)).matchCitationsAgainst(anyString(), anyList(), anyDouble(), any());
    }
  }

  @Test
  void legacyNumericVerdictLoadsWithoutRejudging() {
    Map<String, Object> match = Map.of("sentenceIndex", 0, "sentenceText", ANSWER,
        "sourceIndex", 0, "similarity", 0.976, "parentDocId", "doc");
    Map<String, Object> oldClaimMatches = Map.of("matches", List.of(match),
        "sentencesTotal", 1, "sentencesMatched", 1, "sentencesScored", 1,
        "scoringIncomplete", false, "scorer", "CROSS_ENCODER");
    Map<String, Object> oldTurn = Map.of("role", "assistant", "content", ANSWER,
        "claimMatches", oldClaimMatches, "citations", List.of(Map.of("parentDocId", "doc",
            "excerpt", "Vespera Observatory opened in 1883.")));
    new FileConversationStore(storeRoot).appendMessage("legacy", "rag-ask", oldTurn);
    var reloaded = new FileConversationStore(storeRoot).loadHistory("legacy").getFirst();
    assertEquals(oldClaimMatches, reloaded.get("claimMatches"), "stored verification is never recomputed");
    assertEquals(oldTurn.get("citations"), reloaded.get("citations"));
    assertEquals(ANSWER, reloaded.get("content"));
  }
}
