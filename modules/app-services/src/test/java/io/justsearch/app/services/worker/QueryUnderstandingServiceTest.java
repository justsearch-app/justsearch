package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineFutures;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryUnderstandingServiceTest {
  private static final EngineContext TEST_CONTEXT =
      io.justsearch.app.services.intent.EngineProvenance.internal(
          "query-understanding-test",
          EngineContext.Survival.INTERACTIVE,
          EngineContext.Urgency.FOREGROUND);

  @Mock OnlineAiService aiService;

  @Test
  void extractIfAvailableSamplesOncePerOperationAndCallsAiOnlyWhenEnabled() throws Exception {
    AtomicInteger samples = new AtomicInteger();
    QueryUnderstandingService service =
        new QueryUnderstandingService(aiService, () -> samples.getAndIncrement() == 0);
    when(aiService.isAvailable()).thenReturn(true);
    when(aiService.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture("{\"query\":\"budget report\"}"));

    CompletableFuture<QueryUnderstandingService.QuResult> enabled =
        service.extractIfAvailable("budget report", null, TEST_CONTEXT);
    CompletableFuture<QueryUnderstandingService.QuResult> disabled =
        service.extractIfAvailable("second query", null, TEST_CONTEXT);

    assertNotNull(enabled);
    assertEquals("budget report", enabled.get().refinedQuery());
    assertNull(disabled);
    assertEquals(2, samples.get());
    verify(aiService, times(1)).isAvailable();
    verify(aiService, times(1)).chatCompletion(any(), anyInt(), any(), any());
  }

  @Test
  void extractRetainsCompletedNullCompatibilityWhenDisabled() throws Exception {
    QueryUnderstandingService service = new QueryUnderstandingService(aiService, () -> false);

    CompletableFuture<QueryUnderstandingService.QuResult> result =
        service.extract("budget report", null, TEST_CONTEXT);

    assertNotNull(result);
    assertNull(result.get());
    verify(aiService, never()).isAvailable();
    verify(aiService, never()).chatCompletion(any(), anyInt(), any(), any());
  }
  @Test
  void cancellingProjectionAbandonsOriginalQueuedCompletionBeforeItCanDispatch() {
    var queued = new AtomicReference<Runnable>();
    var started = new AtomicInteger();
    var released = new AtomicInteger();
    var abandoned = new AtomicInteger();
    var root = EngineFutures.supplyAsync(() -> {
      started.incrementAndGet();
      return "{\"query\":\"budget report\"}";
    }, queued::set, released::incrementAndGet, abandoned::incrementAndGet);
    when(aiService.isAvailable()).thenReturn(true);
    when(aiService.chatCompletion(any(), anyInt(), any(), any())).thenReturn(root);
    var projection = new QueryUnderstandingService(aiService, () -> true)
        .extract("budget report", null, TEST_CONTEXT);

    try {
      assertTrue(projection.cancel(false));
      queued.get().run();
      assertTrue(root.isCancelled(), "the actual completion, not only its projection, is abandoned");
      assertEquals(0, started.get(), "a cancelled queued completion cannot dispatch later");
      assertEquals(1, abandoned.get());
      assertEquals(1, released.get(), "the retained queued reference releases exactly once");
    } finally { root.cancel(false); }
  }

  @Test
  void cancellingProjectionRetainsRunningCompletionUntilActualExit() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var released = new AtomicInteger();
    var abandoned = new AtomicInteger();
    var interrupted = new AtomicInteger();
    var producer = new AtomicReference<Thread>();
    var root = EngineFutures.supplyAsync(() -> {
      entered.countDown();
      while (release.getCount() != 0) {
        try { release.await(); }
        catch (InterruptedException unexpected) { interrupted.incrementAndGet(); }
      }
      return "{\"query\":\"budget report\"}";
    }, command -> {
      var thread = new Thread(command, "qu-projection-producer");
      producer.set(thread);
      thread.start();
    }, () -> { released.incrementAndGet(); exited.countDown(); }, abandoned::incrementAndGet);
    when(aiService.isAvailable()).thenReturn(true);
    when(aiService.chatCompletion(any(), anyInt(), any(), any())).thenReturn(root);
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var projection = new QueryUnderstandingService(aiService, () -> true)
          .extract("budget report", null, TEST_CONTEXT);
      assertTrue(projection.cancel(true));
      assertTrue(root.isCancelled());
      assertEquals(1, abandoned.get());
      assertEquals(0, released.get(), "caller return is not producer exit");
      assertEquals(0, interrupted.get(), "QU forwards cancel(false), even for cancel(true)");
    } finally {
      release.countDown();
      producer.get().join(3000);
      root.cancel(false);
    }
    assertTrue(exited.await(3, TimeUnit.SECONDS));
    assertEquals(1, released.get());
  }

  @Test
  void originalCallerCancellationIsNotAnOrdinaryExtractionFailure() {
    when(aiService.isAvailable()).thenReturn(true);
    when(aiService.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new CancellationException("caller left")));
    var projection = new QueryUnderstandingService(aiService, () -> true)
        .extract("budget report", null, TEST_CONTEXT);
    assertInstanceOf(CancellationException.class,
        assertThrows(CompletionException.class, projection::join).getCause());
  }

  @Test
  void executorRefusalKeepsItsIdentityThroughProjection() {
    var refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "qu-test", 1);
    when(aiService.isAvailable()).thenReturn(true);
    when(aiService.chatCompletion(any(), anyInt(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(refusal));
    var projection = new QueryUnderstandingService(aiService, () -> true)
        .extract("budget report", null, TEST_CONTEXT);
    assertSame(refusal, assertThrows(CompletionException.class, projection::join).getCause());
  }

  @Test
  void noHeadEntrypointKeepsEightSecondRootBudgetAndSuccessfulProjection() throws Exception {
    var budgetNs = new java.util.concurrent.atomic.AtomicLong();
    var root = new CompletableFuture<String>() {
      @Override public CompletableFuture<String> orTimeout(long timeout, TimeUnit unit) {
        budgetNs.set(unit.toNanos(timeout));
        return super.orTimeout(timeout, unit);
      }
    };
    when(aiService.isAvailable()).thenReturn(true);
    when(aiService.chatCompletion(any(), anyInt(), any(), any())).thenReturn(root);
    var projection = new QueryUnderstandingService(aiService, () -> true)
        .extract("budget report", null, TEST_CONTEXT);
    try {
      assertTrue(budgetNs.get() > TimeUnit.SECONDS.toNanos(7));
      assertTrue(budgetNs.get() <= TimeUnit.SECONDS.toNanos(8));
      root.complete("{\"query\":\"budget report\",\"meta_source\":[\"Financial Times\"]}");
      assertEquals("budget report", projection.get().refinedQuery());
      assertEquals(java.util.List.of("Financial Times"), projection.get().boostFilters().metaSource());
    } finally { root.cancel(false); }
  }

}
