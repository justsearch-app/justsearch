/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class EngineQueuedRootOwnershipTest {
  @Test
  void initialScanRetainsRequestWorkBeforeQueueing(@TempDir Path directory) throws Exception {
    String previous = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", directory.resolve("data").toString());
    var releaseQueue = new CountDownLatch(1);
    var queueEntered = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var seen = new AtomicReference<CallContext>();
    var seenTrace = new AtomicReference<String>();
    var seenRequest = new AtomicReference<String>();
    var services = mock(WorkerAppServices.class);
    var ingest = mock(WorkerIngestService.class);
    when(services.ingestService()).thenReturn(ingest);
    doAnswer(call -> {
      seen.set(call.getArgument(2));
      seenTrace.set(io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId());
      seenRequest.set(org.slf4j.MDC.get("request_id"));
      java.util.function.Consumer<ScanRootProgress> progress = call.getArgument(1);
      progress.accept(ScanRootProgress.getDefaultInstance());
      return null;
    }).when(ingest).scanRoot(any(ScanRootRequest.class), any(), any());
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100,
            IpcTelemetry.noop(), () -> {}, admission)) {
      var field = KnowledgeClient.class.getDeclaredField("walkExecutor");
      field.setAccessible(true);
      var queue = (ExecutorService) field.get(client);
      queue.execute(() -> {
        queueEntered.countDown();
        try {
          assertTrue(releaseQueue.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      });
      assertTrue(queueEntered.await(2, TimeUnit.SECONDS));
      EngineContext entering;
      String traceId = "4234567890abcdef1234567890abcdef";
      var span = io.opentelemetry.api.trace.Span.wrap(io.opentelemetry.api.trace.SpanContext.create(
          traceId, "4234567890abcdef", io.opentelemetry.api.trace.TraceFlags.getSampled(),
          io.opentelemetry.api.trace.TraceState.getDefault()));
      String previousRequest = org.slf4j.MDC.get("request_id");
      try (var _ = span.makeCurrent();
          var request = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        org.slf4j.MDC.put("request_id", "queued-scan-request");
        entering = request.context();
        request.onCompletion(finished::countDown);
        client.addWatchedRoot("queued-root", directory, entering);
      } finally {
        if (previousRequest == null) org.slf4j.MDC.remove("request_id");
        else org.slf4j.MDC.put("request_id", previousRequest);
      }
      var physicalQueue = ((java.util.concurrent.ThreadPoolExecutor) queue).getQueue();
      assertEquals(1, physicalQueue.size(), "the scan must really wait behind the occupied worker");
      assertEquals(3, physicalQueue.remainingCapacity(), "the four-slot root queue is physically bounded");
      assertEquals(1, admission.activeWorkCount(), "the queued scan must own work after HTTP returns");
      releaseQueue.countDown();
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      assertEquals(entering, seen.get().engineContext(),
          "the actual scan must retain all caller axes and exact work id");
      assertEquals(traceId, seen.get().traceId());
      assertEquals("queued-scan-request", seen.get().requestId());
      assertEquals(traceId, seenTrace.get(), "the queued body must restore the entering OTel context");
      assertEquals("queued-scan-request", seenRequest.get(), "the queued body must restore entering MDC");
      assertEquals(0, admission.activeWorkCount());
    } finally {
      releaseQueue.countDown();
      if (previous == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previous);
    }
  }

  @Test
  void queuedCancellationAndRejectionReleaseOwnership() throws Exception {
    var admission = new EngineAdmissionController(8, 8, 1);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var ran = new java.util.concurrent.atomic.AtomicBoolean();
    try (var registry = registry();
        var client = new EngineKnowledgeClient(registry, () -> mock(WorkerAppServices.class),
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100,
            IpcTelemetry.noop(), () -> {}, admission)) {
      ExecutorService queue = rootQueue(client);
      queue.execute(() -> {
        entered.countDown();
        await(release);
      });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      try (var request = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        client.executeRootWalk(queue, context -> ran.set(true), request.context());
        request.cancel("root_cancelled");
      }
      assertEquals(0, admission.activeWorkCount());
      assertEquals(0, ((java.util.concurrent.ThreadPoolExecutor) queue).getQueue().size());
      release.countDown();
      queue.shutdown();
      assertTrue(queue.awaitTermination(2, TimeUnit.SECONDS));
      assertFalse(ran.get());
      try (var request = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        var failure = assertThrows(io.justsearch.app.api.EngineAdmissionException.class,
            () -> client.executeRootWalk(queue, context -> ran.set(true), request.context()));
        assertEquals(io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT, failure.reason());
      }
      assertEquals(0, admission.activeWorkCount());
    } finally {
      release.countDown();
    }
  }

  @Test
  void shutdownReleasesQueuedWalkButRunningWalkOwnsUntilActualExit() throws Exception {
    var admission = new EngineAdmissionController(8, 8, 1);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var queuedRan = new java.util.concurrent.atomic.AtomicBoolean();
    try (var registry = registry();
        var client = new EngineKnowledgeClient(registry, () -> mock(WorkerAppServices.class),
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100,
            IpcTelemetry.noop(), () -> {}, admission)) {
      ExecutorService queue = rootQueue(client);
      try (var running = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        running.onCompletion(finished::countDown);
        client.executeRootWalk(queue, context -> {
          entered.countDown();
          await(release);
        }, running.context());
      }
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      try (var queued = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        client.executeRootWalk(queue, context -> queuedRan.set(true), queued.context());
      }
      assertEquals(2, admission.activeWorkCount());
      client.close();
      assertEquals(1, admission.activeWorkCount(), "shutdown cannot release a running body early");
      assertFalse(queuedRan.get());
      release.countDown();
      assertTrue(finished.await(2, TimeUnit.SECONDS));
      assertEquals(0, admission.activeWorkCount());
    } finally {
      release.countDown();
    }
  }

  private static ExecutorService rootQueue(EngineKnowledgeClient client) throws Exception {
    var field = KnowledgeClient.class.getDeclaredField("walkExecutor");
    field.setAccessible(true);
    return (ExecutorService) field.get(client);
  }

  @Test
  void persistedRootBatchSharesOneAdmissionBeforeQueueing(@TempDir Path directory) throws Exception {
    String previous = System.getProperty("justsearch.data.dir");
    var data = java.nio.file.Files.createDirectories(directory.resolve("data"));
    var roots = new java.util.ArrayList<java.util.Map<String, String>>();
    for (int i = 0; i < 20; i++) {
      roots.add(java.util.Map.of("path",
          java.nio.file.Files.createDirectories(directory.resolve("root-" + i)).toString()));
    }
    java.nio.file.Files.writeString(data.resolve("watched_roots.json"),
        new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("roots", roots)));
    System.setProperty("justsearch.data.dir", data.toString());
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var services = mock(WorkerAppServices.class);
    var ingest = mock(WorkerIngestService.class);
    when(services.ingestService()).thenReturn(ingest);
    var ids = java.util.concurrent.ConcurrentHashMap.<java.util.UUID>newKeySet();
    var admission = new EngineAdmissionController(16, 64, 1);
    var batchFinished = new CountDownLatch(1);
    doAnswer(call -> {
      var context = ((CallContext) call.getArgument(2)).engineContext();
      if (ids.add(context.workId().orElseThrow())) {
        try (var observer = admission.attach(context)) {
          observer.onCompletion(batchFinished::countDown);
        }
      }
      java.util.function.Consumer<ScanRootProgress> progress = call.getArgument(1);
      progress.accept(ScanRootProgress.getDefaultInstance());
      return null;
    }).when(ingest).scanRoot(any(ScanRootRequest.class), any(), any());
    try (var registry = registry(32);
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100,
            IpcTelemetry.noop(), () -> {}, admission)) {
      var queue = rootQueue(client);
      queue.execute(() -> { entered.countDown(); await(release); });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      client.reindexPersistedRoots(TestEngineContexts.BACKGROUND);
      assertEquals(1, admission.activeWorkCount(), "restoring 20 roots is one admitted batch");
      release.countDown();
      queue.submit(() -> {}).get(5, TimeUnit.SECONDS);
      verify(ingest, times(20)).scanRoot(any(ScanRootRequest.class), any(), any());
      assertEquals(1, ids.size(), "all queued roots retain the same batch work id");
      assertTrue(batchFinished.await(5, TimeUnit.SECONDS), "scan delivery owners must actually exit");
      assertEquals(0, admission.activeWorkCount());
    } finally {
      release.countDown();
      if (previous == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previous);
    }
  }

  private static void await(CountDownLatch release) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          release.await();
          return;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static DefaultEngineExecutorRegistry registry() {
    return registry(4);
  }

  private static DefaultEngineExecutorRegistry registry(int backgroundQueue) {
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(java.util.Map.of(
            "perContextLimit", 8, "aggregateLimit", 8, "retryAfterSeconds", 1,
            "foregroundThreads", 1, "foregroundQueue", 4,
            "backgroundThreads", 1, "backgroundQueue", backgroundQueue,
            "timerRegistrations", 16, "directMemoryMiB", 1),
            new io.justsearch.core.context.RetainedStateBudget()), java.time.Duration.ofMillis(100));
  }
}
