/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

final class InferenceWiringGpuSchedulingTest {
  @Test
  void asyncIndexConnectionSeedsCurrentModeAndKeepsReceivingTransitions() {
    var manager = mock(InferenceLifecycleManager.class);
    var online = new AtomicBoolean();
    when(manager.isOnline()).thenAnswer(invocation -> online.get());
    var current = new AtomicReference<KnowledgeServerBootstrap>();
    var listener = InferenceWiring.wireGpuStatusBroadcast(manager, current::get);
    verify(manager).addModeChangeListener(listener);

    // Normal boot: inference setup precedes the async index connection. Activation can finish
    // before that connection, so registering a listener alone is insufficient: connect must seed.
    online.set(true);
    listener.onModeChange(Mode.OFFLINE, Mode.ONLINE);
    var firstGauge = new GpuSchedulingGauge();
    var first = bootstrap(firstGauge);
    current.set(first);
    InferenceWiring.refreshGpuStatus(manager, first);
    assertTrue(firstGauge.isMainGpuActive());

    online.set(false);
    listener.onModeChange(Mode.ONLINE, Mode.OFFLINE);
    assertFalse(firstGauge.isMainGpuActive());
    online.set(true);
    listener.onModeChange(Mode.OFFLINE, Mode.ONLINE);
    assertTrue(firstGauge.isMainGpuActive());

    // Reconnect uses the same subscription and seeds the replacement gauge without touching the
    // retired one. Delayed callbacks publish the current manager state, not stale event payloads.
    var replacementGauge = new GpuSchedulingGauge();
    var replacement = bootstrap(replacementGauge);
    current.set(replacement);
    InferenceWiring.refreshGpuStatus(manager, replacement);
    assertTrue(replacementGauge.isMainGpuActive());
    listener.onModeChange(Mode.ONLINE, Mode.OFFLINE);
    assertTrue(replacementGauge.isMainGpuActive());
    online.set(false);
    listener.onModeChange(Mode.ONLINE, Mode.OFFLINE);
    assertFalse(replacementGauge.isMainGpuActive());
    assertTrue(firstGauge.isMainGpuActive());
    verify(manager).addModeChangeListener(listener);
  }

  @Test
  void eagerConnectionSeedsAnAlreadyOnlineManager() {
    var manager = mock(InferenceLifecycleManager.class);
    when(manager.isOnline()).thenReturn(true);
    var gauge = new GpuSchedulingGauge();
    var bootstrap = bootstrap(gauge);
    var listener = InferenceWiring.wireGpuStatusBroadcast(manager, () -> bootstrap);
    assertTrue(gauge.isMainGpuActive());
    verify(manager).addModeChangeListener(listener);
  }

  private static KnowledgeServerBootstrap bootstrap(GpuSchedulingGauge gauge) {
    var bootstrap = mock(KnowledgeServerBootstrap.class);
    when(bootstrap.gpuScheduling()).thenReturn(gauge);
    return bootstrap;
  }
}
