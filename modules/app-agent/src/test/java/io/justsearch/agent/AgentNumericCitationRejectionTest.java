/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.app.api.DocumentService;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** NG6 preservation: an examined but numerically rejected sentence stays a zero-cite result. */
class AgentNumericCitationRejectionTest {
  @Test
  void rejectedSentenceKeepsProducerAndEmptyCitesThroughDelegateEventsAndHistory() {
    for (DocumentService.ScorerKind scorer : List.of(DocumentService.ScorerKind.CROSS_ENCODER,
        DocumentService.ScorerKind.EMBEDDING_COSINE)) {
      DocumentService docs = new DocumentService() {
        @Override
        public CompletionStage<DocumentRecord> fetch(String docId, EngineContext context) {
          return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<CitationMatchResult> matchCitationsAgainst(String answer,
            List<VerificationSource> sources, double threshold, EngineContext context) {
          return CompletableFuture.completedFuture(new CitationMatchResult(
              List.of(), 1, 0, 5L, 1, scorer, List.of()));
        }
      };
      List<AgentEvent.AgentSource> sources = List.of(new AgentEvent.AgentSource(
          "doc", 0, "observatory.md", "Observatory", "Opened in 1883.", 1, 2, ""));
      var resolved = new AgentCitationResolver(docs).resolve("Opened in 2099.", sources,
          EngineContextTestFixtures.AGENT_LOOP);
      assertEquals(List.of(), resolved.cites());
      assertEquals(scorer, resolved.scorer(), "zero matches does not mean no scorer ran");
      var done = new AgentEvent.AgentDone("Opened in 2099.", 1, 0, 0, sources,
          resolved.cites(), resolved.scorer().name());
      var payload = AgentEventPayloads.base(done);
      assertEquals(List.of(), payload.get("citations"));
      assertEquals(scorer.name(), payload.get("citationScorer"));
      var restored = AgentInteractionMapper.fromRunEvent(Map.of("timestamp", "2026-01-01T00:00:01Z",
          "eventType", "done", "payload", payload), "numeric").orElseThrow();
      assertEquals(scorer.name(), restored.attributes().get("citationScorer"));
      assertFalse(restored.attributes().containsKey("citations"), "no verified mark is persisted");
    }
  }
}
