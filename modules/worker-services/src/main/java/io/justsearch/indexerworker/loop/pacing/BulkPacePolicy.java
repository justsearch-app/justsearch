/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

/**
 * How much GPU work a bulk producer may do between two {@link IndexingPacing#pace()} calls (Spec D
 * §3.3; ADR-0050 decision 2, "about 1 s").
 *
 * <p>{@link IndexingPacing} can only yield to foreground work at a {@code pace()} call, and the
 * device gate can only seat a waiting query between two ORT runs. A bulk batch that runs several
 * seconds of encoder work between two {@code pace()} calls therefore holds its duty share for that
 * whole stretch — measured at 1.95-3.28 s for one combined backfill batch (Spec D measurement M5).
 * The phase slice sizes below bound that stretch by document count, derived from M5's per-document
 * cost so that one slice stays at or under {@link #bulkPaceTargetMs}.
 *
 * <p>Values are provisional until Spec D step 11 replaces them from the measurement files.
 *
 * @param bulkPaceTargetMs the longest GPU stretch of a bulk producer between two {@code pace()}
 *     calls. ADR-0050 decision 2 ("about 1 s").
 * @param spladePhaseDocs documents per paced SPLADE slice. M5: 13.3 ms/doc, so 64 docs ≈ 0.85 s.
 * @param embeddingPhaseDocs documents (or encoder windows) per paced embedding slice. M5: 7.3
 *     ms/doc, so 128 ≈ 0.93 s.
 * @param nerPhaseDocs documents per paced NER slice. M5: 5.1 ms/doc, so 192 ≈ 0.98 s.
 */
public record BulkPacePolicy(
    long bulkPaceTargetMs, int spladePhaseDocs, int embeddingPhaseDocs, int nerPhaseDocs) {

  /** The provisional Spec D values (§1 parameter table). */
  public static final BulkPacePolicy DEFAULTS = new BulkPacePolicy(1_000L, 64, 128, 192);

  public BulkPacePolicy {
    if (bulkPaceTargetMs <= 0L) {
      throw new IllegalArgumentException("bulkPaceTargetMs must be positive: " + bulkPaceTargetMs);
    }
    if (spladePhaseDocs < 1 || embeddingPhaseDocs < 1 || nerPhaseDocs < 1) {
      throw new IllegalArgumentException(
          "phase slices must hold at least one document: splade="
              + spladePhaseDocs
              + " embedding="
              + embeddingPhaseDocs
              + " ner="
              + nerPhaseDocs);
    }
  }

  /**
   * Counts the documents one GPU phase has encoded since the last {@code pace()} and paces before a
   * unit that would take the count past the phase's slice size. Single-threaded: one instance
   * belongs to one bulk batch on one thread.
   */
  public static final class PhasePacer {
    private final IndexingPacing pacing;
    private int phaseDocs;
    private int sinceLastPace;

    public PhasePacer(IndexingPacing pacing) {
      this.pacing = pacing;
      this.phaseDocs = Integer.MAX_VALUE;
    }

    /**
     * Enters a new phase with its own slice size. Paces first if the previous phase did any work
     * since the last {@code pace()}: a phase boundary is a pace point (Spec D §3.3).
     */
    public void startPhase(int docsPerSlice) {
      if (sinceLastPace > 0) {
        pacing.pace();
        sinceLastPace = 0;
      }
      this.phaseDocs = docsPerSlice;
    }

    /** Called before a unit of {@code docs} documents; paces if the unit would overrun the slice. */
    public void beforeUnit(int docs) {
      if (sinceLastPace > 0 && sinceLastPace + docs > phaseDocs) {
        pacing.pace();
        sinceLastPace = 0;
      }
    }

    /** Called after a unit of {@code docs} documents ran (successfully or not). */
    public void afterUnit(int docs) {
      sinceLastPace += Math.max(0, docs);
    }
  }
}
