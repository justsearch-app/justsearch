/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.MigrationStartRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(180)
final class KnowledgeServerLiveMigrationTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void inFlightModelInitOpensGreenLiveAfterSuccessOrFailure(boolean failInitialization,
      @TempDir Path tempDir) throws Exception {
    var server = nativeServer(tempDir);
    var initialization = new CompletableFuture<Void>();
    server.deferredModelInit = initialization;
    var restarts = new AtomicInteger();
    var waiting = observeModelWait(server);
    try {
      String building = server.indexGenerationManagerForTests()
          .startFreshMigration("deferred-init").building_generation();
      var liveStart = server.beginUnrecordedBuildingLiveAsync(building, restarts::incrementAndGet);
      assertTrue(waiting.await(30, TimeUnit.SECONDS), "the dispatched thread must reach the wait");
      assertFalse(liveStart.isDone(), "dispatch returns while model initialization is in flight");
      assertEquals(0, restarts.get());
      var swapLock = (ReentrantLock) getField(server, "runtimeSwapLock");
      assertTrue(swapLock.tryLock(), "model publication must be able to acquire the runtime lock");
      try {
        assertTrue((Boolean) getField(server, "running"), "Blue stays running during the wait");
        try (var serving = server.captureServingView()) {
          assertNotNull(serving.searchRuntime().textQueryOps(), "Blue text search stays available");
          assertEquals(0, serving.searchRuntime().indexCountOps()
              .countByFieldOrThrow(SchemaFields.DOC_ID, "missing-during-model-init"));
        }
        if (failInitialization) {
          initialization.completeExceptionally(new IllegalStateException("init failed"));
        } else {
          initialization.complete(null);
        }
      } finally {
        swapLock.unlock();
      }
      assertTrue(liveStart.get(30, TimeUnit.SECONDS), "Green must open live after init settles");
      assertEquals(0, restarts.get(), "neither init success nor failure requests a restart");
    } finally {
      initialization.complete(null);
      server.close();
    }
  }

  @Test
  void anotherFailedPreconditionFallsBackWithoutWaitingForModels(@TempDir Path tempDir)
      throws Exception {
    var server = nativeServer(tempDir);
    var initialization = new CompletableFuture<Void>();
    server.deferredModelInit = initialization;
    var waiting = observeModelWait(server);
    var restarts = new AtomicInteger();
    try {
      setField(server, "rebuildBrakeExhausted", true);
      assertFalse(server.beginUnrecordedBuildingLiveAsync("not-opened", restarts::incrementAndGet)
          .get(30, TimeUnit.SECONDS));
      assertFalse(initialization.isDone(), "another refusal must not wait for model init");
      assertEquals(1, waiting.getCount(), "the model wait is eligible only for an init-only refusal");
      assertEquals(1, restarts.get(), "the existing restart fallback runs exactly once");
      assertNull(getField(server, "buildingIndexPath"));
    } finally {
      initialization.complete(null);
      server.close();
    }
  }

  @Test
  void modelCompletionRechecksOtherPreconditionsUnderTheRuntimeLock(@TempDir Path tempDir)
      throws Exception {
    var server = nativeServer(tempDir);
    var initialization = new CompletableFuture<Void>();
    server.deferredModelInit = initialization;
    var waiting = observeModelWait(server);
    var restarts = new AtomicInteger();
    try {
      var liveStart = server.beginUnrecordedBuildingLiveAsync("not-opened", restarts::incrementAndGet);
      assertTrue(waiting.await(30, TimeUnit.SECONDS));
      var swapLock = (ReentrantLock) getField(server, "runtimeSwapLock");
      swapLock.lock();
      try {
        setField(server, "rebuildBrakeExhausted", true);
        initialization.complete(null);
      } finally {
        swapLock.unlock();
      }
      assertFalse(liveStart.get(30, TimeUnit.SECONDS), "a changed witness must refuse admission");
      assertEquals(1, restarts.get());
      assertNull(getField(server, "buildingIndexPath"));
    } finally {
      initialization.complete(null);
      server.close();
    }
  }

  @Test
  void closeEndsTheModelWaitWithoutOpeningGreenOrRequestingRestart(@TempDir Path tempDir)
      throws Exception {
    var server = nativeServer(tempDir);
    var initialization = new CompletableFuture<Void>();
    server.deferredModelInit = initialization;
    var waiting = observeModelWait(server);
    var restarts = new AtomicInteger();
    try (var closer = Executors.newSingleThreadExecutor()) {
      try {
        String building = server.indexGenerationManagerForTests()
            .startFreshMigration("close-during-deferred-init").building_generation();
        var liveStart = server.beginUnrecordedBuildingLiveAsync(building, restarts::incrementAndGet);
        assertTrue(waiting.await(30, TimeUnit.SECONDS));
        var close = closer.submit(() -> { server.close(); return null; });
        try {
          assertFalse(liveStart.get(30, TimeUnit.SECONDS), "close must release the waiting starter");
          assertFalse(initialization.isDone(), "starter exit must not depend on init completion");
          assertEquals(0, restarts.get(), "ordered close must not trigger a redundant restart");
          assertNull(getField(server, "buildingIndexPath"));
          assertEquals(IndexGenerationManager.BootDisposition.NATIVE,
              getField(server, "generationBootDisposition"));
          assertFalse(close.isDone(), "physical close still joins the pending initializer");
        } finally {
          initialization.complete(null);
        }
        close.get(30, TimeUnit.SECONDS);
      } finally {
        initialization.complete(null);
        server.close();
      }
    }
  }

  private static KnowledgeServer nativeServer(Path tempDir) throws Exception {
    var layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    var server = org.mockito.Mockito.spy(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null));
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    try {
      server.start();
      ((CountDownLatch) getField(server, "modelReadyLatch")).countDown();
      return server;
    } catch (Exception | Error failure) {
      server.close();
      throw failure;
    }
  }

  private static CountDownLatch observeModelWait(KnowledgeServer server) throws Exception {
    var waiting = new CountDownLatch(1);
    var swapLock = (ReentrantLock) getField(server, "runtimeSwapLock");
    var publicationLock = (ReentrantReadWriteLock) getField(server, "publicationLock");
    Object closeLock = getField(server, "closeLock");
    Object servingMonitor = getField(server, "servingViewMonitor");
    org.mockito.Mockito.doAnswer(invocation -> {
      assertEquals("engine-migration-live-start", Thread.currentThread().getName());
      assertFalse(swapLock.isHeldByCurrentThread(), "the wait cannot hold runtimeSwapLock");
      assertFalse(publicationLock.isWriteLockedByCurrentThread());
      assertEquals(0, publicationLock.getReadHoldCount());
      assertFalse(Thread.holdsLock(closeLock), "the wait cannot hold the close lock");
      assertFalse(Thread.holdsLock(servingMonitor), "the wait cannot hold the serving monitor");
      waiting.countDown();
      return invocation.callRealMethod();
    }).when(server).awaitLiveStartModelInitialization(org.mockito.ArgumentMatchers.any());
    return waiting;
  }

  @Test
  void twoCompleteLiveMigrationsReuseTheServerAndMigrationThreads(@TempDir Path tempDir)
      throws Exception {
    var layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    var server = org.mockito.Mockito.spy(new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null));
    org.mockito.Mockito.doNothing().when(server)
        .startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    var restarts = new AtomicInteger();
    setField(server, "migrationRestartAction", (Runnable) restarts::incrementAndGet);
    var enumerations = new AtomicInteger();
    server.installProjectionSeedSources(List.of(new ProjectionSeedSource() {
      @Override public String sourceId() { return "memory"; }
      @Override public void enumerate(java.util.function.Consumer<AcceptedProjection> sink) {
        int revision = enumerations.incrementAndGet();
        sink.accept(new AcceptedProjection("memory", "note", revision,
            AcceptedProjection.Kind.UPSERT, "{\"content\":\"revision " + revision + "\"}"));
      }
    }));
    try {
      server.start();
      ((CountDownLatch) getField(server, "modelReadyLatch")).countDown();
      var manager = (IndexGenerationManager) getField(server, "indexGenerationManager");
      String active = manager.readStateBestEffort().active_generation();
      Thread previousEnumerator = null;
      Thread previousMonitor = null;
      for (int migration = 1; migration <= 2; migration++) {
        var started = server.appServices().ingestService().startMigration(
            MigrationStartRequest.newBuilder().setReason("manual")
                .setProjectionSourceIdsPresent(true).addProjectionSourceIds("memory").build(),
            CallContext.none());
        assertTrue(started.getAccepted(), started::getError);
        assertTrue(server.beginUnrecordedBuildingLiveAsync(
            started.getBuildingGenerationId(), restarts::incrementAndGet).get(30, TimeUnit.SECONDS),
            "migration " + migration + " must open live in the same server");
        Thread enumerator = (Thread) getField(server, "migrationEnumeratorThread");
        Thread monitor = (Thread) getField(server, "migrationCutoverThread");
        assertNotNull(enumerator);
        assertNotNull(monitor);
        assertNotEquals(previousEnumerator, enumerator);
        assertNotEquals(previousMonitor, monitor);
        enumerator.join(30_000);
        monitor.join(30_000);
        assertFalse(enumerator.isAlive(), "each candidate must finish its own enumeration");
        assertFalse(monitor.isAlive(), "each candidate must finish live promotion");
        assertEquals(started.getBuildingGenerationId(), manager.readStateBestEffort().active_generation());
        assertEquals(migration, enumerations.get());
        try (var serving = server.captureServingView()) {
          assertEquals("revision " + migration, serving.searchRuntime().documentFieldOps()
              .getDocumentField(new AcceptedProjection("memory", "note", migration,
                  AcceptedProjection.Kind.UPSERT, "{}").indexId(), SchemaFields.CONTENT));
        }
        var retirement = KnowledgeServer.class.getDeclaredMethod("retryCommittedGenerationRetirement");
        retirement.setAccessible(true);
        retirement.invoke(server);
        assertFalse(Files.exists(manager.resolveGenerationPathStrict(active)),
            "the completed predecessor must release capacity before another migration");
        assertEquals(0, restarts.get());
        active = started.getBuildingGenerationId();
        previousEnumerator = enumerator;
        previousMonitor = monitor;
      }
    } finally {
      server.close();
    }
  }

  @Test
  void unconfiguredServerStartsWithZeroFailedJobBudget(@TempDir Path tempDir) throws Exception {
    var layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    try (var server = new KnowledgeServer(new TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()), null)) {
      assertEquals(0, getField(server, "migrationCutoverMaxFailedJobs"));
    }
  }

  private static Object getField(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }

  private static void setField(KnowledgeServer server, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }
}
