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
import java.util.ArrayList;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NX3c: malformed grouped runs and preservation boundaries through production scorer paths. */
class NumericCitationNx3cTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
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
        scores[i] = scoredPassages.get(i).startsWith("alternative") ? 0.8f : 0.976f;
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
  void rejectsNx3cFragmentsOfMalformedGroupedPassages() {
    assertAll(List.of(" ", " ", " ", ",", "٬", "，").stream().map(separator -> () -> {
      String grouped = String.join(separator, "1", "200", "000");
      assertAll(List.of(".5.1.", ",4.", ".5,1.", "0.").stream().map(ending -> () ->
          checkPair("The shipment contains 200.", "The shipment contains " + grouped + ending, false)));
    }));
  }

  @Test
  void preservesNx3cLiteralControlsAndSkippedClaims() throws Exception {
    assertAll(List.of(" ", " ", " ", ",", "٬", "，").stream().map(separator -> () -> {
      String grouped = String.join(separator, "1", "200", "000");
      checkPair("The shipment contains 1200000.", "The shipment contains " + grouped + ".5.1", false);
      checkPair("The shipment contains 1200000.5.", "The shipment contains " + grouped + ".5", true);
      checkPair("The shipment contains 200.", "The shipment contains " + grouped + ".", false);
      checkPair("The shipment contains " + grouped + ".5.1.", "No number here.", true);
    }));
    checkPair("Opened in 1883.", "The value is 0x10,1883.", true);
    checkPair("Opened in 1883.", "The value is 0x10 1883.", true);
    checkPair("The shipment contains 200.", "1 200 000.5.1. Also contains 200.", true);
    checkPair("The shipment contains 1 200 000.5.1 and 42.", "No number here.", false);
    assertAll(() -> checkPair("The shipment contains 4.", "values 3, 4, 5", true),
        () -> checkPair("The shipment contains 1.2.", "version 1.2.3", false),
        () -> checkPair("The shipment contains 1200.", "1,200 5kg bags", true));
  }

  @Test
  void preservesNx3cMarkdownKeysScoresAndCounts() throws Exception {
    for (String indentation : List.of("", "    ", "     ", "        ")) {
      String answer = indentation + "The engine owns the index.";
      assertEquals(List.of("The engine owns the index."),
          AnswerSegmentation.splitSentences(answer), "Markdown code indentation stays structural");
      checkPair(answer, "The engine owns the index.", true);
      // Code brackets remain data; the same prose brackets remain a reference marker.
      checkPair(indentation + "The array contains ［４］.", "The array contains [5].",
          indentation.isEmpty());
    }
  }

  /** Production segmentation feeds the real CE loop and both raw cosine passage paths. */
  private void checkPair(String answer, String passage, boolean compatible) throws Exception {
    String label = "answer=" + answer + ", passage=" + passage;
    var claims = AnswerSegmentation.splitClaims(answer);
    assertEquals(1, claims.size(), label);
    index(DOC_A, passage);
    assertAll(() -> {
      var result = scorer.scoreClaims(claims, List.of(passage), List.of(DOC_A), 0.5, 2000,
          InferenceRequest.foreground());
      var expected = compatible ? List.of(new CitationScorer.ScoredMatch(
          0, claims.getFirst().text(), 0, DOC_A, 0.976f)) : List.of();
      assertEquals(expected, result.matches(), "cross-encoder: " + label);
      assertEquals(1, result.sentencesScored(), label);
      assertEquals(compatible ? 1 : 0, result.sentencesMatched(), label);
      assertEquals(List.of(), scorer.scoreClaims(claims, List.of(passage), List.of(DOC_A), 0.99,
          2000, InferenceRequest.foreground()).matches(), "never promote a low score: " + label);
    }, () -> assertAll(List.of(true, false).stream().map(supplied -> () -> assertAll(() -> {
      var ops = new CitationMatchOps(runtime.readPathOps(), runtime.commitOps(), new FixedEmbeddings());
      ops.setCitationScorer(scorer);
      var response = ops.execute(answer, List.of(DOC_A), List.of(0),
          supplied ? List.of(passage) : List.of(), 0.5, InferenceRequest.foreground());
      assertEquals(compatible ? 1 : 0, response.getMatchesCount(), "Worker cross-encoder supplied=" + supplied + ": " + label);
      assertEquals(compatible ? 1 : 0, response.getSentencesMatched(), label);
      assertEquals(1, response.getSentencesScored(), label);
      if (compatible) {
        assertEquals(claims.getFirst().text(), response.getMatches(0).getSentenceText(), label);
        assertEquals((double) 0.976f, response.getMatches(0).getSimilarity(), 0.0, label);
      }
    }, () -> {
      var cosine = execute(answer, List.of(DOC_A), supplied ? List.of(passage) : List.of(), 0.5);
      assertEquals(compatible ? 1 : 0, cosine.getMatchesCount(), "raw cosine supplied=" + supplied + ": " + label);
      assertEquals(compatible ? 1 : 0, cosine.getSentencesMatched(), label);
      assertEquals(1, cosine.getSentencesScored(), label);
      if (compatible) {
        assertEquals(claims.getFirst().text(), cosine.getMatches(0).getSentenceText(), label);
        assertEquals(1.0, cosine.getMatches(0).getSimilarity(), 0.0, label);
      }
    }))), () -> assertEquals(0, execute(answer, List.of(DOC_A),
        List.of("alternative: " + passage), 0.9).getMatchesCount(), "never promote a low cosine score: " + label));
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

  /** Deliberately ignores numbers: the incompatible source wins unless the real guard intervenes. */
  private static final class FixedEmbeddings implements EmbeddingProvider {
    @Override public float[] embedDocument(String text) {
      return text.startsWith("alternative") ? new float[] {0.8f, 0.6f} : new float[] {1, 0};
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
