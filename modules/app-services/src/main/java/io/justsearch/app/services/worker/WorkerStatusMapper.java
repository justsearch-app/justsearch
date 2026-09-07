/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.status.BatchTimingView;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.DebugMigrationEnumeratorView;
import io.justsearch.app.api.status.ChunkCoverageView;
import io.justsearch.app.api.status.EncoderProfileView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.EnrichmentProgressViewBuilder;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.HealthNodeView;
import io.justsearch.app.api.status.MigrationEnumeratorView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.StageCompletenessView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerDebugView;
import io.justsearch.app.api.status.WorkerDebugViewBuilder;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.StatusResponse;
import java.util.List;

/**
 * Builds UI-facing and debug status maps from gRPC proto responses.
 *
 * <p>Pure static functions: no mutable state, no RPC calls. Extracted from {@link
 * KnowledgeClient} to reduce file size.
 */
final class WorkerStatusMapper {

    private WorkerStatusMapper() {}

    /** Maps StatusResponse to the /api/status UI field shape. */
    static WorkerOperationalView toUiStatusMap(StatusResponse status) {
        return toUiStatusMap(status, null);
    }

    /** Maps StatusResponse + optional health response to the /api/status UI field shape. */
    static WorkerOperationalView toUiStatusMap(StatusResponse status, HealthCheckResponse health) {
        var core = status.getCore();
        var migration = status.getMigration();
        var failure = status.getFailure();
        var compat = status.getCompatibility();
        var queueDb = status.getQueueDb();
        var enrichment = status.getEnrichment();
        var telemetry = status.getTelemetry();
        var vecQuant = status.getVectorQuantization();
        var gpuStatus = status.getGpu();

        var enumerator = migration.getEnumerator();

        // Per-subsystem ORT CUDA status (283). Passthrough from Worker proto —
        // the Worker sets configured/attempted/available to reflect the actual state.
        var spladeProbe = gpuStatus.getSpladeOrtCuda();
        OrtCudaView spladeOrtCuda = mapOrtCudaProbe(spladeProbe);
        var embedProbe = gpuStatus.getEmbedOrtCuda();
        OrtCudaView embedOrtCuda = mapOrtCudaProbe(embedProbe);

        var rerankerProbe = gpuStatus.getRerankerOrtCuda();

        return WorkerOperationalViewBuilder.builder()
                .core(new CoreIndexView(
                        core.getIsHealthy(),
                        core.getDocCount(),
                        core.getQueueDepth(),
                        core.getState(),
                        core.getIndexSizeBytes(),
                        core.getPendingVduCount(),
                        core.getWriterQueueDepth(),
                        core.getWriterPendingDocs(),
                        core.getCommitCount(),
                        core.getRefreshLagMs(),
                        toLongArray(core.getRecentJobQueueDepthList()),
                        toDoubleArray(core.getRecentDocsPerSecList()),
                        core.getPendingBytes(),
                        core.getPendingUnknownSizeJobs(),
                        core.getSearchableDocCount(),
                        core.getIndexMaxDoc(),
                        core.getIndexNumDocs()))
                .failure(new FailureTrackingView(
                        failure.getFailedCount(),
                        failure.getLastFailedPath(),
                        failure.getLastFailedErrorMessage(),
                        failure.getLastFailedAtMs(),
                        failure.getNextRetryAtMs(),
                        failure.getSearchesZeroResultCount(),
                        failure.getFailedByFileKindMap()))
                .migration(MigrationGenerationViewBuilder.builder()
                        .activeGenerationId(migration.getActiveGenerationId())
                        .migrationState(migration.getMigrationState())
                        .buildingGenerationId(migration.getBuildingGenerationId())
                        .previousGenerationId(migration.getPreviousGenerationId())
                        .servingSearchGenerationId(migration.getServingSearchGenerationId())
                        .servingIngestGenerationId(migration.getServingIngestGenerationId())
                        .activeIndexedDocuments(migration.getActiveDocCount())
                        .buildingIndexedDocuments(migration.getBuildingDocCount())
                        .switchBufferDepth(migration.getSwitchBufferDepth())
                        .pendingJobsCount(migration.getPendingJobsCount())
                        .processingJobsCount(migration.getProcessingJobsCount())
                        .pendingReadyJobsCount(migration.getPendingReadyJobsCount())
                        .pendingBackoffJobsCount(migration.getPendingBackoffJobsCount())
                        .migrationSwitchingAgeMs(migration.getSwitchingAgeMs())
                        .migrationSwitchingMaxDurationMs(migration.getSwitchingMaxDurationMs())
                        .migrationPaused(migration.getPaused())
                        .migrationPauseReason(migration.getPauseReason())
                        .migrationPausedAtMs(migration.getPausedAtMs())
                        .migrationSource(migration.getMigrationSource())
                        .migrationEnumerator(new MigrationEnumeratorView(
                                enumerator.getRunning(),
                                enumerator.getDone(),
                                enumerator.getRootsTotal(),
                                enumerator.getRootsDone(),
                                enumerator.getFilesSeen(),
                                enumerator.getFilesEnqueued(),
                                enumerator.getStartedAtMs(),
                                enumerator.getFinishedAtMs(),
                                enumerator.getLastPath()))
                        .build())
                .compatibility(new CompatibilityStatusView(
                        compat.getEmbeddingCompatState(),
                        compat.getEmbeddingCompatReason(),
                        compat.getEmbeddingFingerprintCurrent(),
                        compat.getEmbeddingFingerprintStored(),
                        compat.getSchemaFpCurrent(),
                        compat.getSchemaFpStored(),
                        compat.getSchemaCompatState(),
                        compat.getReindexRequired(),
                        compat.getReindexRequiredReason()))
                .queueDb(new QueueDbStatusView(
                        queueDb.getHealthy(),
                        queueDb.getLastBackupAtMs(),
                        queueDb.getLastQuickCheckAtMs(),
                        queueDb.getLastQuickCheckOk(),
                        queueDb.getLastErrorAtMs()))
                .enrichment(EnrichmentProgressViewBuilder.builder()
                        .chunk(new ChunkCoverageView(
                                enrichment.getChunk().getDocCount(),
                                enrichment.getChunk().getCompletedCount(),
                                enrichment.getChunk().getPendingCount(),
                                enrichment.getChunk().getFailedCount(),
                                enrichment.getChunk().getCoveragePercent(),
                                enrichment.getChunk().getVectorsReady(),
                                enrichment.getChunk().getSpladeEnabled(),
                                enrichment.getChunk().getSpladeCompletedCount(),
                                enrichment.getChunk().getSpladePendingCount(),
                                enrichment.getChunk().getSpladeCoveragePercent()))
                        .embeddingDocCount(enrichment.getEmbedding().getDocCount())
                        .embeddingCompletedCount(enrichment.getEmbedding().getCompletedCount())
                        .embeddingPendingCount(enrichment.getEmbedding().getPendingCount())
                        .embeddingFailedCount(enrichment.getEmbedding().getFailedCount())
                        .embeddingCoveragePercent(enrichment.getEmbedding().getCoveragePercent())
                        .spladeDocCount(enrichment.getSplade().getDocCount())
                        .spladeCompletedCount(enrichment.getSplade().getCompletedCount())
                        .spladePendingCount(enrichment.getSplade().getPendingCount())
                        .spladeFailedCount(enrichment.getSplade().getFailedCount())
                        .spladeCoveragePercent(enrichment.getSplade().getCoveragePercent())
                        .pendingNerCount(enrichment.getPendingNerCount())
                        .completedNerCount(enrichment.getCompletedNerCount())
                        .failedNerCount(enrichment.getFailedNerCount())
                        .completeness(mapCompleteness(enrichment))
                        .chunkMinChars(enrichment.getChunkMinChars())
                        .vectorReadyPercent(enrichment.getVectorReadyPercent())
                        .embeddingEnabled(enrichment.getEmbeddingEnabled())
                        .spladeEnabled(enrichment.getSpladeEnabled())
                        .nerEnabled(enrichment.getNerEnabled())
                        .backfillMode(enrichment.getBackfillMode())
                        .enrichmentCompleted(enrichment.getEnrichmentCompletedMap())
                        .batchTiming(new BatchTimingView(
                                enrichment.getBatchTiming().getBatchCountMap(),
                                enrichment.getBatchTiming().getTotalMsMap()))
                        .encoderProfiles(mapEncoderProfiles(enrichment))
                        .build())
                .gpu(new GpuDiagnosticsView(
                        mapOrtCudaProbe(rerankerProbe),
                        spladeOrtCuda,
                        embedOrtCuda,
                        gpuStatus.getEmbedBackend(),
                        gpuStatus.getEmbedGpuLayers(),
                        gpuStatus.getSpladeModelPath(),
                        gpuStatus.getRerankerModelPath(),
                        gpuStatus.getNerModelPath(),
                        gpuStatus.getNerGpuEnabled(),
                        mapOrtCudaProbe(gpuStatus.getNerOrtCuda()),
                        mapOrtCudaProbe(gpuStatus.getCitationOrtCuda()),
                        mapOrtCudaProbe(gpuStatus.getBgeM3OrtCuda())))
                .vectorFormat(new VectorFormatView(
                        vecQuant.getFormatConfig(),
                        vecQuant.getFormatStored(),
                        vecQuant.getFormatActual(),
                        vecQuant.getSegmentsFloat32(),
                        vecQuant.getSegmentsQuantized()))
                .telemetry(new TelemetryMetricsView(
                        telemetry.getContentLengthAvgChars(),
                        telemetry.getContentLengthMinChars(),
                        telemetry.getContentLengthMaxChars(),
                        telemetry.getThroughputDocsPerSec(),
                        telemetry.getThroughputWindowState()))
                .searchConfig(mapSearchConfig(status))
                .visualExtraction(mapVisualExtraction(status))
                .buildStamp(status.getBuildStamp().isEmpty() ? null : status.getBuildStamp())
                .aiReady(health != null ? health.getAiReady() : null)
                .embeddingReady(health != null ? health.getEmbeddingReady() : null)
                .build();
    }

