/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Locale;

/**
 * Outcome of an inference-mode intent write ({@code POST /api/inference/mode}).
 *
 * <p>Tempdoc 804 §B6: the endpoint used to answer with a literal {@code success: true} beside a
 * live mode read taken before the reconciler had converged — round 10 measured
 * {@code {"success":true,"mode":"indexing"}} for a request to go ONLINE, a self-contradicting
 * payload. The write really is asynchronous (the intent lands in the runtime spec; the engine
 * converges on the reconciler thread), so the honest answer separates <i>what was asked</i> from
 * <i>what is live</i> and says whether the two already agree.
 *
 * @param requested normalized target mode ({@code "online"} / {@code "indexing"})
 * @param mode the first-execution live mode; absent on a recorded retry
 * @param state {@link #STATE_CONVERGED} when {@code mode} already equals {@code requested},
 *     {@link #STATE_RECORDED} when the intent is persisted but the engine has not converged yet.
 *     {@link #STATE_ACCEPTED} when the row is still incomplete.
 * @param operationKey the accepted row's issued key, absent only in non-writing compatibility projections
 * @param acceptedRevision the committed settings revision, absent for incomplete rows
 */
public record ModeTransitionOutcome(String requested, String mode, String state,
    String operationKey, Long acceptedRevision) {

  public ModeTransitionOutcome(String requested, String mode, String state) {
    this(requested, mode, state, null, null);
  }

  /** Attach the durable settings receipt; live observations are absent on replay. */
  public ModeTransitionOutcome withReceipt(String key, Long revision) {
    return new ModeTransitionOutcome(requested, mode, state, key, revision);
  }

  /** The intent is durably recorded; the engine has not reached it yet. */
  public static final String STATE_RECORDED = "recorded";

  /** The row exists, but settings commitment has not been durably completed. */
  public static final String STATE_ACCEPTED = "accepted";

  /** The live mode already equals the requested mode. */
  public static final String STATE_CONVERGED = "converged";

  /** Computes the outcome from the requested target and the live mode at return time. */
  public static ModeTransitionOutcome of(String requested, String liveMode) {
    String normalized = requested == null ? null : requested.trim().toLowerCase(Locale.ROOT);
    boolean converged = normalized != null && normalized.equalsIgnoreCase(liveMode);
    return new ModeTransitionOutcome(
        normalized, liveMode, converged ? STATE_CONVERGED : STATE_RECORDED);
  }
}
