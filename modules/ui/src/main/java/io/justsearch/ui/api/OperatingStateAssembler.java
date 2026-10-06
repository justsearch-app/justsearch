/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.chat.ChatReasonCodes;
import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.chat.LoadingCause;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.operating.BulkWorkKind;
import io.justsearch.app.api.status.operating.IndexingReason;
import io.justsearch.app.api.status.operating.IndexingState;
import io.justsearch.app.api.status.operating.OperatingBlock;
import io.justsearch.app.api.status.operating.OperatingChatView;
import io.justsearch.app.api.status.operating.OperatingFreshnessView;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 *       reason code classifies, when exactly one does. {@code reasonCode} and
 *       {@code since} are the component's own, so they equal {@code components.generative}. The
 *       block is absent while the Engine is stopping. Generation activity, reload blocking and load
 *       progress have no producer yet and are {@code null}.
 *   <li><b>indexing</b> comes from the Worker's pending-job count and the energy-saver poll: IDLE
 *       with nothing pending, PAUSED ({@code energy_saver}) when energy is reduced and work is
 *       pending (the condition the Queue card already words), RUNNING otherwise. SLOWED is not
 *       produced: the generation gauge is not observable here.
 *   <li><b>gpu</b> is the reading {@link OperatingGpuProjection} gives the status pass (diagnostic
 *       only: no control decision reads it), or unknown without one. The profile stays UNRESOLVED:
 *       no profile resolver feeds it yet.
 *   <li><b>degradations</b> is empty: none of the standing entries has a producer yet.
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
        List.of(),
        freshness);
    if (!content.equals(lastContent)) {
      seq++;
      lastContent = content;
    }
    return new OperatingStateView(
        OperatingStateView.SCHEMA_VERSION, seq, assembledAt, chat, indexing, gpu, List.of(),
        freshness);
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
    // Spec A step 7: a FAILED chat names its classified failure in the state's own vocabulary.
    String reason = cause != null ? cause.name()
        : state == ChatState.FAILED
            ? ChatReasonCodes.failedCodeOf(generative.reason_code()).map(Enum::name).orElse(null)
            : null;
    return new OperatingChatView(
        state,
        reason,
        null,
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
    boolean energyReduced = power != null && power.energyReduced();
    List<IndexingReason> reasons =
        energyReduced ? List.of(IndexingReason.ENERGY_SAVER) : List.of();
    IndexingState state;
    if (pending == 0) {
      state = IndexingState.IDLE;
    } else if (energyReduced) {
      state = IndexingState.PAUSED;
    } else {
      state = IndexingState.RUNNING;
    }
    if (state != lastIndexingState || !reasons.equals(lastIndexingReasons)) {
      lastIndexingState = state;
      lastIndexingReasons = reasons;
      indexingSince = now.toString();
    }
    List<BulkWorkKind> affects =
        state == IndexingState.PAUSED ? List.of(BulkWorkKind.INDEXING) : List.of();
    return new OperatingIndexingView(
        state,
        reasons,
        null,
        indexingSince,
        affects,
        pending,
        null,
        Instant.ofEpochMilli(workerSampledAtMs).toString());
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
