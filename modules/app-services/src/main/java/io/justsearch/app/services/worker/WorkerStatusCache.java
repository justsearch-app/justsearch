/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.ipc.FacetCounts;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 556 (F-C4.2): owns the Worker status projection ({@link #status()}) and the QU
 * facet-snapshot cache ({@link #refreshFacetSnapshotIfStale()}) extracted verbatim from {@code
 * KnowledgeHttpApiAdapter}/{@code KnowledgeSearchEngine}. These belong together because they share the
 * mutable cache state (avg-content-length for the cross-encoder doc-length gate, the facet snapshot +
 * meta_source vocabulary for query understanding) that the search path reads. {@code KnowledgeSearchEngine}
 * holds one instance and reads the caches via the getters; the facade delegates {@code status()} /
 * {@code getCachedFacetSnapshot()} / {@code setWorkerCapability()} here. Behaviour-preserving.
 */
final class WorkerStatusCache {

  private static final Logger log = LoggerFactory.getLogger(WorkerStatusCache.class);

  /** 363: TTL for facet snapshot cache (5 minutes). */
  private static final long FACET_SNAPSHOT_TTL_MS = 5L * 60 * 1000;

  private final KnowledgeServerBootstrap knowledgeServer;

  /** 258-B1: Cached average content length from the last status poll (cross-encoder doc-length gate). */
  private volatile long cachedAvgContentLengthChars;

  /** 363: Cached index snapshot for QU prompt grounding (empty = no grounding). */
  private volatile String cachedFacetSnapshot = "";

  /** 363: Timestamp of last facet snapshot refresh. */
  private volatile long facetSnapshotTimestampMs;

  /** 385: Cached set of known meta_source values (lowercased) from the last facet refresh. */
  private volatile Set<String> cachedSourceVocabulary = Set.of();

  private volatile io.justsearch.app.services.lifecycle.WorkerCapability workerCapability;

  WorkerStatusCache(KnowledgeServerBootstrap knowledgeServer) {
    this.knowledgeServer = knowledgeServer;
  }

  void setWorkerCapability(io.justsearch.app.services.lifecycle.WorkerCapability cap) {
    this.workerCapability = cap;
  }

  /** Returns the current facet snapshot for filter normalization (366 Phase 6). */
  String getCachedFacetSnapshot() {
    return cachedFacetSnapshot;
  }

  /** Cached avg content length for the cross-encoder document-length gate (258-B1). */
  long avgContentLengthChars() {
    return cachedAvgContentLengthChars;
  }

  /** Cached meta_source vocabulary for deterministic source detection (385). */
  Set<String> sourceVocabulary() {
    return cachedSourceVocabulary;
  }

  boolean isWorkerReady() {
    var cap = workerCapability;
    if (cap != null) return cap.available();
    return knowledgeServer.isReady();
  }

  String workerStateName() {
    var cap = workerCapability;
    if (cap != null) return cap.health().name();
    return knowledgeServer.workerCapability().health().name();
  }

  KnowledgeStatus status() {
    boolean ready = isWorkerReady();

    if (!ready) {
      return new KnowledgeStatus(
          workerStateName(),
          false,
          0,
          0,
          0,
          0,
          "",
          "",
          0L,
          0L,
          0L,
          0L,
          0L,
          0L,
          0L,
          false,
          "",
          0L,
          false,
          workerStateName(),
          Map.of());
    }

    KnowledgeClient client = knowledgeServer.client();
    StatusResponse s = client.getStatus();

    // Include embedding compatibility status in extras
    Map<String, Object> extras = new HashMap<>();
    extras.put("embeddingCompatState", s.getCompatibility().getEmbeddingCompatState());
    extras.put("embeddingCompatReason", s.getCompatibility().getEmbeddingCompatReason());
    extras.put("embeddingFingerprintCurrent", s.getCompatibility().getEmbeddingFingerprintCurrent());
    extras.put("embeddingFingerprintStored", s.getCompatibility().getEmbeddingFingerprintStored());

    // Include schema compatibility status (U21-MIG-001)
    extras.put("indexSchemaFpCurrent", s.getCompatibility().getSchemaFpCurrent());
    extras.put("indexSchemaFpStored", s.getCompatibility().getSchemaFpStored());
    extras.put("indexSchemaCompatState", s.getCompatibility().getSchemaCompatState());
    extras.put("reindexRequired", s.getCompatibility().getReindexRequired());
    extras.put("reindexRequiredReason", s.getCompatibility().getReindexRequiredReason());

    // Phase 7: Chunk vector coverage for RAG readiness UI
    extras.put("chunkDocCount", s.getEnrichment().getChunk().getDocCount());
    extras.put("chunkEmbeddingCompletedCount", s.getEnrichment().getChunk().getCompletedCount());
    extras.put("chunkEmbeddingPendingCount", s.getEnrichment().getChunk().getPendingCount());
    extras.put("chunkEmbeddingFailedCount", s.getEnrichment().getChunk().getFailedCount());
    extras.put("chunkVectorCoveragePercent", s.getEnrichment().getChunk().getCoveragePercent());
    extras.put("chunkVectorsReady", s.getEnrichment().getChunk().getVectorsReady());

    // Tempdoc 931 §E item 8: chunk-SPLADE coverage (chunk documents only — the spladeCoveragePercent
    // below counts parents) plus the flag that says whether zero coverage means "off" or "behind".
    extras.put("chunkSpladeEnabled", s.getEnrichment().getChunk().getSpladeEnabled());
    extras.put(
        "chunkSpladeCompletedCount", s.getEnrichment().getChunk().getSpladeCompletedCount());
    extras.put("chunkSpladePendingCount", s.getEnrichment().getChunk().getSpladePendingCount());
    extras.put(
        "chunkSpladeCoveragePercent", s.getEnrichment().getChunk().getSpladeCoveragePercent());

    // Tempdoc 931 §E item 8: merge state of the active reader — maxDoc - numDocs is the
    // deleted-but-unmerged backlog.
    extras.put("indexMaxDoc", s.getCore().getIndexMaxDoc());
    extras.put("indexNumDocs", s.getCore().getIndexNumDocs());

    // 366: Doc-level enrichment coverage for MCP status (agent discoverability)
    extras.put("embeddingCoveragePercent", s.getEnrichment().getEmbedding().getCoveragePercent());
    extras.put("spladeCoveragePercent", s.getEnrichment().getSplade().getCoveragePercent());
    extras.put("pendingNerCount", s.getEnrichment().getPendingNerCount());
    extras.put("completedNerCount", s.getEnrichment().getCompletedNerCount());

    // SRQ-001: Document content length telemetry
    long avgChars = s.getTelemetry().getContentLengthAvgChars();
    extras.put("contentLengthAvgChars", avgChars);
    extras.put("contentLengthMinChars", s.getTelemetry().getContentLengthMinChars());
    extras.put("contentLengthMaxChars", s.getTelemetry().getContentLengthMaxChars());

    // 258-B1: cache for cross-encoder document-length gate
    cachedAvgContentLengthChars = avgChars;

    return new KnowledgeStatus(
        workerStateName(),
        true,
        s.getCore().getQueueDepth(),
        s.getCore().getDocCount(),
        s.getMigration().getActiveDocCount(),
        s.getMigration().getBuildingDocCount(),
        s.getMigration().getServingSearchGenerationId(),
        s.getMigration().getServingIngestGenerationId(),
        s.getMigration().getSwitchBufferDepth(),
        s.getMigration().getPendingJobsCount(),
        s.getMigration().getProcessingJobsCount(),
        s.getMigration().getPendingReadyJobsCount(),
        s.getMigration().getPendingBackoffJobsCount(),
        s.getMigration().getSwitchingAgeMs(),
        s.getMigration().getSwitchingMaxDurationMs(),
        s.getMigration().getPaused(),
        s.getMigration().getPauseReason(),
        s.getMigration().getPausedAtMs(),
        s.getCore().getIsHealthy(),
        s.getCore().getState(),
        extras);
  }

  /**
   * 363: Refreshes the facet snapshot for QU prompt grounding if stale. Non-blocking: fires a
   * background search with facets and updates the cached snapshot when the result arrives. The
   * snapshot is a text block listing top facet values per field.
   */
  void refreshFacetSnapshotIfStale() {
    long now = System.currentTimeMillis();
    if (now - facetSnapshotTimestampMs < FACET_SNAPSHOT_TTL_MS) return;
    if (!isWorkerReady()) return;

    // Mark as refreshed immediately to avoid concurrent refreshes
    facetSnapshotTimestampMs = now;

    try {
      KnowledgeClient client = knowledgeServer.client();
      SearchRequest facetReq =
          SearchRequest.newBuilder()
              .setQuery("*:*")
              .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
              .setLimit(1)
              // Tempdoc 787 item 4b: a bare request (no pipeline) resolves on the Worker via
              // SearchPlanner's `deprecated_mode_fallback` branch. PipelineConfigs.TEXT is that
              // branch's leg set stated explicitly (sparse+lmart+expansion), so the wire shape
              // becomes current without changing which legs run for this facet-snapshot probe.
              .setPipeline(io.justsearch.ipc.PipelineConfigs.TEXT)
              .setFacets(
                  FacetSpec.newBuilder()
                      .setInclude(true)
                      .addFields(FacetFieldSpec.newBuilder().setField("meta_source").setSize(50))
                      .addFields(FacetFieldSpec.newBuilder().setField("meta_category").setSize(20))
                      .addFields(
                          FacetFieldSpec.newBuilder()
                              .setField("entity_persons_raw")
                              .setSize(30))
                      .addFields(
                          FacetFieldSpec.newBuilder()
                              .setField("entity_organizations_raw")
                              .setSize(30)))
              .build();
      SearchResponse resp = client.search(facetReq);

      // 385: Extract meta_source keys for StructuredQueryAnalyzer vocabulary
      FacetCounts sourceFacet = resp.getFacetsMap().get("meta_source");
      if (sourceFacet != null && !sourceFacet.getCountsMap().isEmpty()) {
        Set<String> vocab = new HashSet<>();
        for (String key : sourceFacet.getCountsMap().keySet()) {
          if (key != null && !key.isBlank()) {
            vocab.add(key.toLowerCase(Locale.ROOT).strip());
          }
        }
        cachedSourceVocabulary = java.util.Collections.unmodifiableSet(vocab);
      }

      StringBuilder sb = new StringBuilder();
      for (var entry : resp.getFacetsMap().entrySet()) {
        FacetCounts counts = entry.getValue();
        if (counts == null || counts.getCountsMap().isEmpty()) continue;
        sb.append(entry.getKey()).append(": ");
        counts.getCountsMap().entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(30)
            .forEach(
                e -> sb.append(e.getKey()).append(" (").append(e.getValue()).append("), "));
        if (sb.length() > 2 && sb.charAt(sb.length() - 2) == ',') {
          sb.setLength(sb.length() - 2); // trim trailing ", "
        }
        sb.append('\n');
      }

      String snapshot = sb.toString().trim();
      if (!snapshot.isEmpty()) {
        cachedFacetSnapshot = "Known index contents:\n" + snapshot;
        log.debug("QU facet snapshot refreshed ({} chars)", cachedFacetSnapshot.length());
      } else {
        // 366: Empty facets — reset timestamp to allow retry on next search.
        facetSnapshotTimestampMs = 0;
        cachedSourceVocabulary = Set.of(); // 385: reset vocabulary
        log.debug("QU facet snapshot empty, will retry on next search");
      }
    } catch (Exception e) {
      facetSnapshotTimestampMs = 0; // 366: allow retry on failure
      cachedSourceVocabulary = Set.of(); // 385: reset vocabulary
      log.debug("QU facet snapshot refresh failed: {}", e.getMessage());
    }
  }
}
