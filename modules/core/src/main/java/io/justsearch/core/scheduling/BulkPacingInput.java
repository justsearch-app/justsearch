/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

/**
 * What the indexing duty cycle reads from Switch A (Spec E §1.4, step E2): whether chat generation
 * puts bulk work on the duty floor, and at which duty. {@link BulkWorkGate} is the one production
 * implementation; {@link #NEVER} is the input of a pacing policy with no gate.
 *
 * <p>The duty methods take the search-load values because NO_GPU and UNRESOLVED keep today's
 * duty while chat generates (re-review N6, Q8): the configured search duty and its debt cap.
 */
public interface BulkPacingInput {

  /** No generation input: the duty floor never applies. */
  BulkPacingInput NEVER =
      new BulkPacingInput() {
        @Override
        public boolean dutyFloor() {
          return false;
        }

        @Override
        public int generationDutyPct(int searchDutyPct) {
          return searchDutyPct;
        }

        @Override
        public long generationMaxDebtMs(long searchMaxDebtMs) {
          return searchMaxDebtMs;
        }
      };

  /** Whether bulk runs at the generation duty because chat is generating. */
  boolean dutyFloor();

  /**
   * The bulk duty while chat generates and no search runs.
   *
   * @param searchDutyPct the configured duty under search load, kept where the profile keeps
   *     today's duty
   */
  int generationDutyPct(int searchDutyPct);

  /**
   * The yield-debt cap paired with {@link #generationDutyPct}.
   *
   * @param searchMaxDebtMs today's cap, kept where the profile keeps today's duty
   */
  long generationMaxDebtMs(long searchMaxDebtMs);
}
