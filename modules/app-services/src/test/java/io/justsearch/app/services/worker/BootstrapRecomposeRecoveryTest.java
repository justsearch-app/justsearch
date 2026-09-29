/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.core.component.ComponentRecoveryAction;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Physical close/open recovery must leave readiness publication to the sampler. */
final class BootstrapRecomposeRecoveryTest {
  @Test
  void encoderRecoveryKeepsTheExistingClientAndIndexObservation(@TempDir Path tempDir)
      throws Exception {
    var host = spy(new ControlledWorkerHost(healthyClient()));
    var request = mock(ComponentRecoveryAction.Request.class);
    when(host.recoverEncoders(request)).thenReturn(ComponentRecoveryAction.Result.REFUSED);
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(
        configFor(tempDir), null, host, false)) {
      var bootstrap = fixture.bootstrap();
      assertEquals(ComponentRecoveryAction.Result.REFUSED, bootstrap.recoverEncoders(request));
      verify(host, never()).recoverEncoders(request);
      bootstrap.start();
      var client = bootstrap.client();
      var index = bootstrap.indexComponent().snapshot();
      assertEquals(ComponentRecoveryAction.Result.REFUSED, bootstrap.recoverEncoders(request));
      verify(host).recoverEncoders(request);
      assertSame(client, bootstrap.client(), "native recovery must retain the exact index client");
      assertEquals(index, bootstrap.indexComponent().snapshot());
      assertEquals(1, host.startCount());
      assertEquals(0, host.closeCount());
    }
  }

  @Test
  @Timeout(30)
  void ownerCallbackRetainsFatalVerdictAcrossSuppressedCloseOpen(@TempDir Path tempDir)
      throws Exception {
    KnowledgeClient oldClient = healthyClient();
    KnowledgeClient replacementClient = healthyClient();
    ControlledWorkerHost host = new ControlledWorkerHost(oldClient, replacementClient);
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(
        configFor(tempDir), null, host, false)) {
      KnowledgeServerBootstrap bootstrap = fixture.bootstrap();
      bootstrap.start();
      io.justsearch.ipc.WorkerFatalReasonMarker.write(tempDir,
          io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_CORRUPT);

      var result = bootstrap.recomposeForRecovery(
          mock(ComponentRecoveryAction.Request.class), (request, body) -> {
            bootstrap.transitionWorkerDown(LifecycleReasonCode.WORKER_SPAWN_FAILED,
                "suppressed generic failure");
            assertEquals(LifecycleReasonCode.WORKER_INDEX_CORRUPT,
                body.fatalReasonCode());
            assertTrue(body.fatalDetail().contains("corrupt"));
            assertTrue(body.run(() -> true));
            return ComponentRecoveryAction.Result.REFUSED;
          });

      assertEquals(ComponentRecoveryAction.Result.REFUSED, result);
      assertEquals(1, host.closeCount());
      assertEquals(2, host.startCount());
    }
  }

  @Test
  void prepublicationCallbackIsCarriedToThePhysicalOwner(@TempDir Path tempDir)
      throws Exception {
    var callbacks = new AtomicInteger();
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(
        configFor(tempDir), null, new ControlledWorkerHost(healthyClient()), false)) {
      var result = fixture.bootstrap().recomposeForRecovery(
          mock(ComponentRecoveryAction.Request.class),
          (request, body) -> {
            body.preparePublication();
            return ComponentRecoveryAction.Result.REFUSED;
          },
          callbacks::incrementAndGet);

      assertEquals(ComponentRecoveryAction.Result.REFUSED, result);
      assertEquals(1, callbacks.get());
    }
  }

  @Test
  @Timeout(30)
  void bootstrapCallbackSerializesConcurrentOwnerClose(@TempDir Path tempDir)
      throws Exception {
    ControlledWorkerHost host = new ControlledWorkerHost(healthyClient(), healthyClient());
    host.holdClose();
    Object rootOwner = new Object();
    AtomicReference<Throwable> recoveryFailure = new AtomicReference<>();
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    CountDownLatch closeAttempted = new CountDownLatch(1);
    Thread recovery = null;
    Thread concurrentClose = null;
    try (var fixture = KnowledgeServerBootstrapTestFixture.create(
        configFor(tempDir), null, host, false)) {
      var bootstrap = fixture.bootstrap();
      bootstrap.start();
      recovery = Thread.ofVirtual().start(() -> {
        try {
          bootstrap.recomposeForRecovery(mock(ComponentRecoveryAction.Request.class),
              (request, body) -> {
                synchronized (rootOwner) {
                  return body.run(() -> true)
                      ? ComponentRecoveryAction.Result.REFUSED
                      : ComponentRecoveryAction.Result.SUPERSEDED;
                }
              });
        } catch (Throwable failure) {
          recoveryFailure.set(failure);
        }
      });
      assertTrue(host.closeEntered().await(5, TimeUnit.SECONDS));
      concurrentClose = Thread.ofVirtual().start(() -> {
        try {
          closeAttempted.countDown();
          synchronized (rootOwner) {
            host.close();
          }
        } catch (Throwable failure) {
          closeFailure.set(failure);
        }
      });

      assertTrue(closeAttempted.await(5, TimeUnit.SECONDS));
      assertTrue(concurrentClose.isAlive(),
          "a concurrent Root close must wait behind the admitted recovery owner");
      host.releaseClose();
      recovery.join(5_000);
      concurrentClose.join(5_000);
      assertFalse(recovery.isAlive());
      assertFalse(concurrentClose.isAlive(),
          "Root close must proceed after Bootstrap releases the ordered recovery callback");
      assertNull(recoveryFailure.get(), String.valueOf(recoveryFailure.get()));
      assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
    } finally {
      host.releaseClose();
      if (recovery != null) recovery.join(5_000);
      if (concurrentClose != null) concurrentClose.join(5_000);
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
