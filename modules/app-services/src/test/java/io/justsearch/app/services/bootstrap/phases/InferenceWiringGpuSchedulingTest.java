/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Owner decision 2026-10-02 (lane F, ADR-0004 amendment): chat going Online does not claim the GPU
 * from the encoders, matching shipped split JustSearch, whose Head never registered a GPU listener.
 * Before this decision the test pinned the opposite (Online => gauge active), which made the merged
 * Engine run every encoder on CPU while chat was loaded (stage E: hybrid search p95 9.8 s vs 0.26 s).
 * The subscription and connect-time seeding stay, so a later budget-aware policy plugs into the same
 * bridge; what they publish is "encoders keep the GPU" in every chat state.
 */
final class InferenceWiringGpuSchedulingTest {
  @Test
  void chatOnlineNeverClaimsTheGpuAcrossConnectReconnectAndTransitions() {
    var manager = mock(InferenceLifecycleManager.class);
    var online = new AtomicBoolean();
    when(manager.isOnline()).thenAnswer(invocation -> online.get());
    var current = new AtomicReference<KnowledgeServerBootstrap>();
    var listener = InferenceWiring.wireGpuStatusBroadcast(manager, current::get);
    verify(manager).addModeChangeListener(listener);

    // Activation before the async index connection, then the connect-time seed.
    online.set(true);
    listener.onModeChange(Mode.OFFLINE, Mode.ONLINE);
    var firstGauge = new GpuSchedulingGauge();
    var first = bootstrap(firstGauge);
    current.set(first);
    InferenceWiring.refreshGpuStatus(manager, first);
    assertFalse(firstGauge.isMainGpuActive());

    online.set(false);
    listener.onModeChange(Mode.ONLINE, Mode.OFFLINE);
    assertFalse(firstGauge.isMainGpuActive());
    online.set(true);
    listener.onModeChange(Mode.OFFLINE, Mode.ONLINE);
    assertFalse(firstGauge.isMainGpuActive());

    // A reconnect seeds the replacement gauge the same way.
    var replacementGauge = new GpuSchedulingGauge();
    var replacement = bootstrap(replacementGauge);
    current.set(replacement);
    InferenceWiring.refreshGpuStatus(manager, replacement);
    assertFalse(replacementGauge.isMainGpuActive());
    listener.onModeChange(Mode.OFFLINE, Mode.ONLINE);
    assertFalse(replacementGauge.isMainGpuActive());
  }

  @Test
  void eagerConnectionToAnAlreadyOnlineManagerLeavesTheEncodersTheGpu() {
    var manager = mock(InferenceLifecycleManager.class);
    when(manager.isOnline()).thenReturn(true);
    var gauge = new GpuSchedulingGauge();
    var bootstrap = bootstrap(gauge);
    var listener = InferenceWiring.wireGpuStatusBroadcast(manager, () -> bootstrap);
    assertFalse(gauge.isMainGpuActive());
    verify(manager).addModeChangeListener(listener);
  }

  private static KnowledgeServerBootstrap bootstrap(GpuSchedulingGauge gauge) {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.gpuScheduling()).thenReturn(gauge);
    return bootstrap;
  }
}
