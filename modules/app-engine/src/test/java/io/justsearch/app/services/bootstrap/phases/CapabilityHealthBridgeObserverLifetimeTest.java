/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** V6: a retired interruptible observer must not survive its delivery owner. */
@Timeout(15)
final class CapabilityHealthBridgeObserverLifetimeTest {
  @ParameterizedTest(name = "{displayName}: {arguments}")
  @ValueSource(strings = {"unsubscribe", "bridge", "channel", "executor"})
  void eachOwnerBoundaryReleasesBlockedObserver(String boundary) throws Exception {
    exerciseOwnerBoundary(boundary, false, false);
  }

  @ParameterizedTest(name = "{displayName}: {arguments}")
  @ValueSource(strings = {"unsubscribe", "bridge", "channel"})
  void synchronousHealthDeliveryAlsoEndsAtItsOwnerBoundary(String boundary) throws Exception {
    exerciseOwnerBoundary(boundary, true, false);
  }

  @ParameterizedTest(name = "{displayName}: {arguments}")
  @ValueSource(strings = {"unsubscribe", "bridge", "channel"})
  void initialHealthReplayPrefixAlsoEndsAtItsOwnerBoundary(String boundary) throws Exception {
    exerciseOwnerBoundary(boundary, true, true);
  }

  @org.junit.jupiter.api.Test
  void retiredRegistrationDoesNotBeginHealthPrefix() {
    var changes = new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
    var started = new java.util.concurrent.atomic.AtomicInteger();
    changes.channel().subscribeAndReplay(envelope -> {}, 0, started::incrementAndGet,
        io.justsearch.app.observability.stream.SseStreamChannel.Subscription::unsubscribe);
    assertEquals(0, started.get(), "retired Health delivery must not start its replay prefix");
  }

  private static void exerciseOwnerBoundary(String boundary, boolean synchronous, boolean prefix)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var worker = new AtomicReference<Thread>();
    var budget = new RetainedStateBudget();
    budget.declare("attempted-configurations", 1, "observer lifetime");
    try (var components = new DefaultEngineComponentRegistry(budget);
        var deliveryExecutors = new io.justsearch.core.execution.TestEngineExecutors();
        var publisher = Executors.newVirtualThreadPerTaskExecutor()) {
      components.register(spec("index", true));
      var generative = components.register(spec("generative", false));
      generative.transition(ComponentState.READY, null, "initial");
      var changes = boundary.equals("executor") ? productionChanges(deliveryExecutors)
          : new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor());
      var bridge = CapabilityHealthBridge.wireListeners(components, new ConditionStore(), changes,
          Source.forProcess("head", "lifetime", "1"));
      Runnable blocked = () -> {
        worker.set(Thread.currentThread());
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      };
      var sub = new AtomicReference<io.justsearch.app.observability.stream.SseStreamChannel.Subscription>();
      if (!prefix) sub.set(changes.subscribeTyped(change -> blocked.run()));
      var publication = publisher.submit(() -> {
        if (prefix) changes.channel().subscribeAndReplay(envelope -> {}, 0, blocked, sub::set);
        else if (synchronous) changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, sampleEvent());
        else generative.transition(ComponentState.FAILED, "inference.crashed", "blocked observer");
      });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "observer entered interruptible wait");
        switch (boundary) {
          case "unsubscribe" -> sub.get().unsubscribe();
          case "bridge" -> bridge.close();
          case "executor" -> deliveryExecutors.close();
          case "channel" -> {
            // Reflective call lets this exact behavioral assertion run on the before-state too.
            try { changes.channel().getClass().getMethod("close").invoke(changes.channel()); }
            catch (NoSuchMethodException missingOwner) { /* before-state has no close owner */ }
          }
          default -> throw new AssertionError(boundary);
        }
        worker.get().join(Duration.ofSeconds(2));
        assertFalse(worker.get().isAlive(), boundary + " must release the blocked delivery thread");
        bridge.close();
        assertEquals(0, changes.channel().listenerCount());
        publication.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
        if (sub.get() != null) sub.get().unsubscribe();
        bridge.close();
        publisher.shutdownNow();
      }
    }
  }

  private static io.justsearch.app.observability.health.HealthEvent sampleEvent() {
    var instant = java.time.Instant.parse("2026-10-11T00:00:00Z");
    return new io.justsearch.app.observability.health.HealthEvent("memory.pressure", instant,
        Source.forProcess("head", "lifetime", "1"), io.justsearch.app.observability.health.Severity.WARNING,
        java.util.Optional.empty(), new io.justsearch.app.observability.health.ThresholdState(
            "head.memory", io.justsearch.app.observability.health.ThresholdPhase.FIRING,
            java.util.Map.of(), instant, java.util.Optional.empty(), java.util.Optional.empty(), java.util.List.of()));
  }

  private static HealthEventChangeRegistry productionChanges(
      io.justsearch.core.execution.EngineExecutorRegistry executors) {
    var projector = new io.justsearch.app.observability.advisory.HealthRecoveryProjector();
    var classes = io.justsearch.app.observability.advisory.AdvisoryClassRegistry.builder()
        .register(projector).build();
    var advisories = new io.justsearch.app.observability.advisory.AdvisoryChangeRegistry(
        classes, java.time.Clock.systemUTC());
    return HealthSubstrateInit.run(executors, 16, projector, advisories,
        java.util.Map.of(projector.classId(), new io.justsearch.app.observability.advisory.AdvisoryLog()),
        new io.justsearch.app.observability.health.ConditionRecoveryIndexChangeRegistry())
        .healthEventChangeRegistry();
  }

  private static ComponentSpec spec(String name, boolean essential) {
    return new ComponentSpec(name, essential, Set.of(),
        ComponentSpec.ComposeCapability.CHOOSES_PER_APPLY, Duration.ZERO, 2);
  }
}