    private static VisualExtractionView mapVisualExtraction(StatusResponse status) {
        if (!status.hasVisualExtraction()) {
            return VisualExtractionView.empty();
        }
        var visual = status.getVisualExtraction();
        return new VisualExtractionView(
                visual.getOcrEnabled(),
                visual.getOcrEngineAvailable(),
                visual.getOcrEngine().isEmpty() ? null : visual.getOcrEngine(),
                visual.getOcrBlockedReason().isEmpty() ? null : visual.getOcrBlockedReason(),
                visual.getVisualTextNeededCount(),
                visual.getVisualEnrichmentNeededCount(),
                visual.getVduBlockedReason().isEmpty() ? null : visual.getVduBlockedReason(),
                // Head-side fact, overlaid by StatusLifecycleHandler.overlayVduCapability; the
                // Worker's own status response has no notion of the Head's coordinator state.
                false);
    }

    /** Builds the health_check node from a HealthCheckResponse. */
    static HealthNodeView buildHealthNode(HealthCheckResponse health) {
        return new HealthNodeView(
                health.getServing(),
                health.getVersion(),
                health.getPid(),
                health.getWorkerState(),
                // ai_ready is a legacy misnomer: reports embedding readiness, not LLM readiness.
                health.getAiReady(),
                health.getEmbeddingReady());
    }

