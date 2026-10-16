/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.DefaultEngineComponentRegistry;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.context.RetainedStateBudget;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Public Health snapshots and independent observers must follow concurrent recovery. */
final class CapabilityHealthBridgeConcurrentPublicationTest {
  @Test
  void recoveryPublishesWhileAnEarlierMapperIsBlocked() throws Exception {
    exercise(true);
  }

  @Test
  void recoveryPublishesWhileAPublicHealthObserverIsBlocked() throws Exception {
    exercise(false);
  }

  @Test
  void failureConditionIsInstalledBeforeOrdinaryComponentObserversRun() throws Exception {
    var budget = new RetainedStateBudget();
    budget.declare("attempted-configurations", 1, "D1");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var registry = new DefaultEngineComponentRegistry(budget);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      registry.register(spec("index", true));
      var inference = registry.register(spec("generative", false));
      inference.transition(ComponentState.READY, null, "seed healthy");
      var store = new ConditionStore();
      var observer = registry.subscribe(snapshot -> {
        if (inference.snapshot().state() == ComponentState.FAILED) awaitBarrier(entered, release);
      });
      var bridge = CapabilityHealthBridge.wireListeners(registry, store,
          new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()), Source.forProcess("head", "atomic-publication", "1"));
      try (bridge; observer) {
        var failure = executor.submit(() -> inference.transition(
            ComponentState.FAILED, "inference.crashed", "failure"));
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS), "ordinary observer must block after publication");
          assertTrue(inference.snapshot().state() == ComponentState.FAILED);
          assertTrue(store.currentSnapshot().stream().anyMatch(
              event -> event.id().equals("inference.capability")),
              "FAILED and its Health condition must become visible in the same publication");
        } finally { release.countDown(); }
        failure.get(5, TimeUnit.SECONDS);
      }
    } finally { release.countDown(); }
  }

  private static void exercise(boolean blockMapper) throws Exception {
    var budget = new RetainedStateBudget();
    budget.declare("attempted-configurations", 1, "D1");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var removed = new CountDownLatch(1);
    try (var registry = new DefaultEngineComponentRegistry(budget);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      registry.register(spec("index", true));
      var inference = registry.register(spec("generative", false));
      inference.transition(ComponentState.FAILED, "inference.model_load_failed", "seed");
      var store = new ConditionStore();
      var changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
      var source = Source.forProcess("head", "concurrent-publication", "1");
      var bridge = blockMapper
          ? CapabilityHealthBridge.wireListeners(registry, store, changes, source, reason -> {
            if ("inference.crashed".equals(reason)) awaitBarrier(entered, release);
            return CapabilityHealthBridge.chatFailureCause(reason);
          })
          : CapabilityHealthBridge.wireListeners(registry, store, changes, source);
      try (bridge) {
        var slow = changes.subscribeTyped(delta -> {
          if (!blockMapper && delta.kind() == HealthEventChangeRegistry.Kind.CONDITION_MODIFIED)
            awaitBarrier(entered, release);
        });
        var healthy = changes.subscribeTyped(delta -> {
          if (delta.kind() == HealthEventChangeRegistry.Kind.CONDITION_REMOVED
              && delta.event().id().equals("inference.capability")) removed.countDown();
        });
        try {
          var failed = executor.submit(() -> inference.transition(
              ComponentState.FAILED, "inference.crashed", "second failure"));
          assertTrue(entered.await(5, TimeUnit.SECONDS), "the earlier observer/mapper must block");
          var recovered = executor.submit(() -> inference.transition(ComponentState.READY, null, "healthy"));
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (inference.snapshot().state() != ComponentState.READY && System.nanoTime() < deadline)
            Thread.onSpinWait();
          assertTrue(inference.snapshot().state() == ComponentState.READY, "recovery must publish");
          var late = changes.subscribeTyped(delta -> {});
          try {
            assertFalse(store.currentSnapshot().stream().anyMatch(
                event -> event.id().equals("inference.capability")),
                "O8/O10: a subscriber attaching while READY must never see a stale failure");
            recovered.get(2, TimeUnit.SECONDS);
            assertTrue(removed.await(2, TimeUnit.SECONDS), "a healthy observer must receive recovery before release");
          } finally { late.unsubscribe(); }
          release.countDown();
          failed.get(5, TimeUnit.SECONDS);
          assertFalse(store.find("inference.capability", "inference").isPresent(),
              "the released mapper must not resurrect the older failure");
        } finally {
          release.countDown();
          healthy.unsubscribe();
          slow.unsubscribe();
        }
      }
    } finally { release.countDown(); }
  }

  private static void awaitBarrier(CountDownLatch entered, CountDownLatch release) {
    entered.countDown();
    try {
      if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }

  private static ComponentSpec spec(String name, boolean essential) {
    return new ComponentSpec(name, essential, Set.of(),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ZERO, 2);
  }
}
