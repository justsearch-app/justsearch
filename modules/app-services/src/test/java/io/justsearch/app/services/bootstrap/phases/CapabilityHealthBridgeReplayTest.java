/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Current-state replay and subscription ownership for registry-backed health conditions. */
@DisplayName("CapabilityHealthBridge — replay current registry state on wire")
final class CapabilityHealthBridgeReplayTest {

  private static final Source HEAD = Source.forProcess("head", "instance-1", "1.0");

  private static boolean hasWorkerCondition(ConditionStore store) {
    return store.currentSnapshot().stream().map(HealthEvent::id)
        .anyMatch("worker.capability"::equals);
  }

  private static boolean hasInferenceCondition(ConditionStore store) {
    return store.currentSnapshot().stream().map(HealthEvent::id)
        .anyMatch("inference.capability"::equals);
  }

  @Test
  void replaysFailedIndexAsWorkerCondition() throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      components.handle("index").transition(
          ComponentState.FAILED, "worker.health.failed", "Health check failed");
      ConditionStore conditions = new ConditionStore();

      try (AutoCloseable ignored = CapabilityHealthBridge.wireListeners(
          components, conditions, new HealthEventChangeRegistry(), HEAD)) {
        assertTrue(hasWorkerCondition(conditions));
      }
    }
  }

  @Test
  void readyIndexSeedsNoWorkerCondition() throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      components.handle("index").transition(ComponentState.READY, null, "healthy");
      ConditionStore conditions = new ConditionStore();

      try (AutoCloseable ignored = CapabilityHealthBridge.wireListeners(
          components, conditions, new HealthEventChangeRegistry(), HEAD)) {
        assertFalse(hasWorkerCondition(conditions));
      }
    }
  }

  @Test
  void startingIndexSeedsPendingConditionAsCurrentState() throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      components.handle("index").transition(
          ComponentState.STARTING, "worker.starting", "Worker startup is in progress");
      ConditionStore conditions = new ConditionStore();

      try (AutoCloseable ignored = CapabilityHealthBridge.wireListeners(
          components, conditions, new HealthEventChangeRegistry(), HEAD)) {
        assertTrue(
            hasWorkerCondition(conditions),
            "STARTING is an actual registry observation, so its PENDING projection is visible");
      }
    }
  }

  @Test
  void closingBridgeUnsubscribesFromLaterRegistryTransitions() throws Exception {
    try (var components = TestEngineComponents.fourComponents()) {
      components.handle("index").transition(ComponentState.READY, null, "healthy");
      components.handle("generative").transition(ComponentState.READY, null, "healthy");
      ConditionStore conditions = new ConditionStore();
      AutoCloseable bridge = CapabilityHealthBridge.wireListeners(
          components, conditions, new HealthEventChangeRegistry(), HEAD);
      bridge.close();

      components.handle("index").transition(
          ComponentState.FAILED, "worker.lost", "late failure");
      components.handle("generative").transition(
          ComponentState.FAILED, "inference.crashed", "late failure");

      assertFalse(
          hasWorkerCondition(conditions),
          "a closed bridge must not mutate conditions from later registry revisions");
      assertFalse(
          hasInferenceCondition(conditions),
          "closing the bridge owns and releases both component subscriptions");
    }
  }
}
