/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tempdoc 720 (U14, tempdoc 270) — exercises the real {@link RagContextOps#diversifyChunks} /
 * {@link RagContextOps#diversifyByMmr} path end-to-end (config read → field lookup → embedding →
 * {@code MmrSelector}), not just the pure {@code MmrSelector.select()} algorithm covered by
 * {@code MmrSelectorTest}.
 *
 * <p>Fixture: three hits where hit0/hit1 embed to the SAME vector (redundant) and hit2 to a distinct
 * one (diverse). With {@code lambda=0.3} (novelty-favouring) and {@code targetK=2}, MMR must select
 * the diverse {hit0, hit2} and DROP the redundant hit1 — whereas position diversification, blind to
 * vectors, takes the first two in order {hit0, hit1}. That divergence is what distinguishes
 * "MMR actually ran" from "fell back to position".
 */
@DisplayName("RagContextOps — MMR diversification (tempdoc 720)")
final class RagContextOpsDiversifyTest {

  private static final String Q = "the question";
  private static final float[] QUERY_VECTOR = {1.0f, 0.0f};
  private static final int TARGET_K = 2;

  /** Deterministic provider: content prefix "dup" → {1,0}; "diverse" → {0.7,0.7}. */
  private static final class FakeEmbeddingProvider implements EmbeddingProvider {
    @Override
    public float[] embedDocument(String text) {
      if (text != null && text.startsWith("diverse")) {
        return new float[] {0.7f, 0.7f};
      }
      return new float[] {1.0f, 0.0f};
    }

    @Override
    public float[] embedQuery(String text) {
      return new float[] {1.0f, 0.0f};
    }

    @Override
    public List<float[]> embedDocumentBatch(List<String> texts) {
      return texts.stream().map(this::embedDocument).toList();
    }

    @Override
    public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
      return null;
    }

    @Override
    public int dimension() {
      return 2;
    }

    @Override
    public Availability availability() {
      return Availability.READY;
    }

    @Override
    public boolean isUsingGpu() {
      return false;
    }
  }

  private static ResolvedConfig config(String diversifyMode) {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.putDefault("rag.diversify.mode", diversifyMode);
    builder.putDefault("rag.mmr.lambda", "0.3");
    return builder.build();
  }

  private static RagContextOps opsWith(ResolvedConfig cfg, EmbeddingProvider provider) {
    Supplier<ResolvedConfig> supplier = () -> cfg;
    return new RagContextOps(null, null, null, supplier, provider);
  }

  /** Chunk-level hits carry {@code chunk_content}; the flat position keys default when absent. */
  private static LuceneRuntimeTypes.SearchHit chunkHit(String docId, String content) {
    return new LuceneRuntimeTypes.SearchHit(
        docId,
        1.0f,
        Map.of(
            SchemaFields.CHUNK_CONTENT, content,
            SchemaFields.CHUNK_INDEX, "0",
            SchemaFields.CHUNK_TOTAL, "1"));
  }

  /** Document-level hits carry {@code content}, NOT {@code chunk_content} (the U14 adapter case). */
  private static LuceneRuntimeTypes.SearchHit docHit(String docId, String content) {
    return new LuceneRuntimeTypes.SearchHit(docId, 1.0f, Map.of(SchemaFields.CONTENT, content));
  }

  private static List<String> ids(List<LuceneRuntimeTypes.SearchHit> hits) {
    return hits.stream().map(LuceneRuntimeTypes.SearchHit::docId).toList();
  }

  @Test
  @DisplayName("chunk hits + mmr mode: MMR selects the diverse hit and drops the redundant one")
  void chunkHitsMmrSelectsDiverse() {
    List<LuceneRuntimeTypes.SearchHit> hits =
        List.of(
            chunkHit("h0", "dup-a"),
            chunkHit("h1", "dup-b"),
            chunkHit("h2", "diverse"));

    RagContextOps ops = opsWith(config("mmr"), new FakeEmbeddingProvider());
    List<LuceneRuntimeTypes.SearchHit> selected =
        ops.diversifyChunks(Q, QUERY_VECTOR, hits, TARGET_K, true, InferenceRequest.foreground());

    assertEquals(TARGET_K, selected.size());
    assertTrue(ids(selected).contains("h0"), "keeps the most-relevant hit");
    assertTrue(ids(selected).contains("h2"), "MMR promotes the diverse hit over the redundant one");
    assertFalse(ids(selected).contains("h1"), "MMR drops the redundant near-duplicate");
  }

  @Test
  @DisplayName("document-level hits (CONTENT, no CHUNK_CONTENT): adapter lets MMR run instead of degrading")
  void docLevelHitsMmrRunsViaAdapter() {
    // Pre-adapter, diversifyByMmr read only CHUNK_CONTENT → every candidate embedded to blank →
    // silent fallback to position (which would keep h0,h1 and NOT h2). This asserts the adapter path.
    List<LuceneRuntimeTypes.SearchHit> hits =
        List.of(
            docHit("h0", "dup-a"),
            docHit("h1", "dup-b"),
            docHit("h2", "diverse"));

    RagContextOps ops = opsWith(config("mmr"), new FakeEmbeddingProvider());
    List<LuceneRuntimeTypes.SearchHit> selected =
        ops.diversifyChunks(Q, QUERY_VECTOR, hits, TARGET_K, true, InferenceRequest.foreground());

    assertEquals(TARGET_K, selected.size());
    assertTrue(ids(selected).contains("h2"), "adapter falls back to CONTENT so MMR embeds and runs");
    assertFalse(
        ids(selected).contains("h1"), "MMR (not position fallback) drops the redundant hit");
  }

  @Test
  @DisplayName("mmr mode but embeddings unavailable: falls back to position diversification")
  void embeddingsUnavailableFallsBackToPosition() {
    List<LuceneRuntimeTypes.SearchHit> hits =
        List.of(
            chunkHit("h0", "dup-a"),
            chunkHit("h1", "dup-b"),
            chunkHit("h2", "diverse"));

    // NoOpEmbeddingProvider.isAvailable() == false → diversifyByMmr short-circuits to position.
    RagContextOps ops = opsWith(config("mmr"), NoOpEmbeddingProvider.INSTANCE);
    List<LuceneRuntimeTypes.SearchHit> selected =
        ops.diversifyChunks(Q, QUERY_VECTOR, hits, TARGET_K, true, InferenceRequest.foreground());

    assertEquals(TARGET_K, selected.size());
    // Position diversification is vector-blind: it keeps the first targetK in order, so the diverse
    // h2 is NOT specially promoted — proving MMR did not run.
    assertTrue(ids(selected).contains("h0"));
    assertTrue(ids(selected).contains("h1"));
    assertFalse(ids(selected).contains("h2"), "position fallback does not promote the diverse hit");
  }

  @Test
  @DisplayName("diversifyChunks dispatches on rag.diversify.mode: mmr → MMR, position → position")
  void modeDispatchGate() {
    List<LuceneRuntimeTypes.SearchHit> hits =
        List.of(
            chunkHit("h0", "dup-a"),
            chunkHit("h1", "dup-b"),
            chunkHit("h2", "diverse"));

    RagContextOps mmrOps = opsWith(config("mmr"), new FakeEmbeddingProvider());
    List<LuceneRuntimeTypes.SearchHit> mmrSelected =
        mmrOps.diversifyChunks(Q, QUERY_VECTOR, hits, TARGET_K, true, InferenceRequest.foreground());
    assertTrue(ids(mmrSelected).contains("h2"), "mmr mode routes to MMR (promotes diverse hit)");

    RagContextOps positionOps = opsWith(config("position"), new FakeEmbeddingProvider());
    List<LuceneRuntimeTypes.SearchHit> positionSelected =
        positionOps.diversifyChunks(Q, QUERY_VECTOR, hits, TARGET_K, true, InferenceRequest.foreground());
    assertFalse(
        ids(positionSelected).contains("h2"),
        "position mode routes to position diversification (ignores vectors)");
  }

  // Deterministic failure controls exercise caller policy, not real-model compatibility.
  private static EmbeddingProvider controlledProvider() {
    EmbeddingProvider provider = mock(EmbeddingProvider.class);
    when(provider.availability()).thenReturn(EmbeddingProvider.Availability.READY);
    when(provider.dimension()).thenReturn(2);
    when(provider.embedQuery(anyString(), any())).thenReturn(QUERY_VECTOR);
    when(provider.embedDocument(anyString(), any())).thenAnswer(call ->
        call.<String>getArgument(0).startsWith("diverse")
            ? new float[] {0.7f, 0.7f} : new float[] {1, 0});
    return provider;
  }

  private static ResolvedConfig mmrConfig(int cap, double lambda) {
    var builder = new ResolvedConfigBuilder();
    builder.putDefault("rag.diversify.mode", "mmr");
    builder.putDefault("rag.mmr.max_candidates", Integer.toString(cap));
    builder.putDefault("rag.mmr.lambda", Double.toString(lambda));
    return builder.build();
  }

  private static float[] invalidVector(String kind) {
    return switch (kind) {
      case "null" -> null;
      case "empty" -> new float[0];
      case "zero" -> new float[] {0, 0};
      case "nan" -> new float[] {Float.NaN, 1};
      case "infinity" -> new float[] {1, Float.POSITIVE_INFINITY};
      case "dimension" -> new float[] {1, 0, 0};
      case "throw" -> throw new IllegalStateException("ordinary fixture encode failure");
      default -> throw new AssertionError(kind);
    };
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4})
  void anyFailureUsesPositionEvenAfterKOrKPlusOneSuccesses(int successes) {
    var hits = List.of(chunkHit("h0", "dup-0"), chunkHit("h1", "dup-1"),
        chunkHit("h2", "dup-2"), chunkHit("h3", "dup-3"),
        chunkHit("h4", "dup-4"), chunkHit("h5", "diverse"));
    var provider = controlledProvider();
    var calls = new AtomicInteger();
    when(provider.embedDocument(anyString(), any())).thenAnswer(call -> {
      if (calls.getAndIncrement() == successes) throw new IllegalStateException("ordinary failure");
      return call.<String>getArgument(0).equals("diverse")
          ? new float[] {0.7f, 0.7f} : QUERY_VECTOR;
    });
    var ops = opsWith(mmrConfig(6, 0.3), provider);
    var selected = ops.diversifyChunks(Q, QUERY_VECTOR, hits, 3, true, InferenceRequest.foreground());
    assertEquals(List.of("h0", "h1", "h2"), ids(selected));
    assertEquals(successes + 1, calls.get(), "stop at the first failure, without a partial selection");
  }

  @ParameterizedTest
  @CsvSource({"null,0", "null,1", "null,3", "empty,0", "empty,1", "empty,3",
      "throw,0", "throw,1", "throw,3", "zero,0", "zero,1", "zero,3",
      "nan,0", "nan,1", "nan,3", "infinity,0", "infinity,1", "infinity,3",
      "dimension,0", "dimension,1", "dimension,3"})
  void invalidCandidateAtAnyPositionUsesTextPool(String kind, int badIndex) {
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"), chunkHit("h3", "dup-tail"));
    var provider = controlledProvider();
    var calls = new AtomicInteger();
    when(provider.embedDocument(anyString(), any())).thenAnswer(call ->
        calls.getAndIncrement() == badIndex ? invalidVector(kind)
            : call.<String>getArgument(0).startsWith("diverse")
                ? new float[] {0.7f, 0.7f} : QUERY_VECTOR);
    var selected = opsWith(config("mmr"), provider)
        .diversifyChunks(Q, QUERY_VECTOR, hits, 2, true, InferenceRequest.foreground());
    assertEquals(List.of("h0", "h1"), ids(selected));
    assertEquals(badIndex + 1, calls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"zero", "nan", "infinity", "dimension"})
  void invalidSuppliedQueryDoesNotEncodeAnything(String kind) {
    var provider = controlledProvider();
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"));
    assertEquals(List.of("h0", "h1"), ids(opsWith(config("mmr"), provider)
        .diversifyChunks(Q, invalidVector(kind), hits, 2, true, InferenceRequest.foreground())));
    verify(provider, never()).embedQuery(anyString(), any());
    verify(provider, never()).embedDocument(anyString(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "empty", "zero", "nan", "infinity", "dimension", "throw"})
  void unusableAcquiredQueryStopsBeforePassageInference(String kind) {
    var provider = controlledProvider();
    when(provider.embedQuery(anyString(), any())).thenAnswer(call -> invalidVector(kind));
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"));
    assertEquals(List.of("h0", "h1"), ids(opsWith(config("mmr"), provider)
        .diversifyChunks(Q, null, hits, 2, true, InferenceRequest.foreground())));
    verify(provider, times(1)).embedQuery(anyString(), any());
    verify(provider, never()).embedDocument(anyString(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"supplied", "null", "empty"})
  void validQueryIsReusedOrAcquiredExactlyOnce(String supplied) {
    var provider = controlledProvider();
    var acquisition = InferenceRequest.foreground().withoutSessionCreation();
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"));
    float[] query = supplied.equals("supplied") ? QUERY_VECTOR
        : supplied.equals("null") ? null : new float[0];
    assertEquals(List.of("h0", "h2"), ids(opsWith(config("mmr"), provider)
        .diversifyChunks(Q, query, hits, 2, true, acquisition)));
    verify(provider, times(supplied.equals("supplied") ? 0 : 1)).embedQuery(Q, acquisition);
    for (var hit : hits) verify(provider).embedDocument(RagContextOps.excerptTextFor(hit), acquisition);
    assertFalse(acquisition.mayCreateSessions(), "the same no-creation authority reaches each call");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 2})
  void nonpositiveProviderDimensionIsUndeclared(int declaredDimension) {
    var provider = controlledProvider();
    when(provider.dimension()).thenReturn(declaredDimension);
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"));
    assertEquals(List.of("h0", "h2"), ids(opsWith(config("mmr"), provider)
        .diversifyChunks(Q, QUERY_VECTOR, hits, 2, true, InferenceRequest.foreground())));
  }

  @Test
  void positiveDeclaredDimensionMustMatchTheQuery() {
    var provider = controlledProvider();
    when(provider.dimension()).thenReturn(3);
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"));
    assertEquals(List.of("h0", "h1"), ids(opsWith(config("mmr"), provider)
        .diversifyChunks(Q, QUERY_VECTOR, hits, 2, true, InferenceRequest.foreground())));
    verify(provider, never()).embedDocument(anyString(), any());
    verify(provider, never()).embedQuery(anyString(), any());
  }

  @Test
  void validNegativeCosinesRemainUsableSemanticCandidates() {
    var provider = controlledProvider();
    when(provider.embedDocument(org.mockito.ArgumentMatchers.eq("negative"), any()))
        .thenReturn(new float[] {-1, 0});
    when(provider.embedDocument(org.mockito.ArgumentMatchers.eq("orthogonal"), any()))
        .thenReturn(new float[] {0, 1});
    var hits = List.of(chunkHit("h0", "negative"), chunkHit("h1", "orthogonal"),
        chunkHit("h2", "dup"));
    assertEquals(List.of("h2", "h1"), ids(opsWith(mmrConfig(3, 1.0), provider)
        .diversifyChunks(Q, QUERY_VECTOR, hits, 2, true, InferenceRequest.foreground())));
    verify(provider, times(3)).embedDocument(anyString(), any());
  }

  @ParameterizedTest
  @ValueSource(floats = {Float.MIN_VALUE, Float.MAX_VALUE})
  void finiteNonzeroVectorsAtFloatExtremesAreUsable(float magnitude) {
    var provider = controlledProvider();
    when(provider.embedDocument(anyString(), any())).thenAnswer(call ->
        call.<String>getArgument(0).equals("low") ? new float[] {0, magnitude}
            : new float[] {magnitude, 0});
    var hits = List.of(chunkHit("h0", "low"), chunkHit("h1", "high"), chunkHit("h2", "high"));
    assertEquals(List.of("h1", "h2"), ids(opsWith(mmrConfig(3, 1.0), provider)
        .diversifyChunks(Q, new float[] {magnitude, 0}, hits, 2, true, InferenceRequest.foreground())));
  }

  @Test
  void blanksAreRemovedBeforeTheExpensiveCap() {
    var provider = controlledProvider();
    var hits = List.of(chunkHit("blank", " "), docHit("empty", ""),
        chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"), chunkHit("tail", "tail"));
    var selected = opsWith(mmrConfig(3, 0.3), provider)
        .diversifyChunks(Q, QUERY_VECTOR, hits, 2, true, InferenceRequest.foreground());
    assertEquals(List.of("h0", "h2"), ids(selected));
    verify(provider, times(3)).embedDocument(anyString(), any());
    verify(provider, never()).embedDocument(org.mockito.ArgumentMatchers.eq("tail"), any());
  }

  @Test
  void anAllValidPrefixOfExactlyKIsRankedAndDoesNotTopUpFromItsTail() {
    var provider = controlledProvider();
    when(provider.embedDocument(org.mockito.ArgumentMatchers.eq("low"), any()))
        .thenReturn(new float[] {0, 1});
    var hits = List.of(chunkHit("h0", "low"), chunkHit("h1", "dup"),
        chunkHit("tail", "diverse"));
    var selected = opsWith(mmrConfig(1, 1.0), provider)
        .diversifyChunks(Q, QUERY_VECTOR, hits, 2, true, InferenceRequest.foreground());
    assertEquals(List.of("h1", "h0"), ids(selected));
    verify(provider, times(2)).embedDocument(anyString(), any());
    verify(provider, never()).embedDocument(org.mockito.ArgumentMatchers.eq("diverse"), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"unavailable", "starting", "failed", "disallowed", "query-failure",
      "invalid-query", "invalid-document", "expired"})
  void everyFallbackUsesTheFullNonblankPoolBeyondTheCap(String cause) {
    var provider = controlledProvider();
    if (cause.equals("unavailable")) when(provider.availability())
        .thenReturn(EmbeddingProvider.Availability.ABSENT);
    if (cause.equals("starting")) when(provider.availability())
        .thenReturn(EmbeddingProvider.Availability.STARTING);
    if (cause.equals("failed")) when(provider.availability()).thenReturn(
        new EmbeddingProvider.Availability(EmbeddingProvider.Availability.State.FAILED,
            new IllegalStateException("load failed")));
    if (cause.equals("query-failure")) when(provider.embedQuery(anyString(), any()))
        .thenThrow(new IllegalStateException("encode failed"));
    if (cause.equals("invalid-document")) when(provider.embedDocument(anyString(), any()))
        .thenReturn(new float[0]);
    var hits = List.of(chunkHit("blank", " "), positionedHit("a", "dup-a", 0),
        positionedHit("b", "dup-b", 0), positionedHit("c", "diverse", 0),
        positionedHit("end", "tail", 9));
    float[] query = cause.equals("query-failure") || cause.equals("expired") ? null
        : cause.equals("invalid-query") ? new float[] {0, 0} : QUERY_VECTOR;
    var authority = cause.equals("expired")
        ? new InferenceRequest(InferenceRequest.Urgency.FOREGROUND, System.nanoTime() - 1,
            () -> false, InferenceRequest.SemanticBudget.FALLBACK)
        : InferenceRequest.foreground();
    assertEquals(List.of("a", "end"), ids(opsWith(mmrConfig(3, 0.3), provider)
        .diversifyChunks(Q, query, hits, 2, !cause.equals("disallowed"), authority)));
    if (!cause.equals("invalid-document")) {
      verify(provider, never()).embedDocument(anyString(), any());
    }
    verify(provider, times(cause.equals("query-failure") ? 1 : 0)).embedQuery(anyString(), any());
  }

  private static LuceneRuntimeTypes.SearchHit positionedHit(String id, String text, int index) {
    return new LuceneRuntimeTypes.SearchHit(id, 1.0f, Map.of(
        SchemaFields.CHUNK_CONTENT, text, SchemaFields.CHUNK_INDEX, Integer.toString(index),
        SchemaFields.CHUNK_TOTAL, "10"));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 2, 10})
  void emptyOrNoSelectionNeededRemovesBlanksWithoutInference(int k) {
    var provider = controlledProvider();
    var ops = opsWith(config("mmr"), provider);
    var good = chunkHit("good", "text");
    assertEquals(List.of(), ops.diversifyChunks(Q, null, List.of(), k, true,
        InferenceRequest.foreground()));
    assertEquals(k > 0 ? List.of(good) : List.of(), ops.diversifyChunks(Q, null,
        List.of(chunkHit("blank", " "), good), k, true, InferenceRequest.foreground()));
    verifyNoInteractions(provider);
  }

  @Test
  void distinctRealAndSyntheticPassagesKeepTheirOriginalIdentityAndMetadata() {
    var first = new LuceneRuntimeTypes.SearchHit("parent", 7f, Map.of(
        SchemaFields.CHUNK_CONTENT, "dup", SchemaFields.PARENT_DOC_ID, "parent",
        SchemaFields.CHUNK_INDEX, "0", SchemaFields.CHUNK_START_CHAR, "0",
        SchemaFields.CHUNK_END_CHAR, "3"));
    var second = new LuceneRuntimeTypes.SearchHit("parent", 6f, Map.of(
        SchemaFields.CHUNK_CONTENT, "dup", SchemaFields.PARENT_DOC_ID, "parent",
        SchemaFields.CHUNK_INDEX, "1", SchemaFields.CHUNK_START_CHAR, "4",
        SchemaFields.CHUNK_END_CHAR, "7"));
    var real = chunkHit("real-chunk", "dup");
    var otherParent = chunkHit("other-parent", "dup");
    var hits = List.of(first, second, real, otherParent);
    var snapshot = List.copyOf(hits);
    var ops = opsWith(mmrConfig(4, 1.0), controlledProvider());
    for (int repeat = 0; repeat < 3; repeat++) {
      var selected = ops.diversifyChunks(Q, QUERY_VECTOR, hits, 3, true, InferenceRequest.foreground());
      assertEquals(3, selected.size());
      assertSame(first, selected.get(0));
      assertSame(second, selected.get(1));
      assertSame(real, selected.get(2));
    }
    assertEquals(snapshot, hits);
    assertEquals("4", second.fields().get(SchemaFields.CHUNK_START_CHAR));
    assertEquals(6f, second.score());
  }

  @ParameterizedTest
  @ValueSource(strings = {"position", "unknown", "default"})
  void positionAndUnknownModesKeepTheirExistingBlankRowsWithoutInference(String mode) {
    var provider = controlledProvider();
    var cfg = mode.equals("default") ? new ResolvedConfigBuilder().build() : config(mode);
    var hits = List.of(chunkHit("blank", " "), chunkHit("h1", "dup"), chunkHit("h2", "diverse"));
    assertEquals(List.of("blank", "h1"), ids(opsWith(cfg, provider)
        .diversifyChunks(Q, null, hits, 2, true, InferenceRequest.foreground())));
    verifyNoInteractions(provider);
  }

  @ParameterizedTest
  @ValueSource(strings = {"query", "first-document", "last-document"})
  void normallyReturningProviderCannotStartMoreWorkAfterSemanticExpiry(String expiresAt) {
    var provider = controlledProvider();
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"));
    var ops = opsWith(config("mmr"), provider);
    var docCalls = new AtomicInteger();
    when(provider.embedQuery(anyString(), any())).thenAnswer(call -> {
      if (expiresAt.equals("query")) awaitDeadline(call.getArgument(1));
      return QUERY_VECTOR;
    });
    when(provider.embedDocument(anyString(), any())).thenAnswer(call -> {
      int number = docCalls.incrementAndGet();
      if ((expiresAt.equals("first-document") && number == 1)
          || (expiresAt.equals("last-document") && number == 3)) awaitDeadline(call.getArgument(1));
      return call.<String>getArgument(0).equals("diverse") ? new float[] {0.7f, 0.7f} : QUERY_VECTOR;
    });
    var authority = new InferenceRequest(InferenceRequest.Urgency.FOREGROUND,
        System.nanoTime() + Duration.ofMillis(100).toNanos(), () -> false,
        InferenceRequest.SemanticBudget.FALLBACK);
    assertEquals(List.of("h0", "h1"), ids(ops.diversifyChunks(Q, null, hits, 2, true, authority)));
    assertEquals(expiresAt.equals("query") ? 0 : expiresAt.equals("first-document") ? 1 : 3,
        docCalls.get());
  }

  private static void awaitDeadline(InferenceRequest authority) {
    while (!authority.deadlinePassed()) LockSupport.parkNanos(1_000_000);
  }

  @ParameterizedTest
  @ValueSource(strings = {"NONE", "REQUIRED"})
  void newExpiryPollingDoesNotAddThrowingAuthorityChecksOutsideFallback(String budget) {
    var provider = controlledProvider();
    var authority = new InferenceRequest(InferenceRequest.Urgency.FOREGROUND, System.nanoTime() - 1,
        () -> true, InferenceRequest.SemanticBudget.valueOf(budget));
    var hits = List.of(chunkHit("h0", "dup-a"), chunkHit("h1", "dup-b"),
        chunkHit("h2", "diverse"));
    assertEquals(List.of("h0", "h2"), ids(assertDoesNotThrow(() -> opsWith(config("mmr"), provider)
        .diversifyChunks(Q, QUERY_VECTOR, hits, 2, true, authority))));
  }

}
