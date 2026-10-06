/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.ChunkCoverageView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.EnrichmentProgressViewBuilder;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec E step E1, the operating indexing block over the availability booleans and the bulk-work
 * gate: E-IDLE rows 3 and 4 (preservation) and the verdict-driven states S7 asks the app to show.
 */
@DisplayName("operating indexing: availability-aware IDLE and the bulk-work verdict")
final class OperatingIndexingAvailabilityTest {

  private static final String OBSERVED_AT = "2026-10-06T08:00:00.000Z";
  private static final Instant NOW = Instant.parse("2026-10-06T08:00:00.100Z");
  private static final PowerStatusView AC = new PowerStatusView(false, "AC");

  // E-IDLE row 3: embedding enabled but unavailable (AI not activated) -> IDLE
  @Test
  void unavailableEmbeddingBacklogIsIdle() {
    var e = backlog().embeddingAvailable(false).build();
    assertEquals(IndexingState.IDLE, assemble(new OperatingStateAssembler(), AC,
        worker(core(0), e)).state());
  }

  // E-IDLE row 4: 50 pending VDU with VDU unavailable -> IDLE; available -> RUNNING
  @Test
  void vduBacklogCountsOnlyWhenAvailable() {
    var none = EnrichmentProgressView.empty();
    var unavailable = vdu(50).withVduAvailable(false);
    var available = vdu(50).withVduAvailable(true);
    assertEquals(IndexingState.IDLE,
        assemble(new OperatingStateAssembler(), AC, worker(unavailable, none)).state());
    assertEquals(IndexingState.RUNNING,
        assemble(new OperatingStateAssembler(), AC, worker(available, none)).state());
  }

  // every enabled and available stage's backlog holds IDLE off; unavailable ones do not
  @Test
  void eachStageBacklog() {
    var empty = EnrichmentProgressView.empty();
    var chunkPending =
        new ChunkCoverageView(10, 0, 10, 0, 0.0, false, true, 0, 10, 0.0);
    record Row(String name, EnrichmentProgressView e, IndexingState expected) {}
    List<Row> rows = List.of(
        new Row("nothing pending", empty, IndexingState.IDLE),
        new Row("chunk embedding pending",
            builder(empty).chunk(chunkPending).spladeAvailable(false).build(),
            IndexingState.RUNNING),
        new Row("chunk splade pending, sparse available",
            builder(empty).chunk(chunkPending).embeddingAvailable(false).build(),
            IndexingState.RUNNING),
        new Row("chunk backlog, both unavailable",
            builder(empty).chunk(chunkPending).embeddingAvailable(false).spladeAvailable(false)
                .build(),
            IndexingState.IDLE),
        new Row("splade pending", builder(empty).spladePendingCount(4).build(),
            IndexingState.RUNNING),
        new Row("splade pending, disabled",
            builder(empty).spladePendingCount(4).spladeEnabled(false).build(), IndexingState.IDLE),
        new Row("NER pending", builder(empty).pendingNerCount(4).build(), IndexingState.RUNNING),
        new Row("NER pending, unavailable",
            builder(empty).pendingNerCount(4).nerAvailable(false).build(), IndexingState.IDLE));
    for (Row row : rows) {
      assertEquals(row.expected(),
          assemble(new OperatingStateAssembler(), AC, worker(core(0), row.e())).state(),
          row.name());
    }
  }

  // S7: chat generating with work left -> SLOWED [chat_generating], affects indexing
  @Test
  void generatingSlows() {
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P16_PLUS);
    gate.setGenerating(true);
    var assembler = new OperatingStateAssembler();
    assembler.setBulkWork(gate::snapshot);

    var slowed = assemble(assembler, AC, worker(core(5), EnrichmentProgressView.empty()));
    assertEquals(IndexingState.SLOWED, slowed.state());
    assertEquals(List.of(IndexingReason.CHAT_GENERATING), slowed.reasons());
    assertEquals(List.of(BulkWorkKind.INDEXING), slowed.affects());
    assertEquals("indexing.slowed_chat_generating", slowed.reasonCode(),
        "Spec E step E2 lands the chat-generating code with its producer");

    gate.setGenerating(false);
    var resumed = assemble(assembler, AC, worker(core(5), EnrichmentProgressView.empty()));
    assertEquals(IndexingState.RUNNING, resumed.state(), "resumes afterwards");
    assertEquals(List.of(), resumed.reasons());
  }

  // S7: a game or external memory pauses; reasons accumulate in precedence order
  @Test
  void holdPausesAndReasonsAccumulate() {
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setGame(true);
    gate.setGenerating(true);
    var assembler = new OperatingStateAssembler();
    assembler.setBulkWork(gate::snapshot);

    var paused = assemble(assembler, new PowerStatusView(true, "BATTERY"),
        worker(core(5), EnrichmentProgressView.empty()));
    assertEquals(IndexingState.PAUSED, paused.state());
    assertEquals(
        List.of(IndexingReason.GAME_MODE, IndexingReason.ENERGY_SAVER,
            IndexingReason.CHAT_GENERATING),
        paused.reasons());
    assertNull(paused.reasonCode(), "game mode is primary; its code lands in E4");
  }

  // energy saver comes from this pass's power view, never twice and never stale
  @Test
  void energyFromPowerView() {
    var gate = new BulkWorkGate();
    gate.setEnergyReduced(true); // the gate's mirror may lag the power view by one poll
    var assembler = new OperatingStateAssembler();
    assembler.setBulkWork(gate::snapshot);

    var running = assemble(assembler, AC, worker(core(5), EnrichmentProgressView.empty()));
    assertEquals(IndexingState.RUNNING, running.state());
    assertEquals(List.of(), running.reasons());

    var paused = assemble(assembler, new PowerStatusView(true, "BATTERY"),
        worker(core(5), EnrichmentProgressView.empty()));
    assertEquals(IndexingState.PAUSED, paused.state());
    assertEquals(List.of(IndexingReason.ENERGY_SAVER), paused.reasons());
    assertEquals("indexing.paused_energy_saver", paused.reasonCode());
  }

  // a gate that cannot be read leaves the block as if no gate reason were present
  @Test
  void unreadableGate() {
    var assembler = new OperatingStateAssembler();
    assembler.setBulkWork(() -> {
      throw new IllegalStateException("bootstrap not connected");
    });
    var view = assemble(assembler, AC, worker(core(5), EnrichmentProgressView.empty()));
    assertEquals(IndexingState.RUNNING, view.state());
  }

  private static EnrichmentProgressViewBuilder backlog() {
    return builder(EnrichmentProgressView.empty()).embeddingPendingCount(3_000L);
  }

  private static EnrichmentProgressViewBuilder builder(EnrichmentProgressView from) {
    return EnrichmentProgressViewBuilder.builder(from);
  }

  private static CoreIndexView vdu(int pendingVdu) {
    return new CoreIndexView(true, 3_000, 0, "SERVING", 0, pendingVdu);
  }

  private static CoreIndexView core(long pendingJobs) {
    return new CoreIndexView(true, 3_000, pendingJobs, "SERVING", 0, 0);
  }

  private static WorkerOperationalView worker(CoreIndexView core, EnrichmentProgressView e) {
    return WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("SERVING"))
        .core(core)
        .enrichment(e)
        .build();
  }

  private static OperatingIndexingView assemble(
      OperatingStateAssembler assembler, PowerStatusView power, WorkerOperationalView worker) {
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
    return assembler
        .assemble(lifecycle, inference, power, worker, NOW.toEpochMilli(), false, 2_000L, NOW, null)
        .indexing();
  }
}
