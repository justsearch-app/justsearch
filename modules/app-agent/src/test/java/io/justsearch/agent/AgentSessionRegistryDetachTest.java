/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.RunObservation;
import io.justsearch.agent.api.AgentEvent;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import io.justsearch.app.observability.stream.run.RunChannelObservation;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.observability.stream.run.RunFrame;
import io.justsearch.app.observability.stream.run.RunId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class AgentSessionRegistryDetachTest {
  @Test
  @Timeout(10)
  void rawPrimerOverflowSignalsDetachBeforeBlockedCallbackReturns() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-primer", "agent", "");
    var run = (io.justsearch.app.observability.stream.run.SteppedRunChannel)
        channels.find(new RunId("raw-primer")).orElseThrow();
    run.setSnapshotSupplier(() -> new io.justsearch.app.observability.stream.run.RunStateSnapshot(java.util.Map.of()));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch detached = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var attaching = executor.submit(() -> observation.observe(0, frame -> {
        entered.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
      }, detached::countDown));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        int capacity = io.justsearch.app.observability.stream.run.RunChannelPolicy.agent().maxFrames();
        for (int n = 0; n <= capacity; n++) run.publish(RunFrame.of("chunk"));
        assertTrue(detached.await(2, TimeUnit.SECONDS));
        assertFalse(attaching.isDone(), "detachment cannot interrupt a synchronous socket callback");
        assertEquals(0, run.observerCount());
        assertFalse(run.retired());
      } finally {
        release.countDown();
        var failed = org.junit.jupiter.api.Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class, () -> attaching.get(5, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failed.getCause());
        observation.retire();
      }
    }
  }

  @Test
  @Timeout(10)
  void callbackFailureWhilePrimerIsBlockedRemainsAnAttachFailure() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("primer-failure", "agent", "");
    var run = channels.find(new RunId("primer-failure")).orElseThrow();
    AgentSession session = new AgentSession(java.util.List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    session.observeThrough(observation);
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    sessions.register("primer-failure", session);
    run.publish(RunFrame.of("ready"));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var attached = executor.submit(() -> sessions.attachToRun("primer-failure", 0, frame -> {
        if ("ready".equals(frame.name())) {
          entered.countDown();
          try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
          catch (InterruptedException failure) { throw new AssertionError(failure); }
        } else throw new IllegalStateException("raw socket failed during primer");
      }));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        run.publish(RunFrame.of("live"));
        release.countDown();
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class, () -> attached.get(5, TimeUnit.SECONDS));
        assertEquals("raw socket failed during primer", failure.getCause().getMessage());
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(0, observation.observerCount());
        assertFalse(run.retired());
      } finally {
        release.countDown();
        observation.retire();
      }
    }
  }

  @Test
  @Timeout(10)
  void deliveryEvictionReleasesRawAttachBeforeRunRetirement() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-detach", "agent", "");
    var run = channels.find(new RunId("raw-detach")).orElseThrow();
    CountDownLatch primed = new CountDownLatch(1);
    var observed = new PrimedObservation(observation, primed);
    AgentSession session = new AgentSession(java.util.List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    session.observeThrough(observed);
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    sessions.register("raw-detach", session);
    run.publish(RunFrame.of("ready"));
    CountDownLatch replayed = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var attached = executor.submit(() -> sessions.attachToRun("raw-detach", 0, frame -> {
        if ("ready".equals(frame.name())) replayed.countDown();
        else throw new IllegalStateException("raw socket failed");
      }));
      try {
        assertTrue(primed.await(5, TimeUnit.SECONDS), "live delivery starts only after observe returns");
        assertEquals(0, replayed.getCount(), "the ready frame was replayed during attachment");
        run.publish(RunFrame.of("live"));
        assertFalse(run.retired(), "this is observer retirement, not terminal run cleanup");
        assertEquals(0, observation.observerCount(), "delivery evicted the actual observer before checking attach release");
        assertTrue(attached.get(5, TimeUnit.SECONDS), "retired observer releases the existing latch");
      } finally {
        observation.retire();
      }
    }
  }

  /** Signals only after the real substrate has finished primer/replay and returned the subscription. */
  private record PrimedObservation(RunObservation.Handle delegate, CountDownLatch primed) implements RunObservation.Handle {
    @Override public void publish(AgentEvent event) { delegate.publish(event); }
    @Override public int observerCount() { return delegate.observerCount(); }
    @Override public void setSnapshotSupplier(Supplier<AgentEvent.StateSnapshot> supplier) { delegate.setSnapshotSupplier(supplier); }
    @Override public Optional<Runnable> observe(long since, Consumer<RunObservation.WireFrame> observer, Runnable detached) {
      var subscription = delegate.observe(since, observer, detached);
      primed.countDown();
      return subscription;
    }
    @Override public void onRetire(Runnable listener) { delegate.onRetire(listener); }
    @Override public void retire() { delegate.retire(); }
  }
}
