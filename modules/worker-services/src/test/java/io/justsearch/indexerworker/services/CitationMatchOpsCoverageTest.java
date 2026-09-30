/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.ipc.SourceCoverage;
import io.justsearch.reranker.CitationScorer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 836 S2S3-A.6 a/b/c — the TEXT-coverage axis on the wire.
 *
 * <p>These exist because admission control preserves SENTENCE coverage by cutting WINDOWS
 * (§3.5), which produces a response that can truthfully say "every sentence was scored" about a
 * pass that read 4% of the caller's text (§10.7 gap 1) — and can starve a source of every window,
 * making it indistinguishable from a source that simply supports nothing (§10.7 gap 2). Both are
 * facts the Worker already knows; the tests pin that it now says them.
 */
@DisplayName("CitationMatchOps — per-source examination facts")
class CitationMatchOpsCoverageTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final String DOC_A = "d:/docs/a.md";

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  // ==================== A.6a ====================

  @Test
  @DisplayName("a complete-looking response over partially-examined text reports the shortfall")
  void completeSentencesOverPartialText() {
    // §10.7 gap 1, verbatim: ONE 200 KB source, 15 sentences, the real 2000 ms budget. The Worker
    // scores every sentence — against 5 of ~134 windows. Before this slice the response said
    // `sentences_scored == sentences_total` and carried nothing that could contradict it.
    String passage = repeatTo("Cursor pagination resumes from the encoded sort key. ", 200_000);

    MatchCitationsResponse response =
        ops().execute(answerOf(15), List.of(DOC_A), List.of(0), List.of(passage), 0.9);

    assertEquals(15, response.getSentencesTotal());
    assertEquals(
        response.getSentencesTotal(),
        response.getSentencesScored(),
        "the SENTENCE axis is complete — which is exactly what makes the text axis necessary");

    assertEquals(1, response.getSourceCoverageCount());
    SourceCoverage coverage = response.getSourceCoverage(0);
    assertEquals(0, coverage.getSourceIndex());
    assertTrue(
        coverage.getWindowsConsidered() > 100,
        "200 KB at 1500 chars per window is well over 100 windows, before admission");
    assertTrue(
        coverage.getWindowsScored() < coverage.getWindowsConsidered(),
        "the budget cannot have paid for every window — reporting otherwise is §10.7 gap 1: "
            + coverage.getWindowsScored()
            + " of "
            + coverage.getWindowsConsidered());
    assertTrue(coverage.getWindowsScored() > 0, "something was examined");
  }

  // ==================== A.6b ====================

  @Test
  @DisplayName("a starved source is distinguishable from one that supports nothing")
  void starvedSourceIsDistinguishable() {
    // A cap of 2 windows across 5 sources: the per-source round-robin guarantees representation
    // only WHILE SLOTS REMAIN, so three sources receive no window at all. They are uncitable for
    // reasons of BUDGET, and without this they report identically to "nothing here supports it".
    String passage = repeatTo("Sourdough starters need a warm kitchen. ", 4_000);
    List<String> docIds = List.of(DOC_A, DOC_A, DOC_A, DOC_A, DOC_A);

    MatchCitationsResponse response =
        ops()
            .execute(
                answerOf(40),
                docIds,
                List.of(0, 0, 0, 0, 0),
                List.of(passage, passage, passage, passage, passage),
                0.9);

    assertEquals(5, response.getSourceCoverageCount());
    List<Integer> starved = new ArrayList<>();
    for (SourceCoverage c : response.getSourceCoverageList()) {
      if (c.getWindowsConsidered() > 0 && c.getWindowsScored() == 0) {
        starved.add(c.getSourceIndex());
      }
    }
    assertFalse(starved.isEmpty(), "the cap must have starved at least one source");

    for (int index : starved) {
      SourceCoverage c = response.getSourceCoverage(index);
      assertTrue(
          c.getWindowsConsidered() > 0,
          "a starved source HAD text — that is what separates it from a source with none");
      assertEquals(0, c.getWindowsScored());
      for (var m : response.getMatchesList()) {
        assertFalse(
            m.getSourceIndex() == index,
            "a source nothing scored cannot appear in a match");
      }
    }
  }

  @Test
  @DisplayName("a source with no text at all is reported as considered 0, not as starved")
  void noTextIsNotStarvation() {
    // The third state: blank supply AND a lookup that resolves nothing. It is unverifiable, and it
    // is NOT the same fact as "the budget skipped it" — one is about supply, the other about cost.
    MatchCitationsResponse response =
        ops()
            .execute(
                answerOf(2),
                List.of(DOC_A, "d:/docs/never-indexed.md"),
                List.of(0, 0),
                List.of("Cursor pagination resumes from the encoded sort key.", ""),
                0.9);

    assertEquals(2, response.getSourceCoverageCount());
    assertEquals(0, response.getSourceCoverage(1).getWindowsConsidered());
    assertEquals(0, response.getSourceCoverage(1).getWindowsScored());
    assertTrue(response.getSourceCoverage(0).getWindowsConsidered() > 0);
  }

  // ==================== A.6c ====================

  @Test
  @DisplayName("a request inside the budget reports every source fully examined")
  void noFalseIncompleteness() {
    // The counter-test to A.6a: a fix that made every answer look partial would be a different
    // dishonesty, not an improvement.
    MatchCitationsResponse response =
        ops()
            .execute(
                answerOf(3),
                List.of(DOC_A),
                List.of(0),
                List.of("Cursor pagination resumes from the encoded sort key."),
                0.9);

    assertEquals(1, response.getSourceCoverageCount());
    SourceCoverage coverage = response.getSourceCoverage(0);
    assertTrue(coverage.getWindowsConsidered() > 0);
    assertEquals(
        coverage.getWindowsConsidered(),
        coverage.getWindowsScored(),
        "a small request fits the budget whole; claiming otherwise invents a shortfall");
  }

  // ==================== the coverage is reported on the no-producer path too ====================

  @Test
  @DisplayName("nothing scored ⇒ every source reports scored 0, not the admitted count")
  void nothingScoredReportsZeroExamined() {
    // Neither producer ran, so no window was examined. Reporting the ADMITTED count here would
    // claim an examination that never happened — the phantom-field class §5.10 forbids.
    CitationMatchOps ops =
        new CitationMatchOps(
            lifecycle.readPathOps(), lifecycle.commitOps(), new UnavailableEmbeddings());

    MatchCitationsResponse response =
        ops.execute(
            answerOf(2),
            List.of(DOC_A),
            List.of(0),
            List.of("Cursor pagination resumes from the encoded sort key."),
            0.9);

    assertEquals("EMBEDDING_UNAVAILABLE", response.getError());
    assertEquals("NONE", response.getScorer());
    for (SourceCoverage c : response.getSourceCoverageList()) {
      assertEquals(0, c.getWindowsScored());
    }
  }

  // ==================== helpers ====================

  /** Cross-encoder arm with an embedding provider that is unavailable, so only the CE branch runs. */
  private CitationMatchOps ops() {
    CitationMatchOps ops =
        new CitationMatchOps(
            lifecycle.readPathOps(), lifecycle.commitOps(), new UnavailableEmbeddings());
    ops.setCrossEncoderProducer(CitationMatchOpsCoverageTest::scoreNothing);
    return ops;
  }

  /**
   * A producer that scores every sentence and matches none. The coverage facts are about WHAT WAS
   * EXAMINED, not about what matched, so a stub that mints no match keeps the two apart: a test
   * that needed matches to observe coverage would be measuring the wrong thing.
   */
  // passages/passageDocIds/threshold/deadlineMs are unused in this stub body, but the method
  // reference below is installed as a CitationMatchOps.CrossEncoderProducer, a
  // @FunctionalInterface whose scoreAll signature fixes this parameter list exactly.
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private static CitationScorer.ScoringResult scoreNothing(
      List<String> sentences,
      List<String> passages,
      List<String> passageDocIds,
      double threshold,
      long deadlineMs) {
    return new CitationScorer.ScoringResult(List.of(), sentences.size(), 0, 1L, sentences.size());
  }

  /** An answer of exactly {@code n} sentences, as {@code BreakIterator} counts them. */
  private static String answerOf(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append("Sentence number ").append(i).append(" states a fact. ");
    }
    return sb.toString().trim();
  }

  private static String repeatTo(String seed, int chars) {
    StringBuilder sb = new StringBuilder(chars + seed.length());
    while (sb.length() < chars) {
      sb.append(seed);
    }
    return sb.substring(0, chars);
  }

  /** Reports unavailable, which is how a Worker with no embedding provider behaves. */
  private static final class UnavailableEmbeddings implements EmbeddingProvider {
    @Override
    public float[] embedDocument(String text) {
      return null;
    }

    @Override
    public float[] embedQuery(String text) {
      return null;
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      return List.of();
    }

    @Override
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return 0;
    }

    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public boolean isUsingGpu() {
      return false;
    }
  }
}
