/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.chat.ChatReasonCodes;
import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.chat.LoadingCause;
import io.justsearch.app.api.chat.UnloadReason;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.ChunkCoverageView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.operating.BulkWorkKind;
import io.justsearch.app.api.status.operating.DegradationCode;
import io.justsearch.app.api.status.operating.DegradationScope;
import io.justsearch.app.api.status.operating.IndexingReason;
import io.justsearch.app.api.status.operating.IndexingState;
import io.justsearch.app.api.status.operating.OperatingBlock;
import io.justsearch.app.api.status.operating.OperatingChatView;
import io.justsearch.app.api.status.operating.OperatingDegradationView;
import io.justsearch.app.api.status.operating.OperatingFreshnessView;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.app.services.gpu.GpuProfileResolver;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.scheduling.BulkWorkGate;
import io.justsearch.core.scheduling.BulkWorkPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Assembles the {@code operating} block of {@code /api/status} from the values the status pass has
 * already read, so it can never disagree with them (ADR-0050).
 *
 * <p>This first producer projects today's state only. It invents nothing it cannot observe:
 *
 * <ul>
 *   <li><b>chat</b> comes from the runtime ENGINE axis ({@code inference.engineState}) and the
 *       generative component of the same pass. Healthy is READY, Starting and Recovering are
 *       LOADING, and Down is FAILED when the component failed, LOADING while the component reports
 *       a start (the boot-order wait), else OFF. A FAILED chat's {@code reason} is the failure its
 *       reason code classifies, when exactly one does. A stopped chat whose reason code says the
 *       chat controller unloaded it for another GPU user is UNLOADED (RESIDENCY_PRESSURE) with
 *       detail {@code gpu_busy} or {@code game_mode} (Spec A step 9). {@code reasonCode} and
 *       {@code since} are the component's own, so they equal {@code components.generative}. The
 *       block is absent while the Engine is stopping. Generation activity, reload blocking and load
 *       progress have no producer yet and are {@code null}.
 *   <li><b>indexing</b> (Spec E step E1) is IDLE when the queue is empty and no enabled and
 *       available enrichment stage, VDU included, has work pending. Otherwise it is the mode of the
 *       reasons present: the Engine's bulk-work gate verdict, with energy saver taken from this
 *       pass's power view. PAUSED for energy saver carries {@code indexing.paused_energy_saver}.
 *       SLOWED (step E2: chat is answering, from the chat server's generation signal) carries
 *       {@code indexing.slowed_chat_generating}. PAUSED because another application needs the
 *       graphics card's memory (step E3: the Engine's GPU residency sampler raised external
 *       pressure) carries {@code indexing.paused_gpu_busy}. PAUSED because a game runs (step E4:
 *       the sampler's game detection) carries {@code indexing.paused_game_mode}; a game is the
 *       first reason whenever it is present.
 *   <li><b>gpu</b> is the reading {@link OperatingGpuProjection} gives the status pass (diagnostic
 *       only: no control decision reads it), or unknown without one. Since step E3 the projection
 *       reads the Engine's GPU residency sampler, never a reader of its own. Its profile and
 *       profile source are the Engine's device-memory plan (step E7b), UNRESOLVED without one.
 *   <li><b>degradations</b> lists {@code user_setting_unmet} while an explicit user setting outranks
 *       the plan and cannot be honoured (step E7b, P4): its cause is the unmet settings' tokens,
 *       its reasonCode {@code inference.setting_unmet}. It lists {@code device_below_p8} while the
 *       pinned GPU is below the P8 boundary, so no plan applies and today's constants stay; the
 *       profile then reads P8, the smallest class, and this entry says the device is below it.
 *       An unplanned P8 device names its over-commit, and an unmeasured BGE-M3 CPU placement
 *       names that reason while other roles keep the plan.
 * </ul>
 *
 * <p>{@code seq} grows whenever the content, ignoring sample times, differs from the previous
 * assembly. GPU memory figures and ages are measurements, not content, so they do not move seq;
 * the gpu source, adapter and staleness do. The status path calls this from the sampler and from
 * request threads, so it is synchronized.
 */
final class OperatingStateAssembler {

  private long seq;
  private Object lastContent;
  private IndexingState lastIndexingState;
  private List<IndexingReason> lastIndexingReasons;
  private String indexingSince;
  private volatile Supplier<BulkWorkGate.Snapshot> bulkWork;
  private volatile Supplier<GpuProfileResolver.Resolution> devicePlan;
  private final Episode unmet = new Episode();
  private final Episode belowP8 = new Episode();
  private final Episode overcommit = new Episode();
  private final Episode bgeUnmeasured = new Episode();

  /**
   * The Engine's bulk-work gate (Spec E step E1), read once per pass. Without one, or while it
   * cannot be read, the indexing block reads as if no gate reason were present.
   */
  void setBulkWork(Supplier<BulkWorkGate.Snapshot> source) {
    this.bulkWork = source;
  }

  /**
   * The Engine's device-memory plan (Spec E step E7b), read once per pass. Without one, or while it
   * cannot be read, the gpu profile is the reading's and no degradation is listed.
   */
  void setDevicePlan(Supplier<GpuProfileResolver.Resolution> source) {
    this.devicePlan = source;
  }

  /**
   * One assembly.
   *
   * @param worker the sampled Worker view, or null when there is none
   * @param workerSampledAtMs when {@code worker} was observed
   * @param workerStale whether that observation is stale or failed
   * @param samplingPeriodMs the sampler's current period
   * @param gpu the projected GPU memory reading, or null when there is no reader
   */
  synchronized OperatingStateView assemble(
      LifecycleSnapshotV2 lifecycle,
      InferenceRuntimeView inference,
      PowerStatusView power,
      WorkerOperationalView worker,
      long workerSampledAtMs,
      boolean workerStale,
      long samplingPeriodMs,
      Instant now,
      OperatingGpuView gpu) {
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(now, "now");
    String assembledAt = lifecycle.observed_at();

    OperatingChatView chat = chat(lifecycle, inference, assembledAt);
    OperatingIndexingView indexing = indexing(power, worker, workerSampledAtMs, now);
    if (gpu == null) gpu = OperatingGpuView.unknown();
    GpuProfileResolver.Resolution plan = readDevicePlan();
    if (plan != null) gpu = withProfile(gpu, plan);
    List<OperatingDegradationView> degradations = degradations(plan, assembledAt);
    List<OperatingBlock> staleSources = new ArrayList<>();
    if (indexing != null && workerStale) staleSources.add(OperatingBlock.INDEXING);
    if (gpu.stale()) staleSources.add(OperatingBlock.GPU);
    OperatingFreshnessView freshness =
        new OperatingFreshnessView(Math.max(1L, 3L * samplingPeriodMs), List.copyOf(staleSources));

    // Sample times are not content: a pass that only re-reads the same state keeps its seq.
    Object content = Arrays.asList(
        chat == null ? null : withChatSampledAt(chat, ""),
        indexing == null ? null : withIndexingSampledAt(indexing, ""),
        withoutMeasurements(gpu),
        degradations.stream().map(OperatingStateAssembler::withoutLastSeen).toList(),
        freshness);
    if (!content.equals(lastContent)) {
      seq++;
      lastContent = content;
    }
    return new OperatingStateView(
        OperatingStateView.SCHEMA_VERSION, seq, assembledAt, chat, indexing, gpu, degradations,
        freshness);
  }

  private GpuProfileResolver.Resolution readDevicePlan() {
    Supplier<GpuProfileResolver.Resolution> source = devicePlan;
    if (source == null) return null;
    try {
      return source.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The gpu block with the plan's profile and source in place of the reading's. */
  private static OperatingGpuView withProfile(
      OperatingGpuView g, GpuProfileResolver.Resolution plan) {
    return new OperatingGpuView(plan.statusProfile(), plan.source(), g.adapter(),
        g.adapterTotalBytes(), g.adapterFreeBytes(), g.ourDedicatedBytes(), g.ourSharedBytes(),
        g.externalUsedBytes(), g.spillSuspected(), g.source(), g.sampledAt(), g.ageMs(), g.stale());
  }

  /**
   * {@code user_setting_unmet} while the plan reports unmet settings, and {@code device_below_p8}
   * while the device is below the P8 boundary, the P8 over-commit notice, and BGE-M3's CPU reason.
   * Each entry is an episode: it keeps its {@code since}
   * while its cause is unchanged, a changed cause starts a new episode, and {@code count} is the
   * number of episodes so far.
   */
  private List<OperatingDegradationView> degradations(
      GpuProfileResolver.Resolution plan, String assembledAt) {
    List<OperatingDegradationView> out = new ArrayList<>();
    String unmetCause = plan == null || plan.unmetSettings().isEmpty()
        ? null : String.join(",", new java.util.TreeSet<>(plan.unmetSettings()));
    unmet.see(unmetCause, assembledAt).ifPresent(e -> out.add(new OperatingDegradationView(
        DegradationCode.USER_SETTING_UNMET, unmetCause, DegradationScope.RUNTIME,
        LifecycleReasonCode.INFERENCE_SETTING_UNMET.code(), e.since, assembledAt, e.count)));
    String belowCause = plan != null && plan.belowP8() ? "device_memory" : null;
    belowP8.see(belowCause, assembledAt).ifPresent(e -> out.add(new OperatingDegradationView(
        DegradationCode.DEVICE_BELOW_P8, belowCause, DegradationScope.RUNTIME,
        LifecycleReasonCode.INFERENCE_DEVICE_BELOW_P8.code(), e.since, assembledAt, e.count)));
    var arena = plan == null ? java.util.Optional.<io.justsearch.ort.GpuArenaPlan>empty()
        : plan.plan();
    String overcommitCause = arena.filter(p -> !p.planned()
        && p.profile() == io.justsearch.ort.GpuArenaPlan.Profile.P8
        && p.unmetSettings().isEmpty()).isPresent() ? "device_memory" : null;
    overcommit.see(overcommitCause, assembledAt).ifPresent(e -> out.add(new OperatingDegradationView(
        DegradationCode.DEVICE_MEMORY_OVERCOMMIT, overcommitCause, DegradationScope.RUNTIME,
        LifecycleReasonCode.INFERENCE_DEVICE_MEMORY_OVERCOMMIT.code(), e.since, assembledAt, e.count)));
    String bgeCause = arena.filter(p -> p.roles().values().stream().anyMatch(r ->
        r.bulkCpuReason().filter(io.justsearch.ort.GpuArenaPlan.BGE_M3_UNMEASURED::equals)
            .isPresent())).isPresent() ? "bge_m3_unmeasured" : null;
    bgeUnmeasured.see(bgeCause, assembledAt).ifPresent(e -> out.add(new OperatingDegradationView(
        DegradationCode.BGE_M3_UNMEASURED, bgeCause, DegradationScope.RUNTIME,
        LifecycleReasonCode.INDEXING_BGE_M3_UNMEASURED.code(), e.since, assembledAt, e.count)));
    return List.copyOf(out);
  }

  /** One standing degradation's episodes. */
  private static final class Episode {
    private String cause;
    private String since;
    private long count;

    /** Records a sighting; empty while the entry is absent ({@code cause} null). */
    java.util.Optional<Episode> see(String current, String at) {
      if (current == null) {
        cause = null;
        since = null;
        return java.util.Optional.empty();
      }
      if (!current.equals(cause)) {
        cause = current;
        since = at;
        count++;
      }
      return java.util.Optional.of(this);
    }
  }

  private static OperatingDegradationView withoutLastSeen(OperatingDegradationView d) {
    return new OperatingDegradationView(
        d.code(), d.cause(), d.scope(), d.reasonCode(), d.since(), d.since(), d.count());
  }

  private static OperatingChatView chat(
      LifecycleSnapshotV2 lifecycle, InferenceRuntimeView inference, String sampledAt) {
    LifecycleState engine = lifecycle.lifecycle().state();
    if (engine == LifecycleState.LIFECYCLE_STATE_STOPPING
        || engine == LifecycleState.LIFECYCLE_STATE_STOPPED) {
      return null;
    }
    LifecycleSnapshotV2.Component generative = lifecycle.components().generative();
    ChatState state;
    LoadingCause cause = null;
    // A null inference view (no inference wiring at all) leaves the component as the only source.
    switch (inference == null ? "" : inference.engineState()) {
      case RuntimeStatus.ENGINE_HEALTHY -> state = ChatState.READY;
      case RuntimeStatus.ENGINE_STARTING -> state = ChatState.LOADING;
      case RuntimeStatus.ENGINE_RECOVERING -> {
        state = ChatState.LOADING;
        cause = LoadingCause.RECOVERY;
      }
      // The engine is down while chat waits for the query encoders (Spec A §6.2): the component
      // already reports the start, so it is LOADING, not OFF.
      case RuntimeStatus.ENGINE_DOWN -> state = switch (generative.state()) {
        case FAILED -> ChatState.FAILED;
        case STARTING, RELOADING -> ChatState.LOADING;
        default -> ChatState.OFF;
      };
      // No runtime authority attached (AI not configured, or the boot window before it attaches):
      // the generative component is the only observation.
      default -> state = fromComponent(generative.state());
    }
    // Spec A step 9: a stopped server the chat controller unloaded for another GPU user is
    // UNLOADED (RESIDENCY_PRESSURE) with its detail, not OFF: a request loads it again.
    StopDetail unloadDetail = state == ChatState.OFF
        ? ChatReasonCodes.residencyUnloadDetailOf(generative.reason_code()).orElse(null)
        : null;
    if (unloadDetail != null) state = ChatState.UNLOADED;
    // Spec A step 7: a FAILED chat names its classified failure in the state's own vocabulary.
    String reason = cause != null ? cause.name()
        : unloadDetail != null ? UnloadReason.RESIDENCY_PRESSURE.name()
        : state == ChatState.FAILED
            ? ChatReasonCodes.failedCodeOf(generative.reason_code()).map(Enum::name).orElse(null)
            : null;
    return new OperatingChatView(
        state,
        reason,
        unloadDetail,
        generative.reason_code(),
        generative.state_since(),
        inference == null || !inference.usingExternal(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        sampledAt);
  }

  private static ChatState fromComponent(ComponentState component) {
    return switch (component) {
      case READY -> ChatState.READY;
      case STARTING, RELOADING -> ChatState.LOADING;
      case FAILED -> ChatState.FAILED;
      case ABSENT, UNAVAILABLE -> ChatState.OFF;
    };
  }

  private OperatingIndexingView indexing(
      PowerStatusView power, WorkerOperationalView worker, long workerSampledAtMs, Instant now) {
    if (worker == null || worker.core() == null) {
      return null;
    }
    long pending = Math.max(0L, worker.core().pendingJobs());
    // Energy saver comes from this pass's power view, so the block agrees with the power card;
    // every other reason comes from the bulk-work gate's verdict.
    boolean energyReduced = power != null && power.energyReduced();
    EnumSet<BulkWorkGate.Reason> present = EnumSet.noneOf(BulkWorkGate.Reason.class);
    BulkWorkGate.Snapshot snapshot = bulkWorkSnapshot();
    if (snapshot != null) present.addAll(snapshot.verdict().reasons());
    present.remove(BulkWorkGate.Reason.ENERGY_SAVER);
    if (energyReduced) present.add(BulkWorkGate.Reason.ENERGY_SAVER);

    // IDLE wins with nothing to drain; the present reasons are still listed, as before E1.
    List<IndexingReason> reasons =
        present.stream().map(r -> IndexingReason.valueOf(r.name())).toList();
    IndexingState state =
        idle(worker)
            ? IndexingState.IDLE
            : switch (BulkWorkPolicy.modeOf(present)) {
              case HOLD -> IndexingState.PAUSED;
              case DUTY_FLOOR -> IndexingState.SLOWED;
              case RUN -> IndexingState.RUNNING;
            };
    if (state != lastIndexingState || !reasons.equals(lastIndexingReasons)) {
      lastIndexingState = state;
      lastIndexingReasons = reasons;
      indexingSince = now.toString();
    }
    String reasonCode =
        state == IndexingState.PAUSED && reasons.get(0) == IndexingReason.GAME_MODE
            ? LifecycleReasonCode.INDEXING_PAUSED_GAME_MODE.code()
            : state == IndexingState.PAUSED && reasons.get(0) == IndexingReason.ENERGY_SAVER
            ? LifecycleReasonCode.INDEXING_PAUSED_ENERGY_SAVER.code()
            : state == IndexingState.PAUSED && reasons.get(0) == IndexingReason.GPU_BUSY_EXTERNAL
                ? LifecycleReasonCode.INDEXING_PAUSED_GPU_BUSY.code()
            : state == IndexingState.SLOWED
                ? LifecycleReasonCode.INDEXING_SLOWED_CHAT_GENERATING.code()
                : null;
    List<BulkWorkKind> affects =
        state == IndexingState.PAUSED || state == IndexingState.SLOWED
            ? List.of(BulkWorkKind.INDEXING)
            : List.of();
    return new OperatingIndexingView(
        state,
        reasons,
        reasonCode,
        indexingSince,
        affects,
        pending,
        null,
        Instant.ofEpochMilli(workerSampledAtMs).toString());
  }

  /**
   * Spec E §1.7: IDLE needs an empty queue and, for each enrichment stage that is enabled and
   * available, nothing pending. A disabled or unavailable stage is skipped: nothing will drain it.
   */
  private static boolean idle(WorkerOperationalView worker) {
    CoreIndexView core = worker.core();
    if (core.pendingJobs() > 0) return false;
    if (Boolean.TRUE.equals(core.vduAvailable()) && core.pendingVduCount() > 0) return false;
    EnrichmentProgressView e = worker.enrichment();
    if (e == null) return true;
    ChunkCoverageView chunk = e.chunk();
    if (e.embeddingEnabled() && Boolean.TRUE.equals(e.embeddingAvailable())) {
      if (e.embeddingPendingCount() > 0) return false;
      if (chunk != null && chunk.chunkEmbeddingPendingCount() > 0) return false;
    }
    if (Boolean.TRUE.equals(e.spladeAvailable())) {
      if (e.spladeEnabled() && e.spladePendingCount() > 0) return false;
      if (chunk != null && chunk.chunkSpladeEnabled() && chunk.chunkSpladePendingCount() > 0) {
        return false;
      }
    }
    return !(e.nerEnabled() && Boolean.TRUE.equals(e.nerAvailable()) && e.pendingNerCount() > 0);
  }

  private BulkWorkGate.Snapshot bulkWorkSnapshot() {
    Supplier<BulkWorkGate.Snapshot> source = bulkWork;
    if (source == null) return null;
    try {
      return source.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static OperatingChatView withChatSampledAt(OperatingChatView c, String sampledAt) {
    return new OperatingChatView(c.state(), c.reason(), c.detail(), c.reasonCode(), c.since(),
        c.managed(), c.generating(), c.inFlight(), c.queued(), c.blockedBy(), c.load(),
        c.reloadAllowed(), c.nextReloadNotBefore(), sampledAt);
  }

  /** The gpu block without its byte figures and times, for the seq comparison. */
  private static OperatingGpuView withoutMeasurements(OperatingGpuView g) {
    return new OperatingGpuView(g.profile(), g.profileSource(), g.adapter(), null, null, null, null,
        null, g.spillSuspected(), g.source(), null, null, g.stale());
  }

  private static OperatingIndexingView withIndexingSampledAt(
      OperatingIndexingView i, String sampledAt) {
    return new OperatingIndexingView(i.state(), i.reasons(), i.reasonCode(), i.since(),
        i.affects(), i.pendingJobs(), i.agingReleaseAt(), sampledAt);
  }
}
