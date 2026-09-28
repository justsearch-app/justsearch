/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentState;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Physical close/open recovery must leave readiness publication to the sampler. */
final class BootstrapRecomposeRecoveryTest {
  @Test
  @Timeout(30)
  void boundReplacementHasNoAbsentGapAndClosesAndOpensOnce(@TempDir Path tempDir)
      throws Exception {
    KnowledgeClient oldClient = healthyClient();
    when(oldClient.isHealthy(any())).thenReturn(true, false);
    KnowledgeClient replacementClient = healthyClient();
    ControlledWorkerHost host = new ControlledWorkerHost(oldClient, replacementClient);
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(
        configFor(tempDir), null, host, false)) {
      KnowledgeServerBootstrap bootstrap = fixture.bootstrap();
      bootstrap.start();

      // The bootstrap has proved physical health. The component sampler is the separate owner of
      // the readiness handover, so establish the incumbent's sampled READY state explicitly.
      fixture.publishSamplerReady();
      List<String> transitions = fixture.recordIndexTransitions();

      // Model the monitor's caller-owned recovery precondition: a real physical loss is observed,
      // then the monitor publishes STARTING/worker.recovering before asking the owner to recompose.
      assertFalse(bootstrap.checkHealth(), "the incumbent must expose its physical loss first");
      bootstrap.indexComponent().transition(
          ComponentState.STARTING,
          LifecycleReasonCode.WORKER_RECOVERING.code(),
          "Reopening index with the current configuration (attempt 1)");
      assertEquals(ComponentState.STARTING, bootstrap.indexComponent().snapshot().state());

      assertTrue(
          bootstrap.recomposeForRecovery(() -> true),
          "a healthy replacement must report physical health");
      assertEquals(2, host.startCount(), "one initial open plus one replacement open");
      assertEquals(1, host.closeCount(), "one old physical owner close per recompose");
      verify(oldClient).close();
      verify(replacementClient, never()).close();

      // The owner is physically bound while the sampler still has to publish the handover.
      assertEquals(ComponentState.STARTING, bootstrap.indexComponent().snapshot().state());
      assertTrue(
          transitions.stream().noneMatch(event -> event.startsWith("OFFLINE/")),
          "recompose must not publish ABSENT/OFFLINE between the two bound owners: "
              + transitions);

      fixture.publishSamplerReady();
      assertEquals(ComponentState.READY, bootstrap.indexComponent().snapshot().state());
      assertEquals(
          List.of(
              "DEGRADED/worker.lost",
              "PENDING/worker.lost",
              "READY/null"),
          transitions,
          "the handover must retain the observed fault until READY, with no shutdown publication");
      assertTrue(bootstrap.isReady(), "the bound replacement is ready after the sampler handover");
    }
  }

  @Test
  @Timeout(30)
  void admissionRevokedWhileCloseIsHeldPreventsTheSubsequentOpen(@TempDir Path tempDir)
      throws Exception {
    KnowledgeClient oldClient = healthyClient();
    KnowledgeClient replacementClient = healthyClient();
    ControlledWorkerHost host = new ControlledWorkerHost(oldClient, replacementClient);
    host.holdClose();
    AtomicBoolean admitted = new AtomicBoolean(true);
    AtomicReference<Boolean> result = new AtomicReference<>();
    AtomicReference<Throwable> threadFailure = new AtomicReference<>();
    Thread recomposer = null;

    try (var fixture = KnowledgeServerBootstrapTestFixture.create(
        configFor(tempDir), null, host, false)) {
      KnowledgeServerBootstrap bootstrap = fixture.bootstrap();
      bootstrap.start();
      fixture.publishSamplerReady();

      recomposer = new Thread(() -> {
        try {
          result.set(bootstrap.recomposeForRecovery(admitted::get));
        } catch (Throwable failure) {
          threadFailure.set(failure);
        }
      }, "bootstrap-recompose-admission-test");
      recomposer.start();

      assertTrue(
          host.closeEntered().await(5, TimeUnit.SECONDS),
          "recompose must reach the physical close before admission is revoked");
      admitted.set(false);
      host.releaseClose();
      joinAndAssertSucceeded(recomposer, threadFailure);

      assertEquals(Boolean.FALSE, result.get(), "recompose must refuse the post-close open");
      assertEquals(1, host.startCount(), "admission revocation must prevent a replacement open");
      assertEquals(1, host.closeCount(), "the held close still executes exactly once");
      assertFalse(bootstrap.hasClient(), "the closed physical owner must not be retained as bound");
    } finally {
      host.releaseClose();
      if (recomposer != null && recomposer.isAlive()) {
        recomposer.join(TimeUnit.SECONDS.toMillis(5));
        if (recomposer.isAlive()) {
          fail("recompose thread did not terminate during bounded cleanup");
        }
      }
    }
  }

  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir,
        5_000L, 1_000L, 3, 1_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  private static KnowledgeClient healthyClient() {
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.isHealthy(any())).thenReturn(true);
    return client;
  }

  private static void joinAndAssertSucceeded(
      Thread thread, AtomicReference<Throwable> threadFailure) throws Exception {
    thread.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(thread.isAlive(), "recompose thread did not terminate");
    Throwable failure = threadFailure.get();
    if (failure != null) {
      throw new AssertionError("recompose thread failed", failure);
    }
  }

  /** A deterministic in-process host whose close can hold the bootstrap's recompose lock. */
  private static final class ControlledWorkerHost implements WorkerHost {
    private final Queue<KnowledgeClient> clients = new ArrayDeque<>();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch releaseClose = new CountDownLatch(1);
    private volatile boolean holdClose;

    private ControlledWorkerHost(KnowledgeClient... clients) {
      this.clients.addAll(List.of(clients));
    }

    @Override
    public synchronized KnowledgeClient start(
        io.justsearch.core.scheduling.GpuSchedulingGauge gpuScheduling, IpcTelemetry telemetry) {
      starts.incrementAndGet();
      KnowledgeClient client = clients.poll();
      if (client == null) {
        throw new AssertionError("unexpected replacement open");
      }
      return client;
    }

    @Override
    public ServingLease captureServingView() {
      return new ServingLease() {
        @Override
        public <T> T withClient(
            KnowledgeClient client, java.util.function.Function<KnowledgeClient, T> action) {
          return action.apply(client);
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public long ownerPid() {
      return 1L;
    }

    @Override
    public void close() {
      closes.incrementAndGet();
      if (!holdClose) return;
      closeEntered.countDown();
      try {
        if (!releaseClose.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("test did not release the held host close");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("held host close was interrupted", interrupted);
      }
    }

    private void holdClose() {
      holdClose = true;
    }

    private CountDownLatch closeEntered() {
      return closeEntered;
    }

    private void releaseClose() {
      releaseClose.countDown();
    }

    private int startCount() {
      return starts.get();
    }

    private int closeCount() {
      return closes.get();
    }
  }
}
