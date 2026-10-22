/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The client budget/ownership and server serving-lease/swap boundary are real; native IO is scripted. */
@Timeout(25)
final class EngineRuntimeReloadTest {
  @TempDir Path directory;

  @Test
  void idleClientReloadAndImmediateRepeatDoNotRetainTheirOwnServingView() throws Exception {
    try (var f = new Fixture(5_000)) {
      reloadSuccessfully(f);
      reloadSuccessfully(f);
      assertEquals(2, f.opens.get());
      try (var fresh = f.server.captureServingView()) {
        assertSame(f.server.appServices(), fresh.services());
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void holderTimeoutRestoresAdmissionAndRetrySucceedsWithoutClosingIssuedRuntime() throws Exception {
    try (var f = new Fixture(5_000)) {
      var held = f.server.captureServingView();
      try {
        var failure = assertThrows(KnowledgeClientException.class, f::reload);
        assertTrue(failure.getMessage().contains("retains active serving holders"), failure.toString());
        assertEquals(0, f.opens.get());
        verify(f.initialRuntime, never()).drainAndClose(any(), any());
        try (var restored = f.server.captureServingView()) {
          assertSame(held.services(), restored.services());
        }
      } finally { held.close(); }
      reloadSuccessfully(f);
    }
  }

  @Test
  void interruptedHolderDrainRestoresAdmissionAndRetrySucceeds() throws Exception {
    try (var f = new Fixture(5_000)) {
      var held = f.server.captureServingView();
      var failure = new AtomicReference<Throwable>();
      Thread swap = Thread.ofVirtual().start(() -> {
        try { f.swap(); } catch (Throwable cause) { failure.set(cause); }
      });
      try {
        await(() -> retiring(f.server));
        swap.interrupt();
        swap.join(2_000);
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertTrue(failure.get().getCause().getMessage().contains("Interrupted waiting"));
        try (var restored = f.server.captureServingView()) {
          assertSame(held.services(), restored.services());
        }
        verify(f.initialRuntime, never()).drainAndClose(any(), any());
      } finally { held.close(); swap.join(2_000); }
      reloadSuccessfully(f);
    }
  }

  @Test
  void interruptedReloadWithoutHoldersLeavesServingAdmissionOpen() throws Exception {
    try (var f = new Fixture(5_000)) {
      var failure = new AtomicReference<Throwable>();
      Thread interrupted = Thread.ofVirtual().start(() -> {
        Thread.currentThread().interrupt();
        try { f.swap(); } catch (Throwable cause) { failure.set(cause); }
      });
      interrupted.join(2_000);
      assertFalse(interrupted.isAlive());
      assertInstanceOf(IllegalStateException.class, failure.get());
      assertTrue(failure.get().getMessage().contains("interruption"));
      verify(f.initialRuntime, never()).drainAndClose(any(), any());
      try (var usable = f.server.captureServingView()) {
        assertSame(f.server.appServices(), usable.services());
      }
      reloadSuccessfully(f);
    }
  }

  @Test
  void reloadRefusesMigratingBeforeDestructiveWork() throws Exception {
    assertMigrationRefused("MIGRATING");
  }

  @Test
  void reloadRefusesSwitchingBeforeDestructiveWork() throws Exception {
    assertMigrationRefused("SWITCHING");
  }

  private void assertMigrationRefused(String state) throws Exception {
    try (var f = new Fixture(5_000)) {
      var manager = new IndexGenerationManager(directory.resolve("index"));
      manager.initializeOrLoad();
      manager.startMigration("schema_mismatch");
      manager.updateMigrationState(IndexGenerationManager.MigrationState.valueOf(state));
      set(f.server, "indexGenerationManager", manager);
      var failure = assertThrows(IllegalStateException.class, f::swap);
      assertTrue(failure.getMessage().contains("during migration"), failure.toString());
      verify(f.initialRuntime, never()).drainAndClose(any(), any());
      assertEquals(0, f.opens.get());
      try (var usable = f.server.captureServingView()) {
        assertSame(f.server.appServices(), usable.services());
      }
    }
  }

  @Test
  void reloadRefusesSeparateServingAndWriterEvenBeforeMigrationStatePublication() throws Exception {
    try (var f = new Fixture(5_000)) {
      set(f.server, "searchLifecycle", mock(RunningRuntime.class));
      var failure = assertThrows(IllegalStateException.class, f::swap);
      assertTrue(failure.getMessage().contains("during migration"));
      verify(f.initialRuntime, never()).drainAndClose(any(), any());
    }
  }

  @Test
  void reloadRefusesUnavailableMigrationStateBeforeDestructiveWork() throws Exception {
    try (var f = new Fixture(5_000)) {
      set(f.server, "indexGenerationManager", mock(IndexGenerationManager.class));
      var failure = assertThrows(IllegalStateException.class, f::swap);
      assertTrue(failure.getMessage().contains("migration state is unavailable"));
      verify(f.initialRuntime, never()).drainAndClose(any(), any());
      assertEquals(0, f.opens.get());
    }
  }

  @Test
  void reloadQueuedBehindMigrationStartRechecksTheNewOwner() throws Exception {
    assertConcurrentMigrationRefused(false);
  }

  @Test
  void reloadQueuedBehindCutoverRechecksTheNewOwner() throws Exception {
    assertConcurrentMigrationRefused(true);
  }

  private void assertConcurrentMigrationRefused(boolean switching) throws Exception {
    try (var f = new Fixture(5_000)) {
      var manager = new IndexGenerationManager(directory.resolve("index"));
      manager.initializeOrLoad();
      set(f.server, "indexGenerationManager", manager);
      var entered = new CountDownLatch(1);
      var ingest = f.server.appServices().ingestService();
      doAnswer(call -> {
        entered.countDown();
        return call.callRealMethod();
      }).when(ingest).reloadRuntime(any(), any());
      var lock = (java.util.concurrent.locks.ReentrantLock) get(f.server, "runtimeSwapLock");
      lock.lock();
      CompletableFuture<Long> reload;
      try {
        reload = CompletableFuture.supplyAsync(f::reload);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        manager.startMigration("schema_mismatch");
        if (switching) {
          manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
          set(f.server, "searchLifecycle", mock(RunningRuntime.class));
        }
      } finally { lock.unlock(); }
      var failure = assertThrows(ExecutionException.class, () -> reload.get(8, TimeUnit.SECONDS));
      assertTrue(failure.getCause().getMessage().contains("during migration"), failure.toString());
      verify(f.initialRuntime, never()).drainAndClose(any(), any());
      assertEquals(0, f.opens.get());
    }
  }

  @Test
  void parentAndForkedChildKeepRuntimeOpenUntilBothActuallyExit() throws Exception {
    try (var f = new Fixture(5_000)) {
      var parent = f.server.captureServingView();
      var child = parent.fork();
      var reload = CompletableFuture.runAsync(() -> reloadSuccessfully(f));
      try {
        await(() -> retiring(f.server));
        assertThrows(IllegalStateException.class, f.server::captureServingView);
        parent.close();
        verify(f.initialRuntime, never()).drainAndClose(any(), any());
        assertFalse(reload.isDone());
      } finally { parent.close(); child.close(); }
      reload.get(8, TimeUnit.SECONDS);
      assertEquals(1, f.opens.get());
    }
  }

  @Test
  void olderViewSharingRuntimeMustDrainEvenWithNoCurrentHolders() throws Exception {
    try (var f = new Fixture(5_000)) {
      var old = f.server.captureServingView();
      Object view = get(f.server, "servingView");
      var constructor = view.getClass().getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Object successor = constructor.newInstance(f.server.appServices(), f.initialRuntime,
          f.initialRuntime, null);
      @SuppressWarnings("unchecked")
      var retired = (List<Object>) get(f.server, "retiredServingViews");
      // Simulates the same-runtime successor used by query-settings publication.
      set(view, "retiring", true);
      retired.add(view);
      set(f.server, "servingView", successor);
      try {
        var failure = assertThrows(IllegalStateException.class, f::swap);
        assertTrue(failure.getMessage().contains("retains active serving holders"));
        verify(f.initialRuntime, never()).drainAndClose(any(), any());
        assertSame(f.server.appServices(), old.services());
      } finally { old.close(); retired.clear(); }
      f.swap();
    }
  }

  @Test
  void reloadArrivingInTheServicePublicationGapWaitsForItsPredecessor() throws Exception {
    try (var f = new Fixture(5_000)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.publicationBarrier = () -> { entered.countDown(); awaitUninterruptibly(release); };
      var first = CompletableFuture.runAsync(() -> reloadSuccessfully(f));
      CompletableFuture<Void> second;
      try {
        assertTrue(entered.await(8, TimeUnit.SECONDS), "first reload reaches service publication");
        assertNull(get(f.server, "appServices"), "closed services are withdrawn before publication");
        var resolving = new AtomicReference<Thread>();
        f.serviceLookup = resolving::set;
        second = CompletableFuture.runAsync(() -> reloadSuccessfully(f));
        var queued = second;
        // Admission alone does not prove the worker reached service resolution. Keep the first
        // publication blocked until the second has either failed or actually waits in the getter.
        await(() -> queued.isDone() || waitingForServicePublication(resolving.get()));
        assertFalse(second.isDone(), "A reload must wait while its predecessor publishes services");
        assertEquals(1, f.opens.get());
      } finally { release.countDown(); }
      assertDoesNotThrow(() -> first.get(8, TimeUnit.SECONDS));
      assertDoesNotThrow(() -> second.get(8, TimeUnit.SECONDS),
          "A concurrent reload waits across withdrawal and replacement publication");
      assertEquals(2, f.opens.get());
    }
  }

  @Test
  void concurrentReloadsSerializeAroundPublication() throws Exception {
    try (var f = new Fixture(5_000)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.openBarrier = () -> { entered.countDown(); awaitUninterruptibly(release); };
      var first = CompletableFuture.runAsync(() -> reloadSuccessfully(f));
      CompletableFuture<Void> second;
      try {
        assertTrue(entered.await(8, TimeUnit.SECONDS), "first reload must reach opener");
        second = CompletableFuture.runAsync(() -> reloadSuccessfully(f));
        await(() -> f.admission.activeWorkCount() == 2);
        assertEquals(1, f.opens.get());
      } finally { release.countDown(); }
      first.get(8, TimeUnit.SECONDS);
      assertNotNull(second);
      second.get(8, TimeUnit.SECONDS);
      assertEquals(2, f.opens.get());
      verify(f.initialRuntime).drainAndClose(any(), any());
      verify(f.openedRuntimes.get(0)).drainAndClose(any(), any());
      verify(f.openedRuntimes.get(1), never()).drainAndClose(any(), any());
      try (var current = f.server.captureServingView()) {
        assertSame(f.server.appServices(), current.services());
      }
    }
  }

  @Test
  void shutdownDuringHolderDrainNeverReopensAdmissionOrOpensReplacement() throws Exception {
    try (var f = new Fixture(5_000)) {
      var held = f.server.captureServingView();
      var reload = CompletableFuture.runAsync(f::reload);
      CompletableFuture<Void> close;
      try {
        await(() -> retiring(f.server));
        close = CompletableFuture.runAsync(() -> {
          try { f.server.close(); }
          catch (java.io.IOException boundedRefusal) {
            assertTrue(boundedRefusal.getMessage().contains("retained"), boundedRefusal.toString());
          }
        });
        await(() -> (boolean) getUnchecked(f.server, "closeStarted"));
      } finally { held.close(); }
      var failure = assertThrows(ExecutionException.class, () -> reload.get(8, TimeUnit.SECONDS));
      assertTrue(failure.getCause().getMessage().contains("close"), failure.toString());
      assertNotNull(close);
      close.get(8, TimeUnit.SECONDS);
      assertThrows(IllegalStateException.class, f.server::captureServingView);
      assertEquals(0, f.opens.get());
    }
  }

  @Test
  void refusedDrainDuringShutdownNeverRestoresServingAdmission() throws Exception {
    try (var f = new Fixture(5_000)) {
      var held = f.server.captureServingView();
      var reload = CompletableFuture.runAsync(f::reload);
      CompletableFuture<Void> close;
      try {
        await(() -> retiring(f.server));
        close = CompletableFuture.runAsync(() -> {
          try { f.server.close(); }
          catch (java.io.IOException boundedRefusal) {
            assertTrue(boundedRefusal.getMessage().contains("retained"), boundedRefusal.toString());
          }
        });
        await(() -> (boolean) getUnchecked(f.server, "closeStarted"));
        var failure = assertThrows(ExecutionException.class, () -> reload.get(8, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("retains active serving holders"));
        assertThrows(IllegalStateException.class, f.server::captureServingView);
        verify(f.initialRuntime, never()).drainAndClose(any(), any());
      } finally { held.close(); }
      close.get(8, TimeUnit.SECONDS);
      f.server.close();
      assertTrue(f.server.awaitClosed(0));
      assertEquals(0, f.opens.get());
    }
  }

  @Test
  void queuedReloadCannotResurrectClosedServer() throws Exception {
    try (var f = new Fixture(5_000)) {
      var pool = (ThreadPoolExecutor) get(f.client, "foregroundCallThreads");
      var entered = new CountDownLatch(pool.getCorePoolSize());
      var release = new CountDownLatch(1);
      for (int i = 0; i < pool.getCorePoolSize(); i++) {
        pool.execute(() -> { entered.countDown(); awaitUninterruptibly(release); });
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var reload = CompletableFuture.runAsync(f::reload);
      try {
        await(() -> !pool.getQueue().isEmpty());
        assertDoesNotThrow(f.server::close, "queued reload must not retain a serving holder");
      } finally { release.countDown(); }
      assertThrows(ExecutionException.class, () -> reload.get(8, TimeUnit.SECONDS));
      assertEquals(0, f.opens.get());
      assertThrows(IllegalStateException.class, f.server::captureServingView);
    }
  }

  @Test
  void deadlineReleasesCallerButOwnsRunningReloadUntilWorkerExit() throws Exception {
    try (var f = new Fixture(20)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.openBarrier = () -> { entered.countDown(); awaitUninterruptibly(release); };
      var reload = CompletableFuture.runAsync(f::reload);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var failure = assertThrows(ExecutionException.class, () -> reload.get(4, TimeUnit.SECONDS));
        assertInstanceOf(KnowledgeClientException.class, failure.getCause());
        assertEquals(1, f.admission.activeWorkCount(), "running reload remains Engine-owned");
      } finally { release.countDown(); }
      await(() -> f.admission.activeWorkCount() == 0);
    }
  }

  @Test
  void cancelledButRunningSearchKeepsReloadFromClosingItsRuntime() throws Exception {
    try (var f = new Fixture(5_000)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var searchService = mock(WorkerSearchService.class);
      when(searchService.search(any(), any())).thenAnswer(ignored -> {
        entered.countDown();
        awaitUninterruptibly(release);
        return io.justsearch.ipc.SearchResponse.getDefaultInstance();
      });
      when(f.server.appServices().searchService()).thenReturn(searchService);
      var search = CompletableFuture.runAsync(() ->
          f.client.search("running", 10, TestEngineContexts.FOREGROUND));
      CompletableFuture<Void> reload;
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        f.admission.cancelInteractive("search_cancelled");
        assertThrows(ExecutionException.class, () -> search.get(3, TimeUnit.SECONDS));
        reload = CompletableFuture.runAsync(() -> reloadSuccessfully(f));
        await(() -> retiring(f.server));
        verify(f.initialRuntime, never()).drainAndClose(any(), any());
        assertFalse(reload.isDone());
      } finally { release.countDown(); }
      assertNotNull(reload);
      reload.get(8, TimeUnit.SECONDS);
      await(() -> f.admission.activeWorkCount() == 0);
    }
  }

  @Test
  void cancellationKeepsRunningReloadOwnedUntilItsWorkerExits() throws Exception {
    try (var f = new Fixture(5_000)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.openBarrier = () -> { entered.countDown(); awaitUninterruptibly(release); };
      var reload = CompletableFuture.runAsync(f::reload);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        f.admission.cancelInteractive("reload_cancelled");
        assertThrows(ExecutionException.class, () -> reload.get(3, TimeUnit.SECONDS));
        assertEquals(1, f.admission.activeWorkCount());
      } finally { release.countDown(); }
      await(() -> f.admission.activeWorkCount() == 0);
    }
  }

  @Test
  void cancellingQueuedReloadReleasesItsOwnerWithoutEnteringWorker() throws Exception {
    try (var f = new Fixture(5_000)) {
      var pool = (ThreadPoolExecutor) get(f.client, "foregroundCallThreads");
      var entered = new CountDownLatch(pool.getCorePoolSize());
      var release = new CountDownLatch(1);
      for (int i = 0; i < pool.getCorePoolSize(); i++) {
        pool.execute(() -> { entered.countDown(); awaitUninterruptibly(release); });
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var reload = CompletableFuture.runAsync(f::reload);
      try {
        await(() -> !pool.getQueue().isEmpty());
        f.admission.cancelInteractive("queued_reload_cancelled");
        assertThrows(ExecutionException.class, () -> reload.get(3, TimeUnit.SECONDS));
        assertTrue(pool.getQueue().isEmpty());
        assertEquals(0, f.admission.activeWorkCount());
        assertEquals(0, f.opens.get());
        try (var usable = f.server.captureServingView()) {
          assertSame(f.server.appServices(), usable.services());
        }
      } finally { release.countDown(); }
    }
  }

  @Test
  void executorRejectionReleasesReloadOwnershipWithoutEnteringWorker() throws Exception {
    try (var f = new Fixture(5_000)) {
      var pool = (ThreadPoolExecutor) get(f.client, "foregroundCallThreads");
      pool.shutdown();
      assertThrows(RuntimeException.class, f::reload);
      assertEquals(0, f.admission.activeWorkCount());
      assertEquals(0, f.opens.get());
    }
  }

  @Test
  void otherLifecycleCallsStillRetainServingView() throws Exception {
    try (var f = new Fixture(5_000)) {
      when(f.server.appServices().ingestService().resetIndex(any(), any())).thenAnswer(ignored -> {
        Object view = getUnchecked(f.server, "servingView");
        assertTrue((int) getUnchecked(view, "holders") > 0);
        return io.justsearch.ipc.ResetIndexResponse.newBuilder().setSuccess(true).build();
      });
      assertTrue(f.client.resetIndex(TestEngineContexts.FOREGROUND));
      assertEquals(0, (int) getUnchecked(getUnchecked(f.server, "servingView"), "holders"));
    }
  }

  private static void reloadSuccessfully(Fixture f) {
    try { assertTrue(f.reload() >= 0, "swap duration is nonnegative"); }
    catch (KnowledgeClientException failure) {
      // Specifically detect the product self-wait, rather than accepting a timeout as fail-before.
      assertTrue(failure.getMessage().contains("retains active serving holders"), failure.toString());
      fail("Idle reload retained its own serving holder: " + failure.getMessage());
    }
  }

  private final class Fixture implements AutoCloseable {
    final DefaultEngineExecutorRegistry registry = new DefaultEngineExecutorRegistry();
    final EngineAdmissionController admission = new EngineAdmissionController(16, 16, 1);
    final KnowledgeServer server;
    final RunningRuntime initialRuntime = mock(RunningRuntime.class);
    final AtomicInteger opens = new AtomicInteger();
    final List<RunningRuntime> openedRuntimes = new CopyOnWriteArrayList<>();
    final EngineKnowledgeClient client;
    volatile Runnable openBarrier = () -> {};
    volatile Runnable publicationBarrier = () -> {};
    volatile java.util.function.Consumer<Thread> serviceLookup = ignored -> {};

    Fixture(long deadline) throws Exception {
      server = spy(new KnowledgeServer(registry,
          new WorkerConfig(directory.resolve("data"), 60_000L, "test", Map.of(), "test", 500L), null));
      set(server, "ingestLifecycle", initialRuntime);
      set(server, "searchLifecycle", initialRuntime);
      publish(services());
      var creation = KnowledgeServer.class.getDeclaredMethod("newAppServices");
      creation.setAccessible(true);
      var unused = doAnswer(ignored -> services()).when(server);
      creation.invoke(server);
      client = new EngineKnowledgeClient(registry, () -> {
        serviceLookup.accept(Thread.currentThread());
        return server.appServices();
      },
          new ForegroundLoadGate(new ForegroundLoad()), deadline, 100, IpcTelemetry.noop(),
          () -> {}, admission, WatchedRootsState.inMemory(), server::captureServingView);
    }

    DefaultWorkerAppServices services() {
      var ingest = mock(WorkerIngestService.class);
      doCallRealMethod().when(ingest).reloadRuntime(any(), any());
      doCallRealMethod().when(ingest).setRuntimeReloadTrigger(any());
      ingest.setRuntimeReloadTrigger(reason -> swap());
      // Keep native IO scripted when production reconstruction rewires the new ingest owner.
      doNothing().when(ingest).setRuntimeReloadTrigger(any());
      var services = mock(DefaultWorkerAppServices.class);
      when(services.ingestService()).thenReturn(ingest);
      doAnswer(ignored -> { publicationBarrier.run(); return null; })
          .when(services).startIndexingLoop();
      return services;
    }

    void publish(WorkerAppServices services) throws Exception {
      var method = KnowledgeServer.class.getDeclaredMethod("publishServingView", WorkerAppServices.class);
      method.setAccessible(true);
      method.invoke(server, services);
    }

    long reload() { return client.reloadRuntime("admin_triggered", TestEngineContexts.FOREGROUND); }
    long swap() {
      return server.swapRuntime(() -> {
        opens.incrementAndGet();
        openBarrier.run();
        var fresh = mock(RunningRuntime.class);
        openedRuntimes.add(fresh);
        return fresh;
      }, Duration.ofSeconds(5), SwapReason.ADMIN_TRIGGERED);
    }

    @Override public void close() throws Exception {
      client.close();
      server.close();
      registry.close();
    }
  }

  private static boolean retiring(KnowledgeServer server) {
    return (boolean) getUnchecked(getUnchecked(server, "servingView"), "retiring");
  }

  private static boolean waitingForServicePublication(Thread resolver) {
    if (resolver == null || resolver.getState() != Thread.State.WAITING) return false;
    for (var frame : resolver.getStackTrace()) {
      if (frame.getClassName().equals(KnowledgeServer.class.getName())
          && frame.getMethodName().equals("appServices")) return true;
    }
    return false;
  }

  private static Object getUnchecked(Object owner, String name) {
    try { return get(owner, name); }
    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  private static Object get(Object owner, String name) throws ReflectiveOperationException {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
    assertTrue(condition.getAsBoolean(), "condition did not become true");
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    for (;;) {
      try { latch.await(); break; }
      catch (InterruptedException failure) { interrupted = true; }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
