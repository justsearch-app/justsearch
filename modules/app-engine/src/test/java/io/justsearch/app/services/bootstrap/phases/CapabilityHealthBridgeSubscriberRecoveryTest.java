/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.engine.DefaultEngineComponentRegistry;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.context.RetainedStateBudget;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A Health attachment made during reentrant recovery observes the authority immediately. */
final class CapabilityHealthBridgeSubscriberRecoveryTest {
  @Test
  void subscriberAttachingInsideSuccessfulRecoveryNeverSeesAStaleFailure() throws Exception {
    var budget = new RetainedStateBudget();
    budget.declare("attempted-configurations", 1, "D1");
    try (var registry = new DefaultEngineComponentRegistry(budget)) {
      registry.register(spec("index", true));
      var inference = registry.register(spec("generative", false));
      var store = new ConditionStore();
      var changes = new HealthEventChangeRegistry();
      var observed = new CopyOnWriteArrayList<String>();
      var subscriptions = new CopyOnWriteArrayList<SseStreamChannel.Subscription>();
      inference.transition(ComponentState.FAILED, "inference.model_load_failed", "seed failure");
      var bridge = CapabilityHealthBridge.wireListeners(registry, store, changes,
          Source.forProcess("head", "mid-recovery", "1.0"), reason -> {
            if ("inference.crashed".equals(reason)) {
              inference.transition(ComponentState.READY, null, "recovered");
              // The subscriber's initial snapshot is observable before the mapper returns too.
              observed.add(inference.snapshot().state() + ":"
                  + store.find("inference.capability", "inference").isPresent());
              subscriptions.add(changes.subscribeTyped(delta -> observed.add(
                  inference.snapshot().state() + ":"
                      + store.find("inference.capability", "inference").isPresent())));
            }
            return CapabilityHealthBridge.chatFailureCause(reason);
          });
      try (bridge) {
        inference.transition(ComponentState.FAILED, "inference.crashed", "fault");
        assertFalse(observed.isEmpty(), "the mapper must exercise mid-recovery attachment");
        assertFalse(observed.contains("READY:true"),
            "neither the attachment snapshot nor a delta may expose failure while READY: " + observed);
        assertFalse(store.find("inference.capability", "inference").isPresent());
      } finally {
        subscriptions.forEach(subscription -> subscription.unsubscribe());
      }
    }
  }

  private static ComponentSpec spec(String name, boolean essential) {
    return new ComponentSpec(name, essential, Set.of(),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ZERO, 2);
  }
}
