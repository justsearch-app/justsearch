/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.vdu.VduOfflineTriggerSampler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VduProcedureExecutorLifetimeTest {
  @Test
  void stopWaitsForTheActualProcedureAndRetainsItsAccountingUntilExit() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var coordinator = mock(OfflineCoordinator.class);
    when(coordinator.getPendingVduCount()).thenReturn(1);
    doAnswer(invocation -> {
      entered.countDown();
      try { release.await(); }
      catch (InterruptedException expected) {
        interrupted.countDown();
        release.await();
      }
      return null;
    }).when(coordinator).startOfflineProcessing();
    try (var registry = new DefaultEngineExecutorRegistry()) {
      var sampler = new VduOfflineTriggerSampler(registry, () -> coordinator, () -> null, () -> false);
      Thread closer = null;
      try {
        var check = VduOfflineTriggerSampler.class.getDeclaredMethod("checkOnce");
        check.setAccessible(true);
        check.invoke(sampler);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        check.invoke(sampler);
        verify(coordinator, times(1)).startOfflineProcessing();
        closer = Thread.ofPlatform().daemon().start(() -> {
          try { sampler.stop(); } finally { stopped.countDown(); }
        });
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertFalse(stopped.await(50, TimeUnit.MILLISECONDS));
        assertTrue(registry.snapshot().registrations().stream().anyMatch(row ->
            row.spec().name().equals("head.vdu-auto-trigger") && row.liveInstances() == 1));
        release.countDown();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertTrue(registry.snapshot().registrations().isEmpty());
      } finally {
        release.countDown();
        if (closer != null) closer.join(5_000);
        sampler.stop();
      }
    }
  }
}
