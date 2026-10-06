/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.operating.BulkWorkKind;
import io.justsearch.app.api.status.operating.IndexingReason;
import io.justsearch.app.api.status.operating.IndexingState;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec E step E4, the status half of S7 for a game: while a game runs, the operating indexing block
 * reads PAUSED [game_mode] with the reason code {@code indexing.paused_game_mode}, the game taking
 * precedence over external memory; RUNNING again once the game ends.
 */
final class OperatingIndexingGameModeTest {

  private static final String OBSERVED_AT = "2026-10-06T08:00:00.000Z";
  private static final Instant NOW = Instant.parse("2026-10-06T08:00:00.100Z");
  private static final PowerStatusView AC = new PowerStatusView(false, "AC");

  // S7: a game pauses indexing with indexing.paused_game_mode, and it resumes after
  @Test
  void aGamePausesWithTheGameModeCode() {
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setGame(true);
    var assembler = new OperatingStateAssembler();
    assembler.setBulkWork(gate::snapshot);

    var paused = assemble(assembler);
    assertEquals(IndexingState.PAUSED, paused.state());
    assertEquals(List.of(IndexingReason.GAME_MODE), paused.reasons());
    assertEquals(List.of(BulkWorkKind.INDEXING), paused.affects());
    assertEquals("indexing.paused_game_mode", paused.reasonCode());

    gate.setGame(false);
    var resumed = assemble(assembler);
    assertEquals(IndexingState.RUNNING, resumed.state(), "resumes afterwards");
    assertEquals(List.of(), resumed.reasons());
  }

  // a game with external memory pressure reads as the game first, and its code
  @Test
  void aGameOutranksExternalMemory() {
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setGame(true);
    gate.setExternalPressure(true);
    var assembler = new OperatingStateAssembler();
    assembler.setBulkWork(gate::snapshot);

    var paused = assemble(assembler);
    assertEquals(
        List.of(IndexingReason.GAME_MODE, IndexingReason.GPU_BUSY_EXTERNAL), paused.reasons());
    assertEquals("indexing.paused_game_mode", paused.reasonCode());

    gate.setGame(false);
    assertEquals("indexing.paused_gpu_busy", assemble(assembler).reasonCode(),
        "the memory pressure's code once the game ends");
  }

  private static OperatingIndexingView assemble(OperatingStateAssembler assembler) {
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, OBSERVED_AT);
    var lifecycle =
        new LifecycleSnapshotV2(
            LifecycleSnapshotV2.SCHEMA_VERSION,
            OBSERVED_AT,
            new LifecycleSnapshotV2.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY, null, null),
            new LifecycleSnapshotV2.Components(ready, ready, ready, ready));
    var inference =
        new InferenceRuntimeView(
            "ONLINE", null, false, null, new LifecycleCounters(0L, 0L, 0L), true, "Healthy", "",
            "", "");
    var worker = WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("SERVING"))
        .core(new CoreIndexView(true, 3_000, 5, "SERVING", 0, 0))
        .enrichment(EnrichmentProgressView.empty())
        .build();
    return assembler
        .assemble(lifecycle, inference, AC, worker, NOW.toEpochMilli(), false, 2_000L, NOW, null)
        .indexing();
  }
}
