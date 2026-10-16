/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/*
 * Spec A step 9 in the operating block: a chat model the controller unloaded for another GPU user
 * reads UNLOADED with reason RESIDENCY_PRESSURE and its detail, not OFF; the indexing yield and a
 * chat switched off stay OFF.
 */
final class OperatingStateChatUnloadedTest {

  private static final String OBSERVED_AT = "2026-10-06T10:15:02.114Z";
  private static final Instant NOW = Instant.parse("2026-10-06T10:15:02.200Z");
  private static final long SAMPLED_AT_MS = Instant.parse("2026-10-06T10:15:01Z").toEpochMilli();

  @Test
  void aChatUnloadedForGpuPressureIsUnloadedWithTheGpuBusyDetail() {
    OperatingStateView view = assemble("Down", "inference.unloaded_gpu_pressure");
    assertEquals(ChatState.UNLOADED, view.chat().state());
    assertEquals("RESIDENCY_PRESSURE", view.chat().reason());
    assertEquals(StopDetail.GPU_BUSY, view.chat().detail());
    assertEquals("inference.unloaded_gpu_pressure", view.chat().reasonCode());
  }

  @Test
  void aChatUnloadedForAGameIsUnloadedWithTheGameModeDetail() {
    OperatingStateView view = assemble("", "inference.unloaded_game_mode");
    assertEquals(ChatState.UNLOADED, view.chat().state());
    assertEquals("RESIDENCY_PRESSURE", view.chat().reason());
    assertEquals(StopDetail.GAME_MODE, view.chat().detail());
  }

  @Test
  void theIndexingYieldStaysOff() {
    OperatingStateView view = assemble("Down", "inference.gpu_yielded_to_indexing");
    assertEquals(ChatState.OFF, view.chat().state());
    assertNull(view.chat().reason());
    assertNull(view.chat().detail());
  }

  private static OperatingStateView assemble(String engineState, String reason) {
    return new OperatingStateAssembler().assemble(
        snapshot(reason), inference(engineState), PowerStatusView.unknown(), worker(),
        SAMPLED_AT_MS, false, 2_000L, NOW, null);
  }

  private static LifecycleSnapshotV2 snapshot(String generativeReason) {
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, OBSERVED_AT);
    return new LifecycleSnapshotV2(
        LifecycleSnapshotV2.SCHEMA_VERSION,
        OBSERVED_AT,
        new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
        new LifecycleSnapshotV2.Components(
            ready,
            ready,
            ready,
            new LifecycleSnapshotV2.Component(
                ComponentState.UNAVAILABLE, generativeReason, "2026-10-06T10:00:00Z")));
  }

  private static InferenceRuntimeView inference(String engineState) {
    return new InferenceRuntimeView(
        "INDEXING", null, false, null, new LifecycleCounters(0L, 0L, 0L), true, engineState, "", "",
        "");
  }

  private static WorkerOperationalView worker() {
    return WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("SERVING"))
        .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
        .build();
  }
}
