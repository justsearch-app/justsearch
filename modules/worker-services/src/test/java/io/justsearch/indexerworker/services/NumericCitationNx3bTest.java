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

/** NX3b: whole grouping and code/reference boundaries through production scorer paths. */
class NumericCitationNx3bTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
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
  void readsNx3bGroupedValuesWholeAtEveryEnding() {
    assertAll(List.of(" ", " ", " ", ",", "٬", "，").stream().map(separator -> () -> {
      String grouped = String.join(separator, "1", "200", "000");
      assertAll(List.of(".", ",", ")", "\"", "\n", "", " ", " bags", "bags").stream()
          .map(ending -> () -> {
            String passage = "The shipment contains " + grouped + ending;
            assertAll(() -> checkPair("The shipment contains 1200.", passage, false),
                () -> checkPair("The shipment contains 1200000.", passage, true));
          }));
    }));
  }

  @Test
  void retainsNx3bWholeGroupedClaims() {
    assertAll(List.of(" ", " ", " ", ",", "٬", "，").stream().map(separator -> () -> {
      String grouped = String.join(separator, "1", "200", "000");
      checkPair("The shipment contains " + grouped + ".", "The shipment contains 1200000.", true);
    }));
  }

  @Test
  void retainsNx3bCompatibilityCodeValuesThroughSegmentation() {
    assertAll(List.of("［４］", "［4］", "﹇4﹈", "［⑫］", "[4]").stream().map(literal -> () -> {
      assertAll(List.of("The array contains `" + literal + "`.",
          "```\nThe array contains " + literal + ".\n```",
          "    The array contains " + literal + ".").stream().map(answer -> () -> {
        assertAll(() -> checkPair(answer, "The array contains [5].", false),
            () -> checkPair(answer, "The array is empty.", false));
      }));
    }));
  }

  @Test
  void preservesNx3bAsciiReferencesAndNx3aControls() throws Exception {
    checkPair("The array contains `［４］`.", "The array contains [4].", true);
    checkPair("The array contains `[4]`.", "The array contains [5].", false);
    checkPair("The array contains `[4]`.", "The array contains [4].", true);
    checkPair("The engine owns the index [4].", "The engine owns the index.", true);
    checkPair("The engine owns the index ［４］.", "The engine owns the index.", true);
    checkPair("The shipment contains 1200.", "The shipment contains 1,200 5kg bags.", true);
    checkPair("The voltage measured 5 volts.", "The voltage measured 1٫5 volts.", false);
    checkPair("The meeting starts at 1：30.", "No number here.", true);
    checkPair("The shipment contains 5，000 bags.", "The shipment contains 5000 bags.", true);
    checkPair("The engine owns the index.", "The engine owns the index.", true);
    // The existing agreed NX3 literal table: expected eligibility, never observed scores.
    String[][] pairs = {
        {"Vespera Observatory opened in 2099.", "Vespera Observatory opened in 1883.", "false"},
        {"Vespera Observatory opened in 1923.", "Vespera Observatory opened in 1883.", "false"},
        {"The value is 99.", "The value is 2099.", "false"},
        {"The value is 1.5.", "The value is 15.", "false"},
        {"The value is 1,200.", "The value is 1200.", "true"},
        {"افتتح المرصد في ٢٠٩٩.", "Opened in 1883.", "false"},
        {"The rate is %-5.", "The rate is 5.", "true"},
        {"Oran %-5.", "Oran 5.", "true"},
        {"The rate is % -5.", "The rate is -5.", "true"},
        {"The value is 0x10.", "The values are 0 and 10.", "true"},
        {"The opening date is 21/10/2026.", "The opening date is 21/10/2026.", "true"},
        {"The accepted range is 5kg-10kg.", "The accepted range is 5kg-10kg.", "true"},
        {"The area is measured in m².", "The area is measured in m².", "true"},
        {"Vespera Observatory opened in 1883.", "Vespera Observatory opened in 1883. Admission costs $5.", "true"},
        {"Admission costs $5.", "Admission costs $5.", "true"},
        {"The meeting starts at 1:30.", "The meeting starts at 1:30.", "true"},
        {"The installed version is v1.2.3.", "The installed version is v1.2.3.", "true"},
        {"The accepted range is -10 kg.", "The accepted range is 5 kg -10 kg.", "true"},
        {"The price is 15.", "The price is 1$5.", "false"},
        {"The value is 1.", "The value is 1.2.3e4.", "false"},
        {"The value is 1.", "The value is 1:30e4.", "true"},
        {"The value is 5kg%.", "The value is 5.", "true"},
        {"The value is 5.", "The value is 5kg%.", "true"},
        {"The accepted range is 5 kg - 10 kg.", "The accepted range is 5 kg - 10 kg.", "true"},
        {"The accepted range is 5 kg - 10 kg.", "The accepted range is 5 and 10 kg.", "true"},
        {"`15. literal`", "The value is 15.", "true"},
        {"The value is 15...", "The value is 15.", "true"},
        {"The value is 5 kg.", "No number here.", "false"},
        {"The value is $ 5.", "No number here.", "false"},
        {"The value is 5 %.", "No number here.", "false"},
        {"The label is 5: good.", "No number here.", "false"},
        {"The range is 5 - 10.", "Only 5.", "false"},
        {"The value is 12,34.", "No number here.", "true"},
        {"The values are 1 2.", "No number here.", "true"},
        {"The value is %-5.", "No number here.", "true"},
        {"The value is % -5.", "No number here.", "true"},
        {"The value is %5.", "No number here.", "true"},
        {"The value is 0x10.", "No number here.", "true"},
        {"The value is 0b101.", "No number here.", "true"},
        {"The value is 5kg-10kg.", "No number here.", "true"},
        {"The value is 21/10/2026.", "No number here.", "true"},
        {"The value is 2026-10-21.", "No number here.", "true"},
        {"The value is 21.10.2026.", "No number here.", "true"},
        {"The value is $5.", "No number here.", "true"},
        {"The value is -$5.", "No number here.", "true"},
        {"The value is $-5.", "No number here.", "true"},
        {"The value is 5€.", "No number here.", "true"},
        {"The value is 1:30.", "No number here.", "true"},
        {"The value is v1.2.3.", "No number here.", "true"},
        {"The value is 1.2.3e4.", "No number here.", "true"},
        {"The value is 1e3.", "No number here.", "true"},
        {"The value is 5kg%.", "No number here.", "true"},
        {"The value is -15.", "No number here.", "true"},
        {"The value is +15.", "No number here.", "true"},
        {"The value is 15%.", "No number here.", "true"},
        {"The value is 5°.", "No number here.", "true"},
        {"The value is .5.", "No number here.", "true"},
        {"The value is -.5.", "No number here.", "true"},
        {"The value is +.5%.", "No number here.", "true"},
        {"The value is 1/2.", "No number here.", "true"},
        {"The value is 1..5.", "No number here.", "true"},
        {"The value is 1_500.", "No number here.", "true"},
        {"The value is 5th.", "No number here.", "true"},
        {"The value is 値は１５です.", "No number here.", "true"},
        {"The value is m².", "No number here.", "true"},
        {"The value is cm³.", "No number here.", "true"},
        {"The value is 0.5.", "The value is .5.", "true"},
        {"The value is 15.", "The value is -15.", "true"},
        {"The value is 15.", "The value is 15%.", "true"},
        {"The value is 5.", "The value is 5kg%.", "true"},
        {"The value is 5.", "The value is $5.", "true"},
        {"The value is 10.", "The range is 5kg-10kg.", "true"},
        {"The value is 21.", "The date is 21/10/2026.", "true"},
        {"The value is 1.", "The time is 1:30e4.", "true"},
        {"The value is 10.", "The value is 0x10.", "true"},
        {"The value is 1200.", "The value is $1,200%.", "true"},
        {"The value is 99.", "The value is 2099.", "false"},
        {"The value is 15.", "The value is 1.5.", "false"},
        {"The value is 1.5.", "The value is 15.", "false"},
        {"The price is 15.", "The price is 1$5.", "false"},
        {"The value is 1.", "The value is 1.2.3e4.", "false"},
        {"The value is 5.", "The value is .5.", "false"},
        {"The value is 200.", "The value is 1,200.", "false"},
        {"Opened in 1883 with a rate of 5kg%.", "Opened in 18830.", "false"},
        {"The value is 15.", "The value is 15.", "true"},
        {"The value is 0015.", "The value is 15.", "true"},
        {"The value is 1.5.", "The value is 1.5.", "true"},
        {"The value is 1.50.", "The value is 1.50.", "true"},
        {"The value is 1,500.", "The value is 1500.", "true"},
        {"The value is 1 500.", "The value is 1500.", "true"},
        {"The value is 1 500.", "The value is 1500.", "true"},
        {"The value is 1 500.", "The value is 1500.", "true"},
        {"The value is 1,500,000.", "The value is 1500000.", "true"},
        {"The value is ١٥.", "The value is 15.", "true"},
        {"The value is １５.", "The value is 15.", "true"},
        {"The value is 𝟙𝟝.", "The value is 15.", "true"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () ->
        checkPair(pair[0], pair[1], Boolean.parseBoolean(pair[2]))));
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
