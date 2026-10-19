/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.chat.ChatDeviceLoss;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.runtime.power.PowerEvent;
import io.justsearch.app.api.runtime.power.SystemPowerEvents;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory.GenerationLifetimeSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Spec A §7.4 with Spec F §3.5, wired as the Engine composes it: the machine's resumes reach the
 * chat controller's {@code reportResume} through the Engine root, whichever of the power events and
 * the controller's input is bound first; suspends do not, a rebinding is followed, and the
 * subscription closes with the process resources.
 */
@Timeout(30)
final class EngineResumeWiringTest {

  private final EngineRoot root = new EngineRoot(mock(OperationStore.class),
      mock(OperationAttemptRunner.class),
      (gpu, executors, ingestion, indexComponent, encoderComponent) -> {
        throw new AssertionError("the index half is not composed in this test");
      }, 30_000, 5_000);
  private final FakePowerEvents power = new FakePowerEvents();

  @AfterEach
  void tearDown() throws Exception {
    root.close();
    root.processResources().close();
  }

  private void bindController(ChatDeviceLoss target) {
    ((GenerationLifetimeSource) root.executors()).bindDeviceLoss(target);
  }

  @Test
  void aResumeReachesTheControllerBoundBeforeThePowerEvents() {
    var controller = new CountingController();
    bindController(controller);
    root.bindPowerEvents(power);

    power.emit(PowerEvent.Kind.SUSPEND);
    assertEquals(0, controller.resumes.get(), "a suspend is not a resume");
    power.emit(PowerEvent.Kind.RESUME);
    assertEquals(1, controller.resumes.get());
  }

  @Test
  void aResumeReachesTheControllerBoundAfterThePowerEvents() {
    root.bindPowerEvents(power);
    assertEquals(0, power.listeners.size(), "nothing to deliver to before the controller binds");
    var controller = new CountingController();
    bindController(controller);

    power.emit(PowerEvent.Kind.RESUME);
    assertEquals(1, controller.resumes.get());
  }

  @Test
  void aReboundControllerGetsTheResumesThroughOneSubscription() {
    var first = new CountingController();
    var second = new CountingController();
    bindController(first);
    root.bindPowerEvents(power);
    bindController(second);
    root.bindPowerEvents(new FakePowerEvents());

    power.emit(PowerEvent.Kind.RESUME);
    assertEquals(0, first.resumes.get());
    assertEquals(1, second.resumes.get());
    assertEquals(1, power.listeners.size(), "one subscription per incarnation");
  }

  @Test
  void theSubscriptionClosesWithTheProcessResources() throws Exception {
    var controller = new CountingController();
    bindController(controller);
    root.bindPowerEvents(power);

    root.processResources().close();

    assertTrue(power.closed.get() >= 1, "the subscription closed");
    power.emit(PowerEvent.Kind.RESUME);
    assertEquals(0, controller.resumes.get(), "no resume after the process resources closed");
  }

  private static final class CountingController implements ChatDeviceLoss {
    final AtomicInteger resumes = new AtomicInteger();

    @Override
    public void reportDeviceLost(DeviceLostEvidence evidence) {
      // A device lost in another test of this JVM may be reported here; not this test's concern.
    }

    @Override
    public void reportResume() {
      resumes.incrementAndGet();
    }
  }

  /** Delivers on the caller's thread; a closed subscription receives nothing more. */
  private static final class FakePowerEvents implements SystemPowerEvents {
    final List<Consumer<PowerEvent>> listeners = new CopyOnWriteArrayList<>();
    final AtomicInteger closed = new AtomicInteger();
    private long epoch;

    @Override
    public Subscription subscribe(Consumer<PowerEvent> listener) {
      listeners.add(listener);
      return () -> {
        if (listeners.remove(listener)) closed.incrementAndGet();
      };
    }

    @Override
    public long suspendedNanosBetween(long t0, long t1) {
      return 0;
    }

    void emit(PowerEvent.Kind kind) {
      if (kind == PowerEvent.Kind.SUSPEND) epoch++;
      var event = new PowerEvent(kind, System.nanoTime(), epoch);
      for (Consumer<PowerEvent> listener : listeners) listener.accept(event);
    }
  }
}
