/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/** Exercises the interrupt signal composed for real offline passes, before model or index work. */
final class VduActivityCheckpointTest {
  @Test
  void acceptedPassIgnoresEarlierActivityButYieldsToNewActivity() throws Exception {
    var server = mock(KnowledgeServerBootstrap.class, Answers.RETURNS_DEEP_STUBS);
    var input = mock(ServicePhase.Input.class);
    when(input.inferenceManager()).thenReturn(mock(InferenceLifecycleManager.class));
    when(input.engineAdmission()).thenReturn(mock(EngineAdmissionService.class));
    when(input.knowledgeServerBootstrapSupplier()).thenReturn(() -> server);
    when(server.gpuScheduling()).thenReturn(new GpuSchedulingGauge());
    // The verifier ingests, searches, waits 45 seconds, then explicitly requests this pass.
    when(server.msSinceLastUserActivity(org.mockito.ArgumentMatchers.anyLong())).thenReturn(45_000L);
    activityVersion(server, 1L);
    var captured = new AtomicReference<Object>();
    try (var reconciler = mockConstruction(RuntimeReconciler.class);
         var builder = mockStatic(OfflineCoordinatorBuilder.class, invocation -> {
           captured.set(invocation.getArgument(6));
           throw new IllegalStateException("composition checkpoint captured");
         })) {
      ServicePhase.runWithOutcome(input);
    }
    assertNotNull(captured.get(), "must reach the production VDU composition checkpoint");
    // The old composition supplies a constant BooleanSupplier; the repair supplies a per-pass factory.
    BooleanSupplier interrupted = captured.get() instanceof BooleanSupplier signal
        ? signal : (BooleanSupplier) ((Supplier<?>) captured.get()).get();
    assertFalse(interrupted.getAsBoolean(), "earlier activity must not self-interrupt an accepted VDU pass");
    when(server.energyState().reduced()).thenReturn(true);
    assertTrue(interrupted.getAsBoolean(), "energy reduction still interrupts an accepted pass");
    when(server.energyState().reduced()).thenReturn(false);
    when(server.bulkWork().held()).thenReturn(true);
    assertTrue(interrupted.getAsBoolean(), "the bulk hold still interrupts an accepted pass");
    when(server.bulkWork().held()).thenReturn(false);
    assertFalse(interrupted.getAsBoolean(), "clearing resource holds restores the accepted pass");
    when(server.msSinceLastUserActivity(org.mockito.ArgumentMatchers.anyLong())).thenReturn(0L);
    activityVersion(server, 2L);
    assertTrue(interrupted.getAsBoolean(), "activity during the pass must interrupt at the next unit");
  }

  /** Keep this preservation oracle executable on the pre-version activity source as well. */
  private static void activityVersion(KnowledgeServerBootstrap server, long version) throws Exception {
    try {
      var read = KnowledgeServerBootstrap.class.getMethod("userActivityVersion");
      when((Long) read.invoke(server)).thenReturn(version);
    } catch (NoSuchMethodException legacy) {
      // The legacy source is driven by msSinceLastUserActivity above.
    }
  }
}
