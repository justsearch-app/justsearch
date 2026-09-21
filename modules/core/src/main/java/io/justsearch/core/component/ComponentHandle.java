/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

/** The physical owner uses this handle to publish observations of its existing lifecycle. */
public interface ComponentHandle {
  ComponentSpec spec();

  EngineComponentSnapshot.Component snapshot();

  void transition(ComponentState state, String reasonCode, String evidence);

  /**
   * Publishes only while the complete component observation still equals {@code expected}.
   * A matched no-op returns true without publishing; an obsolete observation returns false.
   * Listeners run after the registry releases its publication lock.
   */
  boolean transitionIfUnchanged(EngineComponentSnapshot.Component expected,
      ComponentState state, String reasonCode, String evidence);

  /**
   * As above, but validates the whole registry observation atomically. Use for an observation
   * whose preconditions include other components, such as index readiness requiring API READY.
   */
  boolean transitionIfUnchanged(EngineComponentSnapshot expected,
      ComponentState state, String reasonCode, String evidence);

  void setAppliedVersion(String digest);

  void setDesiredVersion(String digest);

  void setLastCompose(ComposeEvidence evidence);

  void recordRecoveryAttempt(String evidence);
}
