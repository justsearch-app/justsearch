/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.util.Objects;

/** Readiness projection of one registry observation; contains no independent lifecycle state. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineComponentView(
    ComponentState state,
    String reasonCode,
    String stateSince,
    String appliedVersion,
    String desiredVersion,
    ComposeEvidence.Mode mode,
    String reason,
    Long freeBytes,
    Long footprintBytes,
    long deadlineMs,
    int recoveryAttempts,
    String evidence) {
  public EngineComponentView {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(stateSince, "stateSince");
    if (deadlineMs < 0 || recoveryAttempts < 0) {
      throw new IllegalArgumentException("deadline and recovery attempts must be non-negative");
    }
  }

  public static EngineComponentView from(EngineComponentSnapshot.Component component) {
    var compose = component.lastCompose();
    return new EngineComponentView(component.state(), component.reasonCode(),
        component.stateSince().toString(), component.appliedVersion(), component.desiredVersion(),
        compose == null ? null : compose.mode(),
        compose == null ? null : compose.reason(),
        compose == null ? null : compose.freeBytes(),
        compose == null ? null : compose.footprintBytes(),
        component.spec().startDeadline().toMillis(), component.recoveryAttempts(), component.evidence());
  }
}
