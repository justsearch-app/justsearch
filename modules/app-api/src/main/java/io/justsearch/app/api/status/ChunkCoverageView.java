/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Chunk vector coverage metrics for RAG readiness.
 *
 * <p>Tempdoc 931 §E item 8 added the chunk-SPLADE trio. The sibling {@code spladeCoveragePercent} on
 * {@link EnrichmentProgressView} counts PARENT documents only, so nothing here described the
 * population the chunk-SPLADE retrieval leg scores against. {@code chunkSpladeEnabled} is
 * {@code rag.chunk_splade.enabled}: with it off no chunk carries a {@code splade_status} at all, so
 * the counts are legitimately zero — read the flag before reading the coverage.
 */
public record ChunkCoverageView(
    long chunkDocCount,
    long chunkEmbeddingCompletedCount,
    long chunkEmbeddingPendingCount,
    long chunkEmbeddingFailedCount,
    double chunkVectorCoveragePercent,
    boolean chunkVectorsReady,
    boolean chunkSpladeEnabled,
    long chunkSpladeCompletedCount,
    long chunkSpladePendingCount,
    double chunkSpladeCoveragePercent,
    boolean chunkingNotRequired,
    long chunkSpladeDocCount,
    long chunkSpladeFailedCount) {
  public ChunkCoverageView(long chunkDocCount, long chunkEmbeddingCompletedCount,
      long chunkEmbeddingPendingCount, long chunkEmbeddingFailedCount,
      double chunkVectorCoveragePercent, boolean chunkVectorsReady, boolean chunkSpladeEnabled,
      long chunkSpladeCompletedCount, long chunkSpladePendingCount, double chunkSpladeCoveragePercent) {
    this(chunkDocCount, chunkEmbeddingCompletedCount, chunkEmbeddingPendingCount,
        chunkEmbeddingFailedCount, chunkVectorCoveragePercent, chunkVectorsReady, chunkSpladeEnabled,
        chunkSpladeCompletedCount, chunkSpladePendingCount, chunkSpladeCoveragePercent, false,
        chunkSpladeCompletedCount + chunkSpladePendingCount, 0);
  }
  public static ChunkCoverageView empty() {
    return new ChunkCoverageView(0, 0, 0, 0, 0.0, false, false, 0, 0, 0.0);
  }
}
