/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OfflineProcessingOutcome.EmbeddingHandoff;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Deterministic ownership tests for admission, cancellation, refusal, and actual procedure exit. */
@Timeout(20)
class OfflineCoordinatorOwnershipTest {

  @Test
  void queuedCancellationRunsNoBodyAndReleasesOwnershipExactlyOnce() throws Exception {
    CountDownLatch blockerEntered = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    ThreadPoolExecutor executor = singleThreadExecutor();
    executor.execute(() -> {
      blockerEntered.countDown();
      awaitIgnoringInterrupt(releaseBlocker);
    });
    ControlledRegistry registry = new ControlledRegistry(executor);
    HandleFixture handle = new HandleFixture(TestEngineContexts.durableInternal()
        .withWorkId(UUID.randomUUID()));
    AtomicInteger supplierCalls = new AtomicInteger();
    RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
    OfflineCoordinator coordinator = coordinator(registry, handle, reconciler,
        mock(OnlineAiLifecycleControl.class), mock(VduBatchProcessor.class), () -> {
          supplierCalls.incrementAndGet();
          return null;
        });
    try {
      assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));
      CompletionStage<OfflineProcessingOutcome> stage =
          coordinator.startOfflineProcessing(TestEngineContexts.durableInternal(), ignored -> {});

      handle.cancel("queued cancellation");
      handle.cancel("duplicate request");

