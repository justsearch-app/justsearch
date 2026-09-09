/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineFutures;
import io.justsearch.core.execution.EngineTaskGroup;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class EngineFanoutOwnershipTest {
  @Test void laterRefusalReturnsPromptlyButRetainsAdmissionAndPacingUntilChildExit() throws Exception {
    exercise(false);
  }

  @Test void callerInterruptReturnsPromptlyButRetainsAdmissionAndPacingUntilChildExit() throws Exception {
    exercise(true);
  }

  private static void exercise(boolean interruptCaller) throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var load = new ForegroundLoad();
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var submissions = new AtomicInteger();
    var laterBodies = new AtomicInteger();
    var refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "fanout-test", 3);
    var services = mock(WorkerAppServices.class);
    var search = mock(WorkerSearchService.class);
    when(services.searchService()).thenReturn(search);
    var outcome = new CompletableFuture<Throwable>();
    var callerInterrupted = new AtomicBoolean();
    try (var childExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), (task, executor) -> { throw refusal; });
        var registry = new TestEngineExecutors();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(load), 30_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
      when(search.search(any(), any())).thenAnswer(invocation -> {
        CallContext context = invocation.getArgument(1);
        try (var group = EngineTaskGroup.open(() -> childExecutor, context.childLifetime())) {
          submissions.incrementAndGet();
          var first = group.submit(() -> {
            entered.countDown();
            while (release.getCount() != 0) {
              try { release.await(); }
              catch (InterruptedException expected) { interrupted.countDown(); }
            }
            return 1;
          });
          if (interruptCaller) {
            EngineFutures.await(first);
          } else {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            submissions.incrementAndGet();
            group.submit(laterBodies::incrementAndGet);
            submissions.incrementAndGet();
            group.submit(laterBodies::incrementAndGet);
          }
        }
        return SearchResponse.getDefaultInstance();
      });
      var caller = new Thread(() -> {
        try {
          client.search("fanout", 1, TestEngineContexts.FOREGROUND);
          outcome.complete(new AssertionError("call succeeded"));
        } catch (Throwable failure) {
          callerInterrupted.set(Thread.currentThread().isInterrupted());
          outcome.complete(failure);
        }
      }, "fanout-owner-test");
      try {
        caller.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        if (interruptCaller) caller.interrupt();
        Throwable failure = outcome.get(3, TimeUnit.SECONDS);
        if (interruptCaller) {
          assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
          assertTrue(callerInterrupted.get());
        } else {
          assertSame(refusal, failure, "preserve the exact refusal without replay");
        }
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertEquals(interruptCaller ? 1 : 2, submissions.get());
        assertEquals(0, laterBodies.get());
        assertThrows(EngineAdmissionException.class, () -> admission.attach(TestEngineContexts.BACKGROUND));
        assertEquals(1, load.inFlight(), "accepted child is still foreground work");
        assertEquals(1, load.startedTotal(), "a fanout does not wrap the work twice");
        release.countDown();
        assertTrue(childExecutor.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(0, load.inFlight());
        try (var next = admission.attach(TestEngineContexts.BACKGROUND)) {
          assertNotNull(next.context().workId().orElseThrow());
        }
      } finally {
        release.countDown();
        caller.interrupt();
        caller.join(3000);
      }
    }
  }
}
