/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.Mode;
import java.util.Objects;

/**
 * Result of a transition body. Tempdoc 518 P1.
 *
 * <p>{@link TransitionBody} returns one of two sub-records:
 *
 * <ul>
 *   <li>{@link Success} — the body completed; the runner swaps {@code nextView} into the holder
 *       and calls {@code modeState.complete(target)}.
 *   <li>{@link Failure} — the body categorized its failure; the runner rolls back and records
 *       {@code failure} on the view. {@code rollbackView} is the view to install after rollback
 *       (typically the prior view with any cleanup applied, e.g., clearing
 *       {@code usingExternalLlamaServer} when an adopted server probe failed).
 * </ul>
 *
 * <p>Sealed sum-type — pattern-matched by {@link TransitionRunner}. No methods; data only. This
 * is the 517 pattern (sealed records as dispatch values).
 */
public sealed interface TransitionOutcome permits TransitionOutcome.Success, TransitionOutcome.Failure {

  /** Stable mode selected after a failed transition. */
  enum FailureRestoration {
    PREVIOUS,
    OFFLINE
  }

  /** Body succeeded; runner completes the transition and installs {@code nextView}. */
  record Success(Mode target, InferenceRuntimeView nextView)
      implements TransitionOutcome {}

  /**
   * Body failed; runner restores the selected stable mode and installs {@code rollbackView}
   * with {@code failure} recorded.
   */
  record Failure(
      InferenceFailure failure,
      InferenceRuntimeView rollbackView,
      FailureRestoration restoration)
      implements TransitionOutcome {
    public Failure {
      Objects.requireNonNull(failure, "failure");
      Objects.requireNonNull(rollbackView, "rollbackView");
      Objects.requireNonNull(restoration, "restoration");
    }
  }

  static Success success(Mode target, InferenceRuntimeView nextView) {
    return new Success(target, nextView);
  }

  static Failure failure(InferenceFailure failure, InferenceRuntimeView rollbackView) {
    return new Failure(failure, rollbackView, FailureRestoration.PREVIOUS);
  }

  static Failure failureOffline(InferenceFailure failure, InferenceRuntimeView rollbackView) {
    return new Failure(failure, rollbackView, FailureRestoration.OFFLINE);
  }
}