      assertTrue(failureOf(stage) instanceof CancellationException);
      assertEquals(0, supplierCalls.get());
      verify(reconciler, never()).beginProcedure(any(), any());
      verify(handle.registration).close();
      verify(handle.work).close();
      assertFalse(coordinator.isProcessing());
    } finally {
      releaseBlocker.countDown();
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      coordinator.close();
    }
  }

  @Test
  void runningCancellationRetainsStageGuardAndAdmissionThroughBlockedCleanup() throws Exception {
    ThreadPoolExecutor executor = singleThreadExecutor();
    ControlledRegistry registry = new ControlledRegistry(executor);
    HandleFixture handle = new HandleFixture(TestEngineContexts.durableInternal()
        .withWorkId(UUID.randomUUID()));
    RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
    CountDownLatch cleanupEntered = new CountDownLatch(1);
    CountDownLatch releaseCleanup = new CountDownLatch(1);
    doAnswer(invocation -> {
      cleanupEntered.countDown();
      awaitIgnoringInterrupt(releaseCleanup);
      return null;
    }).when(reconciler).endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    OfflineCoordinator coordinator = coordinator(registry, handle, reconciler,
        mock(OnlineAiLifecycleControl.class), mock(VduBatchProcessor.class), () -> null);
    try {
      CompletionStage<OfflineProcessingOutcome> stage =
          coordinator.startOfflineProcessing(TestEngineContexts.durableInternal(), ignored -> {});
      assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));
      try {
        handle.cancel("running cancellation");

        assertFalse(stage.toCompletableFuture().isDone());
        assertTrue(coordinator.isProcessing());
        verify(handle.registration, never()).close();
        verify(handle.work, never()).close();
        assertThrows(IllegalStateException.class,
            () -> coordinator.startOfflineProcessing(
                TestEngineContexts.internal(), ignored -> {}));
      } finally {
        releaseCleanup.countDown();
      }
      assertTrue(failureOf(stage) instanceof CancellationException);
      verify(handle.registration).close();
      verify(handle.work).close();
      assertFalse(coordinator.isProcessing());
    } finally {
      releaseCleanup.countDown();
      coordinator.close();
    }
  }

  @Test
  void admittedContextWithWorkIdFlowsToEveryProcedurePort() throws Exception {
    ThreadPoolExecutor executor = singleThreadExecutor();
    ControlledRegistry registry = new ControlledRegistry(executor);
    EngineContext caller = TestEngineContexts.durableInternal();
    EngineContext admitted = caller.withWorkId(UUID.randomUUID());
    HandleFixture handle = new HandleFixture(admitted);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.countPendingVdu(admitted)).thenReturn(1);
    when(client.countPendingEmbeddings(admitted)).thenReturn(0);
    VduBatchProcessor batch = mock(VduBatchProcessor.class);
    when(batch.processPendingFiles(same(admitted), any()))
        .thenReturn(outcome(1, 1, 0));
    OnlineAiLifecycleControl inference = mock(OnlineAiLifecycleControl.class);
    when(inference.isOnline()).thenReturn(true);
    OfflineCoordinator coordinator = coordinator(registry, handle, mock(RuntimeReconciler.class),
        inference, batch, () -> client);
    try {
      OfflineProcessingOutcome result =
          await(coordinator.startOfflineProcessing(caller, ignored -> {}));

      assertEquals(1, result.processed());
      verify(client).recoverVduProcessing(same(admitted));
      verify(client).countPendingVdu(same(admitted));
      verify(client).countPendingEmbeddings(same(admitted));
      verify(batch).processPendingFiles(same(admitted), any());
    } finally {
      coordinator.close();
    }
  }

  @Test
  void executorRefusalRetainsCleanupFailureAndReleasesOnce() {
    EngineExecutorRejectedException refusal = new EngineExecutorRejectedException(
        EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "offline-test", 1);
    ControlledRegistry registry = new ControlledRegistry(new RejectingExecutor(refusal));
    HandleFixture handle = new HandleFixture(TestEngineContexts.durableInternal()
        .withWorkId(UUID.randomUUID()));
    IllegalStateException cleanup = new IllegalStateException("detach failed");
    doThrow(cleanup).when(handle.registration).close();
    OfflineCoordinator coordinator = coordinator(registry, handle, mock(RuntimeReconciler.class),
        mock(OnlineAiLifecycleControl.class), mock(VduBatchProcessor.class), () -> null);

    EngineExecutorRejectedException thrown = assertThrows(EngineExecutorRejectedException.class,
        () -> coordinator.startOfflineProcessing(TestEngineContexts.durableInternal(), ignored -> {}));

    assertSame(refusal, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(cleanup, thrown.getSuppressed()[0]);
    verify(handle.registration).close();
    verify(handle.work).close();
    assertFalse(coordinator.isProcessing());
    coordinator.close();
  }

  @Test
  void modeTransitionFailureIsVisibleAfterOwnershipRelease() throws Exception {
    ThreadPoolExecutor executor = singleThreadExecutor();
    ControlledRegistry registry = new ControlledRegistry(executor);
    HandleFixture handle = new HandleFixture(TestEngineContexts.durableInternal()
        .withWorkId(UUID.randomUUID()));
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.countPendingVdu(handle.context)).thenReturn(1);
    RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
    ModeTransitionException transition = new ModeTransitionException(
        ModeTransitionException.Reason.ONLINE_START_FAILED, "mode failed");
    doThrow(transition).when(reconciler).procedureRequireEngine(true);
    OnlineAiLifecycleControl inference = mock(OnlineAiLifecycleControl.class);
    when(inference.isOnline()).thenReturn(false);
    OfflineCoordinator coordinator = coordinator(registry, handle, reconciler, inference,
        mock(VduBatchProcessor.class), () -> client);
    try {
      Throwable failure = failureOf(coordinator.startOfflineProcessing(
          TestEngineContexts.durableInternal(), ignored -> {}));

      assertTrue(failure instanceof IllegalStateException);
      assertSame(transition, failure.getCause());
      verify(handle.work).close();
      assertFalse(coordinator.isProcessing());
    } finally {
      coordinator.close();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void modeEntryCheckpointFailurePreservesCauseAndFatality(boolean fatal) throws Exception {
    ThreadPoolExecutor executor = singleThreadExecutor();
    ControlledRegistry registry = new ControlledRegistry(executor);
    HandleFixture handle = new HandleFixture(TestEngineContexts.durableInternal());
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.countPendingVdu(handle.context)).thenReturn(1);
    RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
    var transition = new ModeTransitionException(
        ModeTransitionException.Reason.ONLINE_START_FAILED, "mode failed");
    doThrow(transition).when(reconciler).procedureRequireEngine(true);
    Throwable checkpoint = fatal ? new AssertionError("checkpoint fatal")
        : new IllegalStateException("checkpoint refused");
    try (var coordinator = coordinator(registry, handle, reconciler,
        mock(OnlineAiLifecycleControl.class), mock(VduBatchProcessor.class), () -> client)) {
      Throwable thrown = failureOf(coordinator.startOfflineProcessing(handle.context, outcome -> {
        if (outcome.blockedReason() == BlockReason.AI_OFFLINE) {
          if (fatal) throw (AssertionError) checkpoint;
          throw (IllegalStateException) checkpoint;
        }
      }));
      assertEquals(1, thrown.getSuppressed().length);
      if (fatal) {
        assertSame(checkpoint, thrown);
        assertSame(transition, thrown.getSuppressed()[0].getCause());
      } else {
        assertSame(transition, thrown.getCause());
        assertSame(checkpoint, thrown.getSuppressed()[0]);
      }
      verify(handle.work).close();
      verify(client, never()).queryPendingVduDocIds(any());
    }
  }

  private static OfflineCoordinator coordinator(EngineExecutorRegistry registry,
      HandleFixture handle, RuntimeReconciler reconciler, OnlineAiLifecycleControl inference,
      VduBatchProcessor batch, java.util.function.Supplier<KnowledgeClient> clients) {
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    when(admission.attach(any())).thenReturn(handle.work);
    return new OfflineCoordinator(registry, admission, inference, reconciler, batch, clients,
        new VduCapabilityState());
  }

  private static OfflineProcessingOutcome outcome(int selected, int processed, int failed) {
    return new OfflineProcessingOutcome(selected, processed, failed, BlockReason.NONE,
        EmbeddingHandoff.NOT_EVALUATED);
  }

  private static OfflineProcessingOutcome await(CompletionStage<OfflineProcessingOutcome> stage)
      throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static Throwable failureOf(CompletionStage<OfflineProcessingOutcome> stage)
      throws Exception {
    try {
      stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
      throw new AssertionError("expected stage failure");
    } catch (CancellationException cancelled) {
      return cancelled;
    } catch (ExecutionException failed) {
      return failed.getCause();
    }
  }

  private static ThreadPoolExecutor singleThreadExecutor() {
    return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().factory());
  }

  private static void awaitIgnoringInterrupt(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static final class HandleFixture {
    final EngineContext context;
    final EngineWorkHandle work = mock(EngineWorkHandle.class);
    final EngineWorkHandle.Registration registration = mock(EngineWorkHandle.Registration.class);
    final AtomicReference<String> reason = new AtomicReference<>();
    final AtomicReference<Consumer<String>> callback = new AtomicReference<>();

    HandleFixture(EngineContext context) {
      this.context = context;
      when(work.context()).thenReturn(context);
      when(work.cancellationReason()).thenAnswer(invocation -> Optional.ofNullable(reason.get()));
      when(work.onCancel(any())).thenAnswer(invocation -> {
        callback.set(invocation.getArgument(0));
        return registration;
      });
    }

    void cancel(String value) {
      if (reason.compareAndSet(null, value)) callback.get().accept(value);
      else callback.get().accept(reason.get());
    }
  }

  private static final class ControlledRegistry implements EngineExecutorRegistry {
    private final ExecutorService executor;
    private final Registration registration = new Registration() {
      @Override public EngineExecutorSpec spec() {
        return new EngineExecutorSpec("head.offline-procedure", EngineExecutorSpec.Kind.BACKGROUND,
            EngineExecutorSpec.Mode.PLATFORM, 1, 8, 1);
      }
      @Override public ExecutorService open(ThreadFactory ignored) { return executor; }
      @Override public ScheduledExecutorService openScheduled(ThreadFactory ignored) {
        throw new UnsupportedOperationException();
      }
      @Override public ExecutorService openVirtual() { throw new UnsupportedOperationException(); }
      @Override public void close() {
        executor.shutdownNow();
        try {
          executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }
    };

    ControlledRegistry(ExecutorService executor) { this.executor = executor; }
    @Override public Registration register(EngineExecutorSpec ignored) { return registration; }
    @Override public Limits limits(EngineExecutorSpec.Kind ignored) { return new Limits(1, 8); }
    @Override public int maxConcurrentWork() { return 8; }
    @Override public int retryAfterSeconds() { return 1; }
    @Override public EngineExecutorSnapshot snapshot() { throw new UnsupportedOperationException(); }
    @Override public void close() { registration.close(); }
  }

  private static final class RejectingExecutor extends AbstractExecutorService {
    private final RuntimeException refusal;
    private volatile boolean shutdown;
    RejectingExecutor(RuntimeException refusal) { this.refusal = refusal; }
    @Override public void execute(Runnable command) { throw refusal; }
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
  }
}
