/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.RunObservation;
import io.justsearch.agent.api.AgentEvent;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import io.justsearch.app.observability.stream.run.RunChannel;
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
        var failed = assertThrows(
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
        var failure = assertThrows(
            java.util.concurrent.ExecutionException.class, () -> attached.get(5, TimeUnit.SECONDS));
        assertEquals("raw socket failed during primer", failure.getCause().getMessage());
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(0, observation.observerCount());
        assertFalse(run.retired());
        assertEquals(0, retirementRegistrationCount(run));
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
        assertEquals(0, retirementRegistrationCount(run));
      } finally {
        observation.retire();
      }
    }
  }

  @Test
  @Timeout(30)
  void repeatedDeliveryEvictionsRetainOnlyLiveRetirementRegistrations() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-reconnect", "agent", "");
    var run = channels.find(new RunId("raw-reconnect")).orElseThrow();
    run.onRetire(() -> {});
    int baseline = retirementRegistrationCount(run);
    assertEquals(1, baseline, "an unrelated live registration must remain registered");
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    try (var executor = Executors.newSingleThreadExecutor()) {
      try {
        for (int i = 0; i < 128; i++) {
          CountDownLatch primed = new CountDownLatch(1);
          registerSession(sessions, "raw-reconnect", new PrimedObservation(observation, primed));
          long cursor = run.channel().currentSeq();
          var attached =
              executor.submit(
                  () -> sessions.attachToRun("raw-reconnect", cursor,
                      frame -> { throw new IllegalStateException("raw socket failed"); }));
          assertTrue(primed.await(5, TimeUnit.SECONDS));
          assertEquals(1, run.observerCount());
          assertEquals(baseline + 1, retirementRegistrationCount(run));
          run.publish(RunFrame.of("live"));
          assertDetached(run, baseline);
          assertTrue(attached.get(5, TimeUnit.SECONDS));
          assertDetached(run, baseline);
        }
      } finally {
        observation.retire();
      }
    }
  }

  @Test
  @Timeout(10)
  void overflowReleasesRetirementRegistrationWhileReplayRemainsBlocked() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-blocked", "agent", "");
    var run = channels.find(new RunId("raw-blocked")).orElseThrow();
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    registerSession(sessions, "raw-blocked", observation);
    run.publish(RunFrame.of("ready"));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var attached = executor.submit(() -> sessions.attachToRun("raw-blocked", 0L, frame -> {
        entered.countDown();
        try {
          assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
          throw new AssertionError(failure);
        }
      }));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, retirementRegistrationCount(run));
        int capacity = run.policy().maxFrames();
        for (int i = 0; i <= capacity; i++) run.publish(RunFrame.of("live"));
        assertFalse(attached.isDone(), "synchronous replay still holds the attachment thread");
        assertDetached(run, 0);
      } finally {
        release.countDown();
        observation.retire();
      }
      var failure = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> attached.get(5, TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
  }

  @Test
  @Timeout(10)
  void repeatedReplayAndFallbackFailuresReleaseRetirementRegistrations() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-replay-failure", "agent", "");
    var run = channels.find(new RunId("raw-replay-failure")).orElseThrow();
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    registerSession(sessions, "raw-replay-failure", observation);
    run.publish(RunFrame.of("ready"));
    var failure = new IllegalStateException("raw replay failed");
    try {
      for (int i = 0; i < 128; i++) {
        long cursor = i % 2 == 0 ? 0L : Long.MAX_VALUE;
        assertSame(
            failure,
            assertThrows(IllegalStateException.class,
                () -> sessions.attachToRun("raw-replay-failure", cursor, frame -> { throw failure; })));
        assertDetached(run, 0);
      }
    } finally {
      observation.retire();
    }
  }

  @Test
  @Timeout(10)
  void attachmentTimeoutReleasesObserverAndRetirementRegistration() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-timeout", "agent", "");
    var run = channels.find(new RunId("raw-timeout")).orElseThrow();
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    registerSession(sessions, "raw-timeout", observation);
    try {
      assertTrue(sessions.attachToRun("raw-timeout", Long.MAX_VALUE, frame -> {}, 0L));
      assertDetached(run, 0);
    } finally {
      observation.retire();
    }
  }

  @Test
  @Timeout(10)
  void interruptedAttachmentReleasesObserverAndRetirementRegistration() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-interrupted", "agent", "");
    var run = channels.find(new RunId("raw-interrupted")).orElseThrow();
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    registerSession(sessions, "raw-interrupted", observation);
    Thread.currentThread().interrupt();
    try {
      assertTrue(sessions.attachToRun("raw-interrupted", 0L, frame -> {}));
      assertTrue(Thread.currentThread().isInterrupted(), "attachment must restore the interrupt flag");
      assertDetached(run, 0);
    } finally {
      Thread.interrupted();
      observation.retire();
    }
  }

  @Test
  void unsuccessfulFallbackReleasesRetirementRegistration() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-rejected", "agent", "");
    var run = channels.find(new RunId("raw-rejected")).orElseThrow();
    var cursors = new java.util.ArrayList<Long>();
    AgentSessionRegistry sessions = new AgentSessionRegistry();
    registerSession(sessions, "raw-rejected", new RejectingObservation(observation, cursors));
    try {
      assertFalse(sessions.attachToRun("raw-rejected", 42L, frame -> {}));
      assertEquals(java.util.List.of(42L, 0L), cursors, "both attempts must be refused");
      assertDetached(run, 0);
    } finally {
      observation.retire();
    }
  }

  private static void registerSession(
      AgentSessionRegistry sessions, String id, RunObservation.Handle observation) {
    AgentSession session =
        new AgentSession(java.util.List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    session.observeThrough(observation);
    sessions.register(id, session);
  }

  private static void assertDetached(RunChannel run, int baseline)
      throws ReflectiveOperationException {
    assertFalse(run.retired(), "cleanup must not depend on terminal run retirement");
    assertEquals(0, run.observerCount());
    assertEquals(
        baseline,
        retirementRegistrationCount(run),
        "only live attachments may retain retirement registrations");
  }

  private static int retirementRegistrationCount(RunChannel run)
      throws ReflectiveOperationException {
    var field = run.getClass().getSuperclass().getDeclaredField("retireListeners");
    field.setAccessible(true);
    return ((java.util.List<?>) field.get(run)).size();
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
    @Override public Runnable onRetire(Runnable listener) { return delegate.onRetire(listener); }
    @Override public void retire() { delegate.retire(); }
  }

  /** Exercises the defensive branch where even the zero-cursor fallback is refused. */
  private record RejectingObservation(RunObservation.Handle delegate, java.util.List<Long> cursors)
      implements RunObservation.Handle {
    @Override
    public void publish(AgentEvent event) {
      delegate.publish(event);
    }

    @Override
    public int observerCount() {
      return delegate.observerCount();
    }

    @Override
    public void setSnapshotSupplier(Supplier<AgentEvent.StateSnapshot> supplier) {
      delegate.setSnapshotSupplier(supplier);
    }

    @Override
    public Optional<Runnable> observe(
        long since, Consumer<RunObservation.WireFrame> observer, Runnable detached) {
      cursors.add(since);
      return Optional.empty();
    }

    @Override
    public Runnable onRetire(Runnable listener) {
      return delegate.onRetire(listener);
    }

    @Override
    public void retire() {
      delegate.retire();
    }
  }
}
