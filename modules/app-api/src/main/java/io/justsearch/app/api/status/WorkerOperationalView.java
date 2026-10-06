/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.soabase.recordbuilder.core.RecordBuilder;

/**
 * Worker operational status view for the /api/status endpoint.
 *
 * <p>341: Decomposed into sub-records. 384: All sub-records are now nested (no
 * {@code @JsonUnwrapped}). The entire view is nested under {@code "worker"} key
 * on {@code StatusResponse}.
 *
 * <p>Stability: stable (API contract)
 */
@RecordBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkerOperationalView(
    CoreIndexView core,
    FailureTrackingView failure,
    MigrationGenerationView migration,
    CompatibilityStatusView compatibility,
    QueueDbStatusView queueDb,
    EnrichmentProgressView enrichment,
    GpuDiagnosticsView gpu,
    VectorFormatView vectorFormat,
    TelemetryMetricsView telemetry,
    SearchConfigView searchConfig,
    VisualExtractionView visualExtraction,

    // 371: Distribution content hash for stale-JVM detection.
    String buildStamp,

    // From HealthCheckResponse (optional, nullable).
    @JsonIgnore Boolean aiReady,
    @JsonIgnore Boolean embeddingReady,
    // Spec D §4.2: a query encoder has no warm session after the embedding was ready once.
    @JsonIgnore Boolean queryEncodersCold,
    // Spec D §6.3: since when (epoch ms) a device's breaker has been open; null while closed.
    @JsonIgnore Long semanticStuckSinceMs) {

  /** Creates a fallback view with default zero/empty values and the given indexState. */
  public static WorkerOperationalView fallback(String indexState) {
    return WorkerOperationalViewBuilder.builder()
        .core(CoreIndexView.fallback(indexState))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationView.empty())
        .compatibility(CompatibilityStatusView.empty())
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(TelemetryMetricsView.empty())
        .searchConfig(SearchConfigView.empty())
        .visualExtraction(VisualExtractionView.empty())
        .build();
  }
}
