/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.engine.DefaultEngineComponentRegistry;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.context.RetainedStateBudget;
import java.time.Duration;
import java.util.Set;
import io.justsearch.core.component.ComponentState;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Real registry/bridge/store contract: failure deltas are not the condition authority. */
final class CapabilityHealthBridgeCurrentStateTest {
  private static final Source HEAD = Source.forProcess("head", "current-state", "1.0");

  @Test
  void recoveryWithoutAReadyClearCannotRetainThePreviousInferenceFailure() throws Exception {
    try (var components = openComponents()) {
      components.inference().transition(
          ComponentState.FAILED, "inference.model_load_failed", "corrupt model");
      var store = new ConditionStore();
      var bridge = CapabilityHealthBridge.wireListeners(
          components.registry(), store, new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()), HEAD);
      try (bridge) {
        assertTrue(store.find("inference.capability", "inference").isPresent());
        components.inference().transition(
            ComponentState.STARTING, "inference.starting", "recovery in progress");
        assertFalse(store.find("inference.capability", "inference").isPresent(),
            "quiet PENDING recovery must not retain InferenceModelLoadFailed");
      }
    }
  }

  @Test
  void recoveryBeforeSubscriptionReconcilesBothOwnedConditions() throws Exception {
    try (var components = openComponents()) {
      var store = new ConditionStore();
      components.index().transition(ComponentState.FAILED, "worker.failed", "failed");
      components.inference().transition(
          ComponentState.FAILED, "inference.model_load_failed", "failed");
      CapabilityHealthBridge.wireListeners(
          components.registry(), store, new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()), HEAD).close();
      components.index().transition(ComponentState.STARTING, "worker.starting", "recovering");
      components.inference().transition(
          ComponentState.STARTING, "inference.starting", "recovering");
      var bridge = CapabilityHealthBridge.wireListeners(
          components.registry(), store, new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()), HEAD);
      try (bridge) {
        assertFalse(store.find("inference.capability", "inference").isPresent(),
            "subscription replay must remove a failure whose clear was never observed");
        assertEquals("Pending", ((AssertedCondition)
            store.find("worker.capability", "worker").orElseThrow().body()).reason());
        components.index().transition(ComponentState.READY, null, "healthy");
        assertFalse(store.find("worker.capability", "worker").isPresent());
      }
    }
  }

  private static Components openComponents() {
    var budget = new RetainedStateBudget();
    budget.declare("attempted-configurations", 1, "D1");
    var registry = new DefaultEngineComponentRegistry(budget);
    return new Components(registry,
        registry.register(spec("index", true)), registry.register(spec("generative", false)));
  }

  private static ComponentSpec spec(String name, boolean essential) {
    return new ComponentSpec(name, essential, Set.of(),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ZERO, 2);
  }

  private record Components(
      DefaultEngineComponentRegistry registry, ComponentHandle index, ComponentHandle inference)
      implements AutoCloseable {
    @Override public void close() { registry.close(); }
  }

}
