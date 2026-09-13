/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.CitationMatchEntry;
import io.justsearch.app.api.DocumentService.CitationMatchResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.DocumentService.ScorerKind;
import io.justsearch.app.api.DocumentService.SourceCoverage;
import io.justsearch.app.api.DocumentService.TextSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 S2S3-A.6d — the Head-to-browser hop carries the honesty fields.
 *
 * <p>This test exists because of how the gap was found. S1 added {@code scorer} and {@code
 * sentences_scored} to the wire and to {@link CitationMatchResult}, and they stopped there: the
 * payload builder still emitted only {@code sentencesTotal / sentencesMatched / tookMs / matches}.
 * Nothing a browser could read said which producer wrote a similarity, which made §4's frontend
 * provenance gate unimplementable rather than deferred — and it was found by READING the builder,
 * not by a failing test, because no test asserted this hop at all (S2S3-A.0 item 3).
 *
 * <p>The same map is the live SSE payload AND the persisted {@code claimMatches}, so both are
 * asserted: a field present live and absent on the record is the 561 P-A divergence.
 */
@DisplayName("StreamingCitationMatcher — the payload hop to the browser")
final class StreamingCitationMatcherPayloadTest {

  @Test
  @DisplayName("the citation_matches payload carries scorer, sentencesScored and sourceCoverage")
  void payloadCarriesHonestyFields() {
    var matcher = new StreamingCitationMatcher(stubDocs(resultWithCoverage()));

    var r = matcher.onDone("A sentence.", ctxWithCitations());

    assertEquals(1, r.events().size());
    assertEquals("rag.citation_matches", r.events().get(0).name());
    Map<String, Object> payload = r.events().get(0).payload();

    assertEquals("CROSS_ENCODER", payload.get("scorer"), "which producer wrote the similarities");
    assertEquals(12, payload.get("sentencesScored"), "how much of the answer was checked");

    @SuppressWarnings("unchecked")
    var coverage = (List<Map<String, Object>>) payload.get("sourceCoverage");
    assertEquals(2, coverage.size(), "the per-source examination facts, one entry per source");
    assertEquals(0, coverage.get(0).get("sourceIndex"));
    assertEquals(134, coverage.get(0).get("windowsConsidered"));
    assertEquals(5, coverage.get(0).get("windowsScored"));
    // The gap-2 discriminator on the wire: text existed, nothing examined it.
    assertEquals(9, coverage.get(1).get("windowsConsidered"));
    assertEquals(0, coverage.get(1).get("windowsScored"));

    @SuppressWarnings("unchecked")
    var matches = (List<Map<String, Object>>) payload.get("matches");
    assertEquals("SUPPLIED", matches.get(0).get("textSource"), "which TEXT was scored");
  }

  @Test
  @DisplayName("the PERSISTED claimMatches carries the same fields as the live event")
  void persistedPayloadMatchesLive() {
    var matcher = new StreamingCitationMatcher(stubDocs(resultWithCoverage()));

    var r = matcher.onDone("A sentence.", ctxWithCitations());

    @SuppressWarnings("unchecked")
    var persisted = (Map<String, Object>) r.donePayloadEntries().get("claimMatches");
    assertEquals(
        r.events().get(0).payload(),
        persisted,
        "the record and the live event are the same map — a reloaded conversation is judged by"
            + " the same facts, which is what keeps the two render paths from diverging");
  }

  @Test
  @DisplayName("a zero-match result still reports what was examined")
  void zeroMatchesStillReportsCoverage() {
    // The case where the distinction matters most: "nothing was examined" and "everything was
    // examined and supports nothing" are the SAME empty match list. Suppressing the event here
    // leaves the consumer no choice but to assert an evidence verdict over a pass that never ran.
    var result =
        new CitationMatchResult(
            List.of(),
            3,
            0,
            5L,
            3,
            ScorerKind.CROSS_ENCODER,
            List.of(new SourceCoverage(0, 40, 0)));
    var matcher = new StreamingCitationMatcher(stubDocs(result));

    var r = matcher.onDone("A sentence.", ctxWithCitations());

    assertFalse(r.events().isEmpty(), "a zero-match result is still an answer to 'what happened?'");
    @SuppressWarnings("unchecked")
    var coverage = (List<Map<String, Object>>) r.events().get(0).payload().get("sourceCoverage");
    assertEquals(40, coverage.get(0).get("windowsConsidered"));
    assertEquals(0, coverage.get(0).get("windowsScored"));
  }

  @Test
  @DisplayName("the cosine fallback names itself on the wire")
  void fallbackProducerIsNamed() {
    // §4 — the two producers write measurably incomparable scales into one field. The frontend
    // gate can only fail closed if the response says which one arrived.
    var result =
        new CitationMatchResult(
            List.of(new CitationMatchEntry(0, "A sentence.", 0, 0.62, "doc-1", TextSource.SUPPLIED)),
            1,
            1,
            5L,
            1,
            ScorerKind.EMBEDDING_COSINE,
            List.of(new SourceCoverage(0, 2, 2)));
    var matcher = new StreamingCitationMatcher(stubDocs(result));

    var r = matcher.onDone("A sentence.", ctxWithCitations());

    assertEquals("EMBEDDING_COSINE", r.events().get(0).payload().get("scorer"));
    assertTrue(
        r.events().get(0).payload().containsKey("sourceCoverage"),
        "the fallback path reports coverage too — a discriminator that exists on one branch only"
            + " reads as an answer on the other");
  }

  // ==================== fixtures ====================

  private static CitationMatchResult resultWithCoverage() {
    return new CitationMatchResult(
        List.of(new CitationMatchEntry(0, "A sentence.", 0, 0.91, "doc-1", TextSource.SUPPLIED)),
        15,
        1,
        7L,
        12,
        ScorerKind.CROSS_ENCODER,
        List.of(new SourceCoverage(0, 134, 5), new SourceCoverage(1, 9, 0)));
  }

  private static ConversationContext ctxWithCitations() {
    return stubCtx(
        Map.of(
            RAGContext.ATTR_CITATIONS,
            List.of(
                new ContextCitation(
                    "doc-1", 0, 1, 0, 100, 1.0f, "excerpt", 0, 10, "", 0, ContextInclusion.ABSENT),
                new ContextCitation(
                    "doc-2", 0, 1, 0, 100, 1.0f, "excerpt", 0, 10, "", 0,
                    ContextInclusion.ABSENT))));
  }

  private static DocumentService stubDocs(CitationMatchResult result) {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitationsAgainst(
          String answerText, List<DocumentService.VerificationSource> sources, double threshold, io.justsearch.core.context.EngineContext engineContext) {
        return CompletableFuture.completedFuture(result);
      }
    };
  }

  private static ConversationContext stubCtx(Map<String, Object> attributes) {
    Map<String, Object> attrs = new HashMap<>(attributes);
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public String sessionId() {
        return "s";
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public Map<String, Object> requestBody() {
        return Map.of();
      }

      @Override
      public Map<String, Object> attributes() {
        return attrs;
      }
    };
  }
}
