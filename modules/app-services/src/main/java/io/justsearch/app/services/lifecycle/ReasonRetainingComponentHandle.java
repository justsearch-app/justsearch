/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.util.Objects;

/**
 * Stateless publication policy shared by every physical and supervisory writer of a component.
 * The registry's conditional transition commits the retention decision atomically; this wrapper
 * holds no lifecycle state or lock and never invokes listeners under a second publication lock.
 */
public final class ReasonRetainingComponentHandle implements ComponentHandle {
  private final ComponentHandle delegate;

  public ReasonRetainingComponentHandle(ComponentHandle delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public ComponentSpec spec() {
    return delegate.spec();
  }

  @Override
  public EngineComponentSnapshot.Component snapshot() {
    return delegate.snapshot();
  }

  @Override
  public void transition(ComponentState state, String reasonCode, String evidence) {
    while (!transitionIfUnchanged(snapshot(), state, reasonCode, evidence)) {
      // A concurrent owner published first. Re-evaluate retention against its evidence.
    }
  }

  @Override
  public boolean transitionIfUnchanged(EngineComponentSnapshot.Component expected,
      ComponentState state, String reasonCode, String evidence) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(state, "state");
    boolean retained = ReasonRetention.retainHeld(
        expected.reasonCode(), reasonCode, RegistryBackedCapability.healthOf(state));
    return delegate.transitionIfUnchanged(expected, state,
        retained ? expected.reasonCode() : reasonCode,
        retained ? expected.evidence() : evidence);
  }

  @Override
  public boolean transitionIfUnchanged(EngineComponentSnapshot expected,
      ComponentState state, String reasonCode, String evidence) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(state, "state");
    var component = expected.components().stream()
        .filter(candidate -> candidate.spec().equals(spec()))
        .findFirst().orElseThrow(() -> new IllegalArgumentException(
            "Expected registry observation does not contain " + spec().name()));
    boolean retained = ReasonRetention.retainHeld(
        component.reasonCode(), reasonCode, RegistryBackedCapability.healthOf(state));
    return delegate.transitionIfUnchanged(expected, state,
        retained ? component.reasonCode() : reasonCode,
        retained ? component.evidence() : evidence);
  }

  @Override
  public void setAppliedVersion(String digest) {
    delegate.setAppliedVersion(digest);
  }

  @Override
  public void setDesiredVersion(String digest) {
    delegate.setDesiredVersion(digest);
  }

  @Override
  public void setLastCompose(ComposeEvidence evidence) {
    delegate.setLastCompose(evidence);
  }

  @Override
  public void recordRecoveryAttempt(String evidence) {
    delegate.recordRecoveryAttempt(evidence);
  }
}
