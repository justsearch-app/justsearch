/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchPerSourceExecutorOwnershipTest {
  private static final SearchRequest REQUEST =
      SearchRequest.newBuilder().setQuery("ownership").setLimit(4).build();

  @Test
  void ownsStableUrgencyRegistrationsAndClosesBoth() {
    EngineExecutorRegistry registry = mock(EngineExecutorRegistry.class);
    EngineExecutorRegistry.Registration foreground = mock(EngineExecutorRegistry.Registration.class);
    EngineExecutorRegistry.Registration background = mock(EngineExecutorRegistry.Registration.class);
    when(registry.maxConcurrentWork()).thenReturn(7);
    when(registry.register(any())).thenReturn(foreground, background);

    SearchPerSourceExecutor executor = new SearchPerSourceExecutor(registry, mock(EngineAdmissionService.class));
    ArgumentCaptor<EngineExecutorSpec> specs = ArgumentCaptor.forClass(EngineExecutorSpec.class);
    verify(registry, org.mockito.Mockito.times(2)).register(specs.capture());
    assertEquals("head.search-per-source.foreground", specs.getAllValues().get(0).name());
    assertEquals(EngineExecutorSpec.Kind.FOREGROUND, specs.getAllValues().get(0).kind());
    assertEquals(EngineExecutorSpec.Mode.VIRTUAL, specs.getAllValues().get(0).mode());
    assertEquals(7, specs.getAllValues().get(0).maxInstances());
    assertEquals("head.search-per-source.background", specs.getAllValues().get(1).name());
    assertEquals(EngineExecutorSpec.Kind.BACKGROUND, specs.getAllValues().get(1).kind());
    executor.close();
    verify(foreground).close();
    verify(background).close();
  }

  @Test
  void ordinaryAllFailedFanoutUsesTheExistingUnfilteredFallback() {
    FakeAdmission admission = new FakeAdmission();
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenAnswer(invocation -> {
      SearchRequest request = invocation.getArgument(0);
      if (request.getFilters().getMetaSourceCount() > 0) throw new IllegalStateException("ordinary failure");
      return response("fallback");
    });

    try (SearchPerSourceExecutor executor = new SearchPerSourceExecutor(
        new TestEngineExecutors(), admission)) {
      SearchResponse result = executor.execute(
          client, REQUEST, List.of("one", "two"), 4, admission.context());
      assertEquals(List.of("fallback"), result.getResultsList().stream().map(SearchResult::getId).toList());
    }
    verify(client, atLeastOnce()).search(any(SearchRequest.class), any(EngineContext.class));
  }

  @Test
  void wrappedChildErrorPropagatesWithoutOptionalFallback() {
    FakeAdmission admission = new FakeAdmission();
    KnowledgeClient client = mock(KnowledgeClient.class);
    AssertionError fatal = new AssertionError("fatal child failure");
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenThrow(fatal);

    try (SearchPerSourceExecutor executor = new SearchPerSourceExecutor(
        new TestEngineExecutors(), admission)) {
      assertSame(fatal, assertThrows(AssertionError.class,
          () -> executor.execute(client, REQUEST, List.of("one"), 4, admission.context())));
    }
  }

  @Test
  void virtualChildPreservesTheCallersTelemetryContext() {
    var key = io.opentelemetry.context.ContextKey.<String>named("per-source-parent");
    var parent = io.opentelemetry.context.Context.current().with(key, "caller-trace");
    FakeAdmission admission = new FakeAdmission();
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenAnswer(call -> {
      assertEquals("caller-trace", io.opentelemetry.context.Context.current().get(key));
      return response("hit");
    });
    var scope = parent.makeCurrent();
    try (var registry = new TestEngineExecutors();
        var executor = new SearchPerSourceExecutor(registry, admission)) {
      assertEquals("hit", executor.execute(client, REQUEST, List.of("one"), 1,
          admission.context()).getResults(0).getId());
    } finally { scope.close(); }
  }

  @Test
  void backfillCannotHideCapacityCancellationOrFatalFailure() {
    var refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "backfill", 1);
    var cancellation = new java.util.concurrent.CancellationException("abandoned");
    var fatal = new AssertionError("fatal backfill");
    for (Throwable expected : List.of(refusal, cancellation, fatal)) {
      Throwable actual = assertThrows(expected.getClass(), () ->
          SearchPerSourceExecutor.mergeSearchResponses(List.of(response("one")), 4, REQUEST,
              request -> { throw new java.util.concurrent.CompletionException(expected); }));
      assertSame(expected, actual);
    }
  }

  @Test
  void childRefusalPropagatesAndCancelsEarlierAcceptedChild() throws Exception {
    EngineExecutorRegistry registry = mock(EngineExecutorRegistry.class);
    EngineExecutorRegistry.Registration foreground = mock(EngineExecutorRegistry.Registration.class);
    EngineExecutorRegistry.Registration background = mock(EngineExecutorRegistry.Registration.class);
    when(registry.maxConcurrentWork()).thenReturn(2);
    when(registry.register(any())).thenReturn(foreground, background);
    EngineExecutorRejectedException refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "fanout-test", 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    ExecutorService physical = new RefusingExecutor(entered, refusal);
    when(foreground.openVirtual()).thenReturn(physical);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenAnswer(invocation -> {
      entered.countDown();
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException expected) {
        interrupted.countDown();
      }
      return response("accepted");
    });
    FakeAdmission admission = new FakeAdmission();

    try (SearchPerSourceExecutor executor = new SearchPerSourceExecutor(registry, admission)) {
      Throwable failure = assertThrows(EngineExecutorRejectedException.class,
          () -> executor.execute(client, REQUEST, List.of("one", "two"), 4, admission.context()));
      assertSame(refusal, failure);
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }
    ArgumentCaptor<SearchRequest> requests = ArgumentCaptor.forClass(SearchRequest.class);
    verify(client, atLeastOnce()).search(requests.capture(), any(EngineContext.class));
    assertTrue(requests.getAllValues().stream()
        .allMatch(request -> request.getFilters().getMetaSourceCount() > 0));
  }

  @Test
  void cancellationInterruptsChildrenButAdmissionLivesUntilActualExit() throws Exception {
    FakeAdmission admission = new FakeAdmission();
    KnowledgeClient client = mock(KnowledgeClient.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenAnswer(invocation -> {
      entered.countDown();
      while (release.getCount() != 0) {
        try {
          release.await();
        } catch (InterruptedException expected) {
          interrupted.countDown();
        }
      }
      return response("late");
    });

    CompletableFuture<SearchResponse> call = null;
    try {
      try (SearchPerSourceExecutor executor = new SearchPerSourceExecutor(
          new TestEngineExecutors(), admission)) {
        call = CompletableFuture.supplyAsync(() ->
            executor.execute(client, REQUEST, List.of("one"), 4, admission.context()));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        admission.cancel("shutdown");
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        long completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!call.isDone() && System.nanoTime() < completionDeadline) {
          Thread.sleep(5);
        }
        assertTrue(call.isDone(), "caller returns while interrupted child is still draining");
        assertTrue(admission.references() > 0,
            "accepted child retains admission while it ignores interrupt");
        assertThrows(java.util.concurrent.CompletionException.class, call::join);
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (admission.references() != 0 && System.nanoTime() < deadline) {
          Thread.sleep(5);
        }
        assertEquals(0, admission.references());
      }
    } finally {
      release.countDown();
      if (call != null) call.cancel(true);
    }
  }

  private static SearchResponse response(String id) {
    return SearchResponse.newBuilder().setTotalHits(1)
        .addResults(SearchResult.newBuilder().setId(id).build()).build();
  }

  private static final class RefusingExecutor extends AbstractExecutorService {
    private final ExecutorService delegate = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final CountDownLatch firstEntered;
    private final RuntimeException refusal;
    private final AtomicInteger submissions = new AtomicInteger();

    RefusingExecutor(CountDownLatch firstEntered, RuntimeException refusal) {
      this.firstEntered = firstEntered;
      this.refusal = refusal;
    }

    @Override public void execute(Runnable command) {
      if (submissions.incrementAndGet() == 2) {
        try {
          firstEntered.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new java.util.concurrent.CompletionException(interrupted);
        }
        throw refusal;
      }
      delegate.execute(command);
    }

    @Override public void shutdown() { delegate.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }
  }

  private static final class FakeAdmission implements EngineAdmissionService {
    private final AtomicInteger references = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Handle current;

    EngineContext context() {
      return new EngineContext(EngineContext.ClientKind.INTERNAL, "ownership-test",
          Optional.empty(), Optional.empty(), "INTERNAL", "TEST",
          EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    }

    int references() { return references.get(); }

    void cancel(String reason) { current.cancel(reason); }

    @Override public EngineWorkHandle admit(EngineContext context, boolean allowWhileFrozen) {
      return attach(context);
    }

    @Override public EngineWorkHandle attach(EngineContext context) {
      Handle handle = new Handle(context.withWorkId(UUID.randomUUID()));
      current = handle;
      references.incrementAndGet();
      return handle;
    }

    @Override public void cancelInteractive(String reason) { cancel(reason); }
    @Override public int retryAfterSeconds() { return 1; }
    @Override public Limits limits() { return new Limits(4, 4, 1); }
    @Override public int activeWorkCount() { return references(); }

    private final class Handle implements EngineWorkHandle {
      private final EngineContext context;
      private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<String>> callbacks =
          new java.util.concurrent.CopyOnWriteArrayList<>();
      private final AtomicBoolean closed = new AtomicBoolean();
      private volatile String cancellationReason;

      Handle(EngineContext context) { this.context = context; }
      @Override public EngineContext context() { return context; }
      @Override public EngineWorkHandle retain() {
        references.incrementAndGet();
        return new Handle(context);
      }
      @Override public Optional<String> cancellationReason() { return Optional.ofNullable(cancellationReason); }
      @Override public void cancel(String reason) {
        if (cancellationReason != null || !cancelled.compareAndSet(false, true)) return;
        cancellationReason = reason;
        callbacks.forEach(callback -> callback.accept(reason));
      }
      @Override public void waitingClientGone() {}
      @Override public Registration onCancel(java.util.function.Consumer<String> callback) {
        callbacks.add(callback);
        if (cancelled.get()) callback.accept(cancellationReason);
        return () -> callbacks.remove(callback);
      }
      @Override public Registration onBackground(Runnable callback) { return () -> {}; }
      @Override public Registration onCompletion(Runnable callback) { return () -> {}; }
      @Override public void close() { if (closed.compareAndSet(false, true)) references.decrementAndGet(); }
    }
  }
}
