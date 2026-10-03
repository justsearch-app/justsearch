/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
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

/** Publishes the boot marker comparison on the existing non-blocking Health condition surface. */
public final class NewerDataNotice {
  private NewerDataNotice() {}

  public static void publish(Optional<String> message, ConditionStore store,
      HealthEventChangeRegistry changes, Source source, Clock clock) {
    message.ifPresent(detail -> {
      String code = LifecycleReasonCode.DATA_NEWER_BUILD.code();
      Instant now = Instant.now(clock);
      var body = new AssertedCondition("data", ConditionStatus.TRUE, "DataNewerBuild", now,
          Optional.of(detail), Optional.empty(), List.of());
      var event = new HealthEvent(code, now, source, Severity.WARNING, Optional.of(code), body);
      var transition = store.upsert(event);
      if (transition != ConditionStore.Transition.UNCHANGED) {
        changes.broadcast(transition == ConditionStore.Transition.ADDED
            ? HealthEventChangeRegistry.Kind.CONDITION_ADDED
            : HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      }
    });
  }
}
