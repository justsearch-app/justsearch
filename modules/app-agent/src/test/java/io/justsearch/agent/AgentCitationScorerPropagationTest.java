/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.app.api.DocumentService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 859 §4 / T12 — the producer stamp survives every hop from the matcher to the persisted
 * assistant message, and a run that never asked a matcher says so.
 *
 * <p>The defect this covers is `wire-emitter-elision` in its exact 836 §4 form: {@code
 * DocumentService.CitationMatchResult} always reported which producer wrote the similarities, {@code
 * AgentCitationResolver} discarded it at the return, and so the FE's producer gate had no input on
 * the agent plane — a cosine-fallback score reached {@code Citation.similarity}, which every
 * downstream tier reads as a cross-encoder probability. Each assertion below is one hop of the chain
 * that was broken: matcher → resolver → {@code AgentDone} → wire payload → thread attributes.
 */
final class AgentCitationScorerPropagationTest {

  private static final String CONV = "conv-scorer";

  private static DocumentService docsScoredBy(DocumentService.ScorerKind scorer) {
    DocumentService.CitationMatchResult result =
        new DocumentService.CitationMatchResult(
            List.of(
                new DocumentService.CitationMatchEntry(
                    0, "A sentence.", 0, 0.9, "doc-1", DocumentService.TextSource.CHUNK_LOOKUP)),
            1,
            1,
            5L,
            1,
            scorer,
            List.of());
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

  private static List<AgentEvent.AgentSource> oneSource() {
    return List.of(
        new AgentEvent.AgentSource("doc-1", 0, "/tmp/doc-1.md", "Doc One", "a passage", 1, 2, ""));
  }

  @Test
  @DisplayName("hop 1 — the resolver returns the matcher's ScorerKind, it does not drop it")
  void resolverCarriesTheScorer() {
    var crossEncoder =
        new AgentCitationResolver(docsScoredBy(DocumentService.ScorerKind.CROSS_ENCODER))
            .resolve("A sentence.", oneSource());
    assertEquals(DocumentService.ScorerKind.CROSS_ENCODER, crossEncoder.scorer());
    assertEquals(1, crossEncoder.cites().size(), "the cites still arrive beside the scorer");

    // The discriminating case: same matcher, same cites, a producer whose numbers are on a
    // different scale. Reporting CROSS_ENCODER here (or nothing) is what admitted a cosine score
    // into a cross-encoder-calibrated tier.
    var cosine =
        new AgentCitationResolver(docsScoredBy(DocumentService.ScorerKind.EMBEDDING_COSINE))
            .resolve("A sentence.", oneSource());
    assertEquals(DocumentService.ScorerKind.EMBEDDING_COSINE, cosine.scorer());
  }

  @Test
  @DisplayName("hop 1 — no matcher at all resolves to NONE, never to an absent stamp")
  void noMatcherResolvesToNone() {
    var resolved = new AgentCitationResolver(null).resolve("A sentence.", oneSource());
    assertEquals(DocumentService.ScorerKind.NONE, resolved.scorer());
    assertTrue(resolved.cites().isEmpty());
  }

  @Test
  @DisplayName("hop 2+3 — the stamp reaches AgentDone and its wire payload")
  void doneAndPayloadCarryTheScorer() {
    var done =
        new AgentEvent.AgentDone(
            "the answer",
            1,
            0,
            0,
            oneSource(),
            List.of(new AgentEvent.AgentSentenceCite("A sentence.", 0, 0.9)),
            DocumentService.ScorerKind.CROSS_ENCODER.name());
    assertEquals("CROSS_ENCODER", done.citationScorer());
    assertEquals("CROSS_ENCODER", AgentEventPayloads.base(done).get("citationScorer"));
  }

  @Test
  @DisplayName("a non-resolver emitter stamps NONE — an absent stamp would mean 'pre-stamp record'")
  void nonResolverEmitterStampsNone() {
    // The ungrounded terminal AgentLoopService emits when max iterations are reached: no matcher
    // ran, so there is nothing to mark, and NONE fails the FE gate closed rather than being read as
    // a record older than the field.
    assertEquals("NONE", new AgentEvent.AgentDone("", 3, 0, 0).citationScorer());
    assertEquals("NONE", AgentEvent.AgentDone.SCORER_NONE);
    assertEquals(
        "NONE",
        AgentEventPayloads.base(new AgentEvent.AgentDone("", 3, 0, 0)).get("citationScorer"));
  }

  @Test
  @DisplayName("hop 4 — the mapper copies the stamp onto the persisted assistant attributes")
  void mapperCarriesTheScorerOntoAttributes() {
    Map<String, Object> source =
        Map.of("parentDocId", "doc-1", "startLine", 1, "endLine", 2, "excerpt", "a passage");
    Map<String, Object> cite =
        Map.of("sentenceText", "A sentence.", "sourceIndex", 0, "similarity", 0.9);
    InteractionEvent mapped =
        AgentInteractionMapper.fromRunEvent(
                Map.of(
                    "timestamp",
                    "2026-01-01T00:00:01Z",
                    "eventType",
                    "done",
                    "payload",
                    Map.of(
                        "finalResponse", "the answer",
                        "sources", List.of(source),
                        "citations", List.of(cite),
                        "citationScorer", "EMBEDDING_COSINE")),
                CONV)
            .orElseThrow();
    assertEquals("EMBEDDING_COSINE", mapped.attributes().get("citationScorer"));
  }

  @Test
  @DisplayName("a pre-stamp record carries NO citationScorer key — absent is not NONE")
  void preStampRecordOmitsTheKey() {
    Map<String, Object> source =
        Map.of("parentDocId", "doc-1", "startLine", 1, "endLine", 2, "excerpt", "a passage");
    InteractionEvent mapped =
        AgentInteractionMapper.fromRunEvent(
                Map.of(
                    "timestamp",
                    "2026-01-01T00:00:01Z",
                    "eventType",
                    "done",
                    "payload",
                    Map.of("finalResponse", "the answer", "sources", List.of(source))),
                CONV)
            .orElseThrow();
    assertTrue(
        !mapped.attributes().containsKey("citationScorer"),
        "an events.ndjson record written before 859 must stay ABSENT, which the FE gate's pre-stamp"
            + " allowance admits; stamping NONE onto it would silently strip its marks");
  }
}
