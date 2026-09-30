/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.app.api.DocumentService;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 799 §Q — the agent half of the citation-cutoff parity regression.
 *
 * <p>Observes the threshold this resolver actually hands to {@link DocumentService#matchCitations},
 * rather than re-deriving it. Paired with
 * {@code StreamingCitationMatcherTest#configuredZeroResolvesToDefaultNotFloor} on the RAG side:
 * together they pin that one configured value cannot produce two effective cutoffs, which is the
 * assertion whose absence let the divergence ship.
 */
class AgentCitationResolverThresholdTest {

  /** Captures the cutoff the resolver passes down, and returns no matches. */
  private static DocumentService capturingDocs(double[] sink) {
    return capturingDocs(sink, new java.util.ArrayList<>());
  }

  /**
   * Captures the cutoff AND the verification sources. Tempdoc 868 §B.1: the resolver moved from the
   * {@code matchCitations} overload (which blanks every literal) to {@code matchCitationsAgainst},
   * the real method — so this fake overrides THAT, or it is never reached.
   */
  private static DocumentService capturingDocs(
      double[] sink, List<DocumentService.VerificationSource> sourceSink) {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitationsAgainst(
          String answerText,
          List<VerificationSource> sources,
          double threshold,
          EngineContext engineContext) {
        sink[0] = threshold;
        sourceSink.addAll(sources);
        return CompletableFuture.completedFuture(null);
      }
    };
  }

  private static List<AgentEvent.AgentSource> oneSource() {
    return List.of(
        new AgentEvent.AgentSource(
            "doc-1", 0, "/tmp/doc-1.md", "Doc One", "the grass is green", 1, 2, ""));
  }

  @Test
  @DisplayName("799 Q: a configured 0 resolves to the DEFAULT, matching the RAG path exactly")
  void configuredZeroResolvesToDefault() {
    double[] seen = {-1.0};
    new AgentCitationResolver(capturingDocs(seen), 0.0).resolve("The grass is green.", oneSource(), EngineContextTestFixtures.AGENT_LOOP);
    assertEquals(DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD, seen[0], 1e-9);
    assertNotEquals(
        0.01, seen[0], 1e-9, "0.01 was the RAG path's old floor — the two must not diverge again");
  }

  @Test
  @DisplayName("799 Q: an in-range configured cutoff passes through untouched")
  void inRangePassesThrough() {
    double[] seen = {-1.0};
    new AgentCitationResolver(capturingDocs(seen), 0.83).resolve("The grass is green.", oneSource(), EngineContextTestFixtures.AGENT_LOOP);
    assertEquals(0.83, seen[0], 1e-9);
  }

  @Test
  @DisplayName("868 §B.1: an OPENED source is verified against its own literal text; a retrieved one is looked up")
  void acquisitionDecidesWhetherTheLiteralTextIsSupplied() {
    // The asymmetry is the whole point. A retrieved source has chunk identity, so the Worker
    // re-fetches that chunk and verifies against the index. An opened source has no chunk to
    // re-fetch — it addresses a character span — so it must carry the page the model actually saw.
    // Blanking it (what the `matchCitations` overload does to every source) would make every read
    // source unverifiable, which is the dead-end §A.6 recorded for doc-level sources.
    var opened =
        new AgentEvent.AgentSource(
            "/tmp/opened.md",
            -1,
            "/tmp/opened.md",
            "Opened",
            "the page the model actually read",
            -1,
            -1,
            "",
            AgentEvent.AgentSource.ACQUISITION_OPENED);
    var retrieved =
        new AgentEvent.AgentSource(
            "doc-1", 0, "/tmp/doc-1.md", "Doc One", "the grass is green", 1, 2, "");

    double[] seen = {-1.0};
    var captured = new java.util.ArrayList<DocumentService.VerificationSource>();
    new AgentCitationResolver(capturingDocs(seen, captured), 0.5)
        .resolve("A sentence.", List.of(opened, retrieved), EngineContextTestFixtures.AGENT_LOOP);

    assertEquals(2, captured.size(), "one VerificationSource per AgentSource, in order");
    assertEquals(
        "the page the model actually read",
        captured.get(0).literalText(),
        "an opened source supplies the text it showed the model");
    assertTrue(captured.get(0).suppliesText());
    assertEquals(
        "",
        captured.get(1).literalText(),
        "a retrieved source supplies no literal — its (parentDocId, chunkIndex) is the lookup key");
    assertFalse(captured.get(1).suppliesText());
    assertEquals(
        "/tmp/opened.md",
        captured.get(0).citation().parentDocId(),
        "the citation identity still travels with the literal");
  }
}
