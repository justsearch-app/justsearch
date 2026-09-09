/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class EngineKnowledgeClientExecutorTest {

  @Test
  void closeRetiresEveryLogicalOwnerSoClientCanRestartWithSameRegistry() {
    var services = mock(WorkerAppServices.class);
    try (var registry = registry(1, 1, 1, 4)) {
      var first = newClient(registry, services, new EngineAdmissionController(8, 8, 1));
      var specs =
          registry.snapshot().registrations().stream()
              .collect(java.util.stream.Collectors.toMap(row -> row.spec().name(), row -> row.spec()));
      assertEquals(7, specs.size());
      assertEquals(Kind.BACKGROUND, specs.get("knowledge-client-root-walk").kind());
      assertEquals(Mode.PLATFORM, specs.get("knowledge-client-root-walk").mode());
      assertEquals(Kind.BACKGROUND, specs.get("knowledge-client-periodic-sync").kind());
      assertEquals(Mode.SCHEDULED, specs.get("knowledge-client-periodic-sync").mode());
      assertEquals(Kind.BACKGROUND, specs.get("engine-knowledge-deadlines").kind());
      first.close();
      assertTrue(registry.snapshot().registrations().isEmpty());
      newClient(registry, services, new EngineAdmissionController(8, 8, 1)).close();
      assertTrue(registry.snapshot().registrations().isEmpty());
    }
  }

  @Test
  void blockedForegroundCallDoesNotConsumeBackgroundExecutor() throws Exception {
    var foregroundEntered = new CountDownLatch(1);
    var releaseForeground = new CountDownLatch(1);
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any()))
        .thenAnswer(
            invocation -> {
              var request = (io.justsearch.ipc.SearchRequest) invocation.getArgument(0);
              if (request.getQuery().equals("foreground")) {
                foregroundEntered.countDown();
                assertTrue(releaseForeground.await(5, TimeUnit.SECONDS));
              }
              return SearchResponse.getDefaultInstance();
            });
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var callers = Executors.newFixedThreadPool(2);

    try (var registry = registry(1, 1, 1, 4);
        var client =
            new EngineKnowledgeClient(
                registry,
                () -> services,
                new ForegroundLoadGate(new ForegroundLoad()),
                5_000,
                100,
                IpcTelemetry.noop(),
                () -> {},
                new EngineAdmissionController(8, 8, 1))) {
      var foreground =
          callers.submit(
              () -> client.search("foreground", 10, TestEngineContexts.FOREGROUND));
      assertTrue(foregroundEntered.await(2, TimeUnit.SECONDS));

      var background =
          callers.submit(
              () -> client.search("background", 10, TestEngineContexts.BACKGROUND));
      background.get(2, TimeUnit.SECONDS);
      releaseForeground.countDown();
      foreground.get(2, TimeUnit.SECONDS);
    } finally {
      releaseForeground.countDown();
      callers.shutdownNow();
    }
  }

  @Test
  void foregroundQueueLimitIsTypedAndDoesNotRunOnSubmittingThread() throws Exception {
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any()))
        .thenAnswer(
            invocation -> {
              var request = (io.justsearch.ipc.SearchRequest) invocation.getArgument(0);
              if (request.getQuery().equals("first")) {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
              }
              return SearchResponse.getDefaultInstance();
            });
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var callers = Executors.newFixedThreadPool(2);

    try (var registry = registry(1, 1, 1, 4);
        var client =
            new EngineKnowledgeClient(
                registry,
                () -> services,
                new ForegroundLoadGate(new ForegroundLoad()),
                5_000,
                100,
                IpcTelemetry.noop(),
                () -> {},
                new EngineAdmissionController(8, 8, 1))) {
      var first = callers.submit(() -> client.search("first", 10, TestEngineContexts.FOREGROUND));
      assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
      var queued = callers.submit(() -> client.search("queued", 10, TestEngineContexts.FOREGROUND));

      long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (queuedCallCount(registry) != 1 && System.nanoTime() < queueDeadline) {
        Thread.onSpinWait();
      }
      assertEquals(1, queuedCallCount(registry));
      EngineAdmissionException refusal =
          assertThrows(
              EngineAdmissionException.class,
              () -> client.search("refused", 10, TestEngineContexts.FOREGROUND));
      assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refusal.reason());
      assertEquals(1, refusal.retryAfterSeconds());

      releaseFirst.countDown();
      first.get(2, TimeUnit.SECONDS);
      queued.get(2, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      callers.shutdownNow();
    }
  }

  @Test
  void cancellingNeverStartedQueuedCallReleasesItsAdmissionReference() throws Exception {
    var runningEntered = new CountDownLatch(1);
    var releaseRunning = new CountDownLatch(1);
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any()))
        .thenAnswer(
            invocation -> {
              var request = (io.justsearch.ipc.SearchRequest) invocation.getArgument(0);
              if (request.getQuery().equals("running")) {
                runningEntered.countDown();
                boolean interrupted = false;
                while (true) {
                  try {
                    releaseRunning.await();
                    break;
                  } catch (InterruptedException ignored) {
                    interrupted = true;
                  }
                }
                if (interrupted) Thread.currentThread().interrupt();
              }
              return SearchResponse.getDefaultInstance();
            });
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var callers = Executors.newFixedThreadPool(2);
    var admission = new EngineAdmissionController(3, 3, 1);

    try (var registry = registry(1, 1, 1, 4);
        var client =
            new EngineKnowledgeClient(
                registry,
                () -> services,
                new ForegroundLoadGate(new ForegroundLoad()),
                5_000,
                100,
                IpcTelemetry.noop(),
                () -> {},
                admission);
        var queuedOwner = admission.admit(TestEngineContexts.FOREGROUND, false)) {
      var running =
          callers.submit(() -> client.search("running", 10, TestEngineContexts.FOREGROUND));
      assertTrue(runningEntered.await(2, TimeUnit.SECONDS));
      var queued =
          callers.submit(
              () ->
                  assertThrows(
                      EngineWorkCancelledException.class,
                      () -> client.search("queued", 10, queuedOwner.context())));

      long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (queuedCallCount(registry) != 1 && System.nanoTime() < queueDeadline) {
        Thread.onSpinWait();
      }
      assertEquals(1, queuedCallCount(registry));
      assertEquals(2, admission.activeWorkCount());

      queuedOwner.cancel("queued_cancelled");
      assertEquals("queued_cancelled", queued.get(2, TimeUnit.SECONDS).reasonCode());
      assertEquals(0, queuedCallCount(registry));
      queuedOwner.close();
      assertEquals(1, admission.activeWorkCount());
      try (var replacement = admission.attach(TestEngineContexts.BACKGROUND)) {
        assertEquals(2, admission.activeWorkCount());
        assertTrue(replacement.context().workId().isPresent());
      }

      releaseRunning.countDown();
      running.get(2, TimeUnit.SECONDS);
    } finally {
      releaseRunning.countDown();
      callers.shutdownNow();
    }
  }

  private static int queuedCallCount(DefaultEngineExecutorRegistry registry) {
    return registry.snapshot().registrations().stream()
        .filter(row -> row.spec().name().equals("engine-knowledge-call-foreground"))
        .findFirst()
        .orElseThrow()
        .queuedTasks();
  }

  private static EngineKnowledgeClient newClient(
      DefaultEngineExecutorRegistry registry,
      WorkerAppServices services,
      EngineAdmissionController admission) {
    return new EngineKnowledgeClient(
        registry,
        () -> services,
        new ForegroundLoadGate(new ForegroundLoad()),
        5_000,
        100,
        IpcTelemetry.noop(),
        () -> {},
        admission);
  }

  private static DefaultEngineExecutorRegistry registry(
      int foregroundThreads,
      int foregroundQueue,
      int backgroundThreads,
      int backgroundQueue) {
    Map<String, Integer> execution = new LinkedHashMap<>();
    execution.put("perContextLimit", 8);
    execution.put("aggregateLimit", 8);
    execution.put("retryAfterSeconds", 1);
    execution.put("foregroundThreads", foregroundThreads);
    execution.put("foregroundQueue", foregroundQueue);
    execution.put("backgroundThreads", backgroundThreads);
    execution.put("backgroundQueue", backgroundQueue);
    execution.put("timerRegistrations", 16);
    execution.put("directMemoryMiB", 1);
    return new DefaultEngineExecutorRegistry(
        new EngineResourcePolicy(Map.copyOf(execution), new RetainedStateBudget()),
        Duration.ofSeconds(1));
  }
}
