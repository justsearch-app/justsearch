/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EnginePerSourceOwnershipTest {
  @Test void cancellationRetainsTheRealAggregateSlotUntilChildExit() throws Exception {
    exercise(false);
  }

  @Test void callerInterruptRetainsTheRealAggregateSlotUntilChildExit() throws Exception {
    exercise(true);
  }

  private static void exercise(boolean interruptCaller) throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var result = new CompletableFuture<Throwable>();
    var restored = new AtomicBoolean();
    var client = mock(KnowledgeClient.class);
    when(client.search(any(SearchRequest.class), any(EngineContext.class))).thenAnswer(call -> {
      EngineContext context = call.getArgument(1);
      assertTrue(context.workId().isPresent(), "child must execute with attached work identity");
      entered.countDown();
      while (release.getCount() != 0) {
        try { release.await(); }
        catch (InterruptedException expected) { interrupted.countDown(); }
      }
      return SearchResponse.getDefaultInstance();
    });
    try (var registry = new DefaultEngineExecutorRegistry();
        var executor = new SearchPerSourceExecutor(registry, admission)) {
      var caller = new Thread(() -> {
        try {
          executor.execute(client, SearchRequest.newBuilder().setQuery("one").setLimit(1).build(),
              List.of("source"), 1, TestEngineContexts.FOREGROUND);
          result.complete(new AssertionError("cancelled caller succeeded"));
        } catch (Throwable failure) {
          restored.set(Thread.currentThread().isInterrupted());
          result.complete(failure);
        }
      }, "per-source-ownership-test");
      try {
        caller.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        if (interruptCaller) caller.interrupt();
        else admission.cancelInteractive("test cancellation");
        Throwable failure = result.get(3, TimeUnit.SECONDS);
        if (interruptCaller) {
          assertInstanceOf(java.util.concurrent.CompletionException.class, failure);
          assertInstanceOf(InterruptedException.class, failure.getCause());
          assertTrue(restored.get());
        } else assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertEquals(1, admission.activeWorkCount());
        assertThrows(EngineAdmissionException.class,
            () -> admission.attach(TestEngineContexts.BACKGROUND));
        verify(client, times(1)).search(any(SearchRequest.class), any(EngineContext.class));
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (admission.activeWorkCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, admission.activeWorkCount());
        try (var next = admission.attach(TestEngineContexts.BACKGROUND)) {
          assertTrue(next.context().workId().isPresent());
        }
      } finally {
        release.countDown();
        caller.interrupt();
        caller.join(3000);
      }
    }
  }
}
