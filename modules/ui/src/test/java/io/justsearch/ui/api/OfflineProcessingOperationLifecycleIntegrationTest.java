/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKind;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.brainruntime.BrainRuntimeServiceImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.TriggerOfflineProcessingHandler;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.vdu.VduBatchProcessor;
import io.justsearch.app.services.vdu.VduCapabilityState;
import io.justsearch.app.services.vdu.VduMetricCatalog;
import io.justsearch.app.services.vdu.VduProcessor;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end durable operation proof for the C2-2 offline procedure owner. */
@Timeout(20)
final class OfflineProcessingOperationLifecycleIntegrationTest {
  @TempDir Path directory;

  @Test
  void completedOutcomePublishesOnlyAfterCleanupReleasesAdmission() throws Exception {
    try (var fixture = new Fixture(directory.resolve("complete.db"))) {
      Path first = fixture.file("first.png");
      Path second = fixture.file("second.png");
      when(fixture.client.queryPendingVduDocIds(any()))
          .thenReturn(List.of(first.toString(), second.toString()));
      when(fixture.client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
      when(fixture.processor.process(any(Path.class), any()))
          .thenReturn(new VduProcessor.VduResult("acknowledged text", "{}", 1));
      when(fixture.client.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
          .thenReturn(true);

      var running = fixture.start();
      fixture.assertCleanupBoundary(running, 2, 0, "\"remaining\":0");
      fixture.releaseCleanup.countDown();

      OperationRecord terminal = fixture.await(running);
      assertEquals(OperationState.COMPLETE, terminal.state());
      assertEquals("SUCCESS", terminal.receipt().code());
      assertEquals(0, fixture.admission.activeWorkCount());
      fixture.assertOneAdmittedContextReachedEveryPort();
    }
  }

  @Test
  void acknowledgedFailureAndBlockedRemainderProduceRecordedIncompleteOutcomeAfterCleanup()
      throws Exception {
    try (var fixture = new Fixture(directory.resolve("incomplete.db"))) {
      Path failed = fixture.file("failed.png");
      Path refused = fixture.file("refused.png");
      Path untouched = fixture.file("untouched.png");
      when(fixture.client.queryPendingVduDocIds(any()))
          .thenReturn(List.of(failed.toString(), refused.toString(), untouched.toString()));
      when(fixture.client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0, -1);
      when(fixture.processor.process(any(Path.class), any()))
          .thenThrow(new VduProcessor.VduException("recorded model failure", null));
      when(fixture.client.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
          .thenReturn(true);

      var running = fixture.start();
      fixture.assertCleanupBoundary(running, 0, 1, "\"remaining\":2");
      assertTrue(fixture.store.find(fixture.operationKey).orElseThrow().checkpointCursor()
          .contains("\"blockedReason\":\"processing_refused\""));
      verify(fixture.client, times(2)).markVduProcessing(anyString(), anyInt(), any());
      verify(fixture.processor).process(any(Path.class), any());
      verify(fixture.client).updateVduResult(anyString(), any(), any(), any(), anyInt(), any());
      fixture.releaseCleanup.countDown();

      OperationRecord terminal = fixture.await(running);
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals("ENRICHMENT_INCOMPLETE", terminal.receipt().code());
      assertEquals(0, fixture.admission.activeWorkCount());
      fixture.assertOneAdmittedContextReachedEveryPort();
    }
  }

  @Test
  void cancellationDuringCleanupCannotPublishOrDetachEarly() throws Exception {
    try (var fixture = new Fixture(directory.resolve("cancel.db"))) {
      Path file = fixture.file("cancel.png");
      when(fixture.client.queryPendingVduDocIds(any())).thenReturn(List.of(file.toString()));
      when(fixture.client.markVduProcessing(anyString(), anyInt(), any())).thenReturn(0);
      when(fixture.processor.process(any(Path.class), any()))
          .thenReturn(new VduProcessor.VduResult("acknowledged text", "{}", 1));
      when(fixture.client.updateVduResult(anyString(), any(), any(), any(), anyInt(), any()))
          .thenReturn(true);

      var running = fixture.start();
      fixture.assertCleanupBoundary(running, 1, 0, "\"remaining\":0");
      fixture.admission.cancelInteractive("integration cancellation");
      fixture.assertCleanupBoundary(running, 1, 0, "\"remaining\":0");
      fixture.releaseCleanup.countDown();

      OperationRecord terminal = fixture.await(running);
      assertEquals(OperationState.CANCELLED, terminal.state());
      assertEquals("cancelled", terminal.receipt().code());
      assertEquals(0, fixture.admission.activeWorkCount());
      fixture.assertOneAdmittedContextReachedEveryPort();
    }
  }

  @Test
  void preselectionModeEntryFailureRecordsAiOfflineBeforeCleanupAndPreservesCause()
      throws Exception {
    try (var fixture = new Fixture(directory.resolve("preselection-mode-failure.db"))) {
      when(fixture.inference.isOnline()).thenReturn(false);
      var transition = new ModeTransitionException(
          ModeTransitionException.Reason.ONLINE_START_FAILED, "mode entry failed");
      org.mockito.Mockito.doThrow(transition).when(fixture.reconciler).procedureRequireEngine(true);

      var running = fixture.start();
      fixture.assertCleanupBoundary(running, 0, 0, "\"selected\":0");
      fixture.assertCheckpointContains("\"remaining\":0", "\"blockedReason\":\"ai_offline\"");
      verify(fixture.client, never()).queryPendingVduDocIds(any());
      fixture.verifyNoDocumentEffects();
      fixture.releaseCleanup.countDown();

      OperationRecord terminal = fixture.await(running);
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals("UNCAUGHT_EXCEPTION", terminal.receipt().code());
      Throwable failure = fixture.coordinatorFailure();
      assertEquals(IllegalStateException.class, failure.getClass());
      assertSame(transition, failure.getCause());
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  @Test
  void postselectionModeEntryFailureRecordsCapturedRemainderBeforeCleanupAndPreservesCause()
      throws Exception {
    try (var fixture = new Fixture(directory.resolve("postselection-mode-failure.db"))) {
      Path first = fixture.file("mode-first.png");
      Path second = fixture.file("mode-second.png");
      when(fixture.client.queryPendingVduDocIds(any()))
          .thenReturn(List.of(first.toString(), second.toString()));
      var modeFailure = new VduProcessor.VduException("VDU mode entry failed", null);
      org.mockito.Mockito.doThrow(modeFailure).when(fixture.processor).enterVduMode();

      var running = fixture.start();
      fixture.assertCleanupBoundary(running, 0, 0, "\"selected\":2");
      fixture.assertCheckpointContains("\"remaining\":2", "\"blockedReason\":\"ai_offline\"");
      fixture.verifyNoDocumentEffects();
      fixture.releaseCleanup.countDown();

      OperationRecord terminal = fixture.await(running);
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals("UNCAUGHT_EXCEPTION", terminal.receipt().code());
      Throwable failure = fixture.coordinatorFailure();
      assertEquals(IllegalStateException.class, failure.getClass());
      assertSame(modeFailure, failure.getCause());
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final SqliteOperationStore store;
    final OperationAttemptRunnerImpl runner;
    final DefaultEngineExecutorRegistry executors = new DefaultEngineExecutorRegistry();
    final EngineAdmissionController admission = new EngineAdmissionController(1, 1, 1);
    final RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
    final OnlineAiLifecycleControl inference = mock(OnlineAiLifecycleControl.class);
    final KnowledgeClient client = mock(KnowledgeClient.class);
    final VduProcessor processor = mock(VduProcessor.class);
    final CountDownLatch cleanupEntered = new CountDownLatch(1);
    final CountDownLatch releaseCleanup = new CountDownLatch(1);
    final EngineContext caller = TestRequestContexts.browser();
    final OfflineCoordinator coordinator;
    final TriggerOfflineProcessingHandler handler;
    final Path files;
    final AtomicReference<CompletionStage<OfflineProcessingOutcome>> coordinatorCompletion =
        new AtomicReference<>();
    String operationKey;
    EngineContext dispatchContext;

    Fixture(Path database) throws Exception {
      files = database.getParent();
      store = new SqliteOperationStore(database);
      runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      when(processor.hasVisionCapability()).thenReturn(true);
      when(client.countPendingVdu(any())).thenReturn(1);
      var gpu = mock(GpuCapabilitiesService.class);
      when(gpu.snapshot()).thenReturn(gpuSnapshot());
      when(inference.isOnline()).thenReturn(true);
      doAnswer(call -> {
        cleanupEntered.countDown();
        awaitIgnoringInterrupt(releaseCleanup);
        return null;
      }).when(reconciler).endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
      var batch = new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
          new VduCapabilityState());
      coordinator = new OfflineCoordinator(
          executors, admission, inference, reconciler, batch, () -> client, new VduCapabilityState());
      var brain = new BrainRuntimeServiceImpl(mock(OnlineAiService.class), null, null,
          (context, progress) -> {
            var completion = coordinator.startOfflineProcessing(context, progress);
            coordinatorCompletion.set(completion);
            return completion;
          });
      handler = new TriggerOfflineProcessingHandler(() -> brain);
    }

    OperationAttemptRunner.Result start() {
      var descriptor = OperationDescriptor.invocation(OperationKind.OPERATION,
          CoreOperationCatalog.TRIGGER_OFFLINE_PROCESSING.value(), "{}", false);
      try (var dispatch = admission.admit(caller, false)) {
        dispatchContext = dispatch.context();
        var prepared = runner.accept(
            new OperationAttemptRunner.Request(null, descriptor, dispatchContext, null));
        operationKey = prepared.accepted().key();
        OperationAttemptRunner.Result result = runner.start(prepared, record ->
            handler.executeRecorded("{}", null, dispatchContext, record));
        assertTrue(result.response().success());
        return result;
      }
    }

    void assertCleanupBoundary(OperationAttemptRunner.Result running, long completed, long failed,
        String cursorFragment) throws Exception {
      assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
      assertFalse(running.completion().toCompletableFuture().isDone(),
          "durable completion must wait for procedure cleanup");
      OperationRecord row = store.find(operationKey).orElseThrow();
      assertEquals(OperationState.RUNNING, row.state());
      assertEquals(completed, row.unitsCompleted());
      assertEquals(failed, row.unitsFailed());
      assertNotNull(row.checkpointCursor(), "mode or captured-pass progress must be checkpointed");
      assertTrue(row.checkpointCursor().contains(cursorFragment), row.checkpointCursor());
      assertEquals(1, admission.activeWorkCount(), "procedure admission must remain attached");
    }

    OperationRecord await(OperationAttemptRunner.Result result) throws Exception {
      return result.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    Throwable coordinatorFailure() throws Exception {
      try {
        coordinatorCompletion.get().toCompletableFuture().get(5, TimeUnit.SECONDS);
        throw new AssertionError("expected coordinator failure");
      } catch (ExecutionException failed) {
        return failed.getCause();
      }
    }

    void assertCheckpointContains(String... fragments) {
      String cursor = store.find(operationKey).orElseThrow().checkpointCursor();
      for (String fragment : fragments) assertTrue(cursor.contains(fragment), cursor);
    }

    void verifyNoDocumentEffects() throws Exception {
      verify(client, never()).markVduProcessing(anyString(), anyInt(), any());
      verify(processor, never()).process(any(Path.class), any());
      verify(client, never()).updateVduResult(anyString(), any(), any(), any(), anyInt(), any());
    }

    void assertOneAdmittedContextReachedEveryPort() throws Exception {
      assertTrue(dispatchContext.workId().isPresent());
      assertEquals(caller,
          store.find(operationKey).orElseThrow().context(), "durable row retains attribution, not process-local workId");
      verify(client).recoverVduProcessing(eq(dispatchContext));
      verify(client).countPendingVdu(eq(dispatchContext));
      verify(client).queryPendingVduDocIds(eq(dispatchContext));
      int acknowledged = (int) (store.find(operationKey).orElseThrow().unitsCompleted()
          + store.find(operationKey).orElseThrow().unitsFailed());
      verify(client, atLeastOnce())
          .markVduProcessing(anyString(), anyInt(), eq(dispatchContext));
      verify(processor, times(acknowledged)).process(any(Path.class), eq(dispatchContext));
      verify(client, times(acknowledged))
          .updateVduResult(anyString(), any(), any(), any(), anyInt(), eq(dispatchContext));
      verify(client).countPendingEmbeddings(eq(dispatchContext));
    }

    Path file(String name) throws Exception {
      return Files.writeString(files.resolve(name), "image");
    }

    @Override public void close() throws java.io.IOException {
      releaseCleanup.countDown();
      try {
        coordinator.close();
      } finally {
        executors.close();
        store.close();
      }
    }
  }

  private static GpuCapabilities gpuSnapshot() {
    var effective = new GpuCapabilities.Effective(true, "test", GpuCapabilities.Confidence.HIGH,
        "1.0", 1, 0, 1, 24_000_000_000L, 24_000_000_000L, 0L,
        GpuCapabilities.Cuda.unknown());
    return new GpuCapabilities(null, null, effective);
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
}
