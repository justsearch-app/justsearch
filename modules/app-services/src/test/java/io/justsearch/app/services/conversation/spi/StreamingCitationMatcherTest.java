package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.CitationMatchEntry;
import io.justsearch.app.api.DocumentService.CitationMatchResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.DocumentService.ScorerKind;
import io.justsearch.app.api.DocumentService.TextSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StreamingCitationMatcher} (slice 493). */
final class StreamingCitationMatcherTest {

  @Test
  @DisplayName("ID is stable and namespaced")
  void idIsStable() {
    assertEquals(
        "core.streaming-citation-matcher", StreamingCitationMatcher.ID);
  }

  @Nested
  @DisplayName("onChunk — streaming citation deltas")
  class OnChunkTests {

    @Test
    @DisplayName("returns empty when no citations stashed")
    void noCitations() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var r = matcher.onChunk("Hello world.", stubCtx(Map.of()));
      assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("returns empty when chunk has no sentence boundary")
    void noSentenceBoundary() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "the grass is green")));
      var r = matcher.onChunk("The grass is", ctx);
      assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("emits rag.citation_delta when sentence completes")
    void emitsDeltaOnSentence() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(
          List.of(citation("doc-1", 0, "the grass is green in the field")));

      // First chunk: no sentence boundary
      var r1 = matcher.onChunk("The grass is green", ctx);
      assertTrue(r1.events().isEmpty());

      // Second chunk: sentence completes
      var r2 = matcher.onChunk(" in the field. ", ctx);
      assertEquals(1, r2.events().size());

      SseEvent event = r2.events().get(0);
      assertEquals("rag.citation_delta", event.name());
      assertEquals(0, event.payload().get("sentenceIndex"));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> citations =
          (List<Map<String, Object>>) event.payload().get("citations");
      assertNotNull(citations);
      assertTrue(citations.size() > 0, "should match the citation lexically");
      assertEquals("doc-1", citations.get(0).get("parentDocId"));
    }

    @Test
    @DisplayName("increments sentence index across chunks")
    void sentenceIndexIncrements() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(
          List.of(citation("doc-1", 0, "important information about search")));

      matcher.onChunk("First sentence about search. ", ctx);
      var r = matcher.onChunk("Second sentence about search. ", ctx);

      assertEquals(1, r.events().size());
      assertEquals(1, r.events().get(0).payload().get("sentenceIndex"));
    }

    @Test
    @DisplayName("no delta emitted when sentence doesn't match any citation")
    void noMatchNoDelta() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(
          List.of(citation("doc-1", 0, "quantum physics entanglement")));

      var r = matcher.onChunk("The weather is nice today. ", ctx);
      assertTrue(r.events().isEmpty(), "no lexical overlap → no delta");
    }
  }

  @Nested
  @DisplayName("onDone — authoritative matching")
  class OnDoneTests {

    @Test
    @DisplayName("emits rag.citation_matches from authoritative matcher")
    void emitsAuthoritativeMatches() {
      var matchResult = new CitationMatchResult(
          List.of(new CitationMatchEntry(0, "Sentence.", 0, 0.9, "doc-1", TextSource.SUPPLIED)),
          1, 1, 10L, 1, ScorerKind.CROSS_ENCODER, List.of());
      var matcher = new StreamingCitationMatcher(stubDocs(matchResult));
      var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "excerpt")));

      var r = matcher.onDone("Sentence.", ctx);

      assertEquals(1, r.events().size());
      assertEquals("rag.citation_matches", r.events().get(0).name());
    }

    /** Tempdoc 822 §3b — the match payload publishes the contract's name, on both channels. */
    @Test
    @DisplayName("the matches payload carries sourceIndex, never chunkIndex")
    void matchPayloadUsesSourceIndex() {
      var matchResult = new CitationMatchResult(
          List.of(new CitationMatchEntry(0, "Sentence.", 2, 0.9, "doc-1", TextSource.SUPPLIED)),
          1, 1, 10L, 1, ScorerKind.CROSS_ENCODER, List.of());
      var matcher = new StreamingCitationMatcher(stubDocs(matchResult));
      var ctx = ctxWithCitations(List.of(citation("doc-1", 41, "excerpt")));

      var r = matcher.onDone("Sentence.", ctx);

      @SuppressWarnings("unchecked")
      var matches =
          (List<Map<String, Object>>) r.events().get(0).payload().get("matches");
      assertEquals(1, matches.size());
      assertEquals(2, matches.get(0).get("sourceIndex"), "the entry's positional index");
      assertFalse(matches.get(0).containsKey("chunkIndex"));
      // The same payload is what gets PERSISTED on the record (claimMatches), so a reloaded
      // conversation reads the contract's name too.
      @SuppressWarnings("unchecked")
      var persisted =
          (Map<String, Object>) r.donePayloadEntries().get("claimMatches");
      @SuppressWarnings("unchecked")
      var persistedMatches = (List<Map<String, Object>>) persisted.get("matches");
      assertEquals(2, persistedMatches.get(0).get("sourceIndex"));
    }

    @Test
    @DisplayName("returns empty when fullText is blank")
    void blankText() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var r = matcher.onDone("", stubCtx(Map.of()));
      assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("tolerates service failure non-fatally")
    void serviceFailure() {
      var matcher = new StreamingCitationMatcher(failingDocs());
      var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "excerpt")));
      var r = matcher.onDone("text", ctx);
      assertTrue(r.events().isEmpty());
    }
  }

  @Nested
  @DisplayName("extractCompleteSentences — sentence segmentation")
  class SentenceExtractionTests {

    @Test
    @DisplayName("extracts complete sentences, leaves tail in buffer")
    void basicExtraction() {
      var buf = new StringBuilder("First sentence. Second sentence. Incompl");
      var sentences = StreamingCitationMatcher.extractCompleteSentences(buf);
      assertEquals(2, sentences.size());
      assertEquals("First sentence.", sentences.get(0));
      assertEquals("Second sentence.", sentences.get(1));
      assertEquals("Incompl", buf.toString());
    }

    @Test
    @DisplayName("returns empty for text with no sentence boundary")
    void noSentenceBoundary() {
      var buf = new StringBuilder("hello world");
      var sentences = StreamingCitationMatcher.extractCompleteSentences(buf);
      assertTrue(sentences.isEmpty());
      assertEquals("hello world", buf.toString());
    }

    @Test
    @DisplayName("handles single complete sentence")
    void singleSentence() {
      var buf = new StringBuilder("Hello world. ");
      var sentences = StreamingCitationMatcher.extractCompleteSentences(buf);
      assertEquals(1, sentences.size());
      assertEquals("Hello world.", sentences.get(0));
    }
  }

  @Nested
  @DisplayName("matchSentenceLexical — fast word-overlap matching")
  class LexicalMatchTests {

    @Test
    @DisplayName("matches when significant words overlap")
    void matchesOverlap() {
      var citations = List.of(
          citation("doc-1", 0, "JustSearch indexes your files locally"));
      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "JustSearch indexes files on your machine locally.", citations);
      assertEquals(1, matches.size());
      assertEquals("doc-1", matches.get(0).get("parentDocId"));
    }

    @Test
    @DisplayName("no match when no significant word overlap")
    void noMatch() {
      var citations = List.of(
          citation("doc-1", 0, "quantum physics entanglement theory"));
      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "The weather is nice today.", citations);
      assertTrue(matches.isEmpty());
    }

    @Test
    @DisplayName("skips citations with empty excerpt")
    void emptyExcerpt() {
      var citations = List.of(citation("doc-1", 0, ""));
      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "Any sentence here.", citations);
      assertTrue(matches.isEmpty());
    }

    /**
     * Tempdoc 822 §3b — THE numbering contract. The emitted index is the source's POSITION in this
     * turn's citations array, never the chunk's ordinal inside its parent document. The ordinals
     * here (7, 3, 19) deliberately differ from the positions (0, 1, 2): the pre-fix code passed
     * only because fixtures used ordinal == position.
     */
    @Test
    @DisplayName("emits the citations-array POSITION, not the document-relative chunk ordinal")
    void emitsArrayPositionNotChunkOrdinal() {
      var citations = List.of(
          citation("doc-1", 7, "JustSearch indexes your files locally"),
          citation("doc-2", 3, "quantum physics entanglement theory"),
          citation("doc-3", 19, "JustSearch indexes your files locally"));

      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "JustSearch indexes files on your machine locally.", citations);

      assertEquals(2, matches.size(), "positions 0 and 2 overlap; position 1 does not");
      assertEquals(0, matches.get(0).get("sourceIndex"), "first match is at ARRAY POSITION 0");
      assertEquals(2, matches.get(1).get("sourceIndex"), "second match is at ARRAY POSITION 2");
      for (var m : matches) {
        assertFalse(
            m.containsKey("chunkIndex"),
            "the document-relative ordinal must not travel on a match at all");
      }
    }

    @Test
    @DisplayName("a 59-ordinal chunk in a 5-source turn emits a position < 5 (the gap-report defect)")
    void misNumberingFixture() {
      var citations = new java.util.ArrayList<ContextCitation>();
      for (int i = 0; i < 5; i++) {
        citations.add(citation("doc-" + i, 55 + i, "JustSearch indexes your files locally"));
      }

      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "JustSearch indexes files on your machine locally.", citations);

      assertEquals(5, matches.size());
      for (var m : matches) {
        int emitted = (int) m.get("sourceIndex");
        assertTrue(
            emitted >= 0 && emitted < citations.size(),
            "every emitted index addresses a real source (was 55..59 against 5 sources)");
      }
    }
  }

  // -- Test helpers --

  private static ContextCitation citation(String docId, int chunkIdx, String excerpt) {
    return new ContextCitation(
        docId, chunkIdx, 1, 0, 100, 1.0f, excerpt, 0, 10, "", 0, ContextInclusion.ABSENT);
  }

  private static ConversationContext ctxWithCitations(List<ContextCitation> citations) {
    return stubCtx(Map.of(RAGContext.ATTR_CITATIONS, citations));
  }

  private static ConversationContext stubCtx(Map<String, Object> attrs) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> a = new HashMap<>(attrs);

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public String sessionId() {
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return Map.of();
      }

      @Override
      public Map<String, Object> attributes() {
        return a;
      }
    };
  }

  // --- tempdoc 799 §N.2: justsearch.citation.match_threshold is wired. It previously resolved as
  // a String, was never parsed, and a hardcoded 0.5 always won. Tempdoc 565 §15.A made this the ONE
  // cutoff shared with the agent path, so the value must actually reach the DocumentService call.

  @Test
  @DisplayName("799 N.2: the configured cutoff reaches matchCitations, not the compiled default")
  void configuredThresholdReachesMatchCall() {
    double[] seen = {-1.0};
    var docs = thresholdCapturingDocs(seen);
    var matcher = new StreamingCitationMatcher(docs, 0.83);
    var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "the grass is green in the field")));
    matcher.onDone("The grass is green in the field.", ctx);
    assertEquals(0.83, seen[0], 1e-9, "configured cutoff must reach the matcher call");
  }

  @Test
  @DisplayName("799 N.2: an out-of-range cutoff falls back to the shared default")
  void outOfRangeThresholdFallsBack() {
    double[] seen = {-1.0};
    var docs = thresholdCapturingDocs(seen);
    var matcher = new StreamingCitationMatcher(docs, 9.0);
    var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "the grass is green in the field")));
    matcher.onDone("The grass is green in the field.", ctx);
    // 799 §Q changed this deliberately. It previously asserted 1.0, encoding the local clamp
    // `max(0.01, min(1.0, t))` — the very clamp that made a configured 0 mean 0.01 here and 0.5 on
    // the agent path. Out-of-range now resolves to the shared default on BOTH paths.
    assertEquals(DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD, seen[0], 1e-9);
  }

  @Test
  @DisplayName("799 Q: a configured 0 resolves to the DEFAULT on this path, not the old 0.01 floor")
  void configuredZeroResolvesToDefaultNotFloor() {
    double[] seen = {-1.0};
    var docs = thresholdCapturingDocs(seen);
    var matcher = new StreamingCitationMatcher(docs, 0.0);
    var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "the grass is green in the field")));
    matcher.onDone("The grass is green in the field.", ctx);
    // Before the fix this observed 0.01 while the agent path observed 0.5 for the same setting.
    assertEquals(DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD, seen[0], 1e-9);
    assertNotEquals(0.01, seen[0], 1e-9, "the old local floor must not come back");
  }

  private static DocumentService thresholdCapturingDocs(double[] sink) {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitationsAgainst(
          String answerText,
          List<DocumentService.VerificationSource> sources,
          double threshold,
          io.justsearch.core.context.EngineContext engineContext) {
        sink[0] = threshold;
        return CompletableFuture.completedFuture(null);
      }
    };
  }

  private static DocumentService stubDocs(CitationMatchResult result) {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitationsAgainst(
          String answerText,
          List<DocumentService.VerificationSource> sources,
          double threshold,
          io.justsearch.core.context.EngineContext engineContext) {
        return CompletableFuture.completedFuture(result);
      }
    };
  }

  private static DocumentService failingDocs() {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
        return CompletableFuture.failedFuture(new RuntimeException("down"));
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitationsAgainst(
          String answerText,
          List<DocumentService.VerificationSource> sources,
          double threshold,
          io.justsearch.core.context.EngineContext engineContext) {
        return CompletableFuture.failedFuture(new RuntimeException("down"));
      }
    };
  }
}
