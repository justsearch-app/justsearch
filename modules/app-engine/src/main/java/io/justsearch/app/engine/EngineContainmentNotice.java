/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Publishes {@code engine.containment_unavailable} on the non-blocking Health condition surface when
 * the Engine could not join its own Windows job at boot (Spec F §1.3). Nothing is published when the
 * Engine is contained or containment does not apply.
 */
public final class EngineContainmentNotice {
  private EngineContainmentNotice() {}

  /**
   * @param status {@link EngineContainment#status()} of the boot containment
   * @param reason {@link EngineContainment#unavailableReason()} of the boot containment
   */
  public static void publish(EngineContainment.Status status, Optional<String> reason,
      ConditionStore store, HealthEventChangeRegistry changes, Source source, Clock clock) {
    if (status != EngineContainment.Status.UNAVAILABLE) return;
    String code = LifecycleReasonCode.ENGINE_CONTAINMENT_UNAVAILABLE.code();
    String detail = "Processes started by this Engine may outlive it: its Windows job could not be"
        + " established (" + reason.orElse("unknown") + ").";
    Instant now = Instant.now(clock);
    var body = new AssertedCondition("engine", ConditionStatus.TRUE, "EngineContainmentUnavailable",
        now, Optional.of(detail), Optional.empty(), List.of());
    var event = new HealthEvent(code, now, source, Severity.WARNING, Optional.of(code), body);
    var transition = store.upsert(event);
    if (transition != ConditionStore.Transition.UNCHANGED) {
      changes.broadcast(transition == ConditionStore.Transition.ADDED
          ? HealthEventChangeRegistry.Kind.CONDITION_ADDED
          : HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
    }
  }
}
