/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

/** The physical owner uses this handle to publish observations of its existing lifecycle. */
public interface ComponentHandle {
  ComponentSpec spec();

  EngineComponentSnapshot.Component snapshot();

  void transition(ComponentState state, String reasonCode, String evidence);

  void setAppliedVersion(String digest);

  void setDesiredVersion(String digest);

  void setLastCompose(ComposeEvidence evidence);

  void recordRecoveryAttempt(String evidence);
}
