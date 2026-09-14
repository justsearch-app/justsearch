/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adapter contract for one accepted recorded root. The worker service is mocked only at the real
 * {@link WorkerIngestService#scanRecordedRoot} boundary; the Engine admission, root-walk executor,
 * ownership, delivery handoff, cancellation, and deadline machinery are real.
 */
@Timeout(15)
final class EngineRecordedIngestionProducerTest {
  private static final String KEY = "recorded-child-0001";
  private static final long EPOCH = 37L;
  private static final String GENERATION = "serving-generation-37";

  @Test
  void completeRecordedRootPreservesFrozenIdentityPolicyAndCallerContext(@TempDir Path directory)
      throws Exception {
    Path root = Files.createDirectory(directory.resolve("documents"));
    Path excluded = root.resolve("private");
    RecordedRootPlan plan = new RecordedRootPlan(GENERATION, List.of(
        new RecordedRootPlan.Root(root, "documents", true, false, List.of("*.tmp"), List.of(excluded))));
    EngineContext context = TestEngineContexts.FOREGROUND;
    var capturedScan = new AtomicReference<WorkerIngestService.RecordedRootScan>();
    var capturedContext = new AtomicReference<CallContext>();
    var ingest = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      capturedScan.set(invocation.getArgument(0));
      capturedContext.set(invocation.getArgument(2));
      invocation.<java.util.function.Consumer<ScanRootProgress>>getArgument(1).accept(
          ScanRootProgress.newBuilder().setComplete(true).build());
      return null;
    }).when(ingest).scanRecordedRoot(any(), any(), any());
    var services = services(ingest);
    var admission = new EngineAdmissionController(8, 8, 1);

    try (var registry = new DefaultEngineExecutorRegistry();
        var client = client(registry, () -> services, admission, 5_000)) {
      CompletionStage<JobQueue.WalkEnumerationOutcome> result =
          client.enumerateRecordedRoot(plan, KEY, EPOCH, context, new CancelToken());

      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, result.toCompletableFuture()
          .get(5, TimeUnit.SECONDS), "only a complete terminal frame may complete the walk");
      WorkerIngestService.RecordedRootScan scan = capturedScan.get();
      assertEquals(root.toAbsolutePath().normalize().toString(), scan.request().getRootPath(),
          "the frozen root path must reach the worker");
      assertEquals("documents", scan.request().getCollection(),
          "the frozen collection policy must reach the worker");
      assertEquals(ScanMode.SCAN_MODE_FORCE_REINDEX, scan.request().getMode(),
          "the frozen force policy must reach the worker");
      assertEquals(List.of("*.tmp"), scan.request().getExcludeGlobsList(),
          "the frozen glob policy must reach the worker");
      assertEquals(KEY, scan.operationKey(), "the child key must be immutable at the service edge");
      assertEquals(new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL"),
          capturedContext.get().provenance(), "exact attribution must reach service admission");
      assertEquals(EPOCH, scan.epoch(), "the issued enumeration epoch must be preserved");
      assertEquals(GENERATION, scan.expectedGeneration(),
          "the captured serving generation must be preserved");
      assertFalse(scan.singleFile(), "the root shape must remain a directory");
      assertEquals(List.of(excluded.toAbsolutePath().normalize()), scan.excludedSubtrees(),
          "the frozen nested-root boundary must reach the worker");
      assertEquals(context.withWorkId(capturedContext.get().engineContext().workId().orElseThrow()),
          capturedContext.get().engineContext(),
          "the admitted caller context axes must be passed into the worker call");
    }
  }

  @Test
  void queuedCancellationRemovesProducerBeforeMutableServiceIsResolved(@TempDir Path directory)
      throws Exception {
    Path root = Files.createDirectory(directory.resolve("queued"));
    RecordedRootPlan plan = plan(root);
    var serviceAEntered = new CountDownLatch(1);
    var releaseServiceA = new CountDownLatch(1);
    var serviceBCalls = new AtomicInteger();
    var ingestA = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      serviceAEntered.countDown();
      await(releaseServiceA);
      complete(invocation);
      return null;
    }).when(ingestA).scanRecordedRoot(any(), any(), any());
    var ingestB = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      serviceBCalls.incrementAndGet();
      complete(invocation);
      return null;
    }).when(ingestB).scanRecordedRoot(any(), any(), any());
    var servicesA = services(ingestA);
    var servicesB = services(ingestB);
    var current = new AtomicReference<WorkerAppServices>(servicesA);
    var admission = new EngineAdmissionController(8, 8, 1);

    try (var registry = boundedRegistry();
        var client = client(registry, current::get, admission, 5_000)) {
      CompletionStage<JobQueue.WalkEnumerationOutcome> first =
          client.enumerateRecordedRoot(plan, KEY + "-running", EPOCH, TestEngineContexts.FOREGROUND,
              new CancelToken());
      assertTrue(serviceAEntered.await(2, TimeUnit.SECONDS),
          "the first producer must hold the real bounded root-walk worker");

      CancelToken queuedToken = new CancelToken();
      CompletionStage<JobQueue.WalkEnumerationOutcome> queued =
          client.enumerateRecordedRoot(plan, KEY + "-queued", EPOCH, TestEngineContexts.FOREGROUND,
              queuedToken);
      current.set(servicesB);
      var saturated = assertThrows(EngineAdmissionException.class,
          () -> client.enumerateRecordedRoot(plan, KEY + "-saturated", EPOCH,
              TestEngineContexts.FOREGROUND, new CancelToken()));
      assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, saturated.reason());
      assertEquals(2, admission.activeWorkCount(), "rejection must release its newly retained owner");
      queuedToken.cancel("queued producer replaced");
      awaitDone(queued);
      assertTrue(queued.toCompletableFuture().isCompletedExceptionally(),
          "queued cancellation must complete the producer exceptionally");
      verify(ingestB, never()).scanRecordedRoot(any(), any(), any());

      releaseServiceA.countDown();
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, first.toCompletableFuture()
          .get(5, TimeUnit.SECONDS));

      CompletionStage<JobQueue.WalkEnumerationOutcome> replacement =
          client.enumerateRecordedRoot(plan, KEY + "-replacement", EPOCH,
              TestEngineContexts.FOREGROUND, new CancelToken());
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, replacement.toCompletableFuture()
          .get(5, TimeUnit.SECONDS), "a later call must use the current service supplier");
      assertEquals(1, serviceBCalls.get(), "the mutable service supplier must be read per call");
      assertEquals(0, admission.activeWorkCount());
    } finally {
      releaseServiceA.countDown();
    }
  }

  @Test
  void executorRejectionCannotReachRecordedWorkerService(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectory(directory.resolve("rejected"));
    var ingest = mock(WorkerIngestService.class);
    var services = services(ingest);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = client(registry, () -> services, admission, 5_000)) {
      client.close();
      EngineAdmissionException failure = assertThrows(EngineAdmissionException.class,
          () -> client.enumerateRecordedRoot(plan(root), KEY, EPOCH,
              TestEngineContexts.FOREGROUND, new CancelToken()));
      assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, failure.reason());
      verifyNoInteractions(services, ingest);
    }
  }

  @Test
  void runningCancellationWaitsForSynchronousWorkerExit(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectory(directory.resolve("cancelled"));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var seenContext = new AtomicReference<CallContext>();
    var ingest = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      seenContext.set(invocation.getArgument(2));
      entered.countDown();
      await(release);
      complete(invocation);
      return null;
    }).when(ingest).scanRecordedRoot(any(), any(), any());
    var services = services(ingest);
    var admission = new EngineAdmissionController(8, 8, 1);
    CancelToken token = new CancelToken();

    try (var registry = new DefaultEngineExecutorRegistry();
        var client = client(registry, () -> services, admission, 5_000)) {
      CompletionStage<JobQueue.WalkEnumerationOutcome> result =
          client.enumerateRecordedRoot(plan(root), KEY, EPOCH, TestEngineContexts.FOREGROUND, token);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      token.cancel("caller disconnected");
      assertTrue(awaitCondition(() -> seenContext.get() != null && seenContext.get().cancelled(),
          Duration.ofSeconds(2)), "worker context must observe producer cancellation");
      assertFalse(result.toCompletableFuture().isDone(),
          "cancellation must not publish completion before the synchronous worker exits");
      release.countDown();
      assertEquals(JobQueue.WalkEnumerationOutcome.CANCELLED,
          result.toCompletableFuture().get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
  }

  @Test
  void deadlineCancellationWaitsForSynchronousWorkerExit(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectory(directory.resolve("deadline"));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var seenContext = new AtomicReference<CallContext>();
    var ingest = mock(WorkerIngestService.class);
    doAnswer(invocation -> {
      seenContext.set(invocation.getArgument(2));
      entered.countDown();
      await(release);
      complete(invocation);
      return null;
    }).when(ingest).scanRecordedRoot(any(), any(), any());
    var services = services(ingest);
    var admission = new EngineAdmissionController(8, 8, 1);

    try (var registry = new DefaultEngineExecutorRegistry();
        var client = client(registry, () -> services, admission, 5)) {
      CompletionStage<JobQueue.WalkEnumerationOutcome> result =
          client.enumerateRecordedRoot(plan(root), KEY, EPOCH, TestEngineContexts.FOREGROUND,
              new CancelToken());
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTrue(awaitCondition(() -> seenContext.get() != null && seenContext.get().cancelled(),
          Duration.ofSeconds(2)), "the long-running deadline must cancel the worker context");
      assertFalse(result.toCompletableFuture().isDone(),
          "deadline cancellation must wait for actual service exit");
      release.countDown();
      assertEquals(JobQueue.WalkEnumerationOutcome.CANCELLED,
          result.toCompletableFuture().get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
  }

  @Test
  void missingIncompleteAndRefusedTerminalsCannotComplete(@TempDir Path directory) throws Exception {
    Path root = Files.createDirectory(directory.resolve("terminal"));
    List<String> kinds = List.of("missing", "incomplete", "refused");
    var admission = new EngineAdmissionController(8, 8, 1);

    try (var registry = new DefaultEngineExecutorRegistry();
        var client = client(registry, () -> currentServices.get(), admission, 5_000)) {
      for (String kind : kinds) {
        var ingest = mock(WorkerIngestService.class);
        doAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          var sink = (java.util.function.Consumer<ScanRootProgress>) invocation.getArgument(1);
          switch (kind) {
            case "incomplete" -> sink.accept(ScanRootProgress.newBuilder().setComplete(false).build());
            case "refused" -> sink.accept(ScanRootProgress.newBuilder().setComplete(true)
                .setTerminalReasonCode("RECORDED_REFUSED").build());
            case "missing" -> { }
            default -> throw new AssertionError(kind);
          }
          return null;
        }).when(ingest).scanRecordedRoot(any(), any(), any());
        currentServices.set(services(ingest));

        JobQueue.WalkEnumerationOutcome outcome = client.enumerateRecordedRoot(
            plan(root), KEY + "-" + kind, EPOCH, TestEngineContexts.FOREGROUND, new CancelToken())
            .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, outcome,
            kind + " terminal evidence must not become COMPLETE");
      }
    }
  }

  private final AtomicReference<WorkerAppServices> currentServices = new AtomicReference<>();

  private static DefaultEngineExecutorRegistry boundedRegistry() {
    return new DefaultEngineExecutorRegistry(new EngineResourcePolicy(java.util.Map.of(
        "perContextLimit", 8, "aggregateLimit", 8, "retryAfterSeconds", 1,
        "foregroundThreads", 1, "foregroundQueue", 4,
        "backgroundThreads", 1, "backgroundQueue", 1,
        "timerRegistrations", 16, "directMemoryMiB", 1),
        new io.justsearch.core.context.RetainedStateBudget()), Duration.ofMillis(100));
  }

  private static RecordedRootPlan plan(Path root) {
    return new RecordedRootPlan(GENERATION, List.of(new RecordedRootPlan.Root(
        root, "documents", false, false, List.of("*.tmp"), List.of())));
  }

  private static WorkerAppServices services(WorkerIngestService ingest) {
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);
    return services;
  }

  private static EngineKnowledgeClient client(
      DefaultEngineExecutorRegistry registry,
      java.util.function.Supplier<WorkerAppServices> services,
      EngineAdmissionController admission, long deadlineMs) {
    return new EngineKnowledgeClient(registry, services,
        new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()),
        deadlineMs, 100, IpcTelemetry.noop(), () -> {}, admission, WatchedRootsState.inMemory());
  }

  private static void complete(org.mockito.invocation.InvocationOnMock invocation) {
    @SuppressWarnings("unchecked")
    var sink = (java.util.function.Consumer<ScanRootProgress>) invocation.getArgument(1);
    sink.accept(ScanRootProgress.newBuilder().setComplete(true).build());
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

  private static void awaitDone(CompletionStage<?> stage) throws InterruptedException {
    assertTrue(awaitCondition(() -> stage.toCompletableFuture().isDone(), Duration.ofSeconds(2)),
        "queued cancellation must finish its owned task");
  }

  private static boolean awaitCondition(java.util.function.BooleanSupplier condition,
      Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    return condition.getAsBoolean();
  }
}
