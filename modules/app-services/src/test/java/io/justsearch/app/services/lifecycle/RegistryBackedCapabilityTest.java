/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class RegistryBackedCapabilityTest {

  @ParameterizedTest
  @MethodSource("stateMappings")
  void projectsAllComponentStates(ComponentState state, CapabilityHealth health) {
    var registry = new ManualRegistry(snapshot(1, state, false, "reason", "detail", null));
    var capability = new RegistryBackedCapability(registry, "generative", "inference");

    var observed = capability.current();

    assertEquals(health, observed.health());
    assertEquals("inference", observed.name());
    if (state == ComponentState.READY) {
      assertNull(observed.reason());
      assertNull(observed.detail());
    } else {
      assertEquals("reason", observed.reason());
      assertEquals("detail", observed.detail());
    }
  }

  @Test
  void optionalAbsenceIsNotRequiredButRequestedUnavailableIsRequired() {
    var registry = new ManualRegistry(snapshot(1, ComponentState.ABSENT, false, null, null, null));
    var capability = new RegistryBackedCapability(registry, "generative", "inference");
    assertFalse(capability.required());

    registry.set(snapshot(2, ComponentState.UNAVAILABLE, false, "missing", null, null));

    assertTrue(capability.required());
    assertEquals(CapabilityHealth.OFFLINE, capability.health());
  }

  @Test
  void constructorRequiresRegisteredNamedComponent() {
    var registry = new ManualRegistry(new EngineComponentSnapshot(1, List.of()));

    assertThrows(
        IllegalArgumentException.class,
        () -> new RegistryBackedCapability(registry, "generative", "inference"));
  }

  @Test
  void dropsStaleCallbacksAndIgnoresVersionOnlyAndUnrelatedChanges() {
    var registry = new ManualRegistry(
        snapshot(1, ComponentState.STARTING, true, "starting", null, null));
    var capability = new RegistryBackedCapability(registry, "generative", "inference");
    var changes = new ArrayList<RegistryBackedCapability.Change>();
    try (var ignored = capability.subscribe(changes::add)) {
      registry.deliver(snapshot(3, ComponentState.READY, true, null, null, "applied-b"));
      registry.deliver(snapshot(2, ComponentState.FAILED, true, "failed", null, null));
      registry.deliver(snapshot(4, ComponentState.READY, true, null, null, "applied-c"));
      registry.deliver(snapshotWithUnrelatedChange(5, ComponentState.READY));
    }

    assertEquals(1, changes.size());
    assertEquals(1, changes.getFirst().previous().revision());
    assertEquals(3, changes.getFirst().current().revision());
    assertEquals(CapabilityHealth.READY, changes.getFirst().current().health());
  }

  @Test
  void reasonOnlyChangeNotifiesAndClosingStopsDelivery() {
    var registry = new ManualRegistry(
        snapshot(1, ComponentState.FAILED, true, "first", "one", null));
    var capability = new RegistryBackedCapability(registry, "generative", "inference");
    var changes = new ArrayList<RegistryBackedCapability.Change>();
    var subscription = capability.subscribe(changes::add);

    registry.deliver(snapshot(2, ComponentState.FAILED, true, "second", "two", null));
    subscription.close();
    registry.deliver(snapshot(3, ComponentState.READY, true, null, null, null));

    assertEquals(1, changes.size());
    assertEquals("first", changes.getFirst().previous().reason());
    assertEquals("second", changes.getFirst().current().reason());
    assertEquals("two", changes.getFirst().current().detail());
    assertEquals(0, registry.listenerCount());
  }

  @Test
  void subscribeBeforeReadReplaysOnlyCallbackNewerThanBootstrapSnapshot() {
    var initial = snapshot(1, ComponentState.STARTING, true, "starting", null, null);
    var registry = new ManualRegistry(initial);
    var capability = new RegistryBackedCapability(registry, "generative", "inference");
    var changes = new ArrayList<RegistryBackedCapability.Change>();
    registry.onNextSnapshot(() -> {
      registry.deliver(snapshot(2, ComponentState.FAILED, true, "superseded", null, null));
      registry.deliver(snapshot(3, ComponentState.READY, true, null, null, null));
    });

    try (var ignored = capability.subscribe(changes::add)) {
      assertEquals(1, changes.size());
      assertEquals(1, changes.getFirst().previous().revision());
      assertEquals(3, changes.getFirst().current().revision());
    }
  }

  @Test
  void blockedListenerCoalescesConcurrentBurstToNewestObservation() throws Exception {
    var registry = new ManualRegistry(
        snapshot(1, ComponentState.STARTING, true, "starting", null, null));
    var capability = new RegistryBackedCapability(registry, "generative", "inference");
    var changes = new CopyOnWriteArrayList<RegistryBackedCapability.Change>();
    var listenerEntered = new CountDownLatch(1);
    var releaseListener = new CountDownLatch(1);

    try (var ignored = capability.subscribe(change -> {
      changes.add(change);
      if (change.current().revision() == 2) {
        listenerEntered.countDown();
        try {
          if (!releaseListener.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to release blocked capability listener");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while blocking capability listener", interrupted);
        }
      }
    })) {
      var firstDelivery = new FutureTask<Void>(() -> {
        registry.deliver(snapshot(2, ComponentState.READY, true, null, null, null));
        return null;
      });
      Thread deliveryThread = new Thread(firstDelivery, "capability-test-delivery");
      deliveryThread.start();
      assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));

      for (long revision = 3; revision < 100; revision++) {
        registry.deliver(snapshot(
            revision,
            revision % 2 == 0 ? ComponentState.FAILED : ComponentState.UNAVAILABLE,
            true,
            "superseded-" + revision,
            null,
            null));
      }
      registry.deliver(snapshot(
          100, ComponentState.FAILED, true, "newest", "final detail", null));
      assertEquals(1, changes.size());

      releaseListener.countDown();
      firstDelivery.get(5, TimeUnit.SECONDS);
    } finally {
      releaseListener.countDown();
    }

    assertEquals(2, changes.size());
    assertEquals(1, changes.getFirst().previous().revision());
    assertEquals(2, changes.getFirst().current().revision());
    assertEquals(2, changes.get(1).previous().revision());
    assertEquals(100, changes.get(1).current().revision());
    assertEquals("newest", changes.get(1).current().reason());
    assertEquals("final detail", changes.get(1).current().detail());
  }

  @Test
  void runtimeListenerFailureIsolatedErrorPropagatesAndReentrantPublicationIsOrdered() {
    var registry = new ManualRegistry(
        snapshot(1, ComponentState.STARTING, true, "starting", null, null));
    var capability = new RegistryBackedCapability(registry, "generative", "inference");
    var observed = new CopyOnWriteArrayList<CapabilityHealth>();
    var throwing = capability.subscribe(change -> {
           throw new RuntimeException("listener failure");
         });
    var reentrant = capability.subscribe(change -> {
           observed.add(change.current().health());
           if (change.current().health() == CapabilityHealth.READY) {
             registry.deliver(snapshot(
                 change.current().revision() + 1,
                 ComponentState.FAILED,
                 true,
                 "failed",
                 null,
                 null));
           }
         });
    try (throwing; reentrant) {
      registry.deliver(snapshot(2, ComponentState.READY, true, null, null, null));
    }
    assertEquals(List.of(CapabilityHealth.READY, CapabilityHealth.DEGRADED), observed);

    var fatal = capability.subscribe(change -> {
      throw new AssertionError("fatal listener");
    });
    try (fatal) {
      assertThrows(
          AssertionError.class,
          () -> registry.deliver(snapshot(4, ComponentState.READY, true, null, null, null)));
    }
  }

  private static Stream<Arguments> stateMappings() {
    return Stream.of(
        Arguments.of(ComponentState.ABSENT, CapabilityHealth.OFFLINE),
        Arguments.of(ComponentState.STARTING, CapabilityHealth.PENDING),
        Arguments.of(ComponentState.READY, CapabilityHealth.READY),
        Arguments.of(ComponentState.RELOADING, CapabilityHealth.RECOVERING),
        Arguments.of(ComponentState.FAILED, CapabilityHealth.DEGRADED),
        Arguments.of(ComponentState.UNAVAILABLE, CapabilityHealth.OFFLINE));
  }

  private static EngineComponentSnapshot snapshot(
      long revision,
      ComponentState state,
      boolean essential,
      String reason,
      String detail,
      String appliedVersion) {
    return new EngineComponentSnapshot(
        revision,
        List.of(component(
            "generative", state, essential, reason, detail, appliedVersion)));
  }

  private static EngineComponentSnapshot snapshotWithUnrelatedChange(
      long revision, ComponentState state) {
    return new EngineComponentSnapshot(
        revision,
        List.of(
            component("generative", state, true, null, null, "applied-c"),
            component("api", ComponentState.FAILED, true, "api.failed", null, null)));
  }

  private static EngineComponentSnapshot.Component component(
      String name,
      ComponentState state,
      boolean essential,
      String reason,
      String detail,
      String appliedVersion) {
    return new EngineComponentSnapshot.Component(
        new ComponentSpec(
            name,
            essential,
            Set.of(),
            ComponentSpec.ComposeCapability.IN_PLACE,
            Duration.ZERO,
            2),
        state,
        reason,
        Instant.EPOCH,
        0,
        appliedVersion,
        null,
        null,
        0,
        detail);
  }

  private static final class ManualRegistry implements EngineComponentRegistry {
    private final List<Consumer<EngineComponentSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private volatile EngineComponentSnapshot current;
    private volatile Runnable nextSnapshotHook;

    private ManualRegistry(EngineComponentSnapshot initial) {
      this.current = initial;
    }

    @Override
    public ComponentHandle register(ComponentSpec spec) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EngineComponentSnapshot snapshot() {
      EngineComponentSnapshot result = current;
      Runnable hook = nextSnapshotHook;
      nextSnapshotHook = null;
      if (hook != null) hook.run();
      return result;
    }

    @Override
    public Subscription subscribe(Consumer<EngineComponentSnapshot> listener) {
      listeners.add(listener);
      return () -> listeners.remove(listener);
    }

    @Override
    public ApplyAttempt tryApply() {
      return new ApplyAttempt.Refused(ApplyAttempt.Reason.BUSY);
    }

    @Override
    public void close() {
      listeners.clear();
    }

    private void set(EngineComponentSnapshot snapshot) {
      current = snapshot;
    }

    private void deliver(EngineComponentSnapshot snapshot) {
      current = snapshot;
      for (Consumer<EngineComponentSnapshot> listener : listeners) {
        listener.accept(snapshot);
      }
    }

    private void onNextSnapshot(Runnable hook) {
      nextSnapshotHook = hook;
    }

    private int listenerCount() {
      return listeners.size();
    }
  }
}
