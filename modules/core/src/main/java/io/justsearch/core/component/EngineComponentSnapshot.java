/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, revisioned view of every registered Engine component. */
public record EngineComponentSnapshot(long revision, List<Component> components) {
  public EngineComponentSnapshot {
    if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    components = List.copyOf(components);
  }

  /** One coherent component observation. Nullable fields have not yet been published. */
  public record Component(
      ComponentSpec spec,
      ComponentState state,
      String reasonCode,
      Instant stateSince,
      long stateSinceMonotonicNanos,
      String appliedVersion,
      String desiredVersion,
      ComposeEvidence lastCompose,
      int recoveryAttempts,
      String evidence) {
    public Component {
      Objects.requireNonNull(spec, "spec");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(stateSince, "stateSince");
      if (recoveryAttempts < 0) {
        throw new IllegalArgumentException("recovery attempts must be non-negative");
      }
    }
  }
}
