/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.CitationScorer;
import io.justsearch.reranker.RerankerShape;
import io.justsearch.reranker.RerankerTokenizer;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.MatchCitationsResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NX4: a model window must retain complete source numbers at its edges. */
class NumericCitationWindowTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final String DOC_A = "numeric-a.md";
  @TempDir Path tempDir;
  private RunningRuntime runtime;
  private CitationScorer scorer;

  @BeforeEach
  void setUp() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    var scoredPassages = new ArrayList<String>();
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(sessions.acquire(any())).thenReturn(lease);
    var tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairsStrict(anyString(), any(String[].class))).thenAnswer(call -> {
      String[] passages = call.getArgument(1);
      scoredPassages.clear();
      scoredPassages.addAll(List.of(passages));
      return new RerankerTokenizer.EncodedBatch(new long[passages.length][2],
          new long[passages.length][2], new long[passages.length][2], passages.length, 2, 0, 2);
    });
    when(lease.run(anyMap())).thenAnswer(call -> {
      float[] scores = new float[scoredPassages.size()];
      for (int i = 0; i < scores.length; i++) {
        scores[i] = belowThreshold(scoredPassages.get(i)) ? 0.4f : 0.976f;
      }
      var tensor = mock(OnnxTensor.class);
      when(tensor.getValue()).thenReturn(scores);
      var result = mock(OrtSession.Result.class);
      when(result.get(0)).thenReturn(tensor);
      return result;
    });
    scorer = new CitationScorer(sessions, new RerankerShape(2, false), tokenizer);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (scorer != null) scorer.close();
    if (runtime != null) runtime.close();
  }

  @Test
  void rejectsGroupedSpacePrefixInventedByWindowing() throws Exception {
    checkPair("The shipment contains 1200.", groupedSpaceSource(), false);
    for (String space : List.of("\u00a0", "\u202f", "\u2009", "\u2007")) {
      checkPair("The shipment contains 1200.", groupedSpaceSource().replace("1 200 000", "1" + space + "200" + space + "000"), false);
    }
  }

  @Test
  void rejectsCommaPrefixInventedByHardCut() throws Exception {
    checkPair("The shipment contains 1200.", "x".repeat(1495) + "1,200,000.", false);
  }

  @Test
  void rejectsDecimalSuffixInventedByHardCut() throws Exception {
    checkPair("The shipment contains 200.", "x".repeat(1498) + "1.200.", false);
  }

  @Test
  void rejectsDigitPrefixInventedByHardCut() throws Exception {
    checkPair("The shipment contains 1200.", "x".repeat(1496) + "1200000.", false);
  }

  @Test
  void rejectsDigitSuffixFromNumberLongerThanWindow() throws Exception {
    checkPair("The shipment contains 1200.", "9".repeat(1500) + "1200.", false);
  }

  @Test
  void retainsWholeValuesAcrossWindowBoundaries() {
    assertAll(
        () -> checkPair("The shipment contains 1200000.", groupedSpaceSource(), true),
        () -> checkPair("The shipment contains 1200000.", "x".repeat(1495) + "1,200,000.", true),
        () -> checkPair("The shipment contains 1.200.", "x".repeat(1498) + "1.200.", true),
        () -> checkPair("The shipment contains 1200000.", "x".repeat(1496) + "1200000.", true));
  }

  @Test
  void retainsCompatibilityAndSupplementaryDigitsAcrossCuts() {
    assertAll(
        () -> checkPair("The shipment contains 1200000.", "ﬃ".repeat(10) + "x".repeat(1486) + "１２０００００.", true),
        // Only the second window clears the threshold; the cut bisects the digit's surrogate pair.
        () -> checkPair("The shipment contains 1.", "alternative" + "x".repeat(1488) + "𝟙.", true));
  }

  @Test
  void retainsExistingWindowVerdictWhenGroupingFollowsAnUngroupedValue() throws Exception {
    // The first window is below threshold; the second already read 1 200 as 1200.
    checkPair("The shipment contains 1200.",
        "alternative" + "x".repeat(1485) + "1200 1 200.", true);
  }

  @Test
  void preservesWholeValuesAndWindowLocalEligibility() throws Exception {
    checkPair("The shipment contains 1200.", "x".repeat(1495) + " 1200.", true);
    checkPair("The shipment contains 1200.", "x".repeat(1500) + " 1200.", true);
    checkPair("The shipment contains 1200.", "x".repeat(1496) + "1200.", true);
    checkPair("The shipment contains 1200.", "alternative" + "x".repeat(1485) + "1200.", false);
    checkPair("The shipment contains 1200.", "x".repeat(1498) + "1.200.", false);
    checkPair("The shipment contains 1200.", "x".repeat(1500) + "alternative 1200.", false);
    checkPair("The shipment is packed.", groupedSpaceSource(), true);
  }

  @Test
  void rejectsLongMalformedSourceWithoutRecursion() {
    assertDoesNotThrow(() -> checkPair("The shipment contains 5.", "1.".repeat(10000) + "1", false));
  }

  @Test
  void preservesNonLatinClaimAgainstLongMalformedSource() {
    assertDoesNotThrow(() -> {
      checkPair("تحتوي الشحنة على ٥.", "1.".repeat(10000) + "1", false);
      checkPair("تحتوي الشحنة على ٥.", "1.".repeat(10000) + "1 5.", true);
    });
  }

  @Test
  void scoresCombiningMarksAcrossWindowBoundary() {
    // NFKC(prefix) has length 1500; NFKC(source) has length 1499.
    assertAll(
        () -> checkPair("The shipment contains 1200.", "x".repeat(1498) + "L\u0304\u0323", false),
        () -> checkPair("The shipment contains 1200.", "1200" + "x".repeat(1494) + "L\u0304\u0323", true));
  }

  @Test
  void keepsCombiningBoundaryValuesWindowLocal() {
    assertAll(
        // The value is confined to the below-threshold first window.
        () -> checkPair("The shipment contains 1200.",
            "alternative" + "x".repeat(1483) + "1200L\u0304\u0323", false),
        // Prefix normalization must not move this value into the preceding high-scoring window.
        () -> checkPair("The shipment contains 1200.",
            "x".repeat(1498) + "L\u0304\u0323" + "1200below-threshold", false));
  }

  @Test
  void preservesOtherLengthChangingUnicodeAtWindowBoundaries() {
    assertAll(
        // Lowercasing for admission expands İ; ß is never uppercased in this path.
        () -> checkPair("The shipment contains 1200.", "İß".repeat(100) + "x".repeat(1296) + "1200.", true),
        () -> checkPair("The shipment contains 1200.", "ﬃ".repeat(100) + "x".repeat(1396) + "１２００.", true),
        () -> checkPair("The shipment contains 1200.", "x".repeat(1499) + "\u1100\u1161 1200.", true),
        () -> checkPair("The shipment contains 1200.", "x".repeat(1499) + "ｶﾞ 1200.", true),
        () -> checkPair("The shipment contains 1200.", "😀".repeat(746) + "𝟙𝟚𝟘𝟘.", true),
        () -> checkPair("The shipment contains 1200.", "x".repeat(1493) + "１２０００００.", false));
  }

  private static String groupedSpaceSource() {
    return "Shipment ledger.\n" + " ".repeat(1456) + "The shipment contains 1 200 000.";
  }

  /** Production segmentation feeds the real CE loop and both raw cosine passage paths. */
  private void checkPair(String answer, String passage, boolean compatible) throws Exception {
    String label = "answer=" + answer + ", passageTail="
        + passage.substring(Math.max(0, passage.length() - 48)) + " (" + passage.length() + " chars)";
    var claims = AnswerSegmentation.splitClaims(answer);
    assertEquals(1, claims.size(), label);
    index(DOC_A, passage);
    assertAll(List.of(true, false).stream().map(supplied -> () -> assertAll(() -> {
      var ops = new CitationMatchOps(runtime.readPathOps(), runtime.commitOps(), new FixedEmbeddings());
      ops.setCitationScorer(scorer);
      var response = ops.execute(answer, List.of(DOC_A), List.of(0),
          supplied ? List.of(passage) : List.of(), 0.5, InferenceRequest.foreground());
      assertEquals("CROSS_ENCODER", response.getScorer(), "supplied=" + supplied + ": " + label);
      assertEquals(compatible ? 1 : 0, response.getMatchesCount(), "Worker cross-encoder supplied=" + supplied + ": " + label);
      assertEquals(compatible ? 1 : 0, response.getSentencesMatched(), label);
      assertEquals(1, response.getSentencesScored(), label);
      if (compatible) {
        assertEquals(0, response.getMatches(0).getSourceIndex(), label);
        assertEquals(DOC_A, response.getMatches(0).getParentDocId(), label);
        assertEquals(claims.getFirst().text(), response.getMatches(0).getSentenceText(), label);
        assertEquals((double) 0.976f, response.getMatches(0).getSimilarity(), 0.0, label);
      }
    }, () -> {
      var cosine = execute(answer, List.of(DOC_A), supplied ? List.of(passage) : List.of(), 0.5);
      assertEquals("EMBEDDING_COSINE", cosine.getScorer(), "supplied=" + supplied + ": " + label);
      assertEquals(compatible ? 1 : 0, cosine.getMatchesCount(), "raw cosine supplied=" + supplied + ": " + label);
      assertEquals(compatible ? 1 : 0, cosine.getSentencesMatched(), label);
      assertEquals(1, cosine.getSentencesScored(), label);
      if (compatible) {
        assertEquals(0, cosine.getMatches(0).getSourceIndex(), label);
        assertEquals(DOC_A, cosine.getMatches(0).getParentDocId(), label);
        assertEquals(claims.getFirst().text(), cosine.getMatches(0).getSentenceText(), label);
        assertEquals(1.0, cosine.getMatches(0).getSimilarity(), 0.0, label);
      }
    })));
  }

  private MatchCitationsResponse execute(String answer, List<String> docs, List<String> passages,
      double threshold) {
    return new CitationMatchOps(runtime.readPathOps(), runtime.commitOps(), new FixedEmbeddings())
        .execute(answer, docs, java.util.Collections.nCopies(docs.size(), 0), passages, threshold,
            InferenceRequest.foreground());
  }

  private void index(String id, String text) throws Exception {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, id,
        SchemaFields.CONTENT, text)));
    Map<String, Object> chunk = new HashMap<>();
    chunk.put(SchemaFields.DOC_ID, "chunk:" + id + "#0");
    chunk.put(SchemaFields.DOC_UID, "chunk:" + id + "#0#0");
    chunk.put(SchemaFields.PATH, id);
    chunk.put(SchemaFields.PARENT_DOC_ID, id);
    chunk.put(SchemaFields.IS_CHUNK, "true");
    chunk.put(SchemaFields.CHUNK_INDEX, "0");
    chunk.put(SchemaFields.CHUNK_TOTAL, "1");
    chunk.put(SchemaFields.CHUNK_CONTENT, text);
    chunk.put(SchemaFields.CHUNK_START_CHAR, "0");
    chunk.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(text.length()));
    chunk.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(text));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(chunk));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static boolean belowThreshold(String text) {
    return text.startsWith("alternative") || text.endsWith("below-threshold");
  }

  /** Deliberately ignores numbers: the incompatible source wins unless the real guard intervenes. */
  private static final class FixedEmbeddings implements EmbeddingProvider {
    @Override public float[] embedDocument(String text) {
      return belowThreshold(text) ? new float[] {0.4f, (float) Math.sqrt(0.84)} : new float[] {1, 0};
    }
    @Override public float[] embedQuery(String text) { return new float[] {1, 0}; }
    @Override public List<float[]> embedDocumentBatch(List<String> texts) {
      return texts.stream().map(this::embedDocument).toList();
    }
    @Override public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] spans) {
      return null;
    }
    @Override public int dimension() { return 2; }
    @Override public Availability availability() { return Availability.READY; }
    @Override public boolean isUsingGpu() { return false; }
  }
}
