/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    public boolean isAvailable() {
      return true;
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
}
