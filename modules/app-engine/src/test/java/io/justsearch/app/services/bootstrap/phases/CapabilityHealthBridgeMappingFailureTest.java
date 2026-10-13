/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

final class CapabilityHealthBridgeMappingFailureTest {
  @Test
  void mappingExceptionRereadsARecoveryThatHappenedInsideTheMapper() throws Exception {
    try (var components = openComponents()) {
      var store = new ConditionStore();
      components.inference().transition(
          ComponentState.FAILED, "inference.model_load_failed", "failed");
      var bridge = CapabilityHealthBridge.wireListeners(components.registry(), store,
          new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()), Source.forProcess("head", "mapping-failure", "1.0"),
          reason -> {
            if ("inference.crashed".equals(reason)) {
              components.inference().transition(ComponentState.READY, null, "healthy");
              throw new IllegalArgumentException("injected classifier failure during recovery");
            }
            return CapabilityHealthBridge.chatFailureCause(reason);
          });
      try (bridge) {
        assertTrue(store.find("inference.capability", "inference").isPresent());
        components.inference().transition(ComponentState.FAILED, "inference.crashed", "fault");
        assertFalse(store.find("inference.capability", "inference").isPresent(),
            "a mapping exception must reconcile from the authority's recovered state");
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
