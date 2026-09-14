/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The guard and admitted work survive through cleanup while primary failures remain observable. */
@Timeout(15)
class OfflineCoordinatorFailureCleanupTest {
  private final RuntimeReconciler reconciler = mock(RuntimeReconciler.class);
  private final List<TestEngineExecutors> registries = new ArrayList<>();
  private final EngineContext context = TestEngineContexts.durableInternal();

  @AfterEach
  void tearDown() {
    registries.forEach(TestEngineExecutors::close);
  }

  @Test
  void setupFailureReleasesGuardAndDoesNotEndUnbegunProcedure() throws Exception {
    IllegalStateException failure = new IllegalStateException("begin refused");
    doThrow(failure).doNothing().when(reconciler)
        .beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "offline-processing");
    OfflineCoordinator coordinator = coordinator(() -> null);

    assertSame(failure, failureOf(coordinator.startOfflineProcessing(context, ignored -> {})));
    assertFalse(coordinator.isProcessing());
    verify(reconciler, never()).endProcedure(any());

    await(coordinator.startOfflineProcessing(context, ignored -> {}));
    verify(reconciler, times(2)).beginProcedure(any(), anyString());
    verify(reconciler).endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    assertFalse(coordinator.isProcessing());
  }

  @Test
  void cleanupFailureReleasesGuardAndRemainsObservable() throws Exception {
    IllegalStateException failure = new IllegalStateException("end failed");
    doThrow(failure).doNothing().when(reconciler).endProcedure(any());
    OfflineCoordinator coordinator = coordinator(() -> null);

    assertSame(failure, failureOf(coordinator.startOfflineProcessing(context, ignored -> {})));
    assertFalse(coordinator.isProcessing());

    await(coordinator.startOfflineProcessing(context, ignored -> {}));
    verify(reconciler, times(2)).beginProcedure(any(), anyString());
    verify(reconciler, times(2)).endProcedure(any());
  }

  @Test
  void bodyFailureRemainsPrimaryWhenProcedureCleanupAlsoFails() throws Exception {
    IllegalStateException body = new IllegalStateException("client unavailable");
    IllegalArgumentException cleanup = new IllegalArgumentException("end failed");
    doThrow(cleanup).when(reconciler).endProcedure(any());
    OfflineCoordinator coordinator = coordinator(() -> { throw body; });

    assertSame(body, failureOf(coordinator.startOfflineProcessing(context, ignored -> {})));
    assertArrayEquals(new Throwable[] {cleanup}, body.getSuppressed());
    assertFalse(coordinator.isProcessing());
  }

  @Test
  void fatalBodyFailureIsPreservedThroughProcedureCleanup() throws Exception {
    AssertionError body = new AssertionError("fatal procedure failure");
    IllegalStateException cleanup = new IllegalStateException("end failed");
    doThrow(cleanup).when(reconciler).endProcedure(any());
    OfflineCoordinator coordinator = coordinator(() -> { throw body; });

    assertSame(body, failureOf(coordinator.startOfflineProcessing(context, ignored -> {})));
    assertArrayEquals(new Throwable[] {cleanup}, body.getSuppressed());
    assertFalse(coordinator.isProcessing());
  }

  private OfflineCoordinator coordinator(Supplier<KnowledgeClient> clientSupplier) {
    TestEngineExecutors executors = new TestEngineExecutors();
    registries.add(executors);
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    EngineWorkHandle work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.cancellationReason()).thenReturn(Optional.empty());
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    when(admission.attach(context)).thenReturn(work);
    OfflineCoordinator coordinator = new OfflineCoordinator(executors, admission,
        mock(OnlineAiLifecycleControl.class), reconciler, mock(VduBatchProcessor.class),
        clientSupplier, new VduCapabilityState());
    return coordinator;
  }

  private static OfflineProcessingOutcome await(CompletionStage<OfflineProcessingOutcome> stage)
      throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static Throwable failureOf(CompletionStage<OfflineProcessingOutcome> stage)
      throws Exception {
    ExecutionException thrown = assertThrows(ExecutionException.class,
        () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
    return thrown.getCause();
  }
}
