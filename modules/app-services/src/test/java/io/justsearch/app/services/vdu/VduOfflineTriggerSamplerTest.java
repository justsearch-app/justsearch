/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.util.EnergyState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code GpuSaturationSamplerTest}'s shape — the Head-side counterpart it's modeled on.
 * Exercises {@link VduOfflineTriggerSampler#checkOnce()} directly (not the real 30s schedule).
 */
final class VduOfflineTriggerSamplerTest {

  @Test
  @DisplayName("triggers startOfflineProcessing when idle, pending VDU work exists, and LLM is offline")
  void triggersWhenConditionsMet() throws Exception {
    OfflineCoordinator coordinator = mock(OfflineCoordinator.class);
    when(coordinator.isProcessing()).thenReturn(false);
    when(coordinator.getPendingVduCount()).thenReturn(3);

    KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    when(ks.msSinceLastUserActivity(org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS + 1000);
    when(ks.energyState()).thenReturn(new EnergyState(EnergyState.Intent.FULL, EnergyState.Source.AC));

    CountDownLatch triggered = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            inv -> {
              triggered.countDown();
              return null;
            })
        .when(coordinator)
        .startOfflineProcessing();

    var sampler =
        new VduOfflineTriggerSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> coordinator, () -> ks, () -> false);
    sampler.checkOnce();

    assertTrue(triggered.await(5, TimeUnit.SECONDS), "startOfflineProcessing should have been dispatched");
  }

  @Test
  @DisplayName("does not trigger when no VDU work is pending")
  void noTriggerWhenNoPendingWork() {
    OfflineCoordinator coordinator = mock(OfflineCoordinator.class);
    when(coordinator.isProcessing()).thenReturn(false);
    when(coordinator.getPendingVduCount()).thenReturn(0);

    var sampler = new VduOfflineTriggerSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> coordinator, () -> null, () -> false);
    sampler.checkOnce();

    verify(coordinator, never()).startOfflineProcessing();
  }

  @Test
  @DisplayName("does not trigger when a batch is already processing")
  void noTriggerWhenAlreadyProcessing() {
    OfflineCoordinator coordinator = mock(OfflineCoordinator.class);
    when(coordinator.isProcessing()).thenReturn(true);

    var sampler = new VduOfflineTriggerSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> coordinator, () -> null, () -> false);
    sampler.checkOnce();

    verify(coordinator, never()).getPendingVduCount();
    verify(coordinator, never()).startOfflineProcessing();
  }

  @Test
  @DisplayName("does not trigger when the LLM is already online")
  void noTriggerWhenLlmOnline() {
    OfflineCoordinator coordinator = mock(OfflineCoordinator.class);
    when(coordinator.isProcessing()).thenReturn(false);
    when(coordinator.getPendingVduCount()).thenReturn(3);

    var sampler = new VduOfflineTriggerSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> coordinator, () -> null, () -> true);
    sampler.checkOnce();

    verify(coordinator, never()).startOfflineProcessing();
  }

  @Test
  @DisplayName("a checkOnce exception does not propagate (transient probe failures must not kill the sampler)")
  void exceptionDuringCheckDoesNotPropagate() {
    var sampler =
        new VduOfflineTriggerSampler(new io.justsearch.core.execution.TestEngineExecutors(),
            () -> {
              throw new RuntimeException("simulated failure");
            },
            () -> null,
            () -> false);

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(sampler::checkOnce);
  }

  @Test
  @DisplayName("start() + stop() are idempotent and don't throw")
  void startStopIdempotent() {
    OfflineCoordinator coordinator = mock(OfflineCoordinator.class);
    when(coordinator.isProcessing()).thenReturn(false);
    when(coordinator.getPendingVduCount()).thenReturn(0);

    var sampler = new VduOfflineTriggerSampler(new io.justsearch.core.execution.TestEngineExecutors(), () -> coordinator, () -> null, () -> false);
    sampler.start();
    sampler.start(); // second call is a no-op
    sampler.stop();
    sampler.stop(); // second call is a no-op
  }
}
