/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.rag.ChunkDocumentWriter;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.reranker.CitationScorer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * WP4.4 S7.2 — both retrieval paths that synthesize passages for chunkless documents (the
 * primary union leg and the virtual-chunk fallback) hand back scorable passage text, aligned with
 * their citations, and that text verifies when the Head supplies it.
 *
 * <p>Documents are indexed the way indexing does it: {@link ChunkDocumentWriter#plan} decides
 * whether chunk documents exist at all, so the 1,999-char document and the one above 2,000 chars
 * that still splits into a single chunk are chunkless, and the 2,000/2,001-char documents are
 * chunked. Matching then consumes the response the way the Head now does — section {@code i}'s
 * content as the literal text of citation {@code i} — on both producer paths.
 *
 * <p>The lookup-only arm is the before-state: a chunkless document's synthesized citation names a
 * chunk that was never stored, so verifying by lookup examines nothing.
 */
@DisplayName("Short-document passages are scorable on both retrieval paths (WP4.4 S7.2)")
class ShortDocumentPassageVerificationTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private enum Producer {
    CROSS_ENCODER,
    EMBEDDING_COSINE
  }

  private enum RetrievalPath {
    /** The primary path; chunkless documents enter it through the doc-level union leg. */
    PRIMARY,
    /** {@code buildFallbackWithVirtualChunks}, reached via return_full_documents. */
    VIRTUAL_CHUNKS
  }

  private enum Doc {
    LEN_1999(1999, 0, true),
    LEN_2000(2000, 0, false),
    LEN_2001(2001, 0, false),
    /** Above the threshold, but its trimmed text fits one chunk — so indexing stores none. */
    LEN_2001_SINGLE_CHUNK(1900, 101, true),
    LEN_2600_SINGLE_CHUNK(1900, 700, true);

    final int textChars;
    final int trailingNewlines;
    final boolean chunkless;

    Doc(int textChars, int trailingNewlines, boolean chunkless) {
      this.textChars = textChars;
      this.trailingNewlines = trailingNewlines;
      this.chunkless = chunkless;
    }

    String content() {
      return document(textChars) + "\n".repeat(trailingNewlines);
    }
  }

  private static final String DOC_ID = "d:/notes/garden.md";

  /** A fact at the very END of the document, well past the 240-char display excerpt. */
  private static final String TAIL_FACT = "The zircon lantern burns violet after midnight.";

  private static final String TAIL_SENTENCE = "The zircon lantern burns violet after midnight.";
  private static final String UNSUPPORTED_SENTENCE = "Penguins operate the quarterly payroll.";

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
            .atPath(tempDir)
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
    service = new WorkerSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  static Stream<Arguments> cases() {
    List<Arguments> out = new ArrayList<>();
    for (Doc doc : Doc.values()) {
      for (RetrievalPath path : RetrievalPath.values()) {
        for (Producer producer : Producer.values()) {
          out.add(Arguments.of(doc, path, producer));
        }
      }
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0} via {1}, {2}")
  @MethodSource("cases")
  @DisplayName("section text verifies the tail fact and leaves an unsupported sentence unverified")
  void sectionTextVerifies(Doc doc, RetrievalPath path, Producer producer) throws Exception {
    String content = doc.content();
    index(content);
    assertEquals(
        doc.chunkless,
        ChunkDocumentWriter.plan(content, "text/plain", null).chunks().isEmpty(),
        "precondition: indexing stores chunks for this document iff it is not chunkless");

    RetrieveContextResponse response = retrieve(path);
    assertTrue(response.getUsedChunks(), "the document must be served: " + response);
    assertEquals(
        response.getChunksCount(),
        response.getSectionsCount(),
        "section i <=> citation i is what lets the Head pair text with a citation");

    List<String> docIds = new ArrayList<>();
    List<Integer> indices = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    for (int i = 0; i < response.getChunksCount(); i++) {
      docIds.add(response.getChunks(i).getParentDocId());
      indices.add(response.getChunks(i).getChunkIndex());
      texts.add(response.getSections(i).getContent());
      assertFalse(texts.get(i).isBlank(), "section " + i + " carries scorable text");
      assertTrue(
          response.getContext().contains(texts.get(i)), "section " + i + " is prompt text");
    }
    assertTrue(
        texts.stream().anyMatch(t -> t.contains(TAIL_FACT)),
        "the passage carries the document's end, not just its excerpt");

    MatchCitationsResponse match =
        opsFor(producer)
            .execute(
                TAIL_SENTENCE + " " + UNSUPPORTED_SENTENCE,
                docIds,
                indices,
                texts,
                0.5,
                InferenceRequest.foreground());

    assertEquals(2, match.getSentencesScored(), "both sentences were examined: " + match);
    assertEquals(1, match.getMatchesCount(), "only the supported sentence verifies: " + match);
    var m = match.getMatches(0);
    assertEquals(0, m.getSentenceIndex());
    assertEquals(DOC_ID, m.getParentDocId());
    assertEquals("SUPPLIED", m.getTextSource());
    assertTrue(
        match.getSourceCoverage(m.getSourceIndex()).getWindowsConsidered() > 0,
        "the matched source's text was examined");
  }

  @ParameterizedTest(name = "{0} via {1}")
  @MethodSource("chunklessOnPrimaryAndFallback")
  @DisplayName("before-state: a chunkless passage verified by chunk lookup examines nothing")
  void chunkLookupFindsNothingForChunklessDocument(Doc doc, RetrievalPath path) throws Exception {
    index(doc.content());
    RetrieveContextResponse response = retrieve(path);

    List<String> docIds = new ArrayList<>();
    List<Integer> indices = new ArrayList<>();
    for (var chunk : response.getChunksList()) {
      docIds.add(chunk.getParentDocId());
      indices.add(chunk.getChunkIndex());
    }
    MatchCitationsResponse match =
        opsFor(Producer.CROSS_ENCODER)
            .execute(
                TAIL_SENTENCE, docIds, indices, List.of(), 0.5, InferenceRequest.foreground());

    assertEquals(0, match.getMatchesCount(), match.toString());
    for (var coverage : match.getSourceCoverageList()) {
      assertEquals(0, coverage.getWindowsConsidered(), "no stored chunk exists to look up");
    }
  }

  static Stream<Arguments> chunklessOnPrimaryAndFallback() {
    List<Arguments> out = new ArrayList<>();
    for (Doc doc : Doc.values()) {
      if (!doc.chunkless) continue;
      for (RetrievalPath path : RetrievalPath.values()) {
        out.add(Arguments.of(doc, path));
      }
    }
    return out.stream();
  }

  // ==================== fixtures ====================

  /** {@code length} chars of prose whose LAST sentence is the tail fact. */
  private static String document(int length) {
    String seed = "Quarterly notes about the garden shed and its paint. ";
    StringBuilder sb = new StringBuilder();
    int body = length - TAIL_FACT.length();
    while (sb.length() < body) {
      sb.append(seed);
    }
    sb.setLength(body - 1);
    sb.append(' ').append(TAIL_FACT);
    return sb.toString();
  }

  /** Indexes the parent, plus chunk documents exactly when indexing would write them. */
  private void index(String content) throws Exception {
    var plan = ChunkDocumentWriter.plan(content, "text/plain", null);
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, DOC_ID,
                    SchemaFields.DOC_UID, DOC_ID + "#0",
                    SchemaFields.PATH, DOC_ID,
                    SchemaFields.CONTENT, content,
                    SchemaFields.MIME, "text/plain")));
    for (ChunkSplitter.Chunk chunk : plan.chunks()) {
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, "chunk:" + DOC_ID + "-" + chunk.index());
      fields.put(SchemaFields.DOC_UID, "chunk:" + DOC_ID + "-" + chunk.index() + "#0");
      fields.put(SchemaFields.PATH, DOC_ID);
      fields.put(SchemaFields.PARENT_DOC_ID, DOC_ID);
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunk.index()));
      fields.put(SchemaFields.CHUNK_TOTAL, String.valueOf(plan.chunks().size()));
      fields.put(SchemaFields.CHUNK_CONTENT, chunk.content());
      fields.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(chunk.startChar()));
      fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(chunk.endChar()));
      fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, plan.revision());
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  private RetrieveContextResponse retrieve(RetrievalPath path) {
    RetrieveContextRequest request =
        RetrieveContextRequest.newBuilder()
            .setQuestion("Which lantern burns violet after midnight?")
            .addDocIds(DOC_ID)
            .setTopK(5)
            .setReturnFullDocuments(path == RetrievalPath.VIRTUAL_CHUNKS)
            .build();
    RetrieveContextResponse response;
    try {
      response = service.retrieveContext(request, CallContext.none());
    } catch (WorkerServiceException e) {
      return fail("RetrieveContext failed: " + e.getMessage());
    }
    if (path == RetrievalPath.VIRTUAL_CHUNKS) {
      assertEquals("FULL_DOCUMENT", response.getRetrievalMode(), "the virtual-chunk path ran");
    } else {
      assertFalse(
          response.getRetrievalMode().equals("FULLTEXT_FALLBACK")
              || response.getRetrievalMode().equals("FULL_DOCUMENT"),
          "the primary path ran: " + response.getRetrievalMode());
    }
    return response;
  }

  private CitationMatchOps opsFor(Producer producer) {
    CitationMatchOps ops =
        new CitationMatchOps(
            lifecycle.readPathOps(),
            lifecycle.commitOps(),
            producer == Producer.EMBEDDING_COSINE
                ? new HashedBagOfWords(EmbeddingProvider.Availability.READY)
                : new HashedBagOfWords(EmbeddingProvider.Availability.ABSENT));
    if (producer == Producer.CROSS_ENCODER) {
      ops.setCrossEncoderProducer(ShortDocumentPassageVerificationTest::overlapCrossEncoder);
    }
    return ops;
  }

  /** Stand-in cross-encoder: a pair scores the fraction of the sentence's terms the passage has. */
  // deadlineMs and acquisition are fixed by the CrossEncoderProducer signature.
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private static CitationScorer.ScoringResult overlapCrossEncoder(
      List<String> sentences,
      List<String> passages,
      List<String> passageDocIds,
      double threshold,
      long deadlineMs,
      InferenceRequest acquisition) {
    List<CitationScorer.ScoredMatch> matches = new ArrayList<>();
    for (int si = 0; si < sentences.size(); si++) {
      Set<String> needed = terms(sentences.get(si));
      double best = 0.0;
      int bestIdx = -1;
      for (int pi = 0; pi < passages.size(); pi++) {
        Set<String> have = terms(passages.get(pi));
        double score =
            needed.isEmpty() ? 0.0 : needed.stream().filter(have::contains).count()
                / (double) needed.size();
        if (score > best) {
          best = score;
          bestIdx = pi;
        }
      }
      if (bestIdx >= 0 && best >= threshold) {
        matches.add(
            new CitationScorer.ScoredMatch(
                si, sentences.get(si), bestIdx, passageDocIds.get(bestIdx), best));
      }
    }
    return new CitationScorer.ScoringResult(
        matches, sentences.size(), matches.size(), 1L, sentences.size());
  }

  private static Set<String> terms(String text) {
    Set<String> out = new HashSet<>();
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
      if (raw.length() >= 4) out.add(raw);
    }
    return out;
  }

  /** Cosine over hashed term presence, so the fallback branch scores like the stand-in CE. */
  private static final class HashedBagOfWords implements EmbeddingProvider {
    private static final int DIMENSION = 512;
    private final EmbeddingProvider.Availability availability;

    HashedBagOfWords(EmbeddingProvider.Availability availability) {
      this.availability = availability;
    }

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
      return texts.stream().map(HashedBagOfWords::vector).toList();
    }

    @Override
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return DIMENSION;
    }

    @Override
    public EmbeddingProvider.Availability availability() {
      return availability;
    }

    @Override
    public boolean isUsingGpu() {
      return false;
    }

    private static float[] vector(String text) {
      float[] v = new float[DIMENSION];
      for (String term : terms(text)) {
        v[Math.floorMod(term.hashCode(), DIMENSION)] = 1f;
      }
      return v;
    }
  }
}
