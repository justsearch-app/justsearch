/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.reranker.CitationScorer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tempdoc 836 §5 — the literal-passage seam, exercised on BOTH producer paths.
 *
 * <p>The parameterization is the point. The cosine fallback is a second producer with its own copy
 * of the matching loop, so a fix applied only to the cross-encoder branch would leave a path that
 * silently reverts to scoring the wrong text whenever the scorer is absent. Every test here runs
 * against both.
 *
 * <p>The cross-encoder arm uses {@link CitationMatchOps.CrossEncoderProducer} rather than a real
 * {@link CitationScorer}: {@code worker-services} excludes the ONNX runtime from its classpath, so
 * a real scorer cannot be constructed here, and the alternative — a model-gated test that never
 * runs — would be a green that proves nothing. The producer sees exactly the passages the branch
 * hands the scorer, which is what these tests assert about.
 */
@DisplayName("CitationMatchOps — verification against supplied passage text")
class CitationMatchOpsLiteralPassageTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private enum Producer {
    CROSS_ENCODER,
    EMBEDDING_COSINE
  }

  private static final String DOC_A = "d:/docs/lucene.md";
  private static final String DOC_B = "d:/docs/baking.md";

  /** A citation identity with nothing behind it in the index — a lookup here resolves nothing. */
  private static final String NOT_INDEXED = "d:/docs/never-indexed.md";

  /** Text indexed as chunk 0 of DOC_A — deliberately disjoint from the passage supplied below. */
  private static final String CHUNK_ZERO_TEXT =
      "This document opens with a table of contents and a note about licensing.";

  /** The passage a selection would supply: it lives deep in the document, not at chunk 0. */
  private static final String SUPPLIED_PASSAGE =
      "Pagination uses searchAfter with a cursor that encodes the sort key and a document"
          + " identifier, so the reader can resume exactly where the previous page stopped.";

  private static final String SENTENCE_ABOUT_PASSAGE =
      "The cursor encodes a sort key and a document identifier.";
  private static final String SENTENCE_ABOUT_CHUNK_ZERO =
      "The document opens with a table of contents and a licensing note.";

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private final Map<String, String> parentContentById = new HashMap<>();
  /** Chunk fixtures per parent, re-stamped whenever that parent grows to a new revision. */
  private final Map<String, List<Map<String, Object>>> chunkDocsByParent = new HashMap<>();

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    indexChunk(DOC_A, 0, CHUNK_ZERO_TEXT);
    indexChunk(DOC_A, 8, SUPPLIED_PASSAGE);
    indexChunk(DOC_B, 0, "Sourdough starters need a warm kitchen and a patient baker.");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  // ==================== 5.1 / 5.2 / 5.3 ====================

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("supplied text wins: the passage is scored, the citation's chunk 0 is not")
  void suppliedTextWins(Producer producer) {
    CitationMatchOps ops = opsFor(producer);

    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE + " " + SENTENCE_ABOUT_CHUNK_ZERO,
            List.of(DOC_A),
            List.of(0),
            List.of(SUPPLIED_PASSAGE),
            0.3);

    assertEquals(2, response.getSentencesTotal());
    assertEquals(
        1,
        response.getMatchesCount(),
        "only the sentence quoting the supplied passage should match");
    var match = response.getMatches(0);
    assertEquals(0, match.getSentenceIndex(), "the passage sentence is sentence 0");
    assertEquals(0, match.getSourceIndex());
    assertEquals(DOC_A, match.getParentDocId());
    assertEquals("SUPPLIED", match.getTextSource());
    assertTrue(
        match.getSimilarity() >= 0.3,
        "the supplied passage must score the sentence it actually supports");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("supplied text is scored even when the citation resolves to nothing in the index")
  void suppliedTextNeedsNoLookup(Producer producer) {
    CitationMatchOps ops = opsFor(producer);

    // This doc id has no indexed chunk at all: a lookup would yield nothing to score, so a match
    // here can ONLY have come from the supplied text. A score-only assertion against an indexed
    // doc could not tell "verified the passage" from "quietly looked the chunk up anyway".
    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE,
            List.of(NOT_INDEXED),
            List.of(0),
            List.of(SUPPLIED_PASSAGE),
            0.3);

    assertEquals(1, response.getMatchesCount());
    assertEquals("SUPPLIED", response.getMatches(0).getTextSource());
    assertEquals(NOT_INDEXED, response.getMatches(0).getParentDocId());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("a blank source whose chunk is missing yields no match, and re-points at nobody")
  void missingChunkIsUnverifiableNotMisattributed(Producer producer) {
    CitationMatchOps ops = opsFor(producer);

    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE,
            List.of(NOT_INDEXED, DOC_B),
            List.of(0, 0),
            List.of(),
            0.3);

    for (var m : response.getMatchesList()) {
      assertNotEquals(
          0, m.getSourceIndex(), "the unresolvable source must never be credited with a match");
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("fallback granularity is per source, not all-or-nothing")
  void perSourceFallback(Producer producer) {
    CitationMatchOps ops = opsFor(producer);

    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE + " Sourdough starters need a warm kitchen.",
            List.of(DOC_A, DOC_B),
            List.of(0, 0),
            List.of(SUPPLIED_PASSAGE, ""),
            0.3);

    assertEquals(2, response.getMatchesCount(), "both sentences find their own source");

    var bySource = new HashMap<Integer, String>();
    for (var m : response.getMatchesList()) {
      bySource.put(m.getSourceIndex(), m.getTextSource());
    }
    assertEquals("SUPPLIED", bySource.get(0), "source 0 was verified against supplied text");
    assertEquals("CHUNK_LOOKUP", bySource.get(1), "source 1 was verified against a looked-up chunk");
  }

  // ==================== 5.4 — the numbering contract on the wire ====================

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("a multi-window source reports its SOURCE position, never a window ordinal")
  void windowingPreservesSourceNumbering(Producer producer) {
    CitationMatchOps ops = opsFor(producer);
    // Source 1's passage is long enough to become many windows, and the text that supports the
    // sentence sits in the LAST of them — so the winning WINDOW ordinal is far from the source
    // position, and a pass-through of the window ordinal reports a source that does not exist.
    String longPassage =
        repeatTo("Unrelated preamble about warm kitchens and patient bakers. ", 12_000)
            + " "
            + SUPPLIED_PASSAGE;

    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE,
            List.of(DOC_B, DOC_A),
            List.of(0, 8),
            List.of("Sourdough starters need a warm kitchen.", longPassage),
            0.3);

    assertTrue(response.getMatchesCount() > 0, "the long passage supports the sentence");
    for (var m : response.getMatchesList()) {
      assertTrue(
          m.getSourceIndex() >= 0 && m.getSourceIndex() < 2,
          "source_index " + m.getSourceIndex() + " is not a position in a 2-source request");
      assertEquals(
          List.of(DOC_B, DOC_A).get(m.getSourceIndex()),
          m.getParentDocId(),
          "parent_doc_id must be chunk_doc_ids[source_index]");
    }
    assertEquals(1, response.getMatches(0).getSourceIndex(), "the passage is source 1");
  }

  // ==================== 5.8 — the chunk path is unchanged ====================

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("with no supplied text the chunk lookup path runs exactly as before")
  void chunkPathUnchangedWhenNothingSupplied(Producer producer) {
    CitationMatchOps ops = opsFor(producer);

    MatchCitationsResponse response =
        ops.execute(SENTENCE_ABOUT_CHUNK_ZERO, List.of(DOC_A), List.of(0), List.of(), 0.3);

    assertEquals(1, response.getMatchesCount());
    assertEquals("CHUNK_LOOKUP", response.getMatches(0).getTextSource());
    assertEquals(0, response.getMatches(0).getSourceIndex());
  }

  // ==================== 5.10 — provenance on every path ====================

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("every response names its producer and every match names its text source")
  void provenanceIsPopulated(Producer producer) {
    CitationMatchOps ops = opsFor(producer);

    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE, List.of(DOC_A), List.of(0), List.of(SUPPLIED_PASSAGE), 0.3);

    assertEquals(producer.name(), response.getScorer());
    assertFalse(response.getMatchesList().isEmpty());
    for (var m : response.getMatchesList()) {
      assertNotEquals("", m.getTextSource(), "a match with no text_source reads as an answer");
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Producer.class)
  @DisplayName("sentences_scored is reported, and never exceeds sentences_total")
  void sentencesScoredIsReported(Producer producer) {
    CitationMatchOps ops = opsFor(producer);

    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE + " " + SENTENCE_ABOUT_CHUNK_ZERO,
            List.of(DOC_A),
            List.of(0),
            List.of(SUPPLIED_PASSAGE),
            0.3);

    assertEquals(2, response.getSentencesTotal());
    assertEquals(2, response.getSentencesScored(), "both sentences fit the budget here");
    assertTrue(response.getSentencesMatched() <= response.getSentencesScored());
  }

  @DisplayName("with no producer at all, the response says NONE rather than looking ungrounded")
  @ParameterizedTest(name = "supplied={0}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void noProducerReportsNone(boolean supplied) {
    CitationMatchOps ops =
        new CitationMatchOps(
            lifecycle.readPathOps(), lifecycle.commitOps(), new UnavailableEmbeddings());

    MatchCitationsResponse response =
        ops.execute(
            SENTENCE_ABOUT_PASSAGE,
            List.of(DOC_A),
            List.of(0),
            supplied ? List.of(SUPPLIED_PASSAGE) : List.of(),
            0.3);

    assertEquals("NONE", response.getScorer());
    assertEquals("EMBEDDING_UNAVAILABLE", response.getError());
    assertEquals(0, response.getSentencesScored());
  }

  // ==================== Helpers ====================

  private CitationMatchOps opsFor(Producer producer) {
    CitationMatchOps ops =
        new CitationMatchOps(
            lifecycle.readPathOps(),
            lifecycle.commitOps(),
            producer == Producer.EMBEDDING_COSINE
                ? new BagOfWordsEmbeddings()
                : new UnavailableEmbeddings());
    if (producer == Producer.CROSS_ENCODER) {
      ops.setCrossEncoderProducer(CitationMatchOpsLiteralPassageTest::bagOfWordsCrossEncoder);
    }
    return ops;
  }

  /**
   * A deterministic stand-in for the cross-encoder: it scores a (sentence, passage) pair by term
   * overlap, which reproduces the property the real scorer has and the tests depend on — text that
   * supports the sentence scores higher than text that does not.
   */
  // deadlineMs is unused in this stub body, but the method reference above is installed as a
  // CitationMatchOps.CrossEncoderProducer, a @FunctionalInterface whose scoreAll signature
  // fixes this parameter list exactly.
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private static CitationScorer.ScoringResult bagOfWordsCrossEncoder(
      List<String> sentences,
      List<String> passages,
      List<String> passageDocIds,
      double threshold,
      long deadlineMs) {

    List<CitationScorer.ScoredMatch> matches = new ArrayList<>();
    int matched = 0;
    for (int si = 0; si < sentences.size(); si++) {
      double best = 0.0;
      int bestIdx = -1;
      for (int pi = 0; pi < passages.size(); pi++) {
        if (passages.get(pi) == null || passages.get(pi).isBlank()) {
          continue;
        }
        double score = overlap(sentences.get(si), passages.get(pi));
        if (score > best) {
          best = score;
          bestIdx = pi;
        }
      }
      if (bestIdx >= 0 && best >= threshold) {
        matched++;
        matches.add(
            new CitationScorer.ScoredMatch(
                si,
                sentences.get(si),
                bestIdx,
                bestIdx < passageDocIds.size() ? passageDocIds.get(bestIdx) : "",
                best));
      }
    }
    return new CitationScorer.ScoringResult(
        matches, sentences.size(), matched, 1L, sentences.size());
  }

  /** Fraction of the sentence's terms present in the passage. */
  private static double overlap(String sentence, String passage) {
    Set<String> sentenceTerms = terms(sentence);
    if (sentenceTerms.isEmpty()) {
      return 0.0;
    }
    Set<String> passageTerms = terms(passage);
    int hits = 0;
    for (String t : sentenceTerms) {
      if (passageTerms.contains(t)) {
        hits++;
      }
    }
    return (double) hits / sentenceTerms.size();
  }

  private static Set<String> terms(String text) {
    Set<String> out = new java.util.HashSet<>();
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
      if (raw.length() >= 4) {
        out.add(raw);
      }
    }
    return out;
  }

  /** Cosine over a bag-of-words vector space, so the fallback branch scores like the CE arm. */
  private static final class BagOfWordsEmbeddings implements EmbeddingProvider {
    private static final List<String> VOCAB =
        List.of(
            "cursor", "sort", "document", "identifier", "pagination", "searchafter", "resume",
            "reader", "page", "contents", "table", "licensing", "note", "opens", "sourdough",
            "starters", "warm", "kitchen", "baker", "patient");

    @Override
    public float[] embedDocument(String text) {
      return vector(text);
    }

    @Override
    public float[] embedQuery(String text) {
      return vector(text);
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      return texts.stream().map(BagOfWordsEmbeddings::vector).toList();
    }

    @Override
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return VOCAB.size();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isUsingGpu() {
      return false;
    }

    private static float[] vector(String text) {
      Set<String> present = terms(text);
      float[] v = new float[VOCAB.size()];
      for (int i = 0; i < VOCAB.size(); i++) {
        v[i] = present.contains(VOCAB.get(i)) ? 1f : 0f;
      }
      return v;
    }
  }

  /** Reports unavailable, which is how a Worker with neither producer wired behaves. */
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

  private static String repeatTo(String seed, int chars) {
    StringBuilder sb = new StringBuilder(chars + seed.length());
    while (sb.length() < chars) {
      sb.append(seed);
    }
    return sb.substring(0, chars);
  }

  private void indexChunk(String parentDocId, int chunkIndex, String content) throws Exception {
    String previous = parentContentById.getOrDefault(parentDocId, "");
    String separator = previous.isEmpty() ? "" : "\n";
    int start = previous.length() + separator.length();
    String parentContent = previous + separator + content;
    parentContentById.put(parentDocId, parentContent);
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, parentDocId,
                    SchemaFields.DOC_UID, parentDocId + "#0",
                    SchemaFields.PATH, parentDocId,
                    SchemaFields.CONTENT, parentContent)));
    Map<String, Object> chunk = new HashMap<>();
    chunk.put(SchemaFields.DOC_ID, "chunk:" + parentDocId + "#" + chunkIndex);
    chunk.put(SchemaFields.DOC_UID, "chunk:" + parentDocId + "#" + chunkIndex + "#0");
    chunk.put(SchemaFields.PATH, parentDocId);
    chunk.put(SchemaFields.PARENT_DOC_ID, parentDocId);
    chunk.put(SchemaFields.IS_CHUNK, "true");
    chunk.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunkIndex));
    chunk.put(SchemaFields.CHUNK_TOTAL, "2");
    chunk.put(SchemaFields.CHUNK_CONTENT, content);
    chunk.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(start));
    chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(start + content.length()));
    chunkDocsByParent.computeIfAbsent(parentDocId, k -> new ArrayList<>()).add(chunk);
    // The parent grew, so it is at a NEW revision and every chunk already cut from it is stale --
    // the state the read-path guard (tempdoc 931 section E item 5) refuses to reconstruct from.
    // Production regenerates chunks after a parent rewrite; this fixture re-stamps them the same
    // way, so the guard sees the in-step state a real corpus is in.
    for (Map<String, Object> sibling : chunkDocsByParent.get(parentDocId)) {
      sibling.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(parentContent));
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(new HashMap<>(sibling)));
    }
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }
}
