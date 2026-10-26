/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.ChunkSearchOps;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.CrossEncoderReranker.RerankedResult;
import io.justsearch.reranker.RerankSkipCause;
import io.justsearch.reranker.RerankerConfig.ChunkRerankerConfig;
import io.justsearch.reranker.RerankerConfig.RerankOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** S9: deterministic score controls through actual selection/assembly, plus real-index responses. */
final class RagContextOpsQualityTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  @TempDir Path tempDir;
  private final Map<String, String> savedProperties = new HashMap<>();
  private RunningRuntime runtime;

  @AfterEach
  void closeRuntimeAndRestoreProperties() throws Exception {
    try {
      if (runtime != null) runtime.close();
    } finally {
      WorkerSearchServiceRagBudgetTest.restoreProperties(savedProperties);
    }
  }

  @ParameterizedTest(
      name = "rawQualityUsesOnlyAppendedHitsRegardlessOfAssemblyOrder({arguments}) [{index}]")
  @CsvSource({"0, false", "10000, false", "0, true", "10000, true"})
  void rawQualityUsesOnlyAppendedHitsRegardlessOfAssemblyOrder(int tokens, boolean negative) {
    var omitted = hit("missing", "missing", 0, 1, "", negative ? -1 : 9);
    var first = hit("same-parent", "same-parent", 0, 3, "first passage", negative ? -8 : 2);
    var overflow = hit("same-parent", "same-parent", 1, 3, "overflow passage", negative ? -3 : 6);
    var other = hit("other", "other", 0, 1, "other passage", negative ? -5 : 4);
    var result = retrieve(ops(List.of(omitted, first, overflow, other)), 4, tokens);

    // The primary parent cap moves the higher-scored same-parent passage into backfill.
    assertUsed(result, first, other, overflow);
    assertEquals(negative ? -3f : 6f, result.qualitySignals().bestChunkScore());
    assertEquals(2f, result.qualitySignals().scoreGap());
    assertEquals(4, result.qualitySignals().chunksConsidered());
    assertFalse(result.contextTruncated());
    if (tokens == 0) assertEquals(0f, result.qualitySignals().retrievalCoverage());
    else assertTrue(result.qualitySignals().retrievalCoverage() > 0f
        && result.qualitySignals().retrievalCoverage() <= 1f);
  }

  @ParameterizedTest(
      name = "rawQualityFindsTheMaximumAfterPositionSelectionReordersHits({arguments}) [{index}]")
  @ValueSource(ints = {0, 10000})
  void rawQualityFindsTheMaximumAfterPositionSelectionReordersHits(int tokens) {
    var high = hit("intro", "intro", 0, 10, "intro passage", 9);
    var lower = hit("lower", "lower", 5, 10, "lower passage", 2);
    var higher = hit("higher", "higher", 5, 10, "higher passage", 6);
    var tail = hit("tail", "tail", 9, 10, "tail passage", 4);
    var result = retrieve(ops(List.of(high, lower, higher, tail)), 2, tokens);
    assertUsed(result, lower, higher);
    assertEquals(6f, result.qualitySignals().bestChunkScore());
    assertEquals(4f, result.qualitySignals().scoreGap());
  }

  @ParameterizedTest(
      name = "ceBeforeSelectionKeepsScoresWithExactSameParentPassages({arguments}) [{index}]")
  @ValueSource(ints = {0, 10000})
  void ceBeforeSelectionKeepsScoresWithExactSameParentPassages(int tokens) {
    // Synthetic passages share docId and parentId; their index/span/content identities differ.
    var high = hit("same", "same", 0, 10, "intro", 90);
    var lower = hit("same", "same", 4, 10, "lower middle", 20);
    var higher = hit("same", "same", 5, 10, "higher middle", 60);
    var tail = hit("same", "same", 9, 10, "tail", 40);
    var ops = ops(List.of(high, lower, higher, tail));
    var reranker = wireCe(ops, RerankOrder.BEFORE_DIVERSIFY, 10,
        Map.of("intro", 9f, "lower middle", 2f, "higher middle", 6f, "tail", 4f), false);
    var result = retrieve(ops, 2, tokens);

    assertUsed(result, higher, lower);
    assertEquals(6f, result.qualitySignals().bestChunkScore());
    assertEquals(4f, result.qualitySignals().scoreGap());
    assertEquals(List.of(60f, 20f), result.usedHits().stream().map(SearchHit::score).toList(),
        "CE must not overwrite raw citation scores");
    verify(reranker).rerank(eq("question"), eq(List.of("intro", "lower middle", "higher middle", "tail")),
        anyLong(), any());
  }

  @ParameterizedTest(
      name = "ceQualityDropsUnappendedTopScoreAndSurvivesParentBackfill({arguments}) [{index}]")
  @CsvSource({"BEFORE_DIVERSIFY, 0", "BEFORE_DIVERSIFY, 10000",
      "AFTER_DIVERSIFY, 0", "AFTER_DIVERSIFY, 10000"})
  void ceQualityDropsUnappendedTopScoreAndSurvivesParentBackfill(RerankOrder order, int tokens) {
    var omitted = hit("missing", "missing", 0, 1, "", 90);
    var first = hit("same", "same", 0, 3, "first", 20);
    var overflow = hit("same", "same", 1, 3, "overflow", 60);
    var other = hit("other", "other", 0, 1, "other", 40);
    var ops = ops(List.of(omitted, first, overflow, other));
    wireCe(ops, order, 10, Map.of("", 9f, "first", 6f, "overflow", 4f, "other", 2f), false);
    var result = retrieve(ops, 4, tokens);

    assertUsed(result, first, other, overflow);
    assertEquals(6f, result.qualitySignals().bestChunkScore());
    assertEquals(2f, result.qualitySignals().scoreGap());
    assertEquals(List.of(20f, 40f, 60f), result.usedHits().stream().map(SearchHit::score).toList());
  }

  @ParameterizedTest(name = "skippedCeUsesIncludedRawScores({arguments}) [{index}]")
  @ValueSource(strings = {"BEFORE_DIVERSIFY", "AFTER_DIVERSIFY"})
  void skippedCeUsesIncludedRawScores(RerankOrder order) {
    var lower = hit("low", "low", 0, 1, "lower", -8);
    var higher = hit("high", "high", 0, 1, "higher", -3);
    var ops = ops(List.of(lower, higher));
    var reranker = wireCe(ops, order, 10, Map.of(), true);
    var result = retrieve(ops, 2, 0);
    assertUsed(result, lower, higher);
    assertEquals(-3f, result.qualitySignals().bestChunkScore());
    assertEquals(5f, result.qualitySignals().scoreGap());
    verify(reranker).rerank(anyString(), anyList(), anyLong(), any());
  }

  @ParameterizedTest(
      name = "nonfiniteScoresAreExcludedWithoutDroppingTheirPassages({arguments}) [{index}]")
  @ValueSource(booleans = {false, true})
  void nonfiniteScoresAreExcludedWithoutDroppingTheirPassages(boolean ce) {
    var hits = List.of(hit("nan", "nan", 0, 1, "nan", Float.NaN),
        hit("inf", "inf", 0, 1, "inf", Float.POSITIVE_INFINITY),
        hit("ninf", "ninf", 0, 1, "ninf", Float.NEGATIVE_INFINITY),
        hit("low", "low", 0, 1, "low", -8), hit("high", "high", 0, 1, "high", -3));
    var ops = ops(hits);
    if (ce) wireCe(ops, RerankOrder.AFTER_DIVERSIFY, 10,
        Map.of("nan", Float.NaN, "inf", Float.POSITIVE_INFINITY,
            "ninf", Float.NEGATIVE_INFINITY, "low", -8f, "high", -3f), false);
    var result = retrieve(ops, 5, 0);
    assertEquals(5, result.usedHits().size());
    assertEquals(5, result.qualitySignals().chunksIncluded());
    assertEquals(-3f, result.qualitySignals().bestChunkScore());
    assertEquals(5f, result.qualitySignals().scoreGap());
  }

  @ParameterizedTest(name = "noFiniteIncludedScoresGivesZeroQuality({arguments}) [{index}]")
  @ValueSource(booleans = {false, true})
  void noFiniteIncludedScoresGivesZeroQuality(boolean ce) {
    var ops = ops(List.of(hit("nan", "nan", 0, 1, "nan", Float.NaN),
        hit("inf", "inf", 0, 1, "inf", Float.NEGATIVE_INFINITY)));
    if (ce) wireCe(ops, RerankOrder.AFTER_DIVERSIFY, 10,
        Map.of("nan", Float.NaN, "inf", Float.NEGATIVE_INFINITY), false);
    var result = retrieve(ops, 2, 0);
    assertEquals(2, result.qualitySignals().chunksIncluded());
    assertEquals(0f, result.qualitySignals().bestChunkScore());
    assertEquals(0f, result.qualitySignals().scoreGap());
  }

  @ParameterizedTest(
      name = "oneFiniteScoreKeepsNegativeSingletonGapAmongNonfinitePassages({arguments}) [{index}]")
  @ValueSource(booleans = {false, true})
  void oneFiniteScoreKeepsNegativeSingletonGapAmongNonfinitePassages(boolean ce) {
    var ops = ops(List.of(hit("inf", "inf", 0, 1, "inf", Float.POSITIVE_INFINITY),
        hit("finite", "finite", 0, 1, "finite", -3)));
    if (ce) wireCe(ops, RerankOrder.AFTER_DIVERSIFY, 10,
        Map.of("inf", Float.POSITIVE_INFINITY, "finite", -3f), false);
    var result = retrieve(ops, 2, 0);
    assertEquals(2, result.qualitySignals().chunksIncluded());
    assertEquals(-3f, result.qualitySignals().bestChunkScore());
    assertEquals(-3f, result.qualitySignals().scoreGap());
  }

  @ParameterizedTest(name = "emptyAssemblyGivesZeroQuality({arguments}) [{index}]")
  @CsvSource({"0, false", "10000, false", "0, true", "10000, true"})
  void emptyAssemblyGivesZeroQuality(int tokens, boolean ce) {
    var ops = ops(List.of(hit("blank", "blank", 0, 1, " ", 9)));
    if (ce) wireCe(ops, RerankOrder.AFTER_DIVERSIFY, 10, Map.of(" ", 9f), false);
    var result = retrieve(ops, 1, tokens);
    assertUsed(result);
    assertEquals(0f, result.qualitySignals().bestChunkScore());
    assertEquals(0f, result.qualitySignals().scoreGap());
    assertEquals(1, result.qualitySignals().chunksConsidered());
  }

  @ParameterizedTest(
      name = "singletonAndSortedNegativeScoresKeepLegacyConvention({arguments}) [{index}]")
  @ValueSource(booleans = {false, true})
  void singletonAndSortedNegativeScoresKeepLegacyConvention(boolean ce) {
    var high = hit("high", "high", 0, 1, "high", -3);
    var low = hit("low", "low", 0, 1, "low", -8);
    for (var hits : List.of(List.of(high), List.of(high, low))) {
      var ops = ops(hits);
      if (ce) wireCe(ops, RerankOrder.AFTER_DIVERSIFY, 10, Map.of("high", -3f, "low", -8f), false);
      var result = retrieve(ops, 2, 0);
      assertEquals(-3f, result.qualitySignals().bestChunkScore());
      assertEquals(hits.size() == 1 ? -3f : 5f, result.qualitySignals().scoreGap());
    }
    var empty = retrieve(ops(List.of()), 2, 0);
    assertEquals(0f, empty.qualitySignals().bestChunkScore());
    assertEquals(0f, empty.qualitySignals().scoreGap());
  }

  @ParameterizedTest(name = "autoOrderKeepsItsExistingDevicePolicy({arguments}) [{index}]")
  @ValueSource(booleans = {false, true})
  void autoOrderKeepsItsExistingDevicePolicy(boolean gpu) {
    var first = hit("a", "a", 0, 1, "a", 30);
    var second = hit("b", "b", 0, 1, "b", 20);
    var third = hit("c", "c", 0, 1, "c", 10);
    var ops = ops(List.of(first, second, third));
    var reranker = wireCe(ops, RerankOrder.AUTO, 3, Map.of("a", 3f, "b", 2f, "c", 1f), false);
    when(reranker.isGpuAvailable()).thenReturn(gpu);
    var result = retrieve(ops, 2, 0);
    assertUsed(result, first, second);
    assertEquals(3f, result.qualitySignals().bestChunkScore());
    assertEquals(1f, result.qualitySignals().scoreGap());
    verify(reranker).rerank(eq("question"), eq(gpu ? List.of("a", "b", "c") : List.of("a", "b")),
        anyLong(), any());
  }

  @ParameterizedTest(
      name = "ceCandidateCapStillDiscardsTailWithoutChangingCitationScores({arguments}) [{index}]")
  @ValueSource(strings = {"BEFORE_DIVERSIFY", "AFTER_DIVERSIFY"})
  void ceCandidateCapStillDiscardsTailWithoutChangingCitationScores(RerankOrder order) {
    var first = hit("a", "a", 0, 1, "a", 30);
    var second = hit("b", "b", 0, 1, "b", 20);
    var tail = hit("c", "c", 0, 1, "c", 10);
    var ops = ops(List.of(first, second, tail));
    var reranker = wireCe(ops, order, 2, Map.of("a", 3f, "b", 2f), false);
    var result = retrieve(ops, 3, 0);
    assertUsed(result, first, second);
    assertEquals(3f, result.qualitySignals().bestChunkScore());
    assertEquals(1f, result.qualitySignals().scoreGap());
    assertEquals(List.of(30f, 20f), result.usedHits().stream().map(SearchHit::score).toList());
    verify(reranker).rerank(eq("question"), eq(List.of("a", "b")), anyLong(), any());
  }

  @Test
  void tokenBudgetExcludesAnUnappendedHigherScore() {
    var first = hit("a", "a", 0, 1, "first passage", 2);
    var excluded = hit("b", "b", 0, 1, "excluded passage", 9);
    // Six tokens fits the first section, then has too little room for the second header.
    var result = retrieve(ops(List.of(first, excluded)), 2, 6);
    assertUsed(result, first);
    assertTrue(result.contextTruncated());
    assertEquals(2f, result.qualitySignals().bestChunkScore());
    assertEquals(2f, result.qualitySignals().scoreGap());
    assertTrue(result.qualitySignals().retrievalCoverage() <= 1f);
  }

  @Test
  void characterBudgetExcludesAnUnappendedHigherScore() {
    String text = "x".repeat(200_000 - ContextBudgeter.sectionHeader(1, "a").length());
    var first = hit("a", "a", 0, 1, text, 2);
    var excluded = hit("b", "b", 0, 1, "excluded passage", 9);
    var result = retrieve(ops(List.of(first, excluded)), 2, 0);
    assertUsed(result, first);
    assertEquals(200_000, result.context().length());
    assertTrue(result.contextTruncated());
    assertEquals(2f, result.qualitySignals().bestChunkScore());
    assertEquals(2f, result.qualitySignals().scoreGap());
  }

  @Test
  void realIndexCeProjectionMatchesIncludedSyntheticPassageAndKeepsRawCitation() throws Exception {
    openRealIndex();
    String parent = "Hello synthetic sentence. ".repeat(300);
    indexParent("doc", parent);
    refresh();
    var service = new WorkerSearchService(runtime);
    var reranker = mock(CrossEncoderReranker.class);
    when(reranker.rerank(anyString(), anyList(), anyLong(), any())).thenAnswer(call -> {
      List<String> texts = call.getArgument(1);
      assertTrue(texts.size() >= 2, "the real union must synthesize distinct passages of one parent");
      List<Float> scores = IntStream.range(0, texts.size()).mapToObj(i -> -3f - i).toList();
      return ranked(scores);
    });
    service.setSearchReranker(reranker);
    service.setChunkRerankerConfig(ceConfig(RerankOrder.BEFORE_DIVERSIFY, 30));
    var response = service.retrieveContext(RetrieveContextRequest.newBuilder()
        .setQuestion("Hello").addDocIds("doc").setTopK(20).setMaxContextTokens(30).build(), CallContext.none());
    assertEquals("BM25", response.getRetrievalMode());
    assertEquals(1, response.getChunksCount());
    assertTrue(response.getContextTruncated());
    var citation = response.getChunks(0);
    assertEquals("doc", citation.getParentDocId());
    assertTrue(citation.getChunkTotal() >= 2);
    assertEquals(0, citation.getChunkIndex());
    assertTrue(citation.getScore() > 0f, "BM25 citation retains its positive raw score");
    String literal = parent.substring(citation.getStartChar(), citation.getEndChar());
    assertTrue(literal.startsWith(response.getSections(0).getContent()));
    assertTrue(response.getSections(0).getTruncated());
    assertLiteralPreview(literal, citation.getExcerpt());
    assertTrue(citation.getExcerpt().length() > response.getSections(0).getContent().length(),
        "the citation previews the original passage, beyond this token-truncated section");
    assertEquals(-3f, response.getQuality().getBestChunkScore());
    assertEquals(-3f, response.getQuality().getScoreGap(), "only the appended CE score counts");
    assertEquals(1, response.getQuality().getChunksIncluded());
    assertAligned(response);
    verify(reranker).rerank(anyString(), anyList(), anyLong(), any());
  }

  @Test
  void fullDocumentEmitterPreservesSortedIncludedSubsequenceAndLiteralCitations() throws Exception {
    openRealIndex();
    indexParent("both", "alpha beta both passage");
    indexParent("one", "alpha single passage");
    refresh();
    var service = new WorkerSearchService(runtime);
    var response = service.retrieveContext(RetrieveContextRequest.newBuilder()
        .setQuestion("alpha beta").addAllDocIds(List.of("one", "both"))
        .setTopK(5).setReturnFullDocuments(true).build(), CallContext.none());
    assertEquals("FULL_DOCUMENT", response.getRetrievalMode());
    assertEquals(List.of("both", "one"),
        response.getChunksList().stream().map(c -> c.getParentDocId()).toList());
    assertEquals(List.of(1f, .5f), response.getChunksList().stream().map(c -> c.getScore()).toList());
    assertEquals(1f, response.getQuality().getBestChunkScore());
    assertEquals(.5f, response.getQuality().getScoreGap());
    assertAligned(response);
    for (int i = 0; i < response.getChunksCount(); i++) {
      assertEquals(response.getSections(i).getContent(), response.getChunks(i).getExcerpt());
    }
  }

  @Test
  void fullDocumentBudgetPreservesSortedIncludedSubsequence() throws Exception {
    openRealIndex();
    String parent = "alpha beta passage. ".repeat(15000);
    indexParent("large", parent);
    refresh();
    var response = new WorkerSearchService(runtime).retrieveContext(RetrieveContextRequest.newBuilder()
        .setQuestion("alpha beta").addDocIds("large").setTopK(5)
        .setReturnFullDocuments(true).build(), CallContext.none());
    assertEquals("FULL_DOCUMENT", response.getRetrievalMode());
    assertTrue(response.getContextTruncated());
    assertTrue(response.getContext().length() <= 200_000);
    assertTrue(response.getQuality().getChunksConsidered() > response.getQuality().getChunksIncluded());
    assertEquals(response.getChunksCount(), response.getQuality().getChunksIncluded());
    assertTrue(response.getChunksCount() >= 2);
    assertEquals(1f, response.getQuality().getBestChunkScore());
    assertEquals(0f, response.getQuality().getScoreGap());
    assertAligned(response);
    int previousIndex = -1;
    for (int i = 0; i < response.getChunksCount(); i++) {
      var chunk = response.getChunks(i);
      assertEquals(1f, chunk.getScore());
      assertTrue(chunk.getChunkIndex() > previousIndex, "stable sorted subsequence retains input order on ties");
      previousIndex = chunk.getChunkIndex();
      String literal = parent.substring(chunk.getStartChar(), chunk.getEndChar());
      assertTrue(literal.startsWith(response.getSections(i).getContent()));
      assertEquals(literal.length() > response.getSections(i).getContent().length(),
          response.getSections(i).getTruncated());
      assertLiteralPreview(literal, chunk.getExcerpt());
    }
  }

  private static void assertLiteralPreview(String literal, String preview) {
    String expected = literal;
    if (literal.length() > 240) {
      // These worded fixtures have a space within the producer's 40-character lookback.
      int boundary = literal.lastIndexOf(' ', 240);
      assertTrue(boundary >= 200, "the fixture supplies a word boundary within the lookback");
      expected = literal.substring(0, boundary).stripTrailing() + "...";
    }
    assertEquals(expected, preview,
        "the citation previews the exact original parent span with its producer-owned ellipsis");
  }

  private static RagContextOps ops(List<SearchHit> hits) {
    var config = new ResolvedConfigBuilder();
    config.putDefault("rag.retrieve.mode", "bm25");
    config.putDefault("rag.union.enabled", "false");
    config.putDefault("rag.max_chunks_per_article", "1");
    var resolved = config.build();
    var chunks = mock(ChunkSearchOps.class);
    var result = new SearchResult(hits, hits.size(), 0);
    when(chunks.searchChunksFiltered(anyString(), anySet(), anyInt(), any())).thenReturn(result);
    when(chunks.searchChunksForDocs(anyString(), anySet(), anyInt())).thenReturn(result);
    return new RagContextOps(chunks, mock(IndexCountOps.class), mock(CommitOps.class),
        () -> resolved, NoOpEmbeddingProvider.INSTANCE);
  }

  private static RagContextOps.ChunkContextResult retrieve(RagContextOps ops, int topK, int tokens) {
    return ops.searchChunksWithMeta("question", Set.of("scope"), topK, tokens,
        false, null, null, List.of(), null, null);
  }

  private static SearchHit hit(String id, String parent, int index, int total, String text, float score) {
    return new SearchHit(id, score, Map.of(SchemaFields.PARENT_DOC_ID, parent,
        SchemaFields.CHUNK_INDEX, Integer.toString(index), SchemaFields.CHUNK_TOTAL, Integer.toString(total),
        SchemaFields.CHUNK_CONTENT, text, SchemaFields.CHUNK_START_CHAR, Integer.toString(index * 100),
        SchemaFields.CHUNK_END_CHAR, Integer.toString(index * 100 + text.length())));
  }

  private static CrossEncoderReranker wireCe(RagContextOps ops, RerankOrder order, int cap,
      Map<String, Float> scoresByText, boolean skipped) {
    var reranker = mock(CrossEncoderReranker.class);
    when(reranker.rerank(anyString(), anyList(), anyLong(), any())).thenAnswer(call -> {
      if (skipped) return new RerankedResult(List.of(), List.of(), RerankSkipCause.INFERENCE_FAILED, 0);
      List<String> texts = call.getArgument(1);
      return ranked(texts.stream().map(scoresByText::get).toList());
    });
    ops.setSearchReranker(reranker);
    ops.setChunkRerankerConfig(ceConfig(order, cap));
    return reranker;
  }

  private static ChunkRerankerConfig ceConfig(RerankOrder order, int cap) {
    return new ChunkRerankerConfig(true, Path.of("fixture.onnx"), cap, cap,
        1000, 1, 512, false, 0, order);
  }

  private static RerankedResult ranked(List<Float> scores) {
    var indices = IntStream.range(0, scores.size()).boxed()
        .sorted(Comparator.comparing((Integer i) -> scores.get(i)).reversed()).toList();
    return new RerankedResult(indices, scores, RerankSkipCause.NONE, 0);
  }

  private static void assertUsed(RagContextOps.ChunkContextResult result, SearchHit... expected) {
    assertEquals(expected.length, result.usedHits().size());
    assertEquals(expected.length, result.sections().size());
    assertEquals(expected.length, result.qualitySignals().chunksIncluded());
    for (int i = 0; i < expected.length; i++) {
      assertSame(expected[i], result.usedHits().get(i), "exact scored passage identity survives assembly");
      assertEquals(expected[i].fields().get(SchemaFields.CHUNK_CONTENT), result.sections().get(i).content());
      assertEquals(i, result.sections().get(i).sectionIndex());
    }
  }

  private void openRealIndex() throws Exception {
    Path config = tempDir.resolve("config.yaml");
    Files.writeString(config, "app:\n  data_dir: '" + tempDir.toString().replace("'", "''") + "'\n");
    for (var entry : Map.of("justsearch.config", config.toString(), "rag.retrieve.mode", "bm25",
        "rag.diversify.mode", "position").entrySet()) {
      savedProperties.put(entry.getKey(), System.getProperty(entry.getKey()));
      System.setProperty(entry.getKey(), entry.getValue());
    }
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir.resolve("index"))
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private void indexParent(String id, String text) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, id,
        SchemaFields.CONTENT, text)));
  }

  private void refresh() throws Exception {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void assertAligned(RetrieveContextResponse response) {
    assertEquals(response.getChunksCount(), response.getSectionsCount());
    List<String> rendered = new ArrayList<>();
    for (int i = 0; i < response.getSectionsCount(); i++) {
      var section = response.getSections(i);
      assertEquals(i, section.getSectionIndex());
      assertEquals(i, section.getChunkIndex());
      rendered.add(ContextBudgeter.sectionHeader(i + 1, section.getSourceLabel()) + section.getContent());
    }
    assertEquals(String.join(ContextBudgeter.SECTION_SEPARATOR, rendered), response.getContext());
  }
}
