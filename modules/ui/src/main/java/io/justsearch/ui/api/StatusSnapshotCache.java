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

  record Sample(StatusResponse response, long startedAtMs, long periodMs) {}

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

  static StatusResponse read(Sample sample, long now, Instant startTime, boolean overdue) {
    StatusResponse s = sample.response();
    long age = Math.max(0L, now - sample.startedAtMs());
    long staleAfterMs = (long) StatusLifecycleHandler.SAMPLE_STALE_PERIODS * sample.periodMs();
    boolean stale = overdue || age > staleAfterMs;
    boolean workerStale = stale || s.meta().workerRpcStale()
        || now - s.meta().workerRpcAtMs() > staleAfterMs;
    var readiness = ageReadiness(s.readiness(), age, stale, workerStale);
    var operating = ageOperating(s.operating(), now, stale, workerStale);
    Runtime runtime = Runtime.getRuntime();
    return new StatusResponse(s.schemaVersion(), s.observedAt(), s.lifecycle(), s.components(),
        s.status(), s.service(), s.indexBasePath(),
        Math.max(0L, now - startTime.toEpochMilli()),
        runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory(), runtime.maxMemory(),
        stale ? null : s.diskPressure(), s.worker(), !workerStale && s.indexAvailable(),
        s.knowledgeServerStartError(), s.indexStatusReason(),
        stale ? PowerStatusView.unknown() : s.power(), !stale && s.catchingUp(), s.inference(),
        readiness, !stale && s.aiReady(), !workerStale && s.embeddingReady(),
        s.embedding(), s.schema(), s.chunkCoverage(), s.queueHealth(), s.migration(),
        stale ? GpuStatusView.unavailable() : s.gpu(),
        stale ? AtRestProtectionView.unknown() : s.atRestProtection(),
        stale ? ConversationProtectionView.unknown() : s.conversationProtection(),
        stale ? ModelDistributionStatusView.unavailable() : s.modelDistribution(),
        stale ? null : s.agentSessions(), s.telemetryHealth(),
        new StatusMeta(s.meta().workerRpcAtMs(), workerStale), operating);
  }

  private static ReadinessEnvelopeView ageReadiness(
      ReadinessEnvelopeView r, long age, boolean stale, boolean workerStale) {
    if (r == null) return null;
    var dimensions = new LinkedHashMap<String, ReadinessComponentView>();
    for (var dim : ReadinessDimension.values()) {
      var c = r.components().get(dim.key());
      if (c == null) continue;
      boolean lost = c.stale() || stale
          || (StatusLifecycleHandler.workerObserved(dim) && workerStale);
      dimensions.put(dim.key(), new ReadinessComponentView(c.state(), c.reasonCode(), c.source(),
          c.observedAt(), lost, lost ? c.stalenessMs() + age : 0L));
    }
    var composites = new LinkedHashMap<String, ReadinessCompositeView>();
    r.composites().forEach((key, c) -> {
      var members = java.util.Arrays.stream(ReadinessDimension.values())
          .filter(d -> d.composite().equals(key)).map(d -> dimensions.get(d.key()))
          .filter(java.util.Objects::nonNull).toList();
      boolean lost = members.stream().anyMatch(ReadinessComponentView::stale);
      long oldest = members.stream().mapToLong(ReadinessComponentView::stalenessMs).max().orElse(0L);
      composites.put(key, new ReadinessCompositeView(c.state(), c.reasonCodes(), lost, oldest));
    });
    return new ReadinessEnvelopeView(r.schemaVersion(), r.observedAt(), r.engineComponents(),
        dimensions, composites);
  }

  private static OperatingStateView ageOperating(
      OperatingStateView o, long now, boolean stale, boolean workerStale) {
    if (o == null) return null;
    var g = o.gpu();
    Long gpuAge = g.sampledAt() == null ? null
        : Math.max(0L, now - Instant.parse(g.sampledAt()).toEpochMilli());
    boolean gpuStale = stale || g.stale() || (gpuAge != null && gpuAge > 6_000L);
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
}
