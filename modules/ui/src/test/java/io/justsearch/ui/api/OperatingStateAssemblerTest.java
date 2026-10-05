/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.operating.BulkWorkKind;
import io.justsearch.app.api.status.operating.IndexingReason;
import io.justsearch.app.api.status.operating.IndexingState;
import io.justsearch.app.api.status.operating.OperatingBlock;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.app.api.status.operating.Profile;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("operating block: a projection of today's runtime state")
final class OperatingStateAssemblerTest {

  private static final String OBSERVED_AT = "2026-10-05T10:15:02.114Z";
  private static final Instant NOW = Instant.parse("2026-10-05T10:15:02.200Z");
  private static final long SAMPLED_AT_MS = Instant.parse("2026-10-05T10:15:01Z").toEpochMilli();

  @ParameterizedTest(name = "engine {0} + generative {1} -> {2}")
  @CsvSource({
    "Healthy,    READY,       READY,   ",
    "Starting,   STARTING,    LOADING, ",
    "Recovering, RELOADING,   LOADING, RECOVERY",
    "Down,       FAILED,      FAILED,  ",
    "Down,       ABSENT,      OFF,     ",
    "Down,       UNAVAILABLE, OFF,     ",
    "'',         READY,       READY,   ",
    "'',         STARTING,    LOADING, ",
    "'',         RELOADING,   LOADING, ",
    "'',         FAILED,      FAILED,  ",
    "'',         ABSENT,      OFF,     ",
  })
  @DisplayName("chat state comes from the ENGINE axis, else from the generative component")
  void chatStateMapping(
      String engineState, ComponentState generative, ChatState expected, String reason) {
    OperatingStateView view =
        new OperatingStateAssembler()
            .assemble(
                snapshot(LifecycleState.LIFECYCLE_STATE_READY, generative, "inference.x"),
                inference(engineState, false),
                PowerStatusView.unknown(),
                worker(0),
                SAMPLED_AT_MS,
                false,
                2_000L,
                NOW);

    assertEquals(expected, view.chat().state());
    assertEquals(reason, view.chat().reason());
  }

  @Test
  @DisplayName("chat reasonCode and since are the generative component's own (same pass)")
  void chatReasonCodeEqualsGenerativeComponent() {
    LifecycleSnapshotV2 snapshot =
        snapshot(
            LifecycleState.LIFECYCLE_STATE_READY,
            ComponentState.UNAVAILABLE,
            "inference.gpu_yielded_to_indexing");
    OperatingStateView view =
        new OperatingStateAssembler()
            .assemble(snapshot, inference("Down", true), PowerStatusView.unknown(), worker(0),
                SAMPLED_AT_MS, false, 2_000L, NOW);

    assertEquals(
        snapshot.components().generative().reason_code(), view.chat().reasonCode());
    assertEquals(snapshot.components().generative().state_since(), view.chat().since());
    assertEquals(OBSERVED_AT, view.assembledAt());
    assertEquals(OBSERVED_AT, view.chat().sampledAt());
    assertEquals(false, view.chat().managed(), "an external server is not managed");
    // Nothing the projection cannot observe is guessed.
    assertNull(view.chat().generating());
    assertNull(view.chat().inFlight());
    assertNull(view.chat().queued());
    assertNull(view.chat().blockedBy());
    assertNull(view.chat().load());
    assertNull(view.chat().reloadAllowed());
  }

  @ParameterizedTest(name = "lifecycle {0} omits chat")
  @CsvSource({"LIFECYCLE_STATE_STOPPING", "LIFECYCLE_STATE_STOPPED"})
  @DisplayName("the Engine leaving is never a chat state: the chat block is absent")
  void chatAbsentWhileEngineLeaves(LifecycleState state) {
    OperatingStateView view =
        new OperatingStateAssembler()
            .assemble(snapshot(state, ComponentState.READY, null), inference("Healthy", false),
                PowerStatusView.unknown(), worker(3), SAMPLED_AT_MS, false, 2_000L, NOW);

    assertNull(view.chat());
  }

