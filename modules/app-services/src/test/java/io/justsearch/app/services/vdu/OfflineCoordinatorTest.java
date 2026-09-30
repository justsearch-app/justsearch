/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OfflineProcessingOutcome.EmbeddingHandoff;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Production coordinator coverage for phase order, recovery, outcomes, and shared guard behavior. */
@Timeout(15)
class OfflineCoordinatorTest {
  private TestEngineExecutors executors;
  private StubInferenceLifecycleManager inference;
  private RuntimeReconciler reconciler;
  private VduBatchProcessor batch;
  private KnowledgeClient client;
  private VduCapabilityState capability;
  private EngineContext context;
  private OfflineCoordinator coordinator;

  @BeforeEach
  void setUp() {
    executors = new TestEngineExecutors();
    inference = new StubInferenceLifecycleManager();
    reconciler = new RuntimeReconciler(inference, inference::getCurrentMode, () -> false,
        null, null, new RuntimeSpecStore(null), new RuntimeGpuLease());
    batch = mock(VduBatchProcessor.class);
    client = mock(KnowledgeClient.class);
    capability = new VduCapabilityState();
    context = TestEngineContexts.durableInternal();
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    EngineWorkHandle work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.cancellationReason()).thenReturn(Optional.empty());
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    when(admission.attach(context)).thenReturn(work);
    when(batch.processPendingFiles(any(), any())).thenReturn(outcome(0, 0, 0));
    coordinator = new OfflineCoordinator(executors, admission, inference, reconciler, batch,
        () -> client, capability);
  }

  @AfterEach
  void tearDown() {
    executors.close();
  }

  @Test
  void vduRunsBeforeEmbeddingHandoff() throws Exception {
    when(client.countPendingVdu(context)).thenReturn(5);
    AtomicBoolean vduFinished = new AtomicBoolean();
    when(batch.processPendingFiles(same(context), any()))
        .thenAnswer(invocation -> {
          vduFinished.set(true);
          return outcome(5, 5, 0);
        });
    when(client.countPendingEmbeddings(context)).thenAnswer(invocation -> {
      assertTrue(vduFinished.get(), "embedding count must be read after the VDU phase");
      return 10;
    });
    inference.withMode(Mode.OFFLINE);

    OfflineProcessingOutcome result = await(coordinator.startOfflineProcessing(context, ignored -> {}));

    assertEquals(5, result.processed());
    assertEquals(EmbeddingHandoff.HANDED_OFF, result.embeddingHandoff());
    assertEquals(1, inference.getOnlineSwitchCount());
    assertEquals(1, inference.getIndexingSwitchCount());
  }

  @Test
  void noVduClearsCapabilityAndSkipsBatch() throws Exception {
    when(client.countPendingVdu(context)).thenReturn(0);
    when(client.countPendingEmbeddings(context)).thenReturn(0);
    capability.block(VduCapabilityState.REASON_AI_OFFLINE);

    OfflineProcessingOutcome result = await(coordinator.startOfflineProcessing(context, ignored -> {}));

    assertNull(capability.snapshot().blockedReason());
    assertEquals(EmbeddingHandoff.NOT_NEEDED, result.embeddingHandoff());
    verify(batch, never()).processPendingFiles(any(), any());
  }

  @Test
  void recoveryRunsOnceBeforeSelection() throws Exception {
    when(client.countPendingVdu(context)).thenReturn(0);
    when(client.countPendingEmbeddings(context)).thenReturn(0);

    await(coordinator.startOfflineProcessing(context, ignored -> {}));

    verify(client).recoverVduProcessing(context);
  }

  @Test
  void zeroRecoveredStillAllowsVdu() throws Exception {
    when(client.recoverVduProcessing(context)).thenReturn(0);
    when(client.countPendingVdu(context)).thenReturn(1);
    when(client.countPendingEmbeddings(context)).thenReturn(0);
    when(batch.processPendingFiles(same(context), any()))
        .thenReturn(outcome(1, 1, 0));

    assertEquals(1,
        await(coordinator.startOfflineProcessing(context, ignored -> {})).processed());
  }

  @Test
  void sharedGuardRejectsASecondStartUntilActualExit() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(client.countPendingVdu(context)).thenReturn(1);
    when(batch.processPendingFiles(same(context), any()))
        .thenAnswer(invocation -> {
          entered.countDown();
          release.await(5, TimeUnit.SECONDS);
          return outcome(1, 1, 0);
        });
    when(client.countPendingEmbeddings(context)).thenReturn(0);

    var first = coordinator.startOfflineProcessing(context, ignored -> {});
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    try {
      assertTrue(coordinator.isProcessing());
      assertThrows(IllegalStateException.class,
          () -> coordinator.startOfflineProcessing(context, ignored -> {}));
    } finally {
      release.countDown();
    }
    await(first);
    assertFalse(coordinator.isProcessing());
    await(coordinator.startOfflineProcessing(context, ignored -> {}));
    verify(batch, times(2)).processPendingFiles(any(), any());
  }

  @Test
  void alreadyOnlineAvoidsRedundantTransition() throws Exception {
    inference.withMode(Mode.ONLINE);
    when(client.countPendingVdu(context)).thenReturn(1);
    when(client.countPendingEmbeddings(context)).thenReturn(0);

    await(coordinator.startOfflineProcessing(context, ignored -> {}));

    assertEquals(0, inference.getOnlineSwitchCount());
  }

  @Test
  void unavailableWorkerReturnsExplicitBlockedOutcome() throws Exception {
    executors.close();
    executors = new TestEngineExecutors();
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    EngineWorkHandle work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.cancellationReason()).thenReturn(Optional.empty());
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    when(admission.attach(context)).thenReturn(work);
    coordinator = new OfflineCoordinator(executors, admission, inference, reconciler, batch,
        () -> null, capability);

    OfflineProcessingOutcome result = await(coordinator.startOfflineProcessing(context, ignored -> {}));

    assertEquals(BlockReason.WORKER_UNAVAILABLE, result.blockedReason());
  }

  @Test
  void helperMethodsReportVduBacklog() {
    when(client.countPendingVdu(any())).thenReturn(5);
    assertTrue(coordinator.hasPendingWork());
    assertEquals(5, coordinator.getPendingVduCount());
    assertEquals(0, coordinator.getPendingEmbeddingCount());
  }

  @Test
  void helperMethodsReportEmbeddingBacklogAndEmptyBacklog() {
    when(client.countPendingVdu(any())).thenReturn(0);
    when(client.countPendingEmbeddings(any())).thenReturn(9, 9, 0, 0);

    assertTrue(coordinator.hasPendingWork());
    assertEquals(9, coordinator.getPendingEmbeddingCount());
    assertFalse(coordinator.hasPendingWork());
  }

  private static OfflineProcessingOutcome outcome(int selected, int processed, int failed) {
    return new OfflineProcessingOutcome(selected, processed, failed, BlockReason.NONE,
        EmbeddingHandoff.NOT_EVALUATED);
  }

  private static OfflineProcessingOutcome await(
      java.util.concurrent.CompletionStage<OfflineProcessingOutcome> stage) throws Exception {
    try {
      return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Exception exception) throw exception;
      if (cause instanceof Error error) throw error;
      throw new CompletionException(cause);
    }
  }
}
