/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV2;
import io.justsearch.app.api.status.AgentSessionView;
import io.justsearch.app.api.status.AtRestProtectionView;
import io.justsearch.app.api.status.ChunkCoverageGroup;
import io.justsearch.app.api.status.ChunkCoverageView;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.ConversationProtectionView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EmbeddingStatusGroup;
import io.justsearch.app.api.status.EngineComponentView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.app.api.status.InferenceFailureView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.MigrationEnumeratorView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.MigrationStatusGroup;
import io.justsearch.app.api.status.ModelDistributionStatusView;
import io.justsearch.app.api.status.ModelVariantView;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.QueueHealthGroup;
import io.justsearch.app.api.status.ReadinessComponentView;
import io.justsearch.app.api.status.ReadinessCompositeView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.RuntimeIdentityView;
import io.justsearch.app.api.status.SchemaStatusGroup;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.StageCompletenessView;
import io.justsearch.app.api.status.StatusMeta;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.TelemetryHealthView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.operating.ChatLoadView;
import io.justsearch.app.api.status.operating.OperatingChatView;
import io.justsearch.app.api.status.operating.OperatingDegradationView;
import io.justsearch.app.api.status.operating.OperatingFreshnessView;
import io.justsearch.app.api.status.operating.OperatingGpuView;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.app.api.status.operating.OperatingStateView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one classification of every field canonical status reports. The status-change counter
 * advances on a change of STATE only. MEASUREMENT (gauges, counts, sizes, rates, percentages,
 * history arrays, estimates and the description of the latest counted event) and TIME (clocks,
 * ages, durations, deadlines) travel with ordinary reads and never advance it.
 *
 * <p>A measurement whose threshold crossing is a condition the UI words is reported as its own
 * STATE field, and that field is what signals. Examples: the throughput window
 * (HEALTHY/UNKNOWN/DEGRADED/STALLED) qualifies the rate sample; a stall or degradation the UI
 * shows is the readiness reason code ({@code index.throughput_*}), which is STATE. Chat
 * {@code generating} is the {@code inFlight > 0} gauge projection; the state it causes for
 * indexing is {@code operating.indexing.state/reasons}, which is STATE. GPU utilisation and VRAM
 * bytes are measurements; GPU availability, CUDA function, profile, spill alarm and staleness are
 * STATE.
 *
 * <p>A record field of record, list or map type classified STATE contributes its own classified
 * fields (and its presence and membership); one classified MEASUREMENT or TIME is left out whole.
 * Every field reachable through STATE fields must be listed here; the completeness check
 * ({@code StatusStateFieldsTest}) walks the reported structure and fails on any that is not. At run time an
 * unclassified field is compared as STATE (unless it carries an observation-clock name) so a
 * missing entry can never hide a change; the completeness check keeps it from going unnoticed.
 */
final class StatusFieldClassification {
  enum Kind { STATE, MEASUREMENT, TIME }

  private StatusFieldClassification() {}

  private static final Map<Class<?>, Map<String, Kind>> FIELDS = build();

  /**
   * Owner publications ({@code StatusChangeCounter.changed}), by simple class name, and the state
   * each compares. Every caller must be listed; each compares state values only, never
   * measurements or clocks.
   */
  static final Map<String, String> PRODUCERS = Map.ofEntries(
      Map.entry("DefaultEngineComponentRegistry",
          "component registry revision: component state, reason, versions, evidence"),
      Map.entry("TransitionRunner",
          "inference mode transition begin/install, identity generation, last failure"),
      Map.entry("PlacementStage",
          "installed download profile and model set"),
      Map.entry("ChatServerControllerImpl",
          "chat unload latch detail"),
      Map.entry("DataKeyManager",
          "conversation key state (not configured, locked, unlocked)"),
      Map.entry("DevicePlanState", "GPU profile and its source"),
      Map.entry("EnergyStatePoller", "OS energy intent and source"),
      Map.entry("RuntimeReconciler",
          "runtime condition axes: status and reason"),
      Map.entry("SettingsCommitCoordinator",
          "committed chat enablement"),
      Map.entry("RemoteIndexingJobsBridge",
          "which job states are present in the Worker queue (not their counts)"),
      Map.entry("WatchedRootsState",
          "watched roots, indexed or never, walk errors and completion, collections,"
              + " delete-detection marks (not timestamps or prune counts)"),
      Map.entry("BulkWorkGate", "bulk work mode and reasons"),
      Map.entry("StatusPublicationBackstop",
          "STATE fields of canonical status, per this classification"));

