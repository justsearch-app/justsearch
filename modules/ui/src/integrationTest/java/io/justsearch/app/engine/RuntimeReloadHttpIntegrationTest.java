/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.SwapReason;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.phases.IndexingJobsBridgeWiring;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.IndexingJobsSnapshot;
import io.justsearch.ui.api.*;
import io.justsearch.ui.api.routes.DebugRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real HTTP route/handler, bridge wiring, client, serving leases and swap; native IO is scripted. */
@Timeout(30)
final class RuntimeReloadHttpIntegrationTest {
  @TempDir Path directory;

  @Test
  void idleHttpReloadRetiresBuiltInSubscriptionAndReconnects() throws Exception {
    try (var f = new Fixture()) {
      assertEquals(1, f.holders(), "the idle built-in observer must really retain a serving view");
      assertReloadSuccess(f.post());
      f.awaitSubscriptions(2);
      assertReloadSuccess(f.post());
      f.awaitSubscriptions(3);
      assertEquals(2, f.opens.get());
      assertEquals(1, f.holders(), "the observer reconnects to the replacement, without leaking A");
    }
  }

  @Test
  void httpReloadStillRefusesActualHolderAfterSubscriptionRetires() throws Exception {
    try (var f = new Fixture()) {
      try (var held = f.server.captureServingView()) {
        var refused = f.post();
        assertEquals(500, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("Runtime reload retains active serving holders"), refused.body());
        assertEquals(0, f.opens.get());
        verify(f.initial, never()).drainAndClose(any(), any());
        assertSame(f.server.appServices(), held.services());
        try (var restored = f.server.captureServingView()) {
          assertSame(held.services(), restored.services());
        }
      }
      assertReloadSuccess(f.post());
      f.awaitSubscriptions(2);
      assertEquals(1, f.opens.get());
    }
  }

  @Test
  void httpRetirementCallbackCanEnterLifecycleBeforeReleasingItsHolder() throws Exception {
    try (var f = new Fixture(); var held = f.server.captureServingView()) {
      var lock = (java.util.concurrent.locks.ReentrantLock) get(f.server, "runtimeSwapLock");
      held.onRetirement(() -> {
        try {
          assertDoesNotThrow(() -> java.util.concurrent.CompletableFuture.runAsync(() -> {
            lock.lock();
            try { assertFalse((boolean) uncheckedGet(f.server, "closeStarted")); }
            finally { lock.unlock(); }
          }).get(2, TimeUnit.SECONDS), "retirement callback cannot run under the lifecycle lock");
        } finally { held.close(); }
      });
      assertReloadSuccess(f.post());
      f.awaitSubscriptions(2);
    }
  }

  @Test
  void httpReloadRechecksMigrationAfterRetirementCallback() throws Exception {
    try (var f = new Fixture(); var held = f.server.captureServingView()) {
      var manager = new io.justsearch.indexerworker.index.IndexGenerationManager(directory.resolve("index"));
      manager.initializeOrLoad();
      set(f.server, "indexGenerationManager", manager);
      held.onRetirement(() -> {
        try { assertDoesNotThrow(() -> manager.startMigration("schema_mismatch")); }
        finally { held.close(); }
      });
      var refused = f.post();
      assertEquals(500, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("during migration"), refused.body());
      assertEquals(0, f.opens.get());
      verify(f.initial, never()).drainAndClose(any(), any());
      try (var restored = f.server.captureServingView()) {
        assertSame(f.server.appServices(), restored.services());
      }
    }
  }

  @Test
  void httpReloadRechecksShutdownAfterRetirementCallback() throws Exception {
    try (var f = new Fixture(); var held = f.server.captureServingView()) {
      held.onRetirement(() -> {
        held.close();
        assertDoesNotThrow(f.server::close);
      });
      var refused = f.post();
      assertEquals(500, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("close"), refused.body());
      assertEquals(0, f.opens.get());
      assertTrue(f.server.awaitClosed(0));
      assertThrows(IllegalStateException.class, f.server::captureServingView);
    }
  }

  @Test
  void httpReloadRechecksSuccessorPublishedByRetirementCallback() throws Exception {
    try (var f = new Fixture(); var held = f.server.captureServingView()) {
      held.onRetirement(() -> {
        held.close();
        f.swap();
      });
      assertReloadSuccess(f.post());
      assertEquals(2, f.opens.get(), "the HTTP reload operates on the nested reload's successor");
      verify(f.initial).drainAndClose(any(), any());
      f.awaitSubscriptions(2);
    }
  }

