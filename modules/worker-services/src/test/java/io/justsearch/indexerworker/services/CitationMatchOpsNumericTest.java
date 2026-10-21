/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

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

/** WP4.5 F4: assertions observe raw cosine matches, before the unchanged UI producer gate. */
class CitationMatchOpsNumericTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final String DOC_A = "numeric-a.md";
  private static final String DOC_B = "numeric-b.md";
  @TempDir Path tempDir;
  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) runtime.close();
  }

  @Test
  void rejectsBothCounterexamplesOnSuppliedAndLookupPaths() throws Exception {
    index(DOC_A, "Vespera Observatory opened in 1883.");
    for (boolean supplied : List.of(true, false)) {
      for (String year : List.of("2099", "1923")) {
        var result = execute("Vespera Observatory opened in " + year + ".", List.of(DOC_A),
            supplied ? List.of("Vespera Observatory opened in 1883.") : List.of(), 0.5);
        assertEquals(0, result.getMatchesCount(), "raw matcher must reject " + year + ", supplied=" + supplied);
        assertEquals(0, result.getSentencesMatched());
        assertEquals(1, result.getSentencesTotal());
        assertEquals(1, result.getSentencesScored(), "numeric rejection is not a timeout");
        assertEquals("EMBEDDING_COSINE", result.getScorer());
        assertEquals(result.getSourceCoverage(0).getWindowsConsidered(),
            result.getSourceCoverage(0).getWindowsScored());
      }
    }
  }

  @Test
  void repairsLeadingDecimalsOnRawCosineSuppliedAndLookupPaths() throws Exception {
    index(DOC_A, "The voltage is 5% and 5.");
    index(DOC_B, "alternative: The voltage is 0.5% and 0.5 and -0.5.");
    assertAll(List.of(true, false).stream().map(supplied -> () -> {
      assertAll(List.of(".5", "-.5", "+.5%").stream().map(value -> () -> {
        String sentence = "The voltage is " + value + ".";
        var rejected = execute(sentence, List.of(DOC_A),
            supplied ? List.of("The voltage is 5% and 5.") : List.of(), 0.5);
        assertEquals(0, rejected.getMatchesCount(), "raw mismatch: " + sentence + ", supplied=" + supplied);
        assertEquals(1, rejected.getSentencesScored());
        var selected = execute(sentence, List.of(DOC_A, DOC_B), supplied
            ? List.of("The voltage is 5% and 5.", "alternative: The voltage is 0.5% and 0.5 and -0.5.")
            : List.of(), 0.7);
        assertEquals(1, selected.getMatchesCount());
        assertEquals(1, selected.getMatches(0).getSourceIndex());
        assertEquals(DOC_B, selected.getMatches(0).getParentDocId());
        assertEquals(1, selected.getSentencesScored());
        assertEquals(0, execute(sentence, List.of(DOC_B), supplied
            ? List.of("alternative: The voltage is 0.5% and 0.5 and -0.5.") : List.of(), 0.9)
            .getMatchesCount(), "containment cannot grant a below-threshold verdict");
      }));
    }));
  }

  @Test
  void withholdsUnsupportedNotationAfterMarkdownSegmentation() {
    assertAll(List.of("The value is `1e3`.", "The value is 1/2.",
        "| Value --5 |\n| --- |\n| The value is --5. |", "القيمة ١٫٥.", "The value is 5%%.")
        .stream().map(answer -> () -> {
      var result = execute(answer, List.of(DOC_A), List.of("Values are 1, 2, 3, 5 and -5 and 5%."), 0.5);
      assertEquals(0, result.getMatchesCount(), answer);
      assertEquals(result.getSentencesTotal(), result.getSentencesScored());
    }));
  }

  @Test
  void rejectsMissingYearInLongLookupDocument() throws Exception {
    index(DOC_A, "Vespera Observatory opened in 1883. "
        + "The observatory studies distant stars and retains historical records. ".repeat(400));
    var result = execute("Vespera Observatory opened in 1923.", List.of(DOC_A), List.of(), 0.5);
    assertEquals(0, result.getMatchesCount(), "no admitted window contains the claimed year");
    assertEquals(1, result.getSentencesScored());
    assertTrue(result.getSourceCoverage(0).getWindowsConsidered() > 10);
  }

  @Test
  void reselectsCompatibleSourceOnSuppliedAndLookupPaths() throws Exception {
    List<String> passages = List.of("Vespera Observatory opened in 1883.",
        "alternative: Vespera Observatory opened in 1923.");
    index(DOC_A, passages.get(0));
    index(DOC_B, passages.get(1));
    for (boolean supplied : List.of(true, false)) {
      var result = execute("Vespera Observatory opened in 1923.", List.of(DOC_A, DOC_B),
          supplied ? passages : List.of(), 0.7);
      assertEquals(1, result.getMatchesCount());
      assertEquals(1, result.getMatches(0).getSourceIndex(), "the mark must open the compatible source");
      assertEquals(DOC_B, result.getMatches(0).getParentDocId());
      assertTrue(passages.get(result.getMatches(0).getSourceIndex()).contains("1923"));
      assertEquals(1, result.getSentencesMatched());
      assertEquals(1, result.getSentencesScored());
      assertEquals(0, execute("Vespera Observatory opened in 1923.", List.of(DOC_A, DOC_B),
          supplied ? passages : List.of(), 0.9).getMatchesCount(), "never lower the score threshold");
    }
  }

  @Test
  void retainsNumericClaimsInCodeTablesArraysAndMathematicsAfterSegmentation() {
    for (String answer : List.of("The array is `[4]`.", "The constant is `2099`.",
        "The array is [4, 7].", "The equation is x = [4].",
        "| Year 2099 |\n| --- |\n| Value is 2099. |", "افتتح المرصد في ٢٠٩٩.", "値は１５です。")) {
      var result = execute(answer, List.of(DOC_A), List.of("The passage contains no numeric value."), 0.5);
      assertEquals(0, result.getMatchesCount(), "claims must survive segmentation: " + answer);
      assertEquals(result.getSentencesTotal(), result.getSentencesScored());
    }
  }

  @Test
  void preservesReferencesOrdinalsAndNumberFreeCorpus() {
    for (String answer : List.of("The engine owns the index [4].",
        "The engine owns the index [4][7].", "4. The engine owns the index.",
        "The engine owns the index.", "索引归引擎所有。", "索引归引擎所有 [4]。",
        "索引归引擎所有。 [4]", "The constant is `value`.")) {
      var result = execute(answer, List.of(DOC_A), List.of("Some passage."), 0.9);
      assertEquals(1, result.getMatchesCount(), answer);
      assertEquals(0, result.getMatches(0).getSourceIndex());
      assertEquals(1.0, result.getMatches(0).getSimilarity(), 0.0);
      assertEquals(AnswerSegmentation.splitSentences(answer).getFirst(),
          result.getMatches(0).getSentenceText(), "sentence keys and mark anchors stay unchanged");
    }
  }

  @Test
  void preservesCompatibleNumbersAndOriginalThreshold() {
    for (String answer : List.of("Opened in 1883.", "افتتح في ١٨٨٣.", "The value is 001883 kg.")) {
      var result = execute(answer, List.of(DOC_A), List.of("Opened in 1883."), 0.9);
      assertEquals(1, result.getMatchesCount(), answer);
      assertEquals(1.0, result.getMatches(0).getSimilarity(), 0.0);
      assertEquals(1, result.getSentencesScored());
      assertEquals(0, execute(answer, List.of(DOC_A), List.of("alternative: Opened in 1883."), 0.9)
          .getMatchesCount());
    }
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
