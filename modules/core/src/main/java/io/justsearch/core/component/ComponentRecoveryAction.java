/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.util.Objects;
import java.util.Optional;

/** One same-applied-configuration attempt performed by the component's physical owner. */
@FunctionalInterface
public interface ComponentRecoveryAction {
  /**
   * Resolve and validate the physical instance and applied configuration under its lifetime lock,
   * then call {@link Request#begin()} before retiring or composing anything. Refusal before
   * admission spends no attempt. A thrown failure after admission spends one attempt. No
   * implementation may perform a nested retry or change the desired settings as part of recovery.
   * Expected failures after admission must be caught while the lifetime lock remains held and
   * completed FAILED before returning. An escaped exception means terminal ownership could not
   * be proven; the monitor may fail only the unchanged admission, otherwise its deadline applies.
   */
  Result recover(Request request) throws Exception;

  /** Terminal outcome of one admitted same-configuration recovery attempt. */
  enum Outcome { RECOVERED, DEGRADED, FAILED, REFUSED, SUPERSEDED }

  /**
   * Result of a physical recovery action. RECOVERED, DEGRADED and FAILED carry the exact terminal row
   * returned by {@link Request#complete}; REFUSED and SUPERSEDED carry no terminal row.
   */
  record Result(Outcome outcome, EngineComponentSnapshot.Component observation) {
    /** Refusal before admission spends no recovery attempt and has no terminal observation. */
    public static final Result REFUSED = new Result(Outcome.REFUSED, null);

    /**
     * An admitted owner lost physical/configuration authority; the spent attempt remains counted.
     * A deadline alone does not supersede its physical owner or justify this result.
     */
    public static final Result SUPERSEDED = new Result(Outcome.SUPERSEDED, null);

    public Result {
      Objects.requireNonNull(outcome, "outcome");
      if (outcome == Outcome.REFUSED || outcome == Outcome.SUPERSEDED) {
        if (observation != null) {
          throw new IllegalArgumentException("A refused or superseded recovery has no terminal observation");
        }
      } else {
        Objects.requireNonNull(observation, "observation");
        ComponentState expected = outcome == Outcome.RECOVERED ? ComponentState.READY
            : outcome == Outcome.DEGRADED ? ComponentState.UNAVAILABLE : ComponentState.FAILED;
        if (observation.state() != expected) {
          throw new IllegalArgumentException(
              outcome + " recovery requires terminal state " + expected);
        }
      }
    }

    public static Result recovered(EngineComponentSnapshot.Component observation) {
      return new Result(Outcome.RECOVERED, observation);
    }

    public static Result failed(EngineComponentSnapshot.Component observation) {
      return new Result(Outcome.FAILED, observation);
    }

    /** Exact prior configuration restored with only its already-known unavailable roles. */
    public static Result degraded(EngineComponentSnapshot.Component observation) {
      return new Result(Outcome.DEGRADED, observation);
    }
  }

  /** Ephemeral monitor admission, valid only for this invocation of the physical owner. */
  interface Request {
    EngineComponentSnapshot.Component expected();

    /** Reads the current observation; this alone does not prove physical ownership. */
    EngineComponentSnapshot.Component current();

    /** Returns the exact STARTING observation captured by a successful {@link #begin()}. */
    Optional<EngineComponentSnapshot.Component> admitted();

    /** Atomically claims the expected observation once; false forbids any physical effect. */
    boolean begin();

    /**
     * Publishes one terminal result only for the exact current admission. The physical owner
     * validates instance identity, applied configuration, and admission under its lifetime lock
     * before calling this method. A coordinator lifetime lock plus a reserved physical view may
     * retain that proof while narrower physical publication locks are released for this exact CAS,
     * so registry observers never enter those physical locks. READY and FAILED are terminal;
     * UNAVAILABLE is permitted only when the owner proves a coherent restoration with no newly
     * missing roles. That degraded result must clear the retryable recovery-failure reason.
     */
    Optional<EngineComponentSnapshot.Component> complete(
        EngineComponentSnapshot.Component expectedCurrent, ComponentState state,
        String reasonCode, String evidence);

    /** Shutdown revokes permission to start new work, including after a physical close. */
    boolean cancelled();
  }
}
