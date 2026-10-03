/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.util.Optional;

/** The physical owner uses this handle to publish observations of its existing lifecycle. */
public interface ComponentHandle {
  ComponentSpec spec();

  /** Identity check for composition projections, including wrappers around this handle. */
  boolean belongsTo(EngineComponentRegistry registry);

  EngineComponentSnapshot.Component snapshot();

  void transition(ComponentState state, String reasonCode, String evidence);

  /**
   * Prepares one complete replacement for this handle using its owning registry. Callers may
   * validate and install it with a physical serving-view publication under the shared write lock,
   * then notify observers only after releasing their owner and publication locks.
   */
  default EngineComponentRegistry.PreparedBatch prepareReplacement(
      EngineComponentSnapshot.Component replacement) {
    throw new UnsupportedOperationException("prepared component publication is not supported");
  }

  /**
   * Publishes only while the complete component observation still equals {@code expected}.
   * A matched no-op returns true without publishing; an obsolete observation returns false.
   * Listeners run after the registry releases its publication lock.
   */
  boolean transitionIfUnchanged(EngineComponentSnapshot.Component expected,
      ComponentState state, String reasonCode, String evidence);

  /**
   * As {@link #transitionIfUnchanged(EngineComponentSnapshot.Component, ComponentState, String,
   * String)}, returning the exact component row captured at publication time. The returned row is
   * captured before listeners run, so a listener racing a caller cannot change the witness.
   * A matched no-op returns the current row without publishing; an obsolete observation returns
   * {@link Optional#empty()}.
   */
  Optional<EngineComponentSnapshot.Component> tryTransitionIfUnchanged(
      EngineComponentSnapshot.Component expected, ComponentState state, String reasonCode,
      String evidence);

  /**
   * As above, but validates the whole registry observation atomically. Use for an observation
   * whose preconditions include other components, such as index readiness requiring API READY.
   */
  boolean transitionIfUnchanged(EngineComponentSnapshot expected,
      ComponentState state, String reasonCode, String evidence);

  /**
   * Atomically admits one physical recovery by replacing the exact expected observation with
   * STARTING and incrementing its cumulative attempt count. The physical owner calls this only
   * after validating its current instance and applied configuration under its own lifetime lock.
   * Returns the exact admitted observation, or empty if another publication won; a lost admission
   * changes neither state nor count. Observer delivery follows publication as for transitions.
   */
  Optional<EngineComponentSnapshot.Component> tryBeginRecovery(
      EngineComponentSnapshot.Component expected, String reasonCode, String evidence);

  /**
   * Admits recovery with STARTING or RELOADING using the same atomic attempt claim. RELOADING
   * preserves an incumbent serving view while its physical owner restores semantic service.
   * Generic recovery continues to use the three-argument STARTING admission.
   */
  default Optional<EngineComponentSnapshot.Component> tryBeginRecovery(
      EngineComponentSnapshot.Component expected, ComponentState recoveryState,
      String reasonCode, String evidence) {
    if (recoveryState != ComponentState.STARTING && recoveryState != ComponentState.RELOADING) {
      throw new IllegalArgumentException("recoveryState must be STARTING or RELOADING");
    }
    if (recoveryState != ComponentState.STARTING) {
      throw new UnsupportedOperationException("selected recovery state is not supported");
    }
    return tryBeginRecovery(expected, reasonCode, evidence);
  }

  void setAppliedVersion(String digest);

  void setDesiredVersion(String digest);

  void setLastCompose(ComposeEvidence evidence);

  /** Increments the attempt count; null evidence preserves the current physical cause. */
  void recordRecoveryAttempt(String evidence);
}
