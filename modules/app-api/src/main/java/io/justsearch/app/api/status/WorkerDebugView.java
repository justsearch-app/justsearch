/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Debug worker state view for the /api/debug/state endpoint.
 *
 * <p>Uses snake_case JSON naming to match the existing debug response shape.
 *
 * <p>{@code effectiveConfig} mirrors the Worker's {@code HealthCheckResponse.effective_config} map
 * (tempdoc 329 divergence detection). tempdoc 623 U7 routes the worker-side {@code ort.version}
 * through it so the benchmark-release hardware projection reads the ORT version from this debug-only,
 * retained, un-hashed surface — WITHOUT expanding the public {@code /api/status} wire contract
 * (debug records are excluded from {@code StatusWireContractConformanceTest}).
 *
 * <p>Stability: internal (debug endpoint only)
 */
@RecordBuilder
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkerDebugView(
    String status,
    long queueDepth,
    long docCount,
    long activeDocCount,
    long buildingDocCount,
    String servingSearchGenerationId,
    String servingIngestGenerationId,
    long switchBufferDepth,
    long pendingJobsCount,
    long processingJobsCount,
    long pendingReadyJobsCount,
    long pendingBackoffJobsCount,
    long migrationSwitchingAgeMs,
    long migrationSwitchingMaxDurationMs,
    boolean migrationPaused,
    String migrationPauseReason,
    long migrationPausedAtMs,
    boolean isHealthy,
    long lastCommitTimestamp,
    DebugMigrationEnumeratorView migrationEnumerator,
    long uptimeMs,
    HealthNodeView healthCheck,
    Map<String, String> effectiveConfig) {

  public WorkerDebugView {
    status = status == null ? "" : status;
    servingSearchGenerationId = servingSearchGenerationId == null ? "" : servingSearchGenerationId;
    servingIngestGenerationId = servingIngestGenerationId == null ? "" : servingIngestGenerationId;
    migrationPauseReason = migrationPauseReason == null ? "" : migrationPauseReason;
    effectiveConfig = effectiveConfig == null ? Map.of() : Map.copyOf(effectiveConfig);
  }
}
