/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.RetentionClass;
import io.justsearch.app.api.runtime.containment.EngineContainment.Status;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Spec F §1.3: an uncontained Engine says so on the Health surface, with the failed call. */
final class EngineContainmentNoticeTest {
  private static final Source SOURCE = new Source("head", "test", Optional.empty());
  private static final String CODE = LifecycleReasonCode.ENGINE_CONTAINMENT_UNAVAILABLE.code();

  @Test
  void unavailableContainmentIsAStickyHealthConditionNamingTheFailedCall() {
    var health = new ConditionStore();
    EngineContainmentNotice.publish(Status.UNAVAILABLE, Optional.of("AssignProcessToJobObject:5"),
        health, new HealthEventChangeRegistry(), SOURCE, Clock.systemUTC());
    var event = health.find(CODE, "engine").orElseThrow();
    String message = ((AssertedCondition) event.body()).message().orElseThrow();
    assertTrue(message.contains("AssignProcessToJobObject:5"), message);
    assertEquals(RetentionClass.STICKY,
        LifecycleReasonCode.ENGINE_CONTAINMENT_UNAVAILABLE.retentionClass(),
        "decided once at boot, so nothing later may erase it");
  }

  @Test
  void containedOrInapplicableEnginePublishesNothing() {
    for (Status status : new Status[] {Status.CONTAINED, Status.NOT_APPLICABLE}) {
      var health = new ConditionStore();
      EngineContainmentNotice.publish(status, Optional.empty(), health,
          new HealthEventChangeRegistry(), SOURCE, Clock.systemUTC());
      assertTrue(health.find(CODE, "engine").isEmpty(), status.name());
    }
  }
}
