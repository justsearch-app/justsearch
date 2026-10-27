/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;

public final class LoopPacingPolicy {
  // Scoped fixtures may shorten idle polling; production has no writer for this value.
  private static volatile long idleSleepMillis = 1000L;
  private static final long ACTIVE_IDLE_SLEEP_MS = 100L;

  // Tempdoc 710 Wave-1.5 Move 4: pollBatchSize / embeddingBackfillBatchSize /
  // nerBackfillBatchSize / disambiguationBackfillBatchSize / spladeBackfillBatchSize /
  // spladeInterleaveBatchSize / spladeInterleaveIntervalMs / commitIntervalMs /
  // maxDocsBeforeCommit moved off bare constants here onto
  // ResolvedConfig.Ai.BackfillPacing (justsearch.backfill.* config surface). Callers now read
  // the resolved pacing snapshot directly; isTimeCommitTriggered/isBufferCommitTriggered below
  // take the threshold as a parameter instead of a static field.

  private LoopPacingPolicy() {}

  public static long idleSleepMs() {
    return idleSleepMillis;
  }

  public static long activeIdleSleepMs() {
    return ACTIVE_IDLE_SLEEP_MS;
  }

  /**
   * Returns a shorter sleep duration when recently active (just finished processing), allowing the
   * loop to pick up newly queued work faster. Falls back to the standard idle sleep after sustained
   * idleness.
   */
  public static long idleSleepMs(boolean recentlyActive) {
    return recentlyActive ? ACTIVE_IDLE_SLEEP_MS : idleSleepMillis;
  }

  /**
   * Whether GPU-heavy bulk backfill may run. The two yield reasons are deliberately distinct
   * (tempdoc 630):
   *
   * <ul>
   *   <li><b>Energy</b> ({@code energyReduced}, OS energy saver): defers backfill <i>regardless of
   *       GPU/CPU</i> — CPU backfill still drains the battery, so the power reason applies even when
   *       embeddings run on CPU (the common case on the GPU-less laptops where energy saver matters).
   *   <li><b>GPU yield</b> ({@code mainGpuActive}, Main claimed the GPU): defers only when embeddings
   *       are <i>actually on the GPU</i> — a VRAM conflict cannot exist on CPU embeddings.
   * </ul>
   *
   * Folding energy into the GPU-conflict escape would silently no-op the energy throttle on CPU
   * embeddings, so the two are kept separate here.
   *
   * <p>Spec E step E3 adds the third reason, the bulk hold ({@code bulkHeld}: external GPU memory
   * pressure, later a game). It defers only when <b>any</b> bulk encoder is on the GPU: the
   * embedding provider, or the sparse (SPLADE / BGE-M3) or NER encoder ({@code
   * otherBulkEncoderOnGpu}). CPU bulk is never held (P2): no new bulk GPU batch starts, and CPU
   * work is not GPU work.
   *
   * @param otherBulkEncoderOnGpu whether the sparse or NER bulk encoder runs on the GPU
   */
  public static boolean shouldRunBackfill(
      boolean mainGpuActive,
      boolean energyReduced,
      boolean bulkHeld,
      boolean otherBulkEncoderOnGpu,
      EmbeddingProvider embeddingProvider) {
    if (energyReduced) {
      return false; // power: defer regardless of GPU/CPU
    }
    boolean embeddingOnGpu = embeddingProvider.isUsingGpu();
    if (mainGpuActive && embeddingOnGpu) {
      return false; // VRAM conflict: only when on GPU
    }
    return !(bulkHeld && (embeddingOnGpu || otherBulkEncoderOnGpu)); // hold: GPU bulk only
  }

  /**
   * Tempdoc 885 item 3: {@code userActive} left this signature. Foreground contention is now a
   * pacing decision ({@link io.justsearch.indexerworker.loop.pacing.IndexingPacing}), not an
   * interrupt — a contended backfill runs slower, it does not stop.
   */
  public static boolean shouldInterruptBackfill(
      boolean running,
      boolean mainGpuActive,
      boolean energyReduced,
      boolean bulkHeld,
      boolean otherBulkEncoderOnGpu,
      EmbeddingProvider embeddingProvider) {
    boolean backfillBlocked =
        !shouldRunBackfill(
            mainGpuActive, energyReduced, bulkHeld, otherBulkEncoderOnGpu, embeddingProvider);
    return !running || backfillBlocked;
  }

  /**
   * @param commitIntervalMs the configured time-based commit threshold ({@link
   *     io.justsearch.configuration.resolved.ResolvedConfig.Ai.BackfillPacing#commitIntervalMs()}).
   */
  public static boolean isTimeCommitTriggered(
      long timeSinceCommitMs, long indexedSinceCommit, long commitIntervalMs) {
    return timeSinceCommitMs >= commitIntervalMs && indexedSinceCommit > 0;
  }

  /**
   * @param maxDocsBeforeCommit the configured buffer-based commit threshold ({@link
   *     io.justsearch.configuration.resolved.ResolvedConfig.Ai.BackfillPacing#maxDocsBeforeCommit()}).
   */
  public static boolean isBufferCommitTriggered(long indexedSinceCommit, int maxDocsBeforeCommit) {
    return indexedSinceCommit >= maxDocsBeforeCommit;
  }
}
