/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.Mode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OwnedStreamCancellationTest {
  @Test
  void cancellationClosesActiveHttpBodyAndReleasesModelLockBeforeServerFinishes() throws Exception {
    var firstChunk = new CountDownLatch(1);
    var releaseServer = new CountDownLatch(1);
    var requests = new AtomicInteger();
    var work = new WorkProbe();
    var failure = new CompletableFuture<Throwable>();
    var nextDone = new CompletableFuture<String>();
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(handlers);
      server.createContext("/v1/chat/completions", exchange -> {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(200, 0);
        try (var body = exchange.getResponseBody()) {
          if (requests.incrementAndGet() == 1) {
            body.write("data: {\"choices\":[{\"delta\":{\"content\":\"started\"}}]}\n\n"
                .getBytes(StandardCharsets.UTF_8));
            body.flush();
            try { releaseServer.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
          } else {
            body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          }
        }
      });
      server.start();
      var ops = new OnlineModeOps(new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()), HttpClient.newHttpClient(), new ObjectMapper(), () -> Mode.ONLINE,
          () -> server.getAddress().getPort(), () -> "test-model", () -> "test-model");
      try {
        ops.stream(List.of(Map.of("role", "user", "content", "first")), null, 32,
            chunk -> firstChunk.countDown(), null, ignored -> {}, null,
            ignored -> failure.completeExceptionally(new AssertionError("cancelled stream completed")),
            failure::complete, null, true, work.handle);
        assertTrue(firstChunk.await(3, TimeUnit.SECONDS));
        work.cancel("upgrade_shutdown");
        work.cancel("later_reason");
        work.handle.close();
        assertEquals("upgrade_shutdown",
            assertInstanceOf(EngineWorkCancelledException.class, failure.get(3, TimeUnit.SECONDS)).reasonCode());
        work.finished.get(3, TimeUnit.SECONDS);
        assertEquals(0, work.references.get());
        assertEquals(1, releaseServer.getCount(), "the server has not completed its original response");
        ops.stream(List.of(Map.of("role", "user", "content", "second")), null, 32,
            ignored -> {}, null, ignored -> {}, null, nextDone::complete,
            nextDone::completeExceptionally, null, true, null);
        nextDone.get(3, TimeUnit.SECONDS);
        assertEquals(2, requests.get(), "the cancelled exchange no longer owns the model lock");
      } finally {
        releaseServer.countDown();
        ops.shutdown();
        server.stop(0);
      }
    }
  }

  @Test
  void callbackRetainsCapacityUntilItActuallyExitsAndPrecedesCancellationTerminal() throws Exception {
    var work = new WorkProbe();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var order = new CopyOnWriteArrayList<String>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pump = new StreamCallbackPump(executor, work.handle);
      pump.dispatch(() -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        order.add("callback-exit");
      });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        work.cancel("user_stop");
        work.handle.close();
        pump.close();
        assertEquals(1, work.references.get());
        assertFalse(work.finished.isDone());
      } finally { release.countDown(); }
      pump.awaitDrain();
      work.finished.get(3, TimeUnit.SECONDS);
      assertEquals(List.of("callback-exit"), order);
    }
  }

  @Test
  void alreadyCancelledOwnerDoesNotStartAndRejectedPumpReturnsItsReference() {
    var work = new WorkProbe();
    work.cancel("user_stop");
    try (var owner = new StreamWorkOwner(work.handle, ignored -> {}, ignored -> {})) {
      assertEquals("user_stop", assertThrows(EngineWorkCancelledException.class, owner::start).reasonCode());
    }
    work.handle.close();
    assertEquals(0, work.references.get());
    // A fresh owner reaches submission. The cancelled owner above must never submit a callback.
    var refusedWork = new WorkProbe();
    var executor = Executors.newSingleThreadExecutor();
    executor.shutdown();
    var pump = new StreamCallbackPump(executor, refusedWork.handle);
    pump.dispatch(() -> {});
    assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, pump.failure());
    assertEquals(1, refusedWork.references.get());
    refusedWork.handle.close();
    assertEquals(0, refusedWork.references.get());
  }

  @Test
  void callbackErrorIsReportedAndQueuedCallbacksAreDropped() throws Exception {
    var work = new WorkProbe();
    var laterCalls = new AtomicInteger();
    var failure = new AssertionError("callback failed");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pump = new StreamCallbackPump(executor, work.handle);
      pump.dispatch(() -> { throw failure; });
      pump.dispatch(laterCalls::incrementAndGet);
      pump.awaitDrain();
      assertSame(failure, pump.failure());
      assertEquals(0, laterCalls.get());
      assertEquals(1, work.references.get());
    }
    work.handle.close();
  }

  @Test
  void interruptedCallbackDispatcherCannotReportSuccessfulDrain() throws Exception {
    var work = new WorkProbe();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var laterCalls = new AtomicInteger();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pump = new StreamCallbackPump(executor, work.handle);
      pump.dispatch(() -> {
        entered.countDown();
        try { release.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        pump.dispatch(laterCalls::incrementAndGet);
        executor.shutdownNow();
        pump.awaitDrain();
        assertInstanceOf(java.util.concurrent.CancellationException.class, pump.failure());
        assertEquals(0, laterCalls.get());
        assertEquals(1, work.references.get());
      } finally { release.countDown(); }
    }
    work.handle.close();
  }

  @Test
  void queuedCallbackMovesToBackgroundExecutorAfterDurableClientDetaches() throws Exception {
    var work = new WorkProbe();
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var threads = new CopyOnWriteArrayList<String>();
    try (var foreground = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "callback-foreground"));
        var background = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "callback-background"))) {
      var pump = new StreamCallbackPump(foreground, background, 2, 7, work.handle);
      pump.dispatch(() -> {
        threads.add(Thread.currentThread().getName());
        firstEntered.countDown();
        try {
          releaseFirst.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
      pump.dispatch(() -> threads.add(Thread.currentThread().getName()));
      work.handle.waitingClientGone();
      releaseFirst.countDown();
      pump.awaitDrain();
      assertEquals(List.of("callback-foreground", "callback-background"), threads);
      assertNull(pump.failure());
    }
    work.handle.close();
  }

  @Test
  void callbackBacklogOverflowIsTypedAndRetainsWorkUntilActiveCallbackExits() throws Exception {
    var work = new WorkProbe();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var queuedCalls = new AtomicInteger();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pump = new StreamCallbackPump(executor, executor, 1, 9, work.handle);
      pump.dispatch(() -> {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      pump.dispatch(queuedCalls::incrementAndGet);
      pump.dispatch(queuedCalls::incrementAndGet);

      var refusal =
          assertInstanceOf(
              io.justsearch.core.execution.EngineExecutorRejectedException.class,
              pump.failure());
      assertEquals(
          io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
          refusal.reason());
      assertEquals(9, refusal.retryAfterSeconds());
      work.handle.close();
      assertFalse(work.finished.isDone(), "the active callback still owns the work");
      release.countDown();
      pump.awaitDrain();
      work.finished.get(3, TimeUnit.SECONDS);
      assertEquals(0, queuedCalls.get());
    }
  }

  @Test
  void nonStreamingRequestsUseDisjointUrgencyExecutors() throws Exception {
    var requestEntered = new CountDownLatch(1);
    var releaseResponse = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      exchange.getRequestBody().readAllBytes();
      requestEntered.countDown();
      try {
        releaseResponse.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      byte[] response =
          "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
              .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      try (var body = exchange.getResponseBody()) {
        body.write(response);
      }
    });
    server.start();
    var registry = new io.justsearch.core.execution.TestEngineExecutors();
    var registrations = new InferenceExecutorRegistrations(registry);
    var ops =
        new OnlineModeOps(
            registrations,
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            () -> Mode.ONLINE,
            () -> server.getAddress().getPort(),
            () -> "test-model",
            () -> "test-model");
    var foreground = new WorkProbe();
    var background = new WorkProbe();
    background.handle.waitingClientGone();
    try {
      var foregroundThread =
          ops.chatCompletion(
                  List.of(Map.of("role", "user", "content", "foreground")),
                  16,
                  null,
                  foreground.handle)
              .thenApply(ignored -> Thread.currentThread().getName());
      var backgroundThread =
          ops.chatCompletion(
                  List.of(Map.of("role", "user", "content", "background")),
                  16,
                  null,
                  background.handle)
              .thenApply(ignored -> Thread.currentThread().getName());
      assertTrue(requestEntered.await(3, TimeUnit.SECONDS));
      releaseResponse.countDown();
      assertTrue(foregroundThread.get(3, TimeUnit.SECONDS).startsWith("Inference-FG"));
      assertTrue(backgroundThread.get(3, TimeUnit.SECONDS).startsWith("Inference-BG"));
    } finally {
      releaseResponse.countDown();
      foreground.handle.close();
      background.handle.close();
      ops.shutdown();
      registrations.close();
      registry.close();
      server.stop(0);
    }
  }

  @Test
  void requestQueueRefusalReleasesPreDispatchWorkReference() throws Exception {
    var registry = new RefusingBackgroundRegistry();
    var registrations = new InferenceExecutorRegistrations(registry);
    var ops =
        new OnlineModeOps(
            registrations,
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            () -> Mode.ONLINE,
            () -> 1,
            () -> "test-model",
            () -> "test-model");
    var blockerEntered = new CountDownLatch(1);
    var releaseBlocker = new CountDownLatch(1);
    registry.background.execute(() -> {
      blockerEntered.countDown();
      try {
        releaseBlocker.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    assertTrue(blockerEntered.await(3, TimeUnit.SECONDS));
    var work = new WorkProbe();
    work.handle.waitingClientGone();
    try {
      var refusal =
          assertThrows(
              io.justsearch.core.execution.EngineExecutorRejectedException.class,
              () ->
                  ops.chatCompletion(
                      List.of(Map.of("role", "user", "content", "refuse")),
                      16,
                      null,
                      work.handle));
      assertEquals(
          io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
          refusal.reason());
      assertEquals(1, work.references.get(), "pre-dispatch retained work was returned");
    } finally {
      releaseBlocker.countDown();
      work.handle.close();
      ops.shutdown();
      registrations.close();
      registry.close();
    }
  }

  /** Records transport-owned references; registry identity/refcount races are tested by app-engine. */
  private static final class WorkProbe {
    final EngineWorkHandle handle = new Reference();
    final AtomicInteger references = new AtomicInteger(1);
    final CompletableFuture<Void> finished = new CompletableFuture<>();
    final AtomicReference<String> reason = new AtomicReference<>();
    final AtomicReference<io.justsearch.core.context.EngineContext.Urgency> urgency =
        new AtomicReference<>(io.justsearch.core.context.EngineContext.Urgency.FOREGROUND);
    final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    final class Reference implements EngineWorkHandle {
      private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

      @Override public EngineWorkHandle retain() {
        if (closed.get()) throw new IllegalStateException("closed test reference");
        references.incrementAndGet();
        return new Reference();
      }
      @Override public Optional<String> cancellationReason() { return Optional.ofNullable(reason.get()); }
      @Override public Registration onCancel(Consumer<String> listener) {
        listeners.add(listener);
        if (reason.get() != null) listener.accept(reason.get());
        return () -> listeners.remove(listener);
      }
      @Override public void close() {
        if (closed.compareAndSet(false, true) && references.decrementAndGet() == 0) finished.complete(null);
      }
      @Override public void cancel(String value) { WorkProbe.this.cancel(value); }
      @Override public io.justsearch.core.context.EngineContext context() {
        return new io.justsearch.core.context.EngineContext(
            io.justsearch.core.context.EngineContext.ClientKind.INTERNAL,
            "inference-test",
            Optional.empty(),
            Optional.empty(),
            "internal",
            "test",
            io.justsearch.core.context.EngineContext.Survival.DURABLE,
            urgency.get());
      }
      @Override public void waitingClientGone() {
        urgency.set(io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);
      }
      @Override public Registration onBackground(Runnable callback) { throw new UnsupportedOperationException(); }
      @Override public Registration onCompletion(Runnable callback) { throw new UnsupportedOperationException(); }
    }

    void cancel(String value) {
      if (reason.compareAndSet(null, value)) listeners.forEach(listener -> listener.accept(value));
    }
  }

  private static final class RefusingBackgroundRegistry
      implements io.justsearch.core.execution.EngineExecutorRegistry {
    private final io.justsearch.core.execution.TestEngineExecutors delegate =
        new io.justsearch.core.execution.TestEngineExecutors();
    private ExecutorService background;

    @Override
    public Registration register(io.justsearch.core.execution.EngineExecutorSpec spec) {
      if (!spec.name().equals("inference.request.background")) return delegate.register(spec);
      return new Registration() {
        @Override
        public io.justsearch.core.execution.EngineExecutorSpec spec() {
          return spec;
        }

        @Override
        public ExecutorService open(ThreadFactory factory) {
          background =
              new ThreadPoolExecutor(
                  1,
                  1,
                  0,
                  TimeUnit.MILLISECONDS,
                  new SynchronousQueue<>(),
                  factory,
                  (task, executor) -> {
                    throw new io.justsearch.core.execution.EngineExecutorRejectedException(
                        io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
                        spec.name(),
                        retryAfterSeconds());
                  });
          return background;
        }

        @Override
        public ScheduledExecutorService openScheduled(ThreadFactory factory) {
          throw new IllegalStateException("platform registration");
        }

        @Override
        public ExecutorService openVirtual() {
          throw new IllegalStateException("platform registration");
        }

        @Override
        public void close() {
          if (background != null) background.shutdownNow();
        }
      };
    }

    @Override
    public Limits limits(io.justsearch.core.execution.EngineExecutorSpec.Kind kind) {
      return delegate.limits(kind);
    }

    @Override
    public int retryAfterSeconds() {
      return 5;
    }

    @Override
    public int maxConcurrentWork() {
      return delegate.maxConcurrentWork();
    }

    @Override
    public io.justsearch.core.execution.EngineExecutorSnapshot snapshot() {
      return delegate.snapshot();
    }

    @Override
    public void close() {
      if (background != null) background.shutdownNow();
      delegate.close();
    }
  }
}
