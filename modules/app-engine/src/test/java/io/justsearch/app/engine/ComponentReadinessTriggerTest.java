/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Uses the production registry's synchronous callbacks, not a manufactured request() callback. */
final class ComponentReadinessTriggerTest {
  @Test
  void samplerPublicationCannotEnqueueAnotherSample() throws Exception {
    try (var components = registry(); var harness = new Harness()) {
      var api = components.register(spec("api"));
      var index = components.register(spec("index"));
      api.transition(ComponentState.READY, null, null);
      index.transition(ComponentState.STARTING, "worker.starting", null);
      var runs = new AtomicInteger();
      var done = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      harness.trigger.wireTo(components);
      harness.trigger.attach(() -> {
        try {
          runs.incrementAndGet();
          assertTrue(index.transitionIfUnchanged(components.snapshot(), ComponentState.READY, null, null));
        } catch (RuntimeException | Error error) {
          failure.set(error);
        } finally {
          done.countDown();
        }
      });
      assertTrue(done.await(5, TimeUnit.SECONDS));
      harness.drain();
      assertNull(failure.get());
      assertEquals(1, runs.get(), "the synchronous READY callback must not feed back");
      assertEquals(ComponentState.READY, index.snapshot().state());
    }
  }

  @Test
  void externalTransitionDuringSamplingQueuesOneFollowupAndInvalidatesTheFirstResult()
      throws Exception {
    try (var components = registry(); var harness = new Harness()) {
      var api = components.register(spec("api"));
      var index = components.register(spec("index"));
      api.transition(ComponentState.READY, null, null);
      index.transition(ComponentState.STARTING, "worker.starting", null);
      var observed = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var completed = new CountDownLatch(2);
      var runs = new AtomicInteger();
      var firstApplied = new AtomicBoolean(true);
      var failure = new AtomicReference<Throwable>();
      harness.trigger.wireTo(components);
      harness.trigger.attach(() -> {
        try {
          int run = runs.incrementAndGet();
          var snapshot = components.snapshot();
          if (run == 1) {
            observed.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            firstApplied.set(index.transitionIfUnchanged(snapshot, ComponentState.READY, null, null));
          } else {
            assertTrue(index.transitionIfUnchanged(snapshot, ComponentState.READY, null, null));
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          failure.set(interrupted);
        } catch (RuntimeException | Error error) {
          failure.set(error);
        } finally {
          completed.countDown();
        }
      });
      try {
        assertTrue(observed.await(5, TimeUnit.SECONDS));
        // The distinct physical publisher can enqueue while the sampler is active. Both
        // transitions coalesce; returning to READY still invalidates the old full snapshot.
        api.transition(ComponentState.ABSENT, null, null);
        api.transition(ComponentState.READY, null, null);
      } finally {
        release.countDown();
      }
      assertTrue(completed.await(5, TimeUnit.SECONDS));
      harness.drain();
      assertNull(failure.get());
      assertFalse(firstApplied.get(), "the first sampled conjunction was overtaken by the API owner");
      assertEquals(2, runs.get(), "the follow-up's READY publication must not queue a third run");
      assertEquals(ComponentState.READY, index.snapshot().state());
    }
  }

  private static ComponentSpec spec(String name) {
    return new ComponentSpec(name, true, Set.of(), ComponentSpec.ComposeCapability.IN_PLACE,
        Duration.ZERO, 2);
  }

  private static DefaultEngineComponentRegistry registry() {
    var budget = new RetainedStateBudget();
    budget.declare(DefaultEngineComponentRegistry.ATTEMPTED_CONFIGURATIONS, 1, "D1");
    return new DefaultEngineComponentRegistry(budget);
  }

  /** Capture the actual single-thread executor to use FIFO barriers instead of timing sleeps. */
  private static final class Harness implements AutoCloseable {
    private final ReadinessReconciliationTrigger trigger;
    private ExecutorService executor;

    private Harness() {
      var executors = mock(EngineExecutorRegistry.class);
      var registration = mock(EngineExecutorRegistry.Registration.class);
      when(executors.limits(any())).thenReturn(new EngineExecutorRegistry.Limits(1, 8));
      when(executors.register(any())).thenReturn(registration);
      when(registration.open(any())).thenAnswer(invocation -> {
        ThreadFactory factory = invocation.getArgument(0);
        executor = Executors.newSingleThreadExecutor(factory);
        return executor;
      });
      trigger = new ReadinessReconciliationTrigger(executors);
    }

    private void drain() throws Exception {
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
      trigger.close();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
