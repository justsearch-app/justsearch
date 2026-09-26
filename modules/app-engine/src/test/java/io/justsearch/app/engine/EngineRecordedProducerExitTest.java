/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.ScanRootProgress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;

@Timeout(15)
final class EngineRecordedProducerExitTest {
  @TempDir Path directory;

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void recordedProducerFailureSettlesStageAndOnlyFatalErrorEscapesWorker(boolean fatal) throws Exception {
    Throwable producerFailure = fatal
        ? new AssertionError("recorded producer fatal failure")
        : new IllegalStateException("Path belongs to another active recorded walk");
    var uncaught = new java.util.concurrent.CompletableFuture<Throwable>();
    var firstWalkThread = new AtomicReference<Thread>();
    var admission = new EngineAdmissionController(8, 8, 1);
    var ingest = mock(WorkerIngestService.class);
    doAnswer(call -> { throw producerFailure; }).when(ingest).scanRecordedRoot(any(), any(), any());
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);

    try (var registry = spy(new DefaultEngineExecutorRegistry())) {
      doAnswer(invocation -> {
        var registration = (io.justsearch.core.execution.EngineExecutorRegistry.Registration)
            invocation.callRealMethod();
        if (!registration.spec().name().equals("knowledge-client-root-walk")) return registration;
        var observed = spy(registration);
        doAnswer(open -> {
          java.util.concurrent.ThreadFactory factory = open.getArgument(0);
          return registration.open(task -> {
            Thread thread = factory.newThread(task);
            firstWalkThread.compareAndSet(null, thread);
            thread.setUncaughtExceptionHandler((owner, failure) -> uncaught.complete(failure));
            return thread;
          });
        }).when(observed).open(any(java.util.concurrent.ThreadFactory.class));
        doAnswer(close -> { registration.close(); return null; }).when(observed).close();
        return observed;
      }).when(registry).register(any());

      try (var client = new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
          admission, OperationAuthority.inMemory().roots());
          var request = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        var plan = new RecordedRootPlan("generation", List.of(
            new RecordedRootPlan.Root(directory, null, true, false, List.of(), List.of())));
        var result = client.enumerateRecordedRoot(
            plan, "recorded-child", 1, request.context(), new CancelToken()).toCompletableFuture();

        var observed = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> result.get(3, TimeUnit.SECONDS));
        var producerStage = assertInstanceOf(java.util.concurrent.CompletionException.class, observed.getCause());
        assertSame(producerFailure, producerStage.getCause(), "the producer's exact causal failure must survive");
        request.close();
        assertEquals(0, admission.activeWorkCount(), "failed producer must release its actual work owner");

