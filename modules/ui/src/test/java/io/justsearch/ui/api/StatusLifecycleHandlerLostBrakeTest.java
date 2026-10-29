/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import org.junit.jupiter.api.Test;

/**
 * #758 — a brake whose state record was lost is shown as an unreadable
 * brake ({@code index.rebuild_brake_unreadable}); the Worker's reason carries the "state lost"
 * detail. Before, any brake reason other than the unreadable one was shown as a spent budget.
 */
final class StatusLifecycleHandlerLostBrakeTest {

  @Test
  void compatBlockedReasonNamesALostBrakeAsUnreadable() {
    WorkerOperationalView workerView =
        WorkerOperationalViewBuilder.builder()
            .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
            .failure(FailureTrackingView.empty())
            .migration(MigrationGenerationView.empty())
            .compatibility(new CompatibilityStatusView(
                "COMPATIBLE", "", "", "", "", "", "BLOCKED_REBUILD_BRAKE", true,
                "rebuild_brake_state_lost"))
            .queueDb(QueueDbStatusView.healthy())
            .enrichment(EnrichmentProgressView.empty())
            .gpu(GpuDiagnosticsView.empty())
            .vectorFormat(VectorFormatView.empty())
            .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
            .searchConfig(SearchConfigView.empty())
            .build();
    assertEquals(
        "index.rebuild_brake_unreadable",
        StatusLifecycleHandler.compatBlockedReason(workerView),
        "a lost brake is not a spent one: nothing says the budget was used");
  }
}
