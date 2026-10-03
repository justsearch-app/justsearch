/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.Map;

/**
 * Presentation view for GET /api/knowledge/status when the Worker is ready.
 *
 * <p>Uses canonical field names ({@code pendingJobs}, {@code indexedDocuments}, etc.) with
 * deprecated legacy accessors that emit the old names ({@code queueDepth}, {@code docCount})
 * during the migration period. Both old and new names coexist in the JSON response.
 *
 * <p>Stability: stable (API contract)
 */
@RecordBuilder
public record KnowledgeStatusView(
    String state,
    boolean ready,

    // Canonical names (renamed from KnowledgeStatus)
    long pendingJobs,
    long indexedDocuments,
    long activeIndexedDocuments,
    long buildingIndexedDocuments,

    // Unchanged names
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
    boolean healthy,
    String indexState,

    // Promoted from extras
    String embeddingCompatState,
    String embeddingCompatReason,
    String embeddingFingerprintCurrent,
    String embeddingFingerprintStored,

    // 366: Enrichment coverage for MCP agent discoverability
    double embeddingCoveragePercent,
    double spladeCoveragePercent,
    int pendingNerCount,
    int completedNerCount,

    // L162: chunk-level embedding readiness (independent from parent-doc embeddingCoveragePercent)
    Boolean chunkEmbeddingReady,

    // L160: stale-cache indicators (set when in-process port to Worker failed and cached view is served)
    Boolean statusStale,
    Long statusStaleMs) {

  public KnowledgeStatusView {
    state = state == null ? "" : state;
    servingSearchGenerationId = servingSearchGenerationId == null ? "" : servingSearchGenerationId;
    servingIngestGenerationId = servingIngestGenerationId == null ? "" : servingIngestGenerationId;
    migrationPauseReason = migrationPauseReason == null ? "" : migrationPauseReason;
    indexState = indexState == null ? "" : indexState;
    embeddingCompatState = embeddingCompatState == null ? "" : embeddingCompatState;
    embeddingCompatReason = embeddingCompatReason == null ? "" : embeddingCompatReason;
    embeddingFingerprintCurrent =
        embeddingFingerprintCurrent == null ? "" : embeddingFingerprintCurrent;
    embeddingFingerprintStored =
        embeddingFingerprintStored == null ? "" : embeddingFingerprintStored;
    chunkEmbeddingReady = chunkEmbeddingReady == null ? Boolean.FALSE : chunkEmbeddingReady;
    statusStale = statusStale == null ? Boolean.FALSE : statusStale;
    statusStaleMs = statusStaleMs == null ? 0L : statusStaleMs;
  }

  /** Creates a view from the internal {@link KnowledgeStatus} data carrier. */
  public static KnowledgeStatusView from(KnowledgeStatus ks) {
    Map<String, Object> extras = ks.extras();
    return KnowledgeStatusViewBuilder.builder()
        .state(ks.state())
        .ready(ks.ready())
        .pendingJobs(ks.queueDepth())
        .indexedDocuments(ks.docCount())
        .activeIndexedDocuments(ks.activeDocCount())
        .buildingIndexedDocuments(ks.buildingDocCount())
        .servingSearchGenerationId(ks.servingSearchGenerationId())
        .servingIngestGenerationId(ks.servingIngestGenerationId())
        .switchBufferDepth(ks.switchBufferDepth())
        .pendingJobsCount(ks.pendingJobsCount())
        .processingJobsCount(ks.processingJobsCount())
        .pendingReadyJobsCount(ks.pendingReadyJobsCount())
        .pendingBackoffJobsCount(ks.pendingBackoffJobsCount())
        .migrationSwitchingAgeMs(ks.migrationSwitchingAgeMs())
        .migrationSwitchingMaxDurationMs(ks.migrationSwitchingMaxDurationMs())
        .migrationPaused(ks.migrationPaused())
        .migrationPauseReason(ks.migrationPauseReason())
        .migrationPausedAtMs(ks.migrationPausedAtMs())
        .healthy(ks.healthy())
        .indexState(ks.indexState())
        .embeddingCompatState(asString(extras, "embeddingCompatState"))
        .embeddingCompatReason(asString(extras, "embeddingCompatReason"))
        .embeddingFingerprintCurrent(asString(extras, "embeddingFingerprintCurrent"))
        .embeddingFingerprintStored(asString(extras, "embeddingFingerprintStored"))
        .embeddingCoveragePercent(asDouble(extras, "embeddingCoveragePercent"))
        .spladeCoveragePercent(asDouble(extras, "spladeCoveragePercent"))
        .pendingNerCount(asInt(extras, "pendingNerCount"))
        .completedNerCount(asInt(extras, "completedNerCount"))
        .chunkEmbeddingReady(asBoolean(extras, "chunkVectorsReady"))
        .statusStale(Boolean.FALSE)
        .statusStaleMs(0L)
        .build();
  }

  // ---- Deprecated legacy accessors (emit old JSON key names during migration) ----

  @JsonProperty("queueDepth")
  @Deprecated
  public long legacyQueueDepth() {
    return pendingJobs;
  }

  @JsonProperty("docCount")
  @Deprecated
  public long legacyDocCount() {
    return indexedDocuments;
  }

  @JsonProperty("activeDocCount")
  @Deprecated
  public long legacyActiveDocCount() {
    return activeIndexedDocuments;
  }

  @JsonProperty("buildingDocCount")
  @Deprecated
  public long legacyBuildingDocCount() {
    return buildingIndexedDocuments;
  }

  private static String asString(Map<String, Object> map, String key) {
    Object v = map.get(key);
    return v instanceof String s ? s : "";
  }

  private static double asDouble(Map<String, Object> map, String key) {
    Object v = map.get(key);
    return v instanceof Number n ? n.doubleValue() : 0.0;
  }

  private static int asInt(Map<String, Object> map, String key) {
    Object v = map.get(key);
    return v instanceof Number n ? n.intValue() : 0;
  }

  private static boolean asBoolean(Map<String, Object> map, String key) {
    Object v = map.get(key);
    return v instanceof Boolean b && b;
  }
}
