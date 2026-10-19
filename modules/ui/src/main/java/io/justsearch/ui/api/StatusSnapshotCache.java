/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.lifecycle.ReadinessDimension;
import io.justsearch.app.api.status.AtRestProtectionView;
import io.justsearch.app.api.status.ConversationProtectionView;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.app.api.status.ModelDistributionStatusView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.ReadinessComponentView;
import io.justsearch.app.api.status.ReadinessCompositeView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.StatusMeta;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.TelemetryHealthView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.operating.OperatingBlock;
import io.justsearch.app.api.status.operating.OperatingFreshnessView;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.OperatingStateView;
import io.justsearch.app.services.lifecycle.LifecycleProjection;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure HTTP projection of a completed observation. No component calls, locks or I/O. */
final class StatusSnapshotCache {
  private StatusSnapshotCache() {}

  static StatusResponse initial(
      LifecycleProjection.Projection projection, Path indexBase, Instant startTime) {
    var lifecycle = projection.lifecycle();
    var dimensions = new LinkedHashMap<String, ReadinessComponentView>();
    var composites = new LinkedHashMap<String, ReadinessCompositeView>();
    for (var dim : ReadinessDimension.values()) {
      dimensions.put(dim.key(),
          new ReadinessComponentView("UNKNOWN", null, dim.source(), null, true, 0L));
      composites.put(dim.composite(),
          new ReadinessCompositeView("UNKNOWN", List.of(), true, 0L));
    }
    var readiness = new ReadinessEnvelopeView(2, lifecycle.observed_at(),
        projection.engineComponents(), dimensions, composites);
    var operating = new OperatingStateView(1, 1L, lifecycle.observed_at(), null, null,
        OperatingGpuView.unknown(), List.of(),
        new OperatingFreshnessView(30_000L, List.of(OperatingBlock.values())));
    Runtime runtime = Runtime.getRuntime();
    return new StatusResponse(lifecycle.schema_version(), lifecycle.observed_at(),
        lifecycle.lifecycle(), lifecycle.components(), "ok", "JustSearch Local API",
        indexBase == null ? "" : indexBase.toString(),
        Math.max(0L, System.currentTimeMillis() - startTime.toEpochMilli()),
        runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory(), runtime.maxMemory(),
        null, WorkerOperationalView.fallback("UNAVAILABLE"), false, null,
        "Status observation pending", PowerStatusView.unknown(), false, null, readiness,
        false, false, null, null, null, null, null, GpuStatusView.unavailable(),
        AtRestProtectionView.unknown(), ConversationProtectionView.unknown(),
        ModelDistributionStatusView.unavailable(), null, TelemetryHealthView.empty(),
        new StatusMeta(0L, true), operating);
  }

  static OperatingStateView ageOperating(
      OperatingStateView o, long now, boolean stale, boolean workerStale, boolean gpuLost, long gpuStaleAfterMs) {
    if (o == null) return null;
    var g = o.gpu();
    Long gpuAge = g.sampledAt() == null ? null
        : Math.max(0L, now - Instant.parse(g.sampledAt()).toEpochMilli());
    boolean gpuStale = gpuLost || g.stale() || (gpuAge != null && gpuAge > gpuStaleAfterMs);
    var gpu = new OperatingGpuView(g.profile(), g.profileSource(), g.adapter(),
        g.adapterTotalBytes(), g.adapterFreeBytes(), g.ourDedicatedBytes(), g.ourSharedBytes(),
        g.externalUsedBytes(), g.spillSuspected(), g.source(), g.sampledAt(), gpuAge, gpuStale);
    var sources = java.util.EnumSet.noneOf(OperatingBlock.class);
    sources.addAll(o.freshness().staleSources());
    if (stale) sources.add(OperatingBlock.CHAT);
    if (workerStale) sources.add(OperatingBlock.INDEXING);
    if (gpuStale) sources.add(OperatingBlock.GPU);
    return new OperatingStateView(o.schemaVersion(), o.seq(), o.assembledAt(), o.chat(),
        o.indexing(), gpu, o.degradations(),
        new OperatingFreshnessView(o.freshness().staleAfterMs(), List.copyOf(sources)));
  }

  /**
   * Assemble the response from cached measurements and its current immutable component view. The
   * chat block's reload fields are the operating lane's, kept while the chat still reads UNLOADED.
   */
  static OperatingStateView projectOperating(OperatingStateView o,
      io.justsearch.app.api.lifecycle.LifecycleSnapshotV2 lifecycle,
      io.justsearch.app.api.status.InferenceRuntimeView inference) {
    if (o == null) return null;
    return new OperatingStateView(o.schemaVersion(), o.seq(), lifecycle.observed_at(),
        OperatingStateAssembler.withObservedReload(
            OperatingStateAssembler.chat(lifecycle, inference, lifecycle.observed_at()), o.chat()),
        o.indexing(), o.gpu(), o.degradations(), o.freshness());
  }
}
