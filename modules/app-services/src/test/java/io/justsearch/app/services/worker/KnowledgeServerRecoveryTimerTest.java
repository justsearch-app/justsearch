/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Regression for the recovery executor sharing the timer's single recovery slot. */
final class KnowledgeServerRecoveryTimerTest {

  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir,
        5_000L, 1_000L, 3, 1_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  @Test
  @Timeout(30)
  void timerReconcilesOverdueHeldRecoveryWithoutStartingASecondWorker(@TempDir Path tempDir)
      throws Exception {
    try (var executors = new TestEngineExecutors();
        var components = new TestEngineComponents()) {
      var index = components.register(new ComponentSpec(
          "index", true, Set.of(), ComponentSpec.ComposeCapability.BESIDE,
          Duration.ofMillis(1), 2));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var reconciledWhileHeld = new CountDownLatch(1);
      var deadlineObserved = new CountDownLatch(1);
      var recoveryCompleted = new CountDownLatch(1);
      var held = new AtomicBoolean();
      var starts = new AtomicInteger();
      var client = mock(KnowledgeClient.class);
      when(client.isHealthy(any())).thenReturn(true);
      var host = mock(WorkerHost.class);
      when(host.start(any(), any())).thenAnswer(ignored -> {
        starts.incrementAndGet();
        held.set(true);
        entered.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS), "test must release the held start");
        return client;
      });
      var bootstrap = new KnowledgeServerBootstrap(
          executors,
          configFor(tempDir),
          null,
          components,
          index,
          host,
          true,
          new ReentrantReadWriteLock());
      bootstrap.transitionWorkerDown(
          LifecycleReasonCode.WORKER_SPAWN_FAILED, "initial composition failed");

      try (var monitor = new KnowledgeServerHealthMonitor(
          executors, bootstrap, 10L, System::currentTimeMillis,
          new BootRecoveryPolicy(2, 0, 0))) {
        monitor.onRecoveryConnected(ignored -> recoveryCompleted.countDown());
        monitor.onTick(() -> {
          if (held.get()) reconciledWhileHeld.countDown();
          var snapshot = index.snapshot();
          if (snapshot.state() == ComponentState.FAILED
              && snapshot.evidence() != null
              && snapshot.evidence().contains("deadline exceeded")) {
            deadlineObserved.countDown();
          }
        });

        monitor.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the timer must submit the held retry");
        assertEquals(
            WorkerRecoveryAuthority.Verdict.ALREADY_RUNNING,
            monitor.requestRecoveryNow(),
            "manual recovery must see the timer's held retry slot");
        assertTrue(
            reconciledWhileHeld.await(5, TimeUnit.SECONDS),
            "the timer must continue reconciliation while WorkerHost.start is held");
        assertTrue(
            deadlineObserved.await(5, TimeUnit.SECONDS),
            "the timer must mark the overdue STARTING component FAILED");
        assertEquals(ComponentState.FAILED, index.snapshot().state());
        assertEquals(1, starts.get(), "the held retry must not be duplicated");
        verify(host, times(1)).start(any(), any());

        held.set(false);
        release.countDown();
        assertTrue(
            recoveryCompleted.await(5, TimeUnit.SECONDS),
            "the original retry must complete after its start is released");
        assertEquals(1, starts.get(), "completion must not trigger a second WorkerHost.start");
        assertTrue(bootstrap.tryCheckHealth().orElse(false),
            "the released physical owner is healthy; the production sampler owns READY publication");
      } finally {
        release.countDown();
        bootstrap.close();
      }
    }
  }
}
