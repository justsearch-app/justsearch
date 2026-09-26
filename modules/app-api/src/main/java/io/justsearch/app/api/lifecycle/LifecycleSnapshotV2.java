/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.util.Objects;

/** Schema-2 health projection of the Engine's four component observations. */
public record LifecycleSnapshotV2(
    int schema_version,
    String observed_at,
    Lifecycle lifecycle,
    Components components) {
  public static final int SCHEMA_VERSION = 2;

  public LifecycleSnapshotV2 {
    if (schema_version != SCHEMA_VERSION) {
      throw new IllegalArgumentException("schema_version must be " + SCHEMA_VERSION);
    }
    Objects.requireNonNull(observed_at, "observed_at");
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(components, "components");
  }

  public record Lifecycle(LifecycleState state, String reason_code, String message) {
    public Lifecycle {
      Objects.requireNonNull(state, "state");
      if (state == LifecycleState.LIFECYCLE_STATE_UNSPECIFIED
          || state == LifecycleState.UNRECOGNIZED) {
        throw new IllegalArgumentException("lifecycle state must be a real state: " + state);
      }
    }
  }

  public record Components(Component api, Component index, Component encoders, Component generative) {
    public Components {
      Objects.requireNonNull(api, "api");
      Objects.requireNonNull(index, "index");
      Objects.requireNonNull(encoders, "encoders");
      Objects.requireNonNull(generative, "generative");
    }
  }

  public record Component(ComponentState state, String reason_code, String state_since) {
    public Component {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(state_since, "state_since");
    }
  }
}
