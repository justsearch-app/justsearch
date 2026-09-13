/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void deliveryEvictionReleasesRawAttachBeforeRunRetirement() throws Exception {
    RunChannelRegistry channels = new RunChannelRegistry();
    var observation = new RunChannelObservation(channels).open("raw-detach", "agent", "");
    var run = channels.find(new RunId("raw-detach")).orElseThrow();
    AgentSession session = new AgentSession(java.util.List.of(), 1000, EngineContextTestFixtures.AGENT_LOOP);
    session.observeThrough(observation);
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
        assertTrue(replayed.await(5, TimeUnit.SECONDS));
        run.publish(RunFrame.of("live"));
        assertTrue(attached.get(5, TimeUnit.SECONDS), "retired observer releases the existing latch");
        assertFalse(run.retired(), "this is observer retirement, not terminal run cleanup");
        assertEquals(0, observation.observerCount());
      } finally {
        observation.retire();
      }
    }
  }
}
