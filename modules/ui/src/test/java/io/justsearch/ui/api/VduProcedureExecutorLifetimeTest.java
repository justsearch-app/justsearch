/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OfflineProcessingOutcome.EmbeddingHandoff;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.vdu.VduBatchProcessor;
import io.justsearch.app.services.vdu.VduCapabilityState;
import io.justsearch.app.services.vdu.VduOfflineTriggerSampler;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VduProcedureExecutorLifetimeTest {
  @Test
  void aRealRegistryTimeoutRefusesCloseAndRetainsTheOwnerUntilRetry() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var admission = new EngineAdmissionController(1, 1, 1);
    var reconciler = mock(RuntimeReconciler.class);
    doAnswer(invocation -> {
      entered.countDown();
      boolean interrupted = false;
      while (true) {
        try { release.await(); break; }
        catch (InterruptedException expected) { interrupted = true; }
      }
      if (interrupted) Thread.currentThread().interrupt();
      return null;
    }).when(reconciler).endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    var client = mock(KnowledgeClient.class);
    try (var registry = new DefaultEngineExecutorRegistry();
        var coordinator = new OfflineCoordinator(registry, admission,
            mock(OnlineAiLifecycleControl.class), reconciler, mock(VduBatchProcessor.class),
            () -> client, new VduCapabilityState())) {
      try {
        var context = EngineProvenance.internal("timeout-enrichment",
            EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
        var stage = coordinator.startOfflineProcessing(context, outcome -> {}).toCompletableFuture();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var failure = assertThrows(IllegalStateException.class, coordinator::close);
        assertTrue(failure.getMessage().contains("dependencies must remain open"));
        assertFalse(stage.isDone());
        assertTrue(coordinator.isProcessing());
        assertEquals(1, admission.activeWorkCount());
        assertTrue(registry.snapshot().registrations().stream().anyMatch(row ->
            row.spec().name().equals("head.offline-procedure") && row.liveInstances() == 1));
        release.countDown();
        coordinator.close();
        var cancelled = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> stage.get(5, TimeUnit.SECONDS));
        assertInstanceOf(java.util.concurrent.CancellationException.class, cancelled.getCause());
        assertEquals(0, admission.activeWorkCount());
        assertTrue(registry.snapshot().registrations().isEmpty());
      } finally { release.countDown(); }
    }
  }

  @Test
  void stopWaitsForTheActualProcedureAndRetainsItsAccountingUntilExit() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var closeFailure = new AtomicReference<Throwable>();
    var admission = new EngineAdmissionController(2, 4, 1);
    var inference = mock(OnlineAiLifecycleControl.class);
    when(inference.isOnline()).thenReturn(true);
    var client = mock(KnowledgeClient.class);
    when(client.countPendingVdu(any())).thenReturn(1);
    var batch = mock(VduBatchProcessor.class);
    when(batch.processPendingFiles(any(), any())).thenReturn(new OfflineProcessingOutcome(
        1, 1, 0, BlockReason.NONE, EmbeddingHandoff.NOT_EVALUATED));
    var reconciler = mock(RuntimeReconciler.class);
    doAnswer(invocation -> {
      entered.countDown();
      boolean wasInterrupted = false;
      while (true) {
        try { release.await(); break; }
        catch (InterruptedException expected) {
          wasInterrupted = true;
          interrupted.countDown();
        }
      }
      if (wasInterrupted) Thread.currentThread().interrupt();
      return null;
    }).when(reconciler).endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    try (var registry = new DefaultEngineExecutorRegistry();
        var coordinator = new OfflineCoordinator(registry, admission, inference, reconciler,
            batch, () -> client, new VduCapabilityState())) {
      var sampler = new VduOfflineTriggerSampler(registry, () -> coordinator, () -> null, () -> false);
      Thread closer = null;
      try {
        var check = VduOfflineTriggerSampler.class.getDeclaredMethod("checkOnce");
        check.setAccessible(true);
        check.invoke(sampler);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        check.invoke(sampler);
        var manual = EngineProvenance.internal("manual-enrichment",
            EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
        assertThrows(IllegalStateException.class,
            () -> coordinator.startOfflineProcessing(manual, outcome -> {}));
        verify(batch, times(1)).processPendingFiles(any(), any());
        assertEquals(1, admission.activeWorkCount());
        sampler.stop();
        closer = Thread.ofPlatform().daemon().start(() -> {
          try { coordinator.close(); }
          catch (Throwable failure) { closeFailure.set(failure); }
          finally { stopped.countDown(); }
        });
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertFalse(stopped.await(50, TimeUnit.MILLISECONDS));
        assertTrue(coordinator.isProcessing());
        assertEquals(1, admission.activeWorkCount());
        assertTrue(registry.snapshot().registrations().stream().anyMatch(row ->
            row.spec().name().equals("head.offline-procedure") && row.liveInstances() == 1));
        release.countDown();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertNull(closeFailure.get());
        assertFalse(coordinator.isProcessing());
        assertEquals(0, admission.activeWorkCount());
        assertTrue(registry.snapshot().registrations().isEmpty());
      } finally {
        release.countDown();
        if (closer != null) closer.join(5_000);
        sampler.stop();
      }
    }
  }
}
