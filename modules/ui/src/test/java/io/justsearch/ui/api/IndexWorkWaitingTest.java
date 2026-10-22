/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.operating.IndexingState;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class IndexWorkWaitingTest {
  @Test
  void publicWorkerStateOwnsActiveWorkAcrossRetryAndFatalTransitions() throws Exception {
    var gpuGate = StatusLifecycleHandler.class.getDeclaredMethod("computeGpuActivityGate", WorkerOperationalView.class);
    gpuGate.setAccessible(true);
    var handler = org.mockito.Mockito.mock(StatusLifecycleHandler.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    for (String state : new String[] {"WAITING", "INDEXING", "INDEXING", "WAITING", "IDLE", "ERROR", "FAILED"}) {
      // Persisted backlog must not imply active work when all rows wait, or the loop died.
      var view = view(state);
      boolean active = "INDEXING".equals(state);
      assertEquals(active, StatusLifecycleHandler.hasActiveIndexWork(view), state);
      assertEquals(active ? 3L : 0L, gpuGate.invoke(handler, view),
          "a fatal loop's orphaned PROCESSING rows do not imply GPU activity");
      var now = Instant.parse("2026-10-21T12:00:00Z");
      var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, now.toString());
      var lifecycle = new LifecycleSnapshotV2(2, now.toString(),
          new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
          new LifecycleSnapshotV2.Components(ready, ready, ready, ready));
      var operating = new OperatingStateAssembler().assemble(lifecycle, null,
          PowerStatusView.unknown(), view, now.toEpochMilli(), false, 2000L, now, null);
      assertEquals(active ? IndexingState.RUNNING : IndexingState.IDLE, operating.indexing().state(), state);
    }
    assertFalse(StatusLifecycleHandler.hasActiveIndexWork(null));
    assertTrue(StatusLifecycleHandler.hasActiveIndexWork(view("INDEXING")));
  }

  private static WorkerOperationalView view(String state) {
    boolean terminal = "IDLE".equals(state) || "ERROR".equals(state);
    return WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback(state))
        .core(new CoreIndexView(!"FAILED".equals(state), 37, terminal ? 0 : 8, state, 0, 0))
        .migration(MigrationGenerationViewBuilder.builder()
            .pendingJobsCount(terminal ? 0 : 8)
            .pendingReadyJobsCount("INDEXING".equals(state) ? 1 : 0)
            .processingJobsCount("INDEXING".equals(state) || "FAILED".equals(state) ? 3 : 0).build())
        .build();
  }
}
