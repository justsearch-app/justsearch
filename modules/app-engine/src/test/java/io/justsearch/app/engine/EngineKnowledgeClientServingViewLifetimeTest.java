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
          TestEngineContexts.FOREGROUND, new io.justsearch.app.services.worker.CancelToken());
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

  private static KnowledgeServer.ServingLease leaseFor(WorkerAppServices services,
      AtomicInteger closed) {
    var lease = mock(KnowledgeServer.ServingLease.class);
    when(lease.services()).thenReturn(services);
    when(lease.fork()).thenAnswer(ignored -> leaseFor(services, closed));
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
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(Map.of(
            "perContextLimit", 16, "aggregateLimit", 16, "retryAfterSeconds", 1,
            "foregroundThreads", 2, "foregroundQueue", 4,
            "backgroundThreads", 2, "backgroundQueue", 4,
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
