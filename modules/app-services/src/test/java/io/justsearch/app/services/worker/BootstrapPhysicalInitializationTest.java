/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.services.lifecycle.ReasonRetainingComponentHandle;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real bootstrap ownership; mocked physical client operations, with no readiness callback driver. */
final class BootstrapPhysicalInitializationTest {
  @Test
  void directHealthyObservationClearsFatalVerdictAndLaterLossIsGeneric(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir)) {
      fixture.bootstrap.start();
      io.justsearch.ipc.WorkerFatalReasonMarker.write(dir,
          io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH);
      fixture.bootstrap.transitionWorkerDown(
          io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_SPAWN_FAILED, "refused");
      assertEquals("worker.index_schema_mismatch", fixture.bootstrap.indexFatalCode().code());
      fixture.publishSamplerReady();
      assertEquals("worker.index_schema_mismatch", fixture.bootstrap.indexFatalCode().code());
      fixture.healthy.set(true);
      assertTrue(fixture.bootstrap.checkHealth());
      org.junit.jupiter.api.Assertions.assertNull(fixture.bootstrap.indexFatalCode());
      fixture.healthy.set(false);
      assertFalse(fixture.bootstrap.checkHealth());
      assertEquals("worker.lost", fixture.capability.pendingReason());
    }
  }

  @Test
  void throwingHealthObservationReportsLossAndRearmsInitialization(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir)) {
      fixture.healthy.set(true);
      fixture.bootstrap.start();
      when(fixture.client.isHealthy(any()))
          .thenThrow(new IllegalStateException("health observation failed")).thenReturn(true);
      assertThrows(IllegalStateException.class, fixture.bootstrap::checkHealth);
      assertEquals("worker.lost", fixture.capability.pendingReason());
      assertTrue(fixture.bootstrap.checkHealth());
      assertTrue(fixture.bootstrap.checkHealth());
      verify(fixture.client, times(2)).reindexPersistedRoots(any());
      verify(fixture.client, times(2)).startPeriodicSync();
    }
  }

  @Test
  void healthyPeriodsInitializeOnceIndependentOfReadiness(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir)) {
      fixture.healthy.set(true);
      fixture.bootstrap.start();
      assertTrue(fixture.bootstrap.checkHealth());
      fixture.indexComponent.transition(ComponentState.STARTING, "worker.starting", null);
      assertTrue(fixture.bootstrap.checkHealth());
      verify(fixture.client).reindexPersistedRoots(any());
      verify(fixture.client).startPeriodicSync();

      // A physical loss is still a loss when sampled readiness was independently non-READY.
      fixture.indexComponent.transition(ComponentState.STARTING, "worker.starting", null);
      fixture.healthy.set(false);
      assertFalse(fixture.bootstrap.checkHealth());
      assertEquals("worker.lost", fixture.capability.pendingReason());
      fixture.healthy.set(true);
      assertTrue(fixture.bootstrap.checkHealth());
      assertTrue(fixture.bootstrap.checkHealth());
      verify(fixture.client, times(2)).reindexPersistedRoots(any());
      verify(fixture.client, times(2)).startPeriodicSync();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void failedInitializationRetriesOnTheNextHealthyPoll(boolean failPeriodicSync, @TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir)) {
      fixture.bootstrap.start(); // Bound client, zero warmup budget and no healthy sample yet.
      verify(fixture.client, never()).reindexPersistedRoots(any());
      if (failPeriodicSync) {
        doThrow(new IllegalStateException("sync refused")).doNothing()
            .when(fixture.client).startPeriodicSync();
      } else {
        doThrow(new IllegalStateException("reindex refused")).doNothing()
            .when(fixture.client).reindexPersistedRoots(any());
      }
      fixture.healthy.set(true);
      assertThrows(IllegalStateException.class, fixture.bootstrap::checkHealth);
      assertTrue(fixture.bootstrap.checkHealth());
      assertTrue(fixture.bootstrap.checkHealth());
      verify(fixture.client, times(2)).reindexPersistedRoots(any());
      verify(fixture.client, times(failPeriodicSync ? 2 : 1)).startPeriodicSync();
    }
  }

  @Test
  void closeAndRestartReinitializeAndRetryHelpWithoutPrivateCounterReflection(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir)) {
      fixture.helpFile();
      fixture.healthy.set(true);
      fixture.bootstrap.start();
      assertTrue(Files.exists(fixture.helpMarker()));
      fixture.bootstrap.closeForUpgrade();
      assertFalse(fixture.bootstrap.hasClient());
      assertFalse(fixture.bootstrap.checkHealth());
      Files.delete(fixture.helpMarker());
      fixture.bootstrap.start();
      assertTrue(Files.exists(fixture.helpMarker()));
      verify(fixture.client, times(2)).reindexPersistedRoots(any());
      verify(fixture.client, times(2)).startPeriodicSync();
      verify(fixture.client, times(2)).submitBatch(anyList(), anyBoolean(), anyString(), any());
    }
  }

  @Test
  void closeWaitsForCapturedClientAndRefusesNewCaptures(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir);
        var tasks = Executors.newSingleThreadExecutor()) {
      fixture.healthy.set(true);
      fixture.bootstrap.start();
      var publication = fixture.bootstrap.publicationLock();
      publication.readLock().lock();
      KnowledgeServerBootstrap.ClientLease held;
      try {
        held = fixture.bootstrap.acquireClientLease();
      } finally {
        publication.readLock().unlock();
      }
      assertEquals(fixture.client, held.client());
      var closing = tasks.submit(fixture.bootstrap::closeForUpgrade);
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean refused = false;
        while (!refused && System.nanoTime() < deadline) {
          publication.readLock().lock();
          try {
            try (var ignored = fixture.bootstrap.acquireClientLease()) {
              // The closer may not have reached the retirement boundary yet.
            } catch (IllegalStateException retired) {
              refused = true;
            }
          } finally {
            publication.readLock().unlock();
          }
          if (!refused) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        assertTrue(refused, "close must stop new leases before retiring the client");
        verify(fixture.client, never()).close();
        assertFalse(closing.isDone(), "close must retain a client still held by a request");
      } finally {
        held.close();
      }
      assertEquals(ShutdownOutcome.GRACEFUL, closing.get(5, TimeUnit.SECONDS));
      verify(fixture.client).close();
    }
  }

  @Test
  void clientCannotBeCapturedBeforeFirstHealthyInitialization(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir);
        var tasks = Executors.newSingleThreadExecutor()) {
      fixture.healthy.set(true);
      var healthEntered = new CountDownLatch(1);
      var releaseHealth = new CountDownLatch(1);
      when(fixture.client.isHealthy(any())).thenAnswer(invocation -> {
        healthEntered.countDown();
        assertTrue(releaseHealth.await(5, TimeUnit.SECONDS));
        return true;
      });
      var starting = tasks.submit(() -> {
        fixture.bootstrap.start();
        return null;
      });
      try {
        assertTrue(healthEntered.await(5, TimeUnit.SECONDS));
        var publication = fixture.bootstrap.publicationLock();
        publication.readLock().lock();
        try {
          assertThrows(IllegalStateException.class, fixture.bootstrap::acquireClientLease);
        } finally {
          publication.readLock().unlock();
        }
      } finally {
        releaseHealth.countDown();
      }
      starting.get(5, TimeUnit.SECONDS);
      try (var lease = fixture.bootstrap.captureClient()) {
        assertEquals(fixture.client, lease.client());
      }
    }
  }

  @Test
  void helpFailureIsBestEffortAndRetriesAfterPhysicalRecovery(@TempDir Path dir) throws Exception {
    try (var fixture = new Fixture(dir)) {
      fixture.helpFile();
      when(fixture.client.submitBatch(anyList(), anyBoolean(), anyString(), any()))
          .thenThrow(new IllegalStateException("help ingest refused")).thenReturn(null);
      fixture.healthy.set(true);
      fixture.bootstrap.start();
      assertFalse(Files.exists(fixture.helpMarker()));
      assertTrue(fixture.bootstrap.checkHealth());
      verify(fixture.client).submitBatch(anyList(), anyBoolean(), anyString(), any());
      fixture.healthy.set(false);
      assertFalse(fixture.bootstrap.checkHealth());
      fixture.healthy.set(true);
      assertTrue(fixture.bootstrap.checkHealth());
      assertTrue(Files.exists(fixture.helpMarker()));
      verify(fixture.client, times(2)).submitBatch(anyList(), anyBoolean(), anyString(), any());
      // Marker suppresses a third help ingestion while essential catch-up still runs.
      fixture.healthy.set(false);
      fixture.bootstrap.checkHealth();
      fixture.healthy.set(true);
      fixture.bootstrap.checkHealth();
      verify(fixture.client, times(3)).reindexPersistedRoots(any());
      verify(fixture.client, times(2)).submitBatch(anyList(), anyBoolean(), anyString(), any());
    }
  }

  @Test
  void closeCannotOvertakeInitializationAndLaterPollCannotUseClosedClient(@TempDir Path dir)
      throws Exception {
    try (var fixture = new Fixture(dir); var tasks = Executors.newFixedThreadPool(2)) {
      fixture.bootstrap.start();
      fixture.healthy.set(true);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var closing = new CountDownLatch(1);
      var closerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
      // Observe real contention, not a scheduling-dependent "close has started" signal.
      var lockField = KnowledgeServerBootstrap.class.getDeclaredField("initLock");
      lockField.setAccessible(true);
      var initializationLock = (java.util.concurrent.locks.ReentrantLock) lockField.get(fixture.bootstrap);
      List<String> events = new CopyOnWriteArrayList<>();
      doAnswer(invocation -> {
        events.add("init-enter");
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        events.add("init-exit");
        return null;
      }).when(fixture.client).reindexPersistedRoots(any());
      doAnswer(invocation -> { events.add("client-close"); return null; })
          .when(fixture.client).close();
      var initialized = tasks.submit(fixture.bootstrap::checkHealth);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var closed = tasks.submit(() -> {
        closerThread.set(Thread.currentThread());
        closing.countDown();
        return fixture.bootstrap.closeForUpgrade();
      });
      try {
        assertTrue(closing.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!initializationLock.hasQueuedThread(closerThread.get())
            && !closed.isDone() && System.nanoTime() < deadline) {
          java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(initializationLock.hasQueuedThread(closerThread.get()),
            "close must actually contend on the in-flight initialization before it is released");
      } finally {
        release.countDown();
      }
      assertTrue(initialized.get(5, TimeUnit.SECONDS));
      assertEquals(ShutdownOutcome.GRACEFUL, closed.get(5, TimeUnit.SECONDS));
      assertEquals(List.of("init-enter", "init-exit", "client-close"), events);
      assertFalse(fixture.bootstrap.checkHealth());
      verify(fixture.client, times(2)).isHealthy(any()); // Startup plus the one live poll.
      verify(fixture.client).reindexPersistedRoots(any());
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Path dir;
    private final TestEngineExecutors executors = new TestEngineExecutors();
    private final TestEngineComponents components = TestEngineComponents.fourComponents();
    private final ComponentHandle indexComponent =
        new ReasonRetainingComponentHandle(components.handle("index"));
    private final AtomicBoolean healthy = new AtomicBoolean();
    private final KnowledgeClient client = mock(KnowledgeClient.class);
    private final Capability capability;
    private final KnowledgeServerBootstrap bootstrap;

    private Fixture(Path dir) throws Exception {
      this.dir = dir;
      var config = new KnowledgeServerConfig(false, dir, dir, dir,
          5_000L, 2_000L, 3, 2_000L, 1_000L, 300_000L, 100, 0L, 0);
      var host = mock(WorkerHost.class);
      when(host.start(any(), any())).thenReturn(client);
      when(client.isHealthy(any())).thenAnswer(invocation -> healthy.get());
      bootstrap =
          new KnowledgeServerBootstrap(
              executors, config, null, components, indexComponent, host,
              new java.util.concurrent.locks.ReentrantReadWriteLock());
      capability = bootstrap.workerCapability();
    }

    private void helpFile() throws Exception {
      var help = Files.createDirectories(dir.resolve("SSOT/docs/help"));
      Files.writeString(help.resolve("welcome.md"), "# Help\n");
    }

    private Path helpMarker() { return dir.resolve(".help-ingested-version"); }

    private void publishSamplerReady() {
      indexComponent.transition(ComponentState.READY, null, null);
    }

    @Override
    public void close() {
      try {
        bootstrap.close();
      } finally {
        try {
          components.close();
        } finally {
          executors.close();
        }
      }
    }
  }
}
