/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.operations.AppliedIndexGeneration;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class EngineKnowledgeClientServingViewLifetimeTest {

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void cancellingQueuedSubscriptionTasksReleasesAdmissionAndServingViews(boolean delivery)
      throws Exception {
    var ingest = mock(WorkerIngestService.class);
    var publishers = new java.util.concurrent.CopyOnWriteArrayList<
        java.util.function.Consumer<IndexingJobsFrame>>();
    var started = new CountDownLatch(5);
    doAnswer(invocation -> {
      publishers.add(invocation.getArgument(1));
      started.countDown();
      return null;
    }).when(ingest).subscribeIndexingJobs(any(), any(), any());
    var services = services(null, ingest);
    var leases = new ServingLeaseTracker(services);
    var admission = new EngineAdmissionController(16, 16, 1);
    var errors = new AtomicInteger();
    var frames = new AtomicInteger();
    var completions = new AtomicInteger();
    var releasePool = new CountDownLatch(1);
    var streams = new java.util.ArrayList<KnowledgeClient.IndexingJobsStream>();
    try (var registry = registry(4, 16);
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
            () -> {}, admission, WatchedRootsState.inMemory(), leases::open)) {
      try {
        var pool = backgroundStreamPool(client);
        if (!delivery) occupyPool(pool, releasePool);
        for (int i = 0; i < 5; i++) {
          streams.add(client.subscribeIndexingJobs(frame -> frames.incrementAndGet(),
              failure -> {
                org.junit.jupiter.api.Assertions.assertInstanceOf(
                    java.util.concurrent.CancellationException.class, failure);
                errors.incrementAndGet();
              }, completions::incrementAndGet, TestEngineContexts.BACKGROUND));
        }
        if (delivery) {
          assertTrue(started.await(3, TimeUnit.SECONDS));
          occupyPool(pool, releasePool);
          assertEquals(5, leases.active.get(), "normal startup return keeps each subscription view");
          for (var publish : publishers) {
            publish.accept(IndexingJobsFrame.newBuilder()
                .setSnapshot(io.justsearch.ipc.IndexingJobsSnapshot.getDefaultInstance()).build());
          }
        }
        assertEquals(5, admission.activeWorkCount());
        assertEquals(10, leases.active.get(), "five subscriptions plus five never-started task views");
        var queued = List.copyOf(pool.getQueue());
        assertEquals(5, queued.size());
        for (var task : queued) {
          assertTrue(((java.util.concurrent.Future<?>) task).cancel(false));
        }
        // The blockers are still parked: no queued producer or delivery body can clean up for us.
        assertEquals(1L, releasePool.getCount());
        assertTrue(pool.getQueue().isEmpty());
        assertEquals(0, admission.activeWorkCount());
        leases.assertReleasedExactlyOnce();
        assertEquals(5, errors.get());
        assertEquals(0, frames.get());
        assertEquals(0, completions.get(), "normal producer return must not complete a live stream");
        if (!delivery) verify(ingest, never()).subscribeIndexingJobs(any(), any(), any());
        streams.forEach(KnowledgeClient.IndexingJobsStream::close);
        leases.assertReleasedExactlyOnce();
        assertEquals(5, errors.get(), "later handle close must not repeat terminal failure");
      } finally {
        releasePool.countDown();
        streams.forEach(KnowledgeClient.IndexingJobsStream::close);
      }
    }
  }

  @Test
  void clientCloseReleasesQueuedSubscriptionOwnersWithoutClosingHandles() throws Exception {
    var services = mock(WorkerAppServices.class);
    var leases = new ServingLeaseTracker(services);
    var admission = new EngineAdmissionController(16, 16, 1);
    var releasePool = new CountDownLatch(1);
    var streams = new java.util.ArrayList<KnowledgeClient.IndexingJobsStream>();
    try (var registry = registry(4, 16);
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
            () -> {}, admission, WatchedRootsState.inMemory(), leases::open)) {
      try {
        var pool = backgroundStreamPool(client);
        occupyPool(pool, releasePool);
        for (int i = 0; i < 5; i++) {
          streams.add(client.subscribeIndexingJobs(frame -> {
            throw new AssertionError("queued producer must never deliver");
          }, failure -> {}, () -> {}, TestEngineContexts.BACKGROUND));
        }
        assertEquals(5, pool.getQueue().size());
        assertEquals(5, admission.activeWorkCount());
        assertEquals(10, leases.active.get());
        client.close();
        assertEquals(1L, releasePool.getCount(), "shutdown assertions run before pool blockers exit");
        assertEquals(0, admission.activeWorkCount());
        leases.assertReleasedExactlyOnce();
        verify(services, never()).ingestService();
        streams.forEach(KnowledgeClient.IndexingJobsStream::close);
        leases.assertReleasedExactlyOnce();
      } finally {
        releasePool.countDown();
        streams.forEach(KnowledgeClient.IndexingJobsStream::close);
      }
    }
  }

  private static java.util.concurrent.ThreadPoolExecutor backgroundStreamPool(
      EngineKnowledgeClient client) throws Exception {
    var field = EngineKnowledgeClient.class.getDeclaredField("backgroundStreamThreads");
    field.setAccessible(true);
    return (java.util.concurrent.ThreadPoolExecutor) field.get(client);
  }

  private static void occupyPool(java.util.concurrent.ThreadPoolExecutor pool, CountDownLatch release)
      throws InterruptedException {
    var entered = new CountDownLatch(pool.getCorePoolSize());
    for (int i = 0; i < pool.getCorePoolSize(); i++) {
      pool.execute(() -> {
        entered.countDown();
        await(release);
      });
    }
    assertTrue(entered.await(3, TimeUnit.SECONDS));
  }

  private static final class ServingLeaseTracker {
    private final WorkerAppServices services;
    private final AtomicInteger active = new AtomicInteger();
    private final List<KnowledgeServer.ServingLease> opened = new java.util.concurrent.CopyOnWriteArrayList<>();

    ServingLeaseTracker(WorkerAppServices services) { this.services = services; }

    KnowledgeServer.ServingLease open() {
      var lease = mock(KnowledgeServer.ServingLease.class);
      active.incrementAndGet();
      opened.add(lease);
      when(lease.services()).thenReturn(services);
      when(lease.fork()).thenAnswer(ignored -> open());
      when(lease.onRetirement(any())).thenReturn(() -> {});
      doAnswer(ignored -> { active.decrementAndGet(); return null; }).when(lease).close();
      return lease;
    }

    void assertReleasedExactlyOnce() {
      assertEquals(0, active.get(), "every captured serving lease returns to baseline");
      opened.forEach(lease -> verify(lease).close());
    }
  }

  @Test
  void encoderRuntimeSnapshotKeepsPolicyAndProbeOnAThroughBPublication() throws Exception {
    var policyEntered = new CountDownLatch(1);
    var releasePolicy = new CountDownLatch(1);
    var aIngest = mock(WorkerIngestService.class);
    var bIngest = mock(WorkerIngestService.class);
    doAnswer(ignored -> {
      policyEntered.countDown();
      await(releasePolicy);
      return io.justsearch.ipc.SessionPoliciesResponse.newBuilder()
          .setConfigStatus("generation-a").build();
    }).when(aIngest).getSessionPolicies(any(), any());
    when(aIngest.indexStatus(any(), any()))
        .thenReturn(io.justsearch.ipc.StatusResponse.getDefaultInstance());
    var aServices = services(null, aIngest);
    var bServices = services(null, bIngest);
    var aClosed = new AtomicInteger();
    var selected = new AtomicReference<KnowledgeServer.ServingLease>(leaseFor(aServices, aClosed));

    try (var registry = registry();
        var client = client(registry, aServices, selected::get)) {
      var result = CompletableFuture.supplyAsync(
          () -> client.getEncoderRuntimeSnapshot(TestEngineContexts.FOREGROUND));
      assertTrue(policyEntered.await(3, TimeUnit.SECONDS));
      selected.set(leaseFor(bServices, new AtomicInteger()));
      releasePolicy.countDown();

      assertEquals("generation-a", result.get(5, TimeUnit.SECONDS).policies().get("configStatus"));
      verify(aIngest).indexStatus(any(), any());
      verify(bIngest, never()).getSessionPolicies(any(), any());
      verify(bIngest, never()).indexStatus(any(), any());
      assertTrue(aClosed.get() >= 3, "parent and both child reads release A");
    } finally {
      releasePolicy.countDown();
    }
  }

  @Test
  void appliedGenerationCaptureCompletesOnIssuedAViewAfterBPublication() throws Exception {
    var aEntered = new CountDownLatch(1);
    var releaseA = new CountDownLatch(1);
    var aIngest = mock(WorkerIngestService.class);
    var bIngest = mock(WorkerIngestService.class);
    var generationA = new AppliedIndexGeneration("generation-a", mock(IndexTargetSnapshot.class));
    doAnswer(ignored -> {
      aEntered.countDown();
      await(releaseA);
      return generationA;
    }).when(aIngest).captureAppliedGeneration(any());
    var aServices = services(null, aIngest);
    var bServices = services(null, bIngest);
    var selectedServices = new AtomicReference<>(aServices);
    var aClosed = new AtomicInteger();
    var selectedLease = new AtomicReference<>(leaseFor(aServices, aClosed));

    try (var registry = registry();
        var client = new EngineKnowledgeClient(registry, selectedServices::get,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
            () -> {}, new EngineAdmissionController(16, 16, 1), WatchedRootsState.inMemory(),
            selectedLease::get)) {
      var result = CompletableFuture.supplyAsync(
          () -> client.captureAppliedGeneration(TestEngineContexts.FOREGROUND));
      assertTrue(aEntered.await(3, TimeUnit.SECONDS));
      selectedServices.set(bServices);
      selectedLease.set(leaseFor(bServices, new AtomicInteger()));
      releaseA.countDown();

      assertSame(generationA, result.get(5, TimeUnit.SECONDS));
      verify(bIngest, never()).captureAppliedGeneration(any());
      assertTrue(aClosed.get() > 0, "issued A capture must release its serving lease");
    } finally {
      releaseA.countDown();
    }
  }

  @Test
  void executingSearchKeepsIssuedAViewThroughBPublication() throws Exception {
    var aEntered = new CountDownLatch(1);
    var releaseA = new CountDownLatch(1);
    var aSearch = mock(io.justsearch.indexerworker.services.WorkerSearchService.class);
    var bSearch = mock(io.justsearch.indexerworker.services.WorkerSearchService.class);
    var aResponse = SearchResponse.newBuilder().setTookMs(1).build();
    var bResponse = SearchResponse.newBuilder().setTookMs(2).build();
    doAnswer(ignored -> {
      aEntered.countDown();
      await(releaseA);
      return aResponse;
    }).when(aSearch).search(any(), any());
    when(bSearch.search(any(), any())).thenReturn(bResponse);
    var aServices = services(aSearch, null);
    var bServices = services(bSearch, null);
    var aClosed = new AtomicInteger();
    var bClosed = new AtomicInteger();
    var selected = new AtomicReference<KnowledgeServer.ServingLease>(
        leaseFor(aServices, aClosed));

    try (var registry = registry();
        var client = client(registry, aServices, selected::get)) {
      var result = CompletableFuture.supplyAsync(
          () -> client.search("issued-on-a", 10, TestEngineContexts.FOREGROUND));
      assertTrue(aEntered.await(3, TimeUnit.SECONDS));

      selected.set(leaseFor(bServices, bClosed));
      assertEquals(0, aClosed.get(), "executing search must retain its issued A lease");
      assertEquals(0, bClosed.get(), "published B must remain untouched by the A search");
      verify(bSearch, never()).search(any(), any());

      releaseA.countDown();
      assertSame(aResponse, result.get(5, TimeUnit.SECONDS));
      awaitClosed(aClosed, 1);
      assertEquals(1, aClosed.get(), "completed A search must release its serving lease");

      assertSame(bResponse,
          client.search("issued-on-b", 10, TestEngineContexts.FOREGROUND));
      verify(aSearch).search(any(), any());
      verify(bSearch).search(any(), any());
      awaitClosed(bClosed, 1);
      assertEquals(1, bClosed.get(), "completed B search must release its serving lease");
    } finally {
      releaseA.countDown();
    }
  }

  @Test
  void queuedRootWalksUseTheViewCapturedBeforeEachQueueEntry() throws Exception {
    var aSearch = mock(io.justsearch.indexerworker.services.WorkerSearchService.class);
    var bSearch = mock(io.justsearch.indexerworker.services.WorkerSearchService.class);
    when(aSearch.search(any(), any())).thenReturn(SearchResponse.getDefaultInstance());
    when(bSearch.search(any(), any())).thenReturn(SearchResponse.getDefaultInstance());
    var aServices = services(aSearch, null);
    var bServices = services(bSearch, null);
    var aClosed = new AtomicInteger();
    var bClosed = new AtomicInteger();
    var aLease = leaseFor(aServices, aClosed);
    var bLease = leaseFor(bServices, bClosed);
    var selected = new AtomicReference<KnowledgeServer.ServingLease>(aLease);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var complete = new CountDownLatch(2);

    try (var registry = registry();
        var client = client(registry, aServices, selected::get)) {
      ExecutorService queue = rootQueue(client);
      queue.execute(() -> {
        entered.countDown();
        await(release);
      });
      assertTrue(entered.await(2, TimeUnit.SECONDS));

      client.executeRootWalk(queue, context -> {
        client.search("A", 10, context);
        complete.countDown();
      }, TestEngineContexts.FOREGROUND);
      selected.set(bLease);
      client.executeRootWalk(queue, context -> {
        client.search("B", 10, context);
        complete.countDown();
      }, TestEngineContexts.FOREGROUND);

      release.countDown();
      assertTrue(complete.await(5, TimeUnit.SECONDS));
      verify(aSearch).search(any(), any());
      verify(bSearch).search(any(), any());
      awaitClosed(aClosed, 2);
      awaitClosed(bClosed, 2);
      assertTrue(aClosed.get() >= 2, "root and nested unary tasks must release view A");
      assertTrue(bClosed.get() >= 2, "root and nested unary tasks must release view B");
    } finally {
      release.countDown();
    }
  }

  @Test
  void capturedRootsKeepOneParentViewAcrossAThenBReplacement() throws Exception {
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var aIngest = mock(WorkerIngestService.class);
    var bIngest = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      if (calls.incrementAndGet() == 1) {
        firstEntered.countDown();
        await(releaseFirst);
      }
      invocation.<java.util.function.Consumer<ScanRootProgress>>getArgument(1)
          .accept(ScanRootProgress.newBuilder().setComplete(true).build());
      return null;
    }).when(aIngest).scanRecordedRoot(any(), any(), any());
    var aServices = services(null, aIngest);
    var bServices = services(null, bIngest);
    var aClosed = new AtomicInteger();
    var selected = new AtomicReference<KnowledgeServer.ServingLease>(leaseFor(aServices, aClosed));
    var plan = new RecordedRootPlan("generation-a", List.of(
        new RecordedRootPlan.Root(Path.of("root-a"), "a", true, false, List.of(), List.of()),
        new RecordedRootPlan.Root(Path.of("root-b"), "b", true, false, List.of(), List.of())));

    try (var registry = registry();
        var client = client(registry, aServices, selected::get)) {
      var result = client.enumerateCapturedRoots(plan, "operation-a", 7,
          TestEngineContexts.FOREGROUND, new io.justsearch.app.services.worker.CancelToken(), false);
      if (!firstEntered.await(3, TimeUnit.SECONDS)) {
        result.toCompletableFuture().join();
        throw new AssertionError("recorded root worker never entered A");
      }
      selected.set(leaseFor(bServices, new AtomicInteger()));
      releaseFirst.countDown();

      assertEquals(io.justsearch.indexerworker.queue.JobQueue.WalkEnumerationOutcome.COMPLETE,
          result.toCompletableFuture().get(5, TimeUnit.SECONDS));
      verify(aIngest, org.mockito.Mockito.times(2)).scanRecordedRoot(any(), any(), any());
      verify(bIngest, never()).scanRecordedRoot(any(), any(), any());
      assertTrue(aClosed.get() >= 5,
          "parent, root tasks, and progress delivery children must all release view A");
    } finally {
      releaseFirst.countDown();
    }
  }

  @Test
  void subscriptionUsesCapturedIngestAndReleasesTheViewAfterCloseOrSetupFailure() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ingest = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      entered.countDown();
      await(release);
      return null;
    }).when(ingest).subscribeIndexingJobs(any(), any(), any());
    var services = services(null, ingest);
    var closed = new AtomicInteger();

    try (var registry = registry();
        var client = client(registry, services, () -> leaseFor(services, closed))) {
      KnowledgeClient.IndexingJobsStream stream = client.subscribeIndexingJobs(
          ignored -> {}, failure -> {}, () -> {}, TestEngineContexts.FOREGROUND);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      stream.close();
      release.countDown();
      awaitClosed(closed, 2);
      verify(ingest).subscribeIndexingJobs(any(), any(), any());
    } finally {
      release.countDown();
    }

    var failedIngest = mock(WorkerIngestService.class);
    doThrow(new IllegalStateException("producer setup failed"))
        .when(failedIngest).subscribeIndexingJobs(any(), any(), any());
    var failedServices = services(null, failedIngest);
    var failure = new AtomicReference<Throwable>();
    var failedClosed = new AtomicInteger();
    try (var registry = registry();
        var client = client(registry, failedServices, () -> leaseFor(failedServices, failedClosed))) {
      client.subscribeIndexingJobs(ignored -> {}, failure::set, () -> {},
          TestEngineContexts.FOREGROUND);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (failure.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
      assertTrue(failure.get() instanceof IllegalStateException);
      awaitClosed(failedClosed, 2);
      verify(failedIngest).subscribeIndexingJobs(any(), any(), any());
    }
  }

  @Test
  void subscriptionRetiresItsCapturedViewAndSignalsBridgeReconnect() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ingest = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      entered.countDown();
      await(release);
      return null;
    }).when(ingest).subscribeIndexingJobs(any(), any(), any());
    var services = services(null, ingest);
    var closed = new AtomicInteger();
    var retirement = new AtomicReference<Runnable>();
    var lease = leaseFor(services, closed);
    when(lease.onRetirement(any())).thenAnswer(invocation -> {
      retirement.set(invocation.getArgument(0));
      return (Runnable) () -> retirement.set(null);
    });
    var error = new AtomicReference<Throwable>();

    try (var registry = registry();
        var client = client(registry, services, () -> lease)) {
      client.subscribeIndexingJobs(ignored -> {}, error::set, () -> {},
          TestEngineContexts.FOREGROUND);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      retirement.get().run();
      release.countDown();
      awaitClosed(closed, 2);
      assertEquals(2, closed.get(), "idle subscription and startup each release their captured view");
      assertTrue(error.get() instanceof io.justsearch.indexerworker.services.WorkerServiceException);
      assertTrue(retirement.get() == null, "closed flow deregisters its retirement listener");
    } finally {
      release.countDown();
    }
  }

  private static KnowledgeServer.ServingLease leaseFor(WorkerAppServices services,
      AtomicInteger closed) {
    var lease = mock(KnowledgeServer.ServingLease.class);
    when(lease.services()).thenReturn(services);
    when(lease.fork()).thenAnswer(ignored -> leaseFor(services, closed));
    when(lease.onRetirement(any())).thenReturn(() -> {});
    doAnswer(ignored -> {
      closed.incrementAndGet();
      return null;
    }).when(lease).close();
    return lease;
  }

  private static WorkerAppServices services(
      io.justsearch.indexerworker.services.WorkerSearchService search,
      WorkerIngestService ingest) {
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    when(services.ingestService()).thenReturn(ingest);
    return services;
  }

  private static EngineKnowledgeClient client(DefaultEngineExecutorRegistry registry,
      WorkerAppServices services,
      java.util.function.Supplier<KnowledgeServer.ServingLease> selected) {
    return new EngineKnowledgeClient(registry, () -> services,
        new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
        () -> {}, new EngineAdmissionController(16, 16, 1), WatchedRootsState.inMemory(), selected);
  }

  private static ExecutorService rootQueue(EngineKnowledgeClient client) throws Exception {
    var field = KnowledgeClient.class.getDeclaredField("walkExecutor");
    field.setAccessible(true);
    return (ExecutorService) field.get(client);
  }

  private static DefaultEngineExecutorRegistry registry() {
    return registry(2, 4);
  }

  private static DefaultEngineExecutorRegistry registry(int backgroundThreads, int queueCapacity) {
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(Map.of(
            "perContextLimit", 16, "aggregateLimit", 16, "retryAfterSeconds", 1,
            "foregroundThreads", 2, "foregroundQueue", queueCapacity,
            "backgroundThreads", backgroundThreads, "backgroundQueue", queueCapacity,
            "timerRegistrations", 32, "directMemoryMiB", 1), new RetainedStateBudget()),
        Duration.ofMillis(100));
  }

  private static void awaitClosed(AtomicInteger closed, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (closed.get() < expected && System.nanoTime() < deadline) Thread.onSpinWait();
    assertTrue(closed.get() >= expected, "captured serving leases did not drain");
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          latch.await();
          return;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