  @Test
  void timedOutSearchStartedByRetirementCallbackRetainsItsOwnViewUntilExit() throws Exception {
    try (var f = new Fixture(); var held = f.server.captureServingView()) {
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var search = mock(io.justsearch.indexerworker.services.WorkerSearchService.class);
      when(search.search(any(), any())).thenAnswer(ignored -> {
        entered.countDown();
        boolean interrupted = false;
        for (;;) {
          try { release.await(); break; }
          catch (InterruptedException failure) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return io.justsearch.ipc.SearchResponse.getDefaultInstance();
      });
      when(f.server.appServices().searchService()).thenReturn(search);
      held.onRetirement(() -> {
        try {
          io.justsearch.app.services.worker.HeadSearchDeadline.within(
              System.nanoTime() + TimeUnit.SECONDS.toNanos(1), () -> {
                assertThrows(io.justsearch.app.api.knowledge.KnowledgeClientException.class, () ->
                    f.client.search("running", 10, io.justsearch.app.services.intent.EngineProvenance.internal(
                        "retirement-search", io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
                        io.justsearch.core.context.EngineContext.Urgency.FOREGROUND)));
                return null;
              });
        } finally { held.close(); }
      });
      try {
        var refused = f.post();
        assertEquals(500, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("Runtime reload retains active serving holders"), refused.body());
        assertEquals(0L, entered.getCount(), "the nested worker must actually have entered");
        assertEquals(0, f.opens.get(), "caller timeout cannot release a running search's physical view");
        verify(f.initial, never()).drainAndClose(any(), any());
      } finally { release.countDown(); }
      assertReloadSuccess(f.post());
    }
  }

  private static Object uncheckedGet(Object owner, String name) {
    try { return get(owner, name); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }

  private static void assertReloadSuccess(HttpResponse<String> response) {
    if (response.statusCode() != 200) {
      assertTrue(response.body().contains("Runtime reload retains active serving holders"), response.body());
      fail("HTTP reload must release the idle subscription: " + response.body());
    }
    var json = new tools.jackson.databind.ObjectMapper().readTree(response.body());
    assertTrue(json.path("swapDurationMs").isNumber(), response.body());
    assertTrue(json.path("swapDurationMs").asLong() >= 0);
  }

  private final class Fixture implements AutoCloseable {
    final DefaultEngineExecutorRegistry executors = new DefaultEngineExecutorRegistry();
    final KnowledgeServer server;
    final RunningRuntime initial = mock(RunningRuntime.class);
    final AtomicInteger opens = new AtomicInteger();
    final AtomicInteger subscriptions = new AtomicInteger();
    final EngineKnowledgeClient client;
    final IndexingJobsBridgeWiring.Output bridge;
    final Javalin http;
    final HttpClient transport = HttpClient.newHttpClient();

    Fixture() throws Exception {
      server = spy(new KnowledgeServer(executors,
          new WorkerConfig(directory, 60_000L, "test", Map.of(), "test", 500L), null));
      set(server, "ingestLifecycle", initial);
      set(server, "searchLifecycle", initial);
      var publish = KnowledgeServer.class.getDeclaredMethod("publishServingView", WorkerAppServices.class);
      publish.setAccessible(true);
      publish.invoke(server, services());
      var creation = KnowledgeServer.class.getDeclaredMethod("newAppServices");
      creation.setAccessible(true);
      var unused = doAnswer(ignored -> services()).when(server);
      creation.invoke(server);
      client = new EngineKnowledgeClient(executors, server::appServices,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
          () -> {}, new EngineAdmissionController(16, 16, 1), WatchedRootsState.inMemory(),
          server::captureServingView);
      bridge = IndexingJobsBridgeWiring.wire(executors, () -> client,
          mock(IndexingJobsChangeRegistry.class), mock(ActionLedgerChangeRegistry.class));
      bridge.bridge().start().get(3, TimeUnit.SECONDS);
      awaitSubscriptions(1);

      var head = mock(HeadAssembly.class);
      when(head.workers()).thenReturn(new WorkerServices(client, null, null, null));
      var api = mock(LocalApiServer.class, CALLS_REAL_METHODS);
      set(api, "HeadAssemblyRef", head);
      var handler = LocalApiServer.class.getDeclaredMethod("handleAdminRuntimeReload", Context.class);
      handler.setAccessible(true);
      http = Javalin.create();
      DebugRoutes.register(http, mock(DebugStateController.class), mock(EffectiveConfigController.class),
          mock(ChunkInfoController.class), ctx -> {}, mock(LogLevelController.class),
          mock(TimeSeriesController.class), mock(SessionPoliciesController.class), ctx -> {},
          ctx -> handler.invoke(api, ctx));
      http.start("127.0.0.1", 0);
    }

    DefaultWorkerAppServices services() {
      var ingest = mock(WorkerIngestService.class);
      doCallRealMethod().when(ingest).reloadRuntime(any(), any());
      doCallRealMethod().when(ingest).setRuntimeReloadTrigger(any());
      ingest.setRuntimeReloadTrigger(reason -> swap());
      doNothing().when(ingest).setRuntimeReloadTrigger(any());
      doAnswer(call -> {
        int seq = subscriptions.incrementAndGet();
        call.<java.util.function.Consumer<IndexingJobsFrame>>getArgument(1).accept(
            IndexingJobsFrame.newBuilder().setSeq(seq)
                .setSnapshot(IndexingJobsSnapshot.getDefaultInstance()).build());
        return null;
      }).when(ingest).subscribeIndexingJobs(any(), any(), any());
      var services = mock(DefaultWorkerAppServices.class);
      when(services.ingestService()).thenReturn(ingest);
      return services;
    }

    long swap() {
      return server.swapRuntime(() -> {
        opens.incrementAndGet();
        return mock(RunningRuntime.class);
      }, Duration.ofSeconds(5), SwapReason.ADMIN_TRIGGERED);
    }

    HttpResponse<String> post() throws Exception {
      return transport.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + http.port() + "/api/admin/runtime/reload"))
          .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }

    int holders() throws Exception {
      synchronized (get(server, "servingViewMonitor")) {
        return (int) get(get(server, "servingView"), "holders");
      }
    }

    void awaitSubscriptions(int count) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
      while ((bridge.bridge().latestSnapshotSeq() < count || holders() != 1)
          && System.nanoTime() < until) Thread.sleep(5);
      assertTrue(bridge.bridge().latestSnapshotSeq() >= count, "bridge must publish a fresh snapshot");
      assertEquals(1, holders(), "only the idle subscription retains the replacement view");
    }

    @Override public void close() throws Exception {
      http.stop();
      transport.close();
      bridge.subscription().close();
      bridge.bridge().stop();
      client.close();
      server.close();
      executors.close();
    }
  }

  private static Object get(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void set(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }
}
