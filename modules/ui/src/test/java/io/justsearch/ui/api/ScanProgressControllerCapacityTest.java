/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.ScanProgressRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ScanProgressControllerCapacityTest {
  @Test
  void saturatedSubscriptionRefusesBeforeStartingSse() {
    try (var executors = new TestEngineExecutors(); var registry = new ScanProgressRegistry(executors)) {
      registry.register("busy", new CancelToken());
      var subscriptions = new ArrayList<ScanProgressRegistry.Subscription>();
      try {
        for (int i = 0; i < 48; i++) subscriptions.add(registry.subscribe("busy"));
        var ctx = mock(Context.class);
        when(ctx.pathParam("scanId")).thenReturn("busy");
        when(ctx.path()).thenReturn("/api/scans/busy/progress");
        new ScanProgressController(registry, null).handleScanProgress(ctx);
        verify(ctx).status(429);
        verify(ctx).header("Retry-After", "1");
        verify(ctx).json(any());
        verify(ctx, never()).contentType("text/event-stream");
      } finally {
        subscriptions.forEach(ScanProgressRegistry.Subscription::close);
      }
    }
  }
}
