/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #758 — a rebuild brake whose state record was lost and re-established
 * ({@code rebuild_brake_state_lost}) is an unreadable brake. Its recovery is the index rebuild, as
 * for a spent or unreadable brake; before, the unknown reason fell through to the plain reindex,
 * which the Worker would not act on while it holds.
 */
final class WorkerSnapshotTapLostBrakeTest {

  @Test
  void aLostRebuildBrakeRoutesToTheIndexRebuild() throws Exception {
    try (var delivery = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      HealthEventChangeRegistry changes;
      try {
        changes = HealthEventChangeRegistry.class
            .getConstructor(java.util.concurrent.ExecutorService.class).newInstance(delivery);
      } catch (NoSuchMethodException beforeOwnerSuppliedDelivery) {
        // The declared fail-before revision owns its delivery executor inside the registry.
        changes = HealthEventChangeRegistry.class.getConstructor().newInstance();
      }
      try {
        ConditionStore conditions = new ConditionStore();
        WorkerSnapshotTap tap =
            new WorkerSnapshotTap(
                conditions,
                new OccurrenceLog(),
                changes,
                Source.forProcess("head", "instance-1", "1.0"),
                Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC));

        tap.accept(view(new CompatibilityStatusView(
            "COMPATIBLE", "", "", "", "", "", "BLOCKED_REBUILD_BRAKE", true,
            "rebuild_brake_state_lost")), false);

        HealthEvent event = conditions.find("schema.reindex-required", "worker.schema").orElseThrow();
        AssertedCondition condition = (AssertedCondition) event.body();
        assertTrue(condition.recovery().isPresent(), "required reindex must expose a recovery");
        assertEquals("core.rebuild-index", condition.recovery().get().target().value(),
            "a held brake is released by a person's rebuild, not by a reindex");
      } finally {
        Object channel = changes.channel();
        if (channel instanceof AutoCloseable ownedChannel) {
          ownedChannel.close();
        }
      }
    }
  }

  private static WorkerOperationalView view(CompatibilityStatusView compat) {
    return WorkerOperationalViewBuilder.builder()
        .core(io.justsearch.app.api.status.CoreIndexView.fallback("READY"))
        .failure(new FailureTrackingView(0, "", "", 0, 0, 0, Map.of()))
        .migration(io.justsearch.app.api.status.MigrationGenerationView.empty())
        .compatibility(compat)
        .queueDb(new QueueDbStatusView(true, 0, 0, true, 0))
        .enrichment(EnrichmentProgressView.empty())
        .gpu(io.justsearch.app.api.status.GpuDiagnosticsView.empty())
        .vectorFormat(io.justsearch.app.api.status.VectorFormatView.empty())
        .telemetry(io.justsearch.app.api.status.TelemetryMetricsView.empty())
        .searchConfig(io.justsearch.app.api.status.SearchConfigView.empty())
        .build();
  }
}