  /** The declared kind, or {@code null} when the field has no classification. */
  static Kind kind(Class<?> type, String field) {
    var fields = FIELDS.get(type);
    return fields == null ? null : fields.get(field);
  }

  private static Map<Class<?>, Map<String, Kind>> build() {
    var table = new LinkedHashMap<Class<?>, Map<String, Kind>>();
    put(table, StatusResponse.class,
        state("schemaVersion", "lifecycle", "components", "status", "service", "indexBasePath",
            "diskPressure", "worker", "indexAvailable", "knowledgeServerStartError",
            "indexStatusReason", "power", "catchingUp", "inference", "readiness", "aiReady",
            "embeddingReady", "embedding", "schema", "chunkCoverage", "queueHealth", "migration",
            "gpu", "atRestProtection", "conversationProtection", "modelDistribution",
            "agentSessions", "telemetryHealth", "meta", "operating"),
        measurement("memoryUsedBytes", "memoryTotalBytes", "memoryMaxBytes"),
        time("observedAt", "uptimeMs"));
    put(table, LifecycleSnapshotV2.Lifecycle.class, state("state", "reason_code", "message"));
    put(table, LifecycleSnapshotV2.Components.class,
        state("api", "index", "encoders", "generative"));
    put(table, LifecycleSnapshotV2.Component.class, state("state", "reason_code"), time("state_since"));
    put(table, WorkerOperationalView.class,
        state("core", "failure", "migration", "compatibility", "queueDb", "enrichment", "gpu",
            "vectorFormat", "telemetry", "searchConfig", "visualExtraction", "buildStamp"));
    put(table, CoreIndexView.class,
        state("indexHealthy", "indexState", "indexedDocumentsUnknown", "vduAvailable"),
        measurement("indexedDocuments", "pendingJobs", "indexSizeBytes", "pendingVduCount",
            "writerQueueDepth", "writerPendingDocs", "commitCount", "refreshLagMs",
            "recentJobQueueDepth", "recentDocsPerSec", "pendingBytes", "pendingUnknownSizeJobs",
            "searchableDocuments", "indexMaxDoc", "indexNumDocs"));
    put(table, FailureTrackingView.class,
        measurement("failedJobs", "lastFailedPath", "lastFailedErrorMessage",
            "searchesZeroResultCount", "failedByFileKind"),
        time("lastFailedAtMs", "nextRetryAtMs"));
    put(table, MigrationGenerationView.class,
        state("activeGenerationId", "migrationState", "buildingGenerationId",
            "previousGenerationId", "servingSearchGenerationId", "servingIngestGenerationId",
            "migrationSwitchingMaxDurationMs", "migrationPaused", "migrationPauseReason",
            "migrationSource", "migrationEnumerator"),
        measurement("activeIndexedDocuments", "buildingIndexedDocuments", "switchBufferDepth",
            "pendingJobsCount", "processingJobsCount", "pendingReadyJobsCount",
            "pendingBackoffJobsCount"),
        time("migrationSwitchingAgeMs", "migrationPausedAtMs"));
    put(table, MigrationEnumeratorView.class,
        state("running", "done"),
        measurement("rootsTotal", "rootsDone", "filesSeen", "filesEnqueued", "lastPath"),
        time("startedAtMs", "finishedAtMs"));
    put(table, CompatibilityStatusView.class,
        state("embeddingCompatState", "embeddingCompatReason", "embeddingFingerprintCurrent",
            "embeddingFingerprintStored", "indexSchemaFpCurrent", "indexSchemaFpStored",
            "indexSchemaCompatState", "reindexRequired", "reindexRequiredReason"));
    put(table, QueueDbStatusView.class,
        state("queueDbHealthy", "queueDbLastQuickCheckOk"),
        time("queueDbLastBackupAtMs", "queueDbLastQuickCheckAtMs", "queueDbLastErrorAtMs"));
    put(table, EnrichmentProgressView.class,
        state("chunk", "completeness", "chunkMinChars", "embeddingEnabled", "spladeEnabled",
            "nerEnabled", "backfillMode", "embeddingAvailable", "spladeAvailable", "nerAvailable"),
        measurement("embeddingDocCount", "embeddingCompletedCount", "embeddingPendingCount",
            "embeddingFailedCount", "embeddingCoveragePercent", "spladeDocCount",
            "spladeCompletedCount", "spladePendingCount", "spladeFailedCount",
            "spladeCoveragePercent", "pendingNerCount", "completedNerCount", "failedNerCount",
            "vectorReadyPercent", "enrichmentCompleted", "batchTiming", "encoderProfiles",
            "embeddedAndSparseCount"));
    put(table, ChunkCoverageView.class,
        state("chunkVectorsReady", "chunkSpladeEnabled"),
        measurement("chunkDocCount", "chunkEmbeddingCompletedCount", "chunkEmbeddingPendingCount",
            "chunkEmbeddingFailedCount", "chunkVectorCoveragePercent", "chunkSpladeCompletedCount",
            "chunkSpladePendingCount", "chunkSpladeCoveragePercent"));
    put(table, StageCompletenessView.class,
        state("stageId", "tier"), measurement("expected", "present", "missing", "failed"));
    put(table, GpuDiagnosticsView.class,
        state("rerankerOrtCuda", "spladeOrtCuda", "embedOrtCuda", "embedBackend", "embedGpuLayers",
            "spladeModelPath", "rerankerModelPath", "nerModelPath", "nerGpuEnabled", "nerOrtCuda",
            "citationOrtCuda", "bgeM3OrtCuda"));
    put(table, OrtCudaView.class,
        state("configured", "attempted", "available", "variantId", "nativePath", "failureReason",
            "missingDlls"));
    put(table, VectorFormatView.class,
        state("vectorFormatConfig", "vectorFormatStored", "vectorFormatActual"),
        measurement("vectorSegmentsFloat32", "vectorSegmentsQuantized"));
    put(table, TelemetryMetricsView.class,
        measurement("contentLengthAvgChars", "contentLengthMinChars", "contentLengthMaxChars",
            "throughputDocsPerSec", "throughputWindowState"));
    put(table, SearchConfigView.class,
        state("chunkAwareEnabled", "ccWeightSparse", "ccWeightDense", "ccWeightSplade",
            "branchCcWeightWhole", "branchCcWeightChunk", "branchChunkMinWeightMultiplier",
            "titleBoost", "entityBoost", "queryClassificationEnabled"));
    put(table, VisualExtractionView.class,
        state("ocrEnabled", "ocrEngineAvailable", "ocrEngine", "ocrBlockedReason",
            "vduBlockedReason", "vduProcessing"),
        measurement("visualTextNeededCount", "visualEnrichmentNeededCount"));
    put(table, PowerStatusView.class, state("energyReduced", "source"));
    put(table, InferenceRuntimeView.class,
        state("phase", "identity", "usingExternal", "lastFailure", "chatEnabledSpec",
            "engineState", "engineReason", "procedure", "leaseHolder"),
        measurement("counters"));
    put(table, RuntimeIdentityView.class,
        state("generationId", "modelId", "port"), time("loadedAtEpochMs"));
    put(table, InferenceFailureView.class, state("code", "detail"));
    put(table, ReadinessEnvelopeView.class,
        state("schemaVersion", "engineComponents", "components", "composites"),
        time("observedAt"));
    put(table, EngineComponentView.class,
        state("state", "reasonCode", "appliedVersion", "desiredVersion", "mode", "reason",
            "deadlineMs", "evidence"),
        measurement("freeBytes", "footprintBytes", "recoveryAttempts"),
        time("stateSince"));
    put(table, ReadinessComponentView.class,
        state("state", "reasonCode", "source", "stale"), time("observedAt", "stalenessMs"));
    put(table, ReadinessCompositeView.class,
        state("state", "reasonCodes", "stale"), time("maxStalenessMs"));
    put(table, EmbeddingStatusGroup.class,
        state("compatState", "compatReason", "fingerprintCurrent", "fingerprintStored", "ready"),
        measurement("docCount", "completedCount", "pendingCount", "failedCount",
            "coveragePercent"),
        time("observedAtMs"));
    put(table, SchemaStatusGroup.class,
        state("fpCurrent", "fpStored", "compatState", "reindexRequired", "reindexRequiredReason"),
        time("observedAtMs"));
    put(table, ChunkCoverageGroup.class,
        state("ready"),
        measurement("docCount", "completedCount", "pendingCount", "failedCount",
            "coveragePercent"),
        time("observedAtMs"));
    put(table, QueueHealthGroup.class,
        state("healthy", "lastQuickCheckOk"),
        time("observedAtMs", "lastBackupAtMs", "lastQuickCheckAtMs", "lastErrorAtMs"));
    put(table, MigrationStatusGroup.class,
        state("state", "buildingGenerationId", "previousGenerationId", "paused", "pauseReason",
            "switchingMaxDurationMs", "enumerator", "migrationSource"),
        time("observedAtMs", "switchingAgeMs"));
    put(table, GpuStatusView.class,
        state("available", "driverVersion", "deviceCount", "cudaFunctional", "source",
            "confidence"),
        measurement("gpuUtilizationPercent", "memoryUtilizationPercent", "totalVramBytes",
            "usedVramBytes", "freeVramBytes", "recentUtilizationPercent",
            "recentMemoryUtilizationPercent"));
    put(table, AtRestProtectionView.class,
        state("diskEncryption", "source", "confidence", "qualityKnown"), time("probedAtMs"));
    put(table, ConversationProtectionView.class, state("state"));
    put(table, ModelDistributionStatusView.class,
        state("installProfile", "modelVariants", "upgradeGuidance"));
    put(table, ModelVariantView.class,
        state("filename", "precision", "executionProvider", "degraded", "degradationReason",
            "skipped", "skipReason"));
    put(table, AgentSessionView.class, measurement("activeCount"));
    put(table, TelemetryHealthView.class,
        measurement("flushFailureCount", "gaugeCallbackFailureCount"));
    put(table, StatusMeta.class, state("workerRpcStale"), time("workerRpcAtMs"));
    put(table, OperatingStateView.class,
        state("schemaVersion", "chat", "indexing", "gpu", "degradations", "freshness"),
        // seq summarises the block including its measurements; the fields it summarises signal.
        measurement("seq"),
        time("assembledAt"));
    put(table, OperatingChatView.class,
        state("state", "reason", "detail", "reasonCode", "managed", "blockedBy", "load",
            "reloadAllowed"),
        measurement("generating", "inFlight", "queued"),
        time("since", "nextReloadNotBefore", "sampledAt"));
    put(table, ChatLoadView.class,
        state("phase", "expectedSource"), measurement("expectedMs"),
        time("startedAt", "elapsedMs"));
    put(table, OperatingIndexingView.class,
        state("state", "reasons", "reasonCode", "affects"), measurement("pendingJobs"),
        time("since", "agingReleaseAt", "sampledAt"));
    put(table, OperatingGpuView.class,
        state("profile", "profileSource", "adapter", "spillSuspected", "source", "stale"),
        measurement("adapterTotalBytes", "adapterFreeBytes", "ourDedicatedBytes",
            "ourSharedBytes", "externalUsedBytes"),
        time("sampledAt", "ageMs"));
    put(table, OperatingDegradationView.class,
        state("code", "cause", "scope", "reasonCode"), measurement("count"),
        time("since", "lastSeenAt"));
    put(table, OperatingFreshnessView.class, state("staleSources"), time("staleAfterMs"));
    return Collections.unmodifiableMap(table);
  }

  private record Group(Kind kind, String... names) {}

  private static Group state(String... names) { return new Group(Kind.STATE, names); }

  private static Group measurement(String... names) { return new Group(Kind.MEASUREMENT, names); }

  private static Group time(String... names) { return new Group(Kind.TIME, names); }

  private static void put(Map<Class<?>, Map<String, Kind>> table, Class<?> type, Group... groups) {
    var fields = new LinkedHashMap<String, Kind>();
    for (var group : groups) {
      for (var name : group.names()) {
        if (fields.put(name, group.kind()) != null) {
          throw new IllegalStateException("Status field classified twice: " + type.getName() + "." + name);
        }
      }
    }
    if (table.put(type, Collections.unmodifiableMap(fields)) != null) {
      throw new IllegalStateException("Status type classified twice: " + type.getName());
    }
  }
}