    /** Maps StatusResponse + health node + worker effective_config to the debug worker state shape. */
    static WorkerDebugView toDebugWorkerState(
            StatusResponse status,
            HealthNodeView healthNode,
            java.util.Map<String, String> effectiveConfig) {
        var enumerator = status.getMigration().getEnumerator();
        var migrationEnumerator =
                new DebugMigrationEnumeratorView(
                        enumerator.getRunning(),
                        enumerator.getDone(),
                        enumerator.getRootsTotal(),
                        enumerator.getRootsDone(),
                        enumerator.getFilesSeen(),
                        enumerator.getFilesEnqueued(),
                        enumerator.getStartedAtMs(),
                        enumerator.getFinishedAtMs(),
                        enumerator.getLastPath());

        var core = status.getCore();
        var migration = status.getMigration();

        return WorkerDebugViewBuilder.builder()
                .status(core.getState())
                .queueDepth(core.getQueueDepth())
                .docCount(core.getDocCount())
                .activeDocCount(migration.getActiveDocCount())
                .buildingDocCount(migration.getBuildingDocCount())
                .servingSearchGenerationId(migration.getServingSearchGenerationId())
                .servingIngestGenerationId(migration.getServingIngestGenerationId())
                .switchBufferDepth(migration.getSwitchBufferDepth())
                .pendingJobsCount(migration.getPendingJobsCount())
                .processingJobsCount(migration.getProcessingJobsCount())
                .pendingReadyJobsCount(migration.getPendingReadyJobsCount())
                .pendingBackoffJobsCount(migration.getPendingBackoffJobsCount())
                .migrationSwitchingAgeMs(migration.getSwitchingAgeMs())
                .migrationSwitchingMaxDurationMs(migration.getSwitchingMaxDurationMs())
                .migrationPaused(migration.getPaused())
                .migrationPauseReason(migration.getPauseReason())
                .migrationPausedAtMs(migration.getPausedAtMs())
                .isHealthy(core.getIsHealthy())
                .lastCommitTimestamp(core.getLastCommitTimestamp())
                .migrationEnumerator(migrationEnumerator)
                .uptimeMs(core.getUptimeMs())
                .healthCheck(healthNode)
                .effectiveConfig(effectiveConfig)
                .build();
    }

