/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

/**
 * Outcome of one backfill-stage batch call.
 *
 * <p>Tempdoc 710 Move 2 item 4: {@code OperationalMetrics.recordStageTiming}/{@code
 * recordEnrichmentCompleted} were previously called only by {@code
 * CombinedEnrichmentBackfillOps} (710 S-B3 finding — the counters froze in individual-backfill
 * mode, since none of {@code EmbeddingBackfillOps}, {@code NerBackfillOps}, {@code
 * SpladeBackfillOps}, {@code BgeM3BackfillOps} recorded anything). Recording responsibility moves
 * to {@link BackfillScheduler} — the only component that knows WHICH pass ran (combined,
 * individual, or neither) — so each {@code process*Backfill} method now returns this record
 * instead of a bare {@code boolean}/{@code void}, and the scheduler records per-stage timing for
 * whichever path executed.
 *
 * @param success the pre-existing per-op control-flow signal, semantics unchanged by this move:
 *     SPLADE/BGE-M3's {@code processSpladeBackfill}/{@code processBgeM3Backfill} mean "not a
 *     systemic failure" (drives {@link BackfillScheduler}'s retry backoff); {@code
 *     processChunkEmbeddingBackfill} means "any forward progress (success or failure) worth
 *     looping again for" (drives the tight-loop continuation); {@code processEmbeddingBackfill}
 *     and {@code processNerBackfill} carry it for shape-consistency only — neither caller reads
 *     it today.
 * @param docsProcessed count of documents this call successfully completed (not merely
 *     attempted) — the same count each op already logged, now surfaced to the caller. Output the
 *     writer corrected to FAILED is reported only in {@code accounting}. Units are documents,
 *     not batches (contrast {@code batchTimingCount}, which counts batches — see
 *     {@code OperationalMetrics#recordStageTiming} javadoc).
 * @param elapsedMs wall-clock time for the whole call, in milliseconds.
 * @param accounting final selected-stage dispositions and computed/publication counters
 * @param attempted whether work was attempted, including calls with zero completions
 */
public record StageOutcome(boolean success, int docsProcessed, long elapsedMs,
    EnrichmentAccounting.Summary accounting, boolean attempted) {
  public StageOutcome(boolean success, int docsProcessed, long elapsedMs) {
    this(success, docsProcessed, elapsedMs, EnrichmentAccounting.Summary.empty(), true);
  }

  static StageOutcome observed(boolean success, int docsProcessed, long startNanos,
      EnrichmentAccounting accounting) {
    return new StageOutcome(success, docsProcessed, (System.nanoTime() - startNanos) / 1_000_000,
        accounting.snapshot(), true);
  }

  /** A no-op outcome: nothing was pending, nothing to record. */
  public static StageOutcome none() {
    return new StageOutcome(true, 0, 0, EnrichmentAccounting.Summary.empty(), false);
  }

  /** No documents processed, but the call took real time (e.g., interrupted mid-batch). */
  public static StageOutcome elapsedSince(long startNanos) {
    return new StageOutcome(true, 0, (System.nanoTime() - startNanos) / 1_000_000);
  }
}