  @Test
  @DisplayName("indexing: IDLE with nothing pending, RUNNING with work, PAUSED for energy saver")
  void indexingProjection() {
    OperatingStateAssembler assembler = new OperatingStateAssembler();
    PowerStatusView saving = new PowerStatusView(true, "BATTERY");
    PowerStatusView normal = new PowerStatusView(false, "AC");

    var idle = assemble(assembler, normal, worker(0)).indexing();
    assertEquals(IndexingState.IDLE, idle.state());
    assertEquals(List.of(), idle.reasons());
    assertEquals(0L, idle.pendingJobs());

    var running = assemble(assembler, normal, worker(5)).indexing();
    assertEquals(IndexingState.RUNNING, running.state());
    assertEquals(List.of(), running.affects());

    var paused = assemble(assembler, saving, worker(5)).indexing();
    assertEquals(IndexingState.PAUSED, paused.state());
    assertEquals(List.of(IndexingReason.ENERGY_SAVER), paused.reasons());
    assertEquals(List.of(BulkWorkKind.INDEXING), paused.affects());
    assertEquals(5L, paused.pendingJobs());
    assertNull(paused.reasonCode(), "no lifecycle code exists for this reason yet");

    var idleButSaving = assemble(assembler, saving, worker(0)).indexing();
    assertEquals(IndexingState.IDLE, idleButSaving.state(), "IDLE wins with nothing pending");
    assertEquals(List.of(IndexingReason.ENERGY_SAVER), idleButSaving.reasons(),
        "the pause condition is still listed");
    assertEquals(Instant.ofEpochMilli(SAMPLED_AT_MS).toString(), idleButSaving.sampledAt());
  }

  @Test
  @DisplayName("indexing since moves only when the state or reasons change")
  void indexingSinceTracksTransitions() {
    OperatingStateAssembler assembler = new OperatingStateAssembler();
    PowerStatusView normal = new PowerStatusView(false, "AC");
    Instant t0 = NOW;
    Instant t1 = NOW.plusSeconds(2);
    Instant t2 = NOW.plusSeconds(4);

    var first = assembler.assemble(snapshot(LifecycleState.LIFECYCLE_STATE_READY,
        ComponentState.READY, null), inference("Healthy", false), normal, worker(5),
        SAMPLED_AT_MS, false, 2_000L, t0).indexing();
    var same = assembler.assemble(snapshot(LifecycleState.LIFECYCLE_STATE_READY,
        ComponentState.READY, null), inference("Healthy", false), normal, worker(4),
        SAMPLED_AT_MS, false, 2_000L, t1).indexing();
    var changed = assembler.assemble(snapshot(LifecycleState.LIFECYCLE_STATE_READY,
        ComponentState.READY, null), inference("Healthy", false), normal, worker(0),
        SAMPLED_AT_MS, false, 2_000L, t2).indexing();

    assertEquals(t0.toString(), first.since());
    assertEquals(t0.toString(), same.since(), "still RUNNING: since does not move");
    assertEquals(t2.toString(), changed.since());
  }

  @Test
  @DisplayName("indexing is absent without a Worker view, and listed stale when the sample is")
  void indexingAbsenceAndStaleness() {
    OperatingStateAssembler assembler = new OperatingStateAssembler();
    var none = assembler.assemble(snapshot(LifecycleState.LIFECYCLE_STATE_READY,
        ComponentState.READY, null), inference("Healthy", false), PowerStatusView.unknown(),
        null, SAMPLED_AT_MS, true, 2_000L, NOW);
    assertNull(none.indexing());
    assertEquals(List.of(), none.freshness().staleSources());

    var stale = assembler.assemble(snapshot(LifecycleState.LIFECYCLE_STATE_READY,
        ComponentState.READY, null), inference("Healthy", false), PowerStatusView.unknown(),
        worker(1), SAMPLED_AT_MS, true, 10_000L, NOW);
    assertEquals(List.of(OperatingBlock.INDEXING), stale.freshness().staleSources());
    assertEquals(30_000L, stale.freshness().staleAfterMs(), "three sampler periods");
  }

  @Test
  @DisplayName("gpu is unknown and degradations empty: no producer feeds them yet")
  void gpuUnknownAndNoDegradations() {
    OperatingStateView view = assemble(new OperatingStateAssembler(), PowerStatusView.unknown(),
        worker(0));

    assertEquals(Profile.UNRESOLVED, view.gpu().profile());
    assertEquals(GpuMemorySource.NONE, view.gpu().source());
    assertNull(view.gpu().adapterTotalBytes());
    assertNull(view.gpu().adapterFreeBytes());
    assertEquals(List.of(), view.degradations());
    assertEquals(OperatingStateView.SCHEMA_VERSION, view.schemaVersion());
  }

  @Test
  @DisplayName("gpu: the projected reading passes through; byte figures do not move seq, a stale one is listed")
  void gpuReadingPassesThrough() {
    var assembler = new OperatingStateAssembler();
    var first = assembleWithGpu(assembler, gpu(GpuMemorySource.PDH, 2_000L, 1_000L, false));
    assertEquals(GpuMemorySource.PDH, first.gpu().source());
    assertEquals(2_000L, first.gpu().adapterFreeBytes());
    assertEquals(1_000L, first.gpu().externalUsedBytes());
    assertEquals(List.of(), first.freshness().staleSources());

    var moved = assembleWithGpu(assembler, gpu(GpuMemorySource.PDH, 1_500L, 1_500L, false));
    assertEquals(first.seq(), moved.seq(), "memory figures are measurements, not content");

    var nvml = assembleWithGpu(assembler, gpu(GpuMemorySource.NVML, 1_500L, null, false));
    assertEquals(first.seq() + 1, nvml.seq(), "a different source is content");

    var stale = assembleWithGpu(assembler, gpu(GpuMemorySource.NVML, 1_500L, null, true));
    assertEquals(List.of(OperatingBlock.GPU), stale.freshness().staleSources());
  }

