/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class EngineWorkCancellationTest {
  @Test
  void subscriptionProducerErrorReachesConsumerAndReleasesEveryOwner() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var load = new ForegroundLoad();
    var completed = new CompletableFuture<Void>();
    var observed = new CompletableFuture<Throwable>();
    var failure = new AssertionError("subscription producer failed");
    var ingest = mock(WorkerIngestService.class);
    doThrow(failure)
        .when(ingest)
        .subscribeIndexingJobs(
            any(SubscribeIndexingJobsRequest.class), any(), any());
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);

    try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client =
            new EngineKnowledgeClient(
                new io.justsearch.core.execution.TestEngineExecutors(),
                () -> services,
                new ForegroundLoadGate(load),
                5_000,
                100,
                IpcTelemetry.noop(),
                () -> {},
                admission)) {
      owner.onCompletion(() -> completed.complete(null));
      client.subscribeIndexingJobs(frame -> fail("unexpected frame"), observed::complete,
          () -> fail("unexpected completion"), owner.context());

      assertSame(failure, observed.get(3, TimeUnit.SECONDS));
      owner.close();
      completed.get(3, TimeUnit.SECONDS);
      assertEquals(0, load.inFlight());
      try (var replacement = admission.attach(TestEngineContexts.FOREGROUND)) {
        assertTrue(replacement.context().workId().isPresent());
      }
    }
  }

  @Test
  void scanSetupFailureReleasesDeliveryOwnerAndPreservesFailure() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var load = new ForegroundLoad();
    var completed = new CompletableFuture<Void>();
    var token = mock(io.justsearch.app.services.worker.CancelToken.class);
    var failure = new IllegalStateException("cancel registration failed");
    doThrow(failure).when(token).onCancel(any());
    var services = mock(WorkerAppServices.class);
    try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client = new EngineKnowledgeClient(
            new io.justsearch.core.execution.TestEngineExecutors(), () -> services,
            new ForegroundLoadGate(load), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
      owner.onCompletion(() -> completed.complete(null));
      assertSame(failure, assertThrows(IllegalStateException.class,
          () -> client.executeScanRoot(io.justsearch.ipc.ScanRootRequest.getDefaultInstance(),
              token, event -> {}, owner.context())));
      owner.close();
      completed.get(3, TimeUnit.SECONDS);
      assertEquals(0, load.inFlight());
      verifyNoInteractions(services);
      try (var replacement = admission.attach(TestEngineContexts.FOREGROUND)) {
        assertTrue(replacement.context().workId().isPresent());
      }
    }
  }

  @Test
  void scanObservationCapacityFailureRemainsTypedAndReleasesWork() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var load = new ForegroundLoad();
    var completed = new CompletableFuture<Void>();
    var failure = new io.justsearch.core.execution.EngineExecutorRejectedException(
        io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
        "head.scan-progress", 1);
    var ingest = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress> progress = invocation.getArgument(1);
      progress.accept(io.justsearch.ipc.ScanRootProgress.newBuilder().setScanId("capacity").build());
      return null;
    }).when(ingest).scanRoot(any(), any(), any());
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client = new EngineKnowledgeClient(executors, () -> services,
            new ForegroundLoadGate(load), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
      owner.onCompletion(() -> completed.complete(null));
      assertSame(failure, assertThrows(io.justsearch.core.execution.EngineExecutorRejectedException.class,
          () -> client.executeScanRoot(io.justsearch.ipc.ScanRootRequest.getDefaultInstance(),
              null, event -> { throw failure; }, owner.context())));
      owner.close();
      completed.get(3, TimeUnit.SECONDS);
      assertEquals(0, load.inFlight());
      try (var replacement = admission.attach(TestEngineContexts.FOREGROUND)) {
        assertTrue(replacement.context().workId().isPresent());
      }
    }
  }

  @Test
  void closingAnIdleStreamClosesFeedAndReleasesWorkWithoutAnotherDelta() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var load = new ForegroundLoad();
    var delivered = new CountDownLatch(1);
    var completed = new CompletableFuture<Void>();
    var closed = new java.util.concurrent.atomic.AtomicInteger();
    var feed = mock(io.justsearch.indexerworker.queue.IndexingJobChangeFeed.class);
    when(feed.subscribeWithSnapshot(any())).thenReturn(
        new io.justsearch.indexerworker.queue.IndexingJobChangeFeed.SnapshotAndSubscription(
            0, java.util.List.of(), closed::incrementAndGet));
    var queue = mock(io.justsearch.indexerworker.queue.JobQueue.class);
    when(queue.indexingJobChangeFeed()).thenReturn(java.util.Optional.of(feed));
    var ingest = new WorkerIngestService(queue, null, null,
        io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), null, null, null, null, null, 0L);
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);
    try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client = new EngineKnowledgeClient(
            new io.justsearch.core.execution.TestEngineExecutors(), () -> services,
            new ForegroundLoadGate(load), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
      owner.onCompletion(() -> completed.complete(null));
      client.subscribeIndexingJobs(frame -> delivered.countDown(), failure -> fail(failure),
          () -> {}, owner.context());
      assertTrue(delivered.await(3, TimeUnit.SECONDS));
      owner.close();
      assertEquals(1, load.inFlight());
      client.close();
      completed.get(3, TimeUnit.SECONDS);
      assertEquals(1, closed.get());
      assertEquals(0, load.inFlight());
      try (var replacement = admission.attach(TestEngineContexts.FOREGROUND)) {
        assertTrue(replacement.context().workId().isPresent());
      }
    }
  }

  @Test
  void cancellationReleasesCallerWithFirstReasonButKeepsCapacityUntilWorkerExits() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var load = new ForegroundLoad();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var completed = new CompletableFuture<Void>();
    var caller = Executors.newSingleThreadExecutor();
    try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client = client(admission, load, 5_000, () -> {
          entered.countDown();
          awaitIgnoringInterrupt(release);
        })) {
      owner.onCompletion(() -> completed.complete(null));
      var result = caller.submit(() -> assertThrows(EngineWorkCancelledException.class,
          () -> client.search("probe", 10, owner.context())));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      owner.cancel("upgrade_shutdown");
      owner.cancel("later_reason");
      assertEquals("upgrade_shutdown", result.get(3, TimeUnit.SECONDS).reasonCode());
      owner.close();
      assertEquals(1, load.inFlight(), "an uncooperative worker still occupies the gauge");
      assertThrows(EngineAdmissionException.class, () -> admission.attach(TestEngineContexts.FOREGROUND));
      release.countDown();
      completed.get(3, TimeUnit.SECONDS);
      assertEquals(0, load.inFlight());
      try (var replacement = admission.attach(TestEngineContexts.FOREGROUND)) {
        assertTrue(replacement.context().workId().isPresent());
      }
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  void deadlineStillWinsWhileCompletionCleanupIsRunning() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var load = new ForegroundLoad();
    var bodyEntered = new CountDownLatch(1);
    var bodyRelease = new CountDownLatch(1);
    var cleanupEntered = new CountDownLatch(1);
    var cleanupRelease = new CountDownLatch(1);
    var caller = Executors.newSingleThreadExecutor();
    try (var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client = client(admission, load, 100, () -> {
          bodyEntered.countDown();
          awaitIgnoringInterrupt(bodyRelease);
        })) {
      owner.onCompletion(() -> {
        cleanupEntered.countDown();
        awaitIgnoringInterrupt(cleanupRelease);
      });
      var context = owner.context();
      var result = caller.submit(() -> assertThrows(KnowledgeClientException.class,
          () -> client.search("probe", 10, context)));
      assertTrue(bodyEntered.await(3, TimeUnit.SECONDS));
      owner.close();
      bodyRelease.countDown();
      assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
      assertEquals(KnowledgeClientException.Status.DEADLINE_EXCEEDED,
          result.get(3, TimeUnit.SECONDS).status());
    } finally {
      bodyRelease.countDown();
      cleanupRelease.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  void transportCloseCannotDiscardTheOnlyDeadlineAndStrandTheCaller() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var caller = Executors.newSingleThreadExecutor();
    try (var client = client(admission, new ForegroundLoad(), 100, () -> {
      entered.countDown();
      awaitIgnoringInterrupt(release);
    })) {
      var result = caller.submit(() -> assertThrows(KnowledgeClientException.class,
          () -> client.search("probe", 10, TestEngineContexts.BACKGROUND)));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      client.close();
      assertEquals(KnowledgeClientException.Status.DEADLINE_EXCEEDED,
          result.get(3, TimeUnit.SECONDS).status());
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  private static EngineKnowledgeClient client(EngineAdmissionController admission, ForegroundLoad load,
      long deadline, Runnable body) {
    var services = mock(WorkerAppServices.class);
    var search = mock(WorkerSearchService.class);
    when(services.searchService()).thenReturn(search);
    when(search.search(any(), any())).thenAnswer(call -> {
      body.run();
      return SearchResponse.getDefaultInstance();
    });
    return new EngineKnowledgeClient(
        new io.justsearch.core.execution.TestEngineExecutors(), () -> services,
        new ForegroundLoadGate(load), deadline, 100, IpcTelemetry.noop(), () -> {}, admission);
  }

  private static void awaitIgnoringInterrupt(CountDownLatch latch) {
    boolean done = false;
    while (!done) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release missing");
        done = true;
      } catch (InterruptedException expected) {
        // Deliberately model a worker that cannot acknowledge cancellation until cleanup finishes.
      }
    }
  }
}