        var field = KnowledgeClient.class.getDeclaredField("walkExecutor");
        field.setAccessible(true);
        var executor = (ExecutorService) field.get(client);
        if (fatal) {
          assertSame(producerFailure, uncaught.get(3, TimeUnit.SECONDS),
              "fatal Error must remain visible to the worker's uncaught handler");
          assertNotSame(firstWalkThread.get(), executor.submit(Thread::currentThread).get(3, TimeUnit.SECONDS),
              "the executor must replace the worker terminated by a fatal Error");
        } else {
          assertSame(firstWalkThread.get(), executor.submit(Thread::currentThread).get(3, TimeUnit.SECONDS),
              "a handled producer refusal must leave its worker usable");
          assertFalse(uncaught.isDone(), "a nonfatal producer failure must not reach the uncaught handler");
        }
      }
    }
  }

  @Test
  void cleanupErrorSupersedesBodyRuntimeFailureAndReleasesOwner() throws Exception {
    var bodyFailure = new IllegalStateException("recorded producer body failed");
    var cleanupFailure = new AssertionError("recorded producer owner close failed");
    var uncaught = new java.util.concurrent.CompletableFuture<Throwable>();
    var attaches = new AtomicInteger();
    var taskOwner = new AtomicReference<EngineWorkHandle>();
    var admission = spy(new EngineAdmissionController(8, 8, 1));
    doAnswer(call -> {
      EngineWorkHandle actual = (EngineWorkHandle) call.callRealMethod();
      if (attaches.incrementAndGet() != 1) return actual;
      var observed = spy(actual);
      taskOwner.set(observed);
      doAnswer(close -> { close.callRealMethod(); throw cleanupFailure; }).when(observed).close();
      return observed;
    }).when(admission).attach(any());

    var ingest = mock(WorkerIngestService.class);
    doAnswer(call -> { throw bodyFailure; }).when(ingest).scanRecordedRoot(any(), any(), any());
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);

    try (var registry = spy(new DefaultEngineExecutorRegistry())) {
      doAnswer(invocation -> {
        var registration = (io.justsearch.core.execution.EngineExecutorRegistry.Registration)
            invocation.callRealMethod();
        if (!registration.spec().name().equals("knowledge-client-root-walk")) return registration;
        var observed = spy(registration);
        doAnswer(open -> {
          java.util.concurrent.ThreadFactory factory = open.getArgument(0);
          return registration.open(task -> {
            Thread thread = factory.newThread(task);
            thread.setUncaughtExceptionHandler((owner, failure) -> uncaught.complete(failure));
            return thread;
          });
        }).when(observed).open(any(java.util.concurrent.ThreadFactory.class));
        doAnswer(close -> { registration.close(); return null; }).when(observed).close();
        return observed;
      }).when(registry).register(any());

      try (var client = new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
          admission, OperationAuthority.inMemory().roots());
          var request = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        var plan = new RecordedRootPlan("generation", List.of(
            new RecordedRootPlan.Root(directory, null, true, false, List.of(), List.of())));
        var result = client.enumerateRecordedRoot(
            plan, "recorded-child", 1, request.context(), new CancelToken()).toCompletableFuture();

        var observed = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> result.get(3, TimeUnit.SECONDS));
        var producerStage = assertInstanceOf(java.util.concurrent.CompletionException.class, observed.getCause());
        assertSame(cleanupFailure, producerStage.getCause(), "cleanup Error must become the fatal stage cause");
        assertArrayEquals(new Throwable[] {bodyFailure}, cleanupFailure.getSuppressed(),
            "the earlier body failure must remain attached to the promoted fatal Error");
        assertSame(cleanupFailure, uncaught.get(3, TimeUnit.SECONDS),
            "cleanup Error must reach the worker's uncaught handler");
        verify(taskOwner.get()).close();
        request.close();
        assertEquals(0, admission.activeWorkCount(), "the task owner must release its admission reference");
      }
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void walkSuccessOrFailureStillWaitsForDeliveryCleanup(boolean failWalk, boolean captured) throws Exception {
    var admission = spy(new EngineAdmissionController(8, 8, 1));
    var attachments = new AtomicInteger();
    var cleanupEntered = new CountDownLatch(1);
    var releaseCleanup = new CountDownLatch(1);
    doAnswer(call -> {
      EngineWorkHandle actual = (EngineWorkHandle) call.callRealMethod();
      if (attachments.incrementAndGet() != (captured ? 3 : 2)) return actual;
      var scanOwner = spy(actual);
      doAnswer(retain -> {
        EngineWorkHandle retained = (EngineWorkHandle) retain.callRealMethod();
        var deliveryOwner = spy(retained);
        doAnswer(close -> {
          cleanupEntered.countDown();
          await(releaseCleanup);
          return close.callRealMethod();
        }).when(deliveryOwner).close();
        return deliveryOwner;
      }).when(scanOwner).retain();
      return scanOwner;
    }).when(admission).attach(any());
    var ingest = mock(WorkerIngestService.class);
    var scans = new AtomicInteger();
    doAnswer(call -> {
      scans.incrementAndGet();
      if (failWalk) throw new IllegalStateException("walk body failed");
      java.util.function.Consumer<ScanRootProgress> progress = call.getArgument(1);
      progress.accept(ScanRootProgress.newBuilder().setComplete(true).build());
      return null;
    }).when(ingest).scanRecordedRoot(any(), any(), any());
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
            admission, OperationAuthority.inMemory().roots());
        var request = admission.admit(TestEngineContexts.FOREGROUND, false)) {
      var first = new RecordedRootPlan.Root(directory, null, captured, false, List.of(), List.of());
      var second = new RecordedRootPlan.Root(directory.resolve("second"), null, true, false, List.of(), List.of());
      var plan = new RecordedRootPlan("generation", captured ? List.of(first, second) : List.of(first));
      var result = (captured
          ? client.enumerateCapturedRoots(plan, "recorded-child", 1, request.context(), new CancelToken())
          : client.enumerateRecordedRoot(plan, "recorded-child", 1, request.context(), new CancelToken())).toCompletableFuture();
      assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
      var field = KnowledgeClient.class.getDeclaredField("walkExecutor");
      field.setAccessible(true);
      ((ExecutorService) field.get(client)).submit(() -> {}).get(3, TimeUnit.SECONDS);
      assertFalse(result.isDone(), "the walk exited, but its delivery owner has not");
      assertEquals(1, scans.get(), "another captured root cannot start before prior delivery cleanup exits");
      request.close();
      assertEquals(1, admission.activeWorkCount(), "held delivery must retain exact work");
      releaseCleanup.countDown();
      if (failWalk) assertThrows(java.util.concurrent.ExecutionException.class,
          () -> result.get(3, TimeUnit.SECONDS));
      else assertEquals(io.justsearch.indexerworker.queue.JobQueue.WalkEnumerationOutcome.COMPLETE,
          result.get(3, TimeUnit.SECONDS));
      assertEquals(captured && !failWalk ? 2 : 1, scans.get());
      assertEquals(0, admission.activeWorkCount());
    } finally { releaseCleanup.countDown(); }
  }

  @Test
  void registrationFailureStillClosesOwnerAndSettlesExitExceptionally() throws Exception {
    var registrationFailure = new IllegalStateException("registration close failed");
    var ownerFailure = new IllegalStateException("owner close failed");
    var work = mock(EngineWorkHandle.class);
    var owner = mock(EngineWorkHandle.class);
    var registration = mock(EngineWorkHandle.Registration.class);
    when(work.context()).thenReturn(TestEngineContexts.FOREGROUND);
    when(work.retain()).thenReturn(owner);
    when(work.onCancel(any())).thenReturn(registration);
    doThrow(registrationFailure).when(registration).close();
    doThrow(ownerFailure).when(owner).close();
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> mock(WorkerAppServices.class),
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
            new EngineAdmissionController(8, 8, 1), OperationAuthority.inMemory().roots())) {
      var method = EngineKnowledgeClient.class.getDeclaredMethod("executeOwnedStream",
          EngineWorkHandle.class, Runnable.class, boolean.class);
      method.setAccessible(true);
      var result = (CompletionStage<?>) method.invoke(client, work, (Runnable) () -> {}, false);
      var failure = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> result.toCompletableFuture().get(3, TimeUnit.SECONDS));
      assertSame(registrationFailure, failure.getCause());
      assertArrayEquals(new Throwable[] {ownerFailure}, registrationFailure.getSuppressed());
      verify(registration).close();
      verify(owner).close();
    }
  }

  @Test
  void rejectedDeliverySurfacesCleanupFailureInsteadOfHidingItAsSaturation() throws Exception {
    var cleanupFailure = new IllegalStateException("rejected owner close failed");
    var work = mock(EngineWorkHandle.class);
    var owner = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(TestEngineContexts.FOREGROUND);
    when(work.retain()).thenReturn(owner);
    when(work.onCancel(any())).thenReturn(() -> {});
    doThrow(cleanupFailure).when(owner).close();
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> mock(WorkerAppServices.class),
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
            new EngineAdmissionController(8, 8, 1), OperationAuthority.inMemory().roots())) {
      var field = EngineKnowledgeClient.class.getDeclaredField("foregroundStreamThreads");
      field.setAccessible(true);
      ((ExecutorService) field.get(client)).shutdown();
      var method = EngineKnowledgeClient.class.getDeclaredMethod("executeOwnedStream",
          EngineWorkHandle.class, Runnable.class, boolean.class);
      method.setAccessible(true);
      var observed = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> method.invoke(client, work, (Runnable) () -> fail("rejected task ran"), false));
      assertSame(cleanupFailure, observed.getCause(), "cleanup failure must stay observable on synchronous refusal");
      assertEquals(1, cleanupFailure.getSuppressed().length);
      assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, cleanupFailure.getSuppressed()[0]);
      verify(owner).close();
    }
  }

  @Test
  void rejectedWalkSurfacesCleanupFailureInsteadOfHidingItAsEngineLimit() {
    var cleanupFailure = new IllegalStateException("rejected walk owner close failed");
    var refusal = new java.util.concurrent.RejectedExecutionException("walk queue full");
    var work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(TestEngineContexts.FOREGROUND);
    when(work.onCancel(any())).thenReturn(() -> {});
    doThrow(cleanupFailure).when(work).close();
    var admission = mock(io.justsearch.app.api.EngineAdmissionService.class);
    when(admission.attach(any())).thenReturn(work);
    when(admission.retryAfterSeconds()).thenReturn(1);
    var refusing = mock(ExecutorService.class);
    doThrow(refusal).when(refusing).execute(any());
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> mock(WorkerAppServices.class),
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
            admission, OperationAuthority.inMemory().roots())) {
      var observed = assertThrows(IllegalStateException.class,
          () -> client.executeRootWalk(refusing, ignored -> fail("rejected body ran"), TestEngineContexts.FOREGROUND));
      assertSame(cleanupFailure, observed);
      assertArrayEquals(new Throwable[] {refusal}, cleanupFailure.getSuppressed());
      verify(work).close();
    }
  }

  @Test
  void subscriptionOuterCleanupClosesDeliveryWhenProducerRejectionHasACleanupFailure() throws Exception {
    var admission = spy(new EngineAdmissionController(8, 8, 1));
    var retains = new AtomicInteger();
    var deliveryExited = new CountDownLatch(1);
    var pool = new AtomicReference<ExecutorService>();
    var cleanupFailure = new IllegalStateException("producer release reported failure");
    doAnswer(call -> {
      EngineWorkHandle actual = (EngineWorkHandle) call.callRealMethod();
      var subscription = spy(actual);
      doAnswer(retain -> {
        EngineWorkHandle retained = (EngineWorkHandle) retain.callRealMethod();
        int ordinal = retains.incrementAndGet();
        if (ordinal == 2) return retained;
        var observed = spy(retained);
        if (ordinal == 1) {
          doAnswer(close -> { close.callRealMethod(); deliveryExited.countDown(); return null; }).when(observed).close();
        } else {
          assertEquals(3, ordinal, "delivery, subscription then producer own the only retained references");
          pool.get().shutdown();
          doAnswer(close -> { close.callRealMethod(); throw cleanupFailure; }).when(observed).close();
        }
        return observed;
      }).when(subscription).retain();
      return subscription;
    }).when(admission).attach(any());
    var services = mock(WorkerAppServices.class);
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {},
            admission, OperationAuthority.inMemory().roots())) {
      var field = EngineKnowledgeClient.class.getDeclaredField("backgroundStreamThreads");
      field.setAccessible(true);
      pool.set((ExecutorService) field.get(client));
      assertSame(cleanupFailure, assertThrows(IllegalStateException.class,
          () -> client.subscribeIndexingJobs(frame -> fail("unexpected frame"),
              failure -> {}, () -> {}, TestEngineContexts.BACKGROUND)));
      assertTrue(deliveryExited.await(3, TimeUnit.SECONDS), "outer subscription catch must close the accepted flow");
      assertEquals(0, admission.activeWorkCount());
      verifyNoInteractions(services);
    }
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
  }
}