  private static OperatingStateView assembleWithGpu(
      OperatingStateAssembler assembler, OperatingGpuView gpu) {
    return assembler.assemble(
        snapshot(LifecycleState.LIFECYCLE_STATE_READY, ComponentState.READY, null),
        inference("Healthy", false), PowerStatusView.unknown(), null, SAMPLED_AT_MS, false, 2_000L,
        NOW, gpu);
  }

  private static OperatingGpuView gpu(
      GpuMemorySource source, long free, Long external, boolean stale) {
    boolean pdh = source == GpuMemorySource.PDH;
    return new OperatingGpuView(Profile.UNRESOLVED, null, "RTX", 12_000L, free,
        pdh ? 3_000L : null, pdh ? 10L : null, external, null, source, "2026-10-05T10:00:00Z",
        stale ? 7_000L : 5L, stale);
  }

  @Test
  @DisplayName("seq starts at 1 and grows only when content other than sample times changes")
  void seqGrowsOnContentChangeOnly() {
    OperatingStateAssembler assembler = new OperatingStateAssembler();
    PowerStatusView normal = new PowerStatusView(false, "AC");

    long first = assemble(assembler, normal, worker(0)).seq();
    long resampled = assembler.assemble(snapshot(LifecycleState.LIFECYCLE_STATE_READY,
        ComponentState.READY, null, "2026-10-05T10:15:04.000Z"), inference("Healthy", false),
        normal, worker(0), SAMPLED_AT_MS + 2_000L, false, 2_000L, NOW.plusSeconds(2)).seq();
    long changed = assemble(assembler, normal, worker(9)).seq();

    assertEquals(1L, first);
    assertEquals(first, resampled, "a new sample of the same state keeps seq");
    assertNotEquals(resampled, changed);
    assertEquals(2L, changed);
  }

  @Test
  @DisplayName("the assembled view always validates (every projected chat reason is in vocabulary)")
  void projectedViewsAreValid() {
    for (String engine : List.of("Healthy", "Starting", "Recovering", "Down", "", "Unknown")) {
      for (ComponentState component : ComponentState.values()) {
        OperatingStateView view = new OperatingStateAssembler().assemble(
            snapshot(LifecycleState.LIFECYCLE_STATE_READY, component, null),
            inference(engine, false), PowerStatusView.unknown(), worker(0), SAMPLED_AT_MS, false,
            2_000L, NOW);
        assertTrue(view.chat() != null, engine + "/" + component);
      }
    }
  }

  private static OperatingStateView assemble(
      OperatingStateAssembler assembler, PowerStatusView power, WorkerOperationalView worker) {
    return assembler.assemble(
        snapshot(LifecycleState.LIFECYCLE_STATE_READY, ComponentState.READY, null),
        inference("Healthy", false), power, worker, SAMPLED_AT_MS, false, 2_000L, NOW);
  }

  private static LifecycleSnapshotV2 snapshot(
      LifecycleState state, ComponentState generative, String generativeReason) {
    return snapshot(state, generative, generativeReason, OBSERVED_AT);
  }

  private static LifecycleSnapshotV2 snapshot(
      LifecycleState state, ComponentState generative, String generativeReason, String observed) {
    var ready = new LifecycleSnapshotV2.Component(ComponentState.READY, null, observed);
    return new LifecycleSnapshotV2(
        LifecycleSnapshotV2.SCHEMA_VERSION,
        observed,
        new LifecycleSnapshotV2.Lifecycle(state, null, null),
        new LifecycleSnapshotV2.Components(
            ready,
            ready,
            ready,
            new LifecycleSnapshotV2.Component(
                generative, generativeReason, "2026-10-05T10:00:00Z")));
  }

  private static InferenceRuntimeView inference(String engineState, boolean external) {
    return new InferenceRuntimeView(
        "ONLINE", null, external, null, new LifecycleCounters(0L, 0L, 0L), true, engineState, "",
        "", "");
  }

  private static WorkerOperationalView worker(long pending) {
    WorkerOperationalView fallback = WorkerOperationalView.fallback("SERVING");
    return WorkerOperationalViewBuilder.builder(fallback)
        .core(new CoreIndexView(true, 10, pending, "SERVING", 0, 0))
        .build();
  }
}
