/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real Engine Budget and caller/worker ownership; only the pre-signal interval is held. */
@Timeout(15)
final class EngineUnaryCancellationGapTest {
  @Test void MD55CancellationOutcomePollPrecedesSignalHandlers() throws Exception {
    outcomePollPrecedesSignalHandlers(false);
  }
  @Test void MD55ExpiryOutcomePollPrecedesSignalHandlers() throws Exception {
    outcomePollPrecedesSignalHandlers(true);
  }
  private void outcomePollPrecedesSignalHandlers(boolean expired) throws Exception {
    var entered = new CountDownLatch(1);
    var workerRelease = new CountDownLatch(1);
    var callerCompleted = new CountDownLatch(1);
    var signalRelease = new CountDownLatch(1);
    var context = new AtomicReference<CallContext>();
    var handlers = new AtomicInteger();
    var search = mock(WorkerSearchService.class);
    when(search.search(any(), any())).thenAnswer(invocation -> {
      CallContext call = invocation.getArgument(1);
      context.set(call); call.onCancel(handlers::incrementAndGet); entered.countDown();
      assertTrue(workerRelease.await(10, TimeUnit.SECONDS));
      return SearchResponse.getDefaultInstance();
    });
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    var admission = new EngineAdmissionController(8, 8, 1);
    var alarm = new CompletableFuture<Runnable>();
    var timer = mock(ScheduledExecutorService.class);
    when(timer.schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenAnswer(invocation -> {
          alarm.complete(invocation.getArgument(0)); return mock(ScheduledFuture.class);
        });
    try (var executors = new TestEngineExecutors();
        var owner = admission.admit(TestEngineContexts.FOREGROUND, false);
        var client = new EngineKnowledgeClient(executors, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 60_000, 100, IpcTelemetry.noop(),
            () -> {}, admission, WatchedRootsState.inMemory())) {
      set(client, "deadlines", timer);
      var caller = new FutureTask<>(() -> client.search("held publisher", 10, owner.context()));
      Thread.ofVirtual().start(caller);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      Runnable expire = alarm.get(3, TimeUnit.SECONDS);
      Object budget = capturedBudget(expire);
      @SuppressWarnings("unchecked")
      Consumer<Throwable> original = (Consumer<Throwable>) get(budget, "fail");
      // Private fault instrumentation wraps the actual caller completion. Production keeps its
      // caller-first order and its existing signal; no product hook or alternate Budget exists.
      set(budget, "fail", (Consumer<Throwable>) failure -> {
        original.accept(failure); callerCompleted.countDown();
        try { assertTrue(signalRelease.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
      });
      Thread cancellation = Thread.ofVirtual().start(() -> {
        if (expired) expire.run(); else owner.cancel("user_stop");
      });
      try {
        assertTrue(callerCompleted.await(3, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> caller.get(1, TimeUnit.SECONDS));
        assertTrue(context.get().deadlineNanos() - System.nanoTime() > 0,
            "cancellation outcome must be visible even while the absolute deadline remains future");
        assertEquals(0, handlers.get(), "handler notification still follows caller completion");
        assertTrue(context.get().cancelled(),
            "an already-decided CANCELLED/EXPIRED outcome must bar a surviving publisher before signal handlers run");
      } finally {
        signalRelease.countDown(); workerRelease.countDown(); cancellation.join(3_000);
      }
      assertFalse(cancellation.isAlive());
      assertEquals(1, handlers.get(), "the existing signal still delivers handlers once");
    } finally { signalRelease.countDown(); workerRelease.countDown(); }
  }

  private static Object capturedBudget(Runnable alarm) throws Exception {
    for (var field : alarm.getClass().getDeclaredFields()) {
      if (field.getType().getSimpleName().equals("Budget")) {
        field.setAccessible(true); return field.get(alarm);
      }
    }
    throw new AssertionError("actual Engine deadline action must retain its own Budget");
  }
  private static Object get(Object object, String name) throws Exception {
    var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
  }
  private static void set(Object object, String name, Object value) throws Exception {
    var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
  }
}
