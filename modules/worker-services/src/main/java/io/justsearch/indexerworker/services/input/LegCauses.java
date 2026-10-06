/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.SearchReasonCode.LegCause;
import io.justsearch.ort.SessionAcquireDeadlineExceededException;

/**
 * The reason/cause pairing rule and the phase-to-cause mapping of Spec D §1.4, shared by search
 * capture and the RAG query leg (§5.1).
 */
public final class LegCauses {
  private LegCauses() {}

  /** A cause is carried exactly when the reason is a semantic budget miss. */
  static void requireConsistent(SearchReasonCode reason, LegCause cause) {
    boolean budget = reason == SearchReasonCode.SEMANTIC_BUDGET_EXCEEDED;
    if (budget == (cause == LegCause.NONE)) {
      throw new IllegalArgumentException(
          "cause " + cause + " does not match reason " + reason
              + "; a cause is carried exactly with SEMANTIC_BUDGET_EXCEEDED");
    }
  }

  /**
   * The cause named by the code that observed the miss. A deadline miss with no phase was observed
   * before a seat (a caller-side check before the device wait), which is the gate phase.
   */
  public static LegCause of(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SessionAcquireDeadlineExceededException deadline) {
        return switch (deadline.phase()) {
          case LOADING -> LegCause.LOADING;
          case GATE -> LegCause.CONTENDED;
          case RUN -> LegCause.STUCK;
        };
      }
      if (t instanceof InferenceRequest.DeadlineExceeded) return LegCause.CONTENDED;
    }
    return LegCause.CONTENDED;
  }
}
