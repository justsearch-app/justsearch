/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The existing single-flight guard covers setup, body and cleanup, including failed exits. */
class OfflineCoordinatorFailureCleanupTest {
  private final RuntimeReconciler reconciler = mock(RuntimeReconciler.class);

  private OfflineCoordinator coordinator(Supplier<KnowledgeClient> client) {
    return new OfflineCoordinator(mock(OnlineAiLifecycleControl.class), reconciler,
        mock(VduBatchProcessor.class), client, new VduCapabilityState());
  }

  @Test
  void setupFailureReleasesGuardAndDoesNotEndAnUnbegunProcedure() {
    var failure = new IllegalStateException("begin refused");
    doThrow(failure).doNothing().when(reconciler)
        .beginProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH, "offline-processing");
    var coordinator = coordinator(() -> null);

    assertSame(failure, assertThrows(IllegalStateException.class, coordinator::startOfflineProcessing));
    assertFalse(coordinator.isProcessing());
    verify(reconciler, never()).endProcedure(any());
    coordinator.startOfflineProcessing();
    verify(reconciler, times(2)).beginProcedure(any(), anyString());
    verify(reconciler).endProcedure(RuntimeStatus.ProcedureKind.VDU_BATCH);
    assertFalse(coordinator.isProcessing());
  }

  @Test
  void cleanupFailureReleasesGuardAndRemainsAnObservableFailure() {
    var failure = new IllegalStateException("end failed");
    doThrow(failure).doNothing().when(reconciler).endProcedure(any());
    var coordinator = coordinator(() -> null);

    assertSame(failure, assertThrows(IllegalStateException.class, coordinator::startOfflineProcessing));
    assertFalse(coordinator.isProcessing());
    coordinator.startOfflineProcessing();
    verify(reconciler, times(2)).beginProcedure(any(), anyString());
    verify(reconciler, times(2)).endProcedure(any());
    assertFalse(coordinator.isProcessing());
  }

  @Test
  void bodyFailureRemainsPrimaryWhenCleanupAlsoFails() {
    var failure = new IllegalStateException("client unavailable");
    var cleanup = new IllegalArgumentException("end failed");
    doThrow(cleanup).when(reconciler).endProcedure(any());
    var coordinator = coordinator(() -> { throw failure; });

    assertSame(failure, assertThrows(IllegalStateException.class, coordinator::startOfflineProcessing));
    assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
    assertFalse(coordinator.isProcessing());
  }

  @Test
  void fatalBodyFailureIsPreservedThroughCleanup() {
    var failure = new AssertionError("fatal procedure failure");
    var cleanup = new IllegalStateException("end failed");
    doThrow(cleanup).when(reconciler).endProcedure(any());
    var coordinator = coordinator(() -> { throw failure; });

    assertSame(failure, assertThrows(AssertionError.class, coordinator::startOfflineProcessing));
    assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
    assertFalse(coordinator.isProcessing());
  }
}
