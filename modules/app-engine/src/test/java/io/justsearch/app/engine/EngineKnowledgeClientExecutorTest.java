/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class EngineKnowledgeClientExecutorTest {

  @Test
  void deadlineRetainsExactServingViewUntilWorkerActuallyExits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var workerInterrupted = new java.util.concurrent.atomic.AtomicBoolean();
    var oldSearch = mock(WorkerSearchService.class);
    when(oldSearch.search(any(), any())).thenAnswer(invocation -> {
      entered.countDown();
      boolean waiting = true;
      while (waiting) {
        try {
          release.await();
          waiting = false;
        } catch (InterruptedException ignored) {
          workerInterrupted.set(true);
        }
      }
      exited.countDown();
      return SearchResponse.getDefaultInstance();
    });
    var oldServices = mock(WorkerAppServices.class);
    when(oldServices.searchService()).thenReturn(oldSearch);
    var oldLease = mock(KnowledgeServer.ServingLease.class);
    when(oldLease.services()).thenReturn(oldServices);
    var newSearch = mock(WorkerSearchService.class);
    when(newSearch.search(any(), any())).thenReturn(SearchResponse.getDefaultInstance());
    var newServices = mock(WorkerAppServices.class);
    when(newServices.searchService()).thenReturn(newSearch);
    var newLease = mock(KnowledgeServer.ServingLease.class);
    when(newLease.services()).thenReturn(newServices);
    var selected = new AtomicReference<>(oldLease);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(2, 2, 1, 4);
        var client = new EngineKnowledgeClient(registry, () -> newServices,
            new ForegroundLoadGate(new ForegroundLoad()), 1_000, 100,
            IpcTelemetry.noop(), () -> {}, admission,
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(), selected::get)) {
      var caller = new java.util.concurrent.FutureTask<>(
          () -> client.search("old", 10, TestEngineContexts.FOREGROUND));
      Thread.ofVirtual().start(caller);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var timeout = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> caller.get(3, TimeUnit.SECONDS));
      assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
          ((io.justsearch.app.api.knowledge.KnowledgeClientException) timeout.getCause()).status());
      org.mockito.Mockito.verify(oldLease, org.mockito.Mockito.never()).close();
      selected.set(newLease);
      client.search("new", 10, TestEngineContexts.FOREGROUND);
      org.mockito.Mockito.verify(newSearch).search(any(), any());
      org.mockito.Mockito.verify(oldSearch).search(any(), any());
      release.countDown();
      assertTrue(exited.await(2, TimeUnit.SECONDS));
      assertFalse(workerInterrupted.get(),
          "deadline cancellation must not interrupt a worker that may hold Lucene's writer lock");
      org.mockito.Mockito.verify(oldLease, org.mockito.Mockito.timeout(2_000)).close();
      org.mockito.Mockito.verify(newLease).close();
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancelledBudgetPreservesAnIndependentWorkerInterruptUntilViewRelease() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var worker = new AtomicReference<Thread>();
    var independentInterrupt = new java.util.concurrent.atomic.AtomicBoolean();
    var interruptAtViewRelease = new java.util.concurrent.atomic.AtomicBoolean();
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any())).thenAnswer(invocation -> {
      worker.set(Thread.currentThread());
      entered.countDown();
      while (release.getCount() != 0) {
        try { release.await(); }
        catch (InterruptedException expected) { independentInterrupt.set(true); }
      }
      if (independentInterrupt.get()) Thread.currentThread().interrupt();
      return SearchResponse.getDefaultInstance();
    });
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var lease = mock(KnowledgeServer.ServingLease.class);
    when(lease.services()).thenReturn(services);
    org.mockito.Mockito.doAnswer(invocation -> {
      interruptAtViewRelease.set(Thread.currentThread().isInterrupted());
      return null;
    }).when(lease).close();
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(1, 1, 1, 4);
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 1_000, 100,
            IpcTelemetry.noop(), () -> {}, admission,
            io.justsearch.app.services.worker.WatchedRootsState.inMemory(), () -> lease)) {
      var caller = new java.util.concurrent.FutureTask<>(
          () -> client.search("interrupt-preservation", 10, TestEngineContexts.FOREGROUND));
      Thread.ofVirtual().start(caller);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var timeout = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> caller.get(3, TimeUnit.SECONDS));
      assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
          ((io.justsearch.app.api.knowledge.KnowledgeClientException) timeout.getCause()).status());
      worker.get().interrupt();
      release.countDown();
      org.mockito.Mockito.verify(lease, org.mockito.Mockito.timeout(2_000)).close();
      assertTrue(independentInterrupt.get(), "the worker must receive the independent interrupt");
      assertTrue(interruptAtViewRelease.get(),
          "budget cleanup must not clear an interrupt it did not issue");
    } finally {
      release.countDown();
    }
  }

  @Test
  void closeRetiresEveryLogicalOwnerSoClientCanRestartWithSameRegistry() {
    var services = mock(WorkerAppServices.class);
    try (var registry = registry(1, 1, 1, 4)) {
      var first = newClient(registry, services, new EngineAdmissionController(8, 8, 1));
      var specs =
          registry.snapshot().registrations().stream()
              .collect(java.util.stream.Collectors.toMap(row -> row.spec().name(), row -> row.spec()));
      assertEquals(8, specs.size());
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void throwingTransportCloseStillRetiresEveryRegistrationAndPreservesFailures(boolean fatal) {
    var services = mock(WorkerAppServices.class);
    Throwable firstFailure = fatal ? new AssertionError("foreground stream close failed")
        : new IllegalStateException("foreground stream close failed");
    var secondFailure = new IllegalStateException("foreground call close failed");
    var attempted = new java.util.ArrayList<String>();
    try (var registry = org.mockito.Mockito.spy(registry(1, 1, 1, 4))) {
      org.mockito.Mockito.doAnswer(invocation -> {
        var registration = (io.justsearch.core.execution.EngineExecutorRegistry.Registration)
            invocation.callRealMethod();
        var observed = org.mockito.Mockito.spy(registration);
        org.mockito.Mockito.doAnswer(close -> {
          String name = registration.spec().name();
          attempted.add(name);
          registration.close();
          if (name.equals("engine-knowledge-stream-foreground")) throw firstFailure;
          if (name.equals("engine-knowledge-call-foreground")) throw secondFailure;
          return null;
        }).when(observed).close();
        return observed;
      }).when(registry).register(any());
      var client = newClient(registry, services, new EngineAdmissionController(8, 8, 1));
      var failure = assertThrows(firstFailure.getClass(), client::close);
      assertTrue(registry.snapshot().registrations().isEmpty(),
          "all base and transport owners must retire despite multiple close failures");
      assertEquals(8, attempted.size());
      org.junit.jupiter.api.Assertions.assertSame(firstFailure, failure);
      org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[] {secondFailure},
          failure.getSuppressed());
      // Base close is one-shot: cleanup must complete on the first attempt, not rely on retry.
      client.close();
      assertEquals(8, attempted.size());
      org.mockito.Mockito.reset(registry);
      newClient(registry, services, new EngineAdmissionController(8, 8, 1)).close();
      assertTrue(registry.snapshot().registrations().isEmpty());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void failureAfterCallerDeadlineIsLoggedAndFatalErrorEscapesWorker(boolean fatal) throws Exception {
    Throwable lateFailure = fatal ? new AssertionError("late worker fatal failure")
        : new IllegalStateException("late worker ordinary failure");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var uncaught = new java.util.concurrent.CompletableFuture<Throwable>();
    var logged = new java.util.concurrent.CompletableFuture<ch.qos.logback.classic.spi.ILoggingEvent>();
    var logger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(EngineKnowledgeClient.class);
    var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
      @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
        if (event.getThrowableProxy() instanceof ch.qos.logback.classic.spi.ThrowableProxy proxy
            && proxy.getThrowable() == lateFailure) logged.complete(event);
      }
    };
    appender.start();
    logger.addAppender(appender);
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any())).thenAnswer(invocation -> {
      var request = (io.justsearch.ipc.SearchRequest) invocation.getArgument(0);
      if (!request.getQuery().equals("late")) return SearchResponse.getDefaultInstance();
      entered.countDown();
      boolean waiting = true;
      while (waiting) {
        try {
          release.await();
          waiting = false;
        } catch (InterruptedException ignored) {
          // Model/native work may finish after its caller has already received the deadline.
        }
      }
      throw lateFailure;
    });
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = org.mockito.Mockito.spy(registry(1, 1, 1, 4))) {
      org.mockito.Mockito.doAnswer(invocation -> {
        var registration = (io.justsearch.core.execution.EngineExecutorRegistry.Registration)
            invocation.callRealMethod();
        if (!registration.spec().name().equals("engine-knowledge-call-foreground")) {
          return registration;
        }
        var observed = org.mockito.Mockito.spy(registration);
        org.mockito.Mockito.doAnswer(open -> {
          java.util.concurrent.ThreadFactory factory = open.getArgument(0);
          return registration.open(task -> {
            Thread thread = factory.newThread(task);
            thread.setUncaughtExceptionHandler((owner, failure) -> uncaught.complete(failure));
            return thread;
          });
        }).when(observed).open(any(java.util.concurrent.ThreadFactory.class));
        org.mockito.Mockito.doAnswer(close -> {
          registration.close();
          return null;
        }).when(observed).close();
        return observed;
      }).when(registry).register(any());
      try (var client = new EngineKnowledgeClient(registry, () -> services,
          new ForegroundLoadGate(new ForegroundLoad()), 1_000, 100, IpcTelemetry.noop(),
          () -> {}, admission)) {
        var caller = new java.util.concurrent.FutureTask<>(
            () -> client.search("late", 10, TestEngineContexts.FOREGROUND));
        Thread.ofVirtual().start(caller);
        assertTrue(entered.await(2, TimeUnit.SECONDS), "worker must enter before caller deadline");
        var timedOut = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> caller.get(3, TimeUnit.SECONDS));
        assertTrue(timedOut.getCause() instanceof io.justsearch.app.api.knowledge.KnowledgeClientException);
        assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
            ((io.justsearch.app.api.knowledge.KnowledgeClientException) timedOut.getCause()).status());
        assertEquals(1, admission.activeWorkCount(), "late body still owns admission after caller exit");
        release.countDown();
        var event = logged.get(3, TimeUnit.SECONDS);
        assertEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("after caller completion"));
        assertEquals(0, admission.activeWorkCount(), "report only after actual-work cleanup");
        if (fatal) {
          org.junit.jupiter.api.Assertions.assertSame(lateFailure, uncaught.get(2, TimeUnit.SECONDS));
        } else {
          client.search("healthy", 10, TestEngineContexts.FOREGROUND);
          assertTrue(!uncaught.isDone(), "ordinary late failures are reported without fatal propagation");
        }
      }
    } finally {
      release.countDown();
      logger.detachAppender(appender);
      appender.stop();
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void expiredNativeCallsLeaveTextSearchAndFetchAvailableWithoutReleasingOwnership(int variant)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new java.util.concurrent.CompletableFuture<Void>();
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any())).thenAnswer(invocation -> {
      var request = (io.justsearch.ipc.SearchRequest) invocation.getArgument(0);
      if (request.getQuery().equals("native")) {
        entered.countDown();
        release.join(); // Native work ignores caller cancellation and interruption.
      }
      return SearchResponse.getDefaultInstance();
    });
    when(search.fetchDocuments(any(), any()))
        .thenReturn(io.justsearch.ipc.FetchDocumentsResponse.getDefaultInstance());
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = registry(1, 1, 1, 4);
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 1_000, 100, IpcTelemetry.noop(),
            () -> {}, admission)) {
      try {
        var request = inferenceRequest(variant);
        var caller = new java.util.concurrent.FutureTask<>(
            () -> client.search(request, TestEngineContexts.FOREGROUND));
        Thread.ofVirtual().start(caller);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var timeout = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> caller.get(3, TimeUnit.SECONDS));
        assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
            ((io.justsearch.app.api.knowledge.KnowledgeClientException) timeout.getCause()).status());
        assertEquals(1, admission.activeWorkCount(), "Issued native work still owns admission");

        var text = new java.util.concurrent.FutureTask<>(() -> {
          client.search("keyword", 10, TestEngineContexts.FOREGROUND);
          client.fetchDocuments(java.util.List.of("doc"), TestEngineContexts.FOREGROUND);
          return true;
        });
        Thread.ofVirtual().start(text);
        assertTrue(text.get(2, TimeUnit.SECONDS), "Text-only APIs must execute while native work stays blocked");
        assertFalse(release.isDone());
        assertEquals(1, admission.activeWorkCount());
        assertThrows(EngineAdmissionException.class,
            () -> client.search(request, TestEngineContexts.FOREGROUND));
        assertThrows(EngineAdmissionException.class,
            () -> client.rerank("q", java.util.List.of("doc"), 0, TestEngineContexts.FOREGROUND));
        assertThrows(EngineAdmissionException.class,
            () -> client.retrieveContext("q", java.util.Set.of("doc"), 1, TestEngineContexts.FOREGROUND));
        assertThrows(EngineAdmissionException.class,
            () -> client.matchCitations("answer", java.util.List.of("doc"), java.util.List.of(0),
                java.util.List.of("passage"), 0.5, TestEngineContexts.FOREGROUND));
        assertEquals(1, admission.activeWorkCount(), "Refusals must release only never-issued work");
      } finally {
        release.complete(null);
      }
    } finally {
      release.complete(null);
    }
  }

  private static io.justsearch.ipc.SearchRequest inferenceRequest(int variant) {
    var request = io.justsearch.ipc.SearchRequest.newBuilder().setQuery("native").setLimit(10);
    var pipeline = io.justsearch.ipc.PipelineConfig.newBuilder();
    switch (variant) {
      case 0 -> request.setPipeline(pipeline.setDenseEnabled(true));
      case 1 -> request.setPipeline(pipeline.setDenseAuto(true));
      case 2 -> request.setPipeline(pipeline.setSpladeEnabled(true));
      case 3 -> request.setPipeline(pipeline.setSparseEnabled(true).setCrossEncoderEnabled(true));
      case 4 -> request.setMode(io.justsearch.ipc.SearchMode.SEARCH_MODE_HYBRID);
      case 5 -> request.setMode(io.justsearch.ipc.SearchMode.SEARCH_MODE_VECTOR);
      case 6 -> request.setMode(io.justsearch.ipc.SearchMode.SEARCH_MODE_SPLADE);
      default -> throw new IllegalArgumentException("Unknown variant");
    }
    return request.build();
  }

  @Test
  void inferencePartitionUsesExistingForegroundThreadBudgetAndRefusesWithoutQueueing() {
    try (var registry = registry(16, 32, 1, 4);
        var client = newClient(registry, mock(WorkerAppServices.class),
            new EngineAdmissionController(8, 8, 1))) {
      var specs = registry.snapshot().registrations().stream().collect(
          java.util.stream.Collectors.toMap(row -> row.spec().name(), row -> row.spec()));
      var text = specs.get("engine-knowledge-call-foreground");
      var inference = specs.get("engine-knowledge-inference-foreground");
      assertEquals(16, text.threadCount() + inference.threadCount());
      assertEquals(8, inference.threadCount());
      assertEquals(0, inference.queueCapacity());
      assertEquals(32, text.queueCapacity());
      assertEquals(Kind.FOREGROUND, inference.kind());
      assertEquals(1, inference.maxInstances());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void reducedAggregateLimitReservesTextAdmissionAcrossInferenceLanes(boolean mixedLanes)
      throws Exception {
    var entered = new CountDownLatch(7);
    var nativeBodies = new java.util.concurrent.atomic.AtomicInteger();
    var release = new java.util.concurrent.CompletableFuture<Void>();
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any())).thenAnswer(invocation -> {
      var request = (io.justsearch.ipc.SearchRequest) invocation.getArgument(0);
      if (request.getQuery().equals("native")) {
        nativeBodies.incrementAndGet();
        entered.countDown();
        release.join();
      }
      return SearchResponse.getDefaultInstance();
    });
    when(search.fetchDocuments(any(), any()))
        .thenReturn(io.justsearch.ipc.FetchDocumentsResponse.getDefaultInstance());
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    // The shipped 16-thread foreground policy with the supported aggregate reduction to eight.
    var admission = new EngineAdmissionController(16, 8, 1);
    try (var registry = registry(16, 16, 16, 16);
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 1_000, 100, IpcTelemetry.noop(),
            () -> {}, admission)) {
      try {
        var callers = new java.util.ArrayList<java.util.concurrent.FutureTask<String>>();
        for (int i = 0; i < 8; i++) {
          var context = io.justsearch.app.services.intent.EngineProvenance.internal("native-" + i,
              io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
              mixedLanes && i % 2 == 0 ? io.justsearch.core.context.EngineContext.Urgency.BACKGROUND
                  : io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
          var caller = new java.util.concurrent.FutureTask<>(() -> {
            try {
              client.search(inferenceRequest(0), context);
              return "completed";
            } catch (EngineAdmissionException refusal) {
              assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refusal.reason());
              return "refused";
            } catch (io.justsearch.app.api.knowledge.KnowledgeClientException expired) {
              assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
                  expired.status());
              return "expired";
            }
          });
          callers.add(caller);
          Thread.ofVirtual().start(caller);
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        int refused = 0;
        int expired = 0;
        for (var caller : callers) {
          String result = caller.get(3, TimeUnit.SECONDS);
          if (result.equals("refused")) refused++;
          if (result.equals("expired")) expired++;
        }
        assertEquals(1, refused, "Inference must leave one aggregate slot for text work");
        assertEquals(7, expired);
        assertEquals(7, nativeBodies.get());
        assertEquals(7, admission.activeWorkCount(), "Expired issued work keeps its owners");
        client.search("keyword", 10, TestEngineContexts.FOREGROUND);
        client.fetchDocuments(java.util.List.of("doc"), TestEngineContexts.FOREGROUND);
        assertEquals(7, admission.activeWorkCount());
        assertFalse(release.isDone(), "Text availability must not depend on native progress");
      } finally {
        release.complete(null);
      }
    } finally {
      release.complete(null);
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
