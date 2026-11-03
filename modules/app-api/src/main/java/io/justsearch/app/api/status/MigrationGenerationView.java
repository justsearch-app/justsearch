/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import io.soabase.recordbuilder.core.RecordBuilder;

@RecordBuilder
public record MigrationGenerationView(
    String activeGenerationId,
    String migrationState,
    String buildingGenerationId,
    String previousGenerationId,
    String servingSearchGenerationId,
    String servingIngestGenerationId,
    long activeIndexedDocuments,
    long buildingIndexedDocuments,
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
    String migrationSource,
    MigrationEnumeratorView migrationEnumerator,
    java.util.List<io.justsearch.app.api.IndexingService.IndexCleanupWarning> cleanupWarnings) {
  public MigrationGenerationView {
    cleanupWarnings = cleanupWarnings == null ? java.util.List.of() : java.util.List.copyOf(cleanupWarnings);
    activeGenerationId = activeGenerationId == null ? "" : activeGenerationId;
    migrationState = migrationState == null ? "" : migrationState;
    buildingGenerationId = buildingGenerationId == null ? "" : buildingGenerationId;
    previousGenerationId = previousGenerationId == null ? "" : previousGenerationId;
    servingSearchGenerationId = servingSearchGenerationId == null ? "" : servingSearchGenerationId;
    servingIngestGenerationId = servingIngestGenerationId == null ? "" : servingIngestGenerationId;
    migrationPauseReason = migrationPauseReason == null ? "" : migrationPauseReason;
    migrationSource = migrationSource == null ? "" : migrationSource;
  }

  public static MigrationGenerationView empty() {
    return MigrationGenerationViewBuilder.builder()
        .migrationEnumerator(new MigrationEnumeratorView(false, false, 0, 0, 0, 0, 0, 0, ""))
        .build();
  }
}