    /** Maps a Worker proto SearchConfig to the API view. */
    private static SearchConfigView mapSearchConfig(StatusResponse status) {
        if (!status.hasSearchConfig()) {
            return SearchConfigView.empty();
        }
        var sc = status.getSearchConfig();
        return new SearchConfigView(
                sc.getChunkAwareEnabled(),
                sc.getCcWeightSparse(),
                sc.getCcWeightDense(),
                sc.getCcWeightSplade(),
                sc.getBranchCcWeightWhole(),
                sc.getBranchCcWeightChunk(),
                sc.getBranchChunkMinWeightMultiplier(),
                sc.getTitleBoost(),
                // Compatibility tombstone: do not expose a legacy Worker's nonzero boost as live.
                0.0,
                sc.getQueryClassificationEnabled());
    }

    private static java.util.Map<String, EncoderProfileView> mapEncoderProfiles(
            io.justsearch.ipc.EnrichmentCoverage enrichment) {
        var profiles = new java.util.LinkedHashMap<String, EncoderProfileView>();
        enrichment.getEncoderProfilesMap().forEach((name, proto) ->
            profiles.put(name, new EncoderProfileView(
                    proto.getCalls(),
                    java.util.Map.copyOf(proto.getPhaseTotalUsMap()),
                    proto.getOrtMinUs(),
                    proto.getOrtMaxUs(),
                    proto.getOrtP50Us(),
                    proto.getOrtP95Us(),
                    proto.getOrtP99Us())));
        return profiles;
    }

    /**
     * Maps the Worker's per-stage completeness audit to the API view (tempdoc 821 §3-C3).
     * Straight passthrough — the arithmetic (including {@code missing}) belongs to the Worker,
     * which is the only side holding the reader the counts came from.
     */
    private static List<StageCompletenessView> mapCompleteness(
            io.justsearch.ipc.EnrichmentCoverage enrichment) {
        return enrichment.getCompletenessList().stream()
                .map(s -> new StageCompletenessView(
                        s.getStageId(),
                        s.getTier(),
                        s.getExpected(),
                        s.getPresent(),
                        s.getMissing(),
                        s.getFailed()))
                .toList();
    }

    /** Maps a Worker proto OrtCudaProbeResult to the API view. Straight passthrough. */
    static OrtCudaView mapOrtCudaProbe(
            io.justsearch.ipc.OrtCudaProbeResult probe) {
        return new OrtCudaView(
                probe.getConfigured(),
                probe.getAttempted(),
                probe.getAvailable(),
                probe.getVariantId(),
                probe.getNativePath(),
                probe.getFailureReason(),
                List.copyOf(probe.getMissingDllsList()));
    }

    /** 419 C3 V1: unbox a proto repeated int64 list into a {@code long[]} for API records. */
    private static long[] toLongArray(List<Long> list) {
        if (list == null || list.isEmpty()) return new long[0];
        long[] out = new long[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    /** 419 C3 V2 P2: unbox a proto repeated double list into a {@code double[]} for API records. */
    private static double[] toDoubleArray(List<Double> list) {
        if (list == null || list.isEmpty()) return new double[0];
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }
}
