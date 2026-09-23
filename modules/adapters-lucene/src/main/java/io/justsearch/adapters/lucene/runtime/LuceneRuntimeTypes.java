/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import java.util.Map;

/**
 * Public API types extracted from {@link LuceneLifecycleManager} for better organization and
 * independent usage.
 *
 * <p>These types are used as parameters and return values for the Lucene runtime search and
 * indexing operations.
 */
public final class LuceneRuntimeTypes {

  private LuceneRuntimeTypes() {
    // Utility class - no instantiation
  }

  // ==========================================================================
  // Batch Update
  // ==========================================================================

  /**
   * Result of a batch read-modify-write operation via {@link
   * LuceneLifecycleManager#updateDocumentsBatch}.
   *
   * @param updatedCount documents successfully found and updated
   * @param notFoundCount document IDs not found in the index snapshot
   */
  public record BatchUpdateResult(int updatedCount, int notFoundCount) {}

  // ==========================================================================
  // Build State
  // ==========================================================================

  /** Migration-oriented commit marker for generation build verification. */
  public enum BuildState {
    BUILDING,
    COMPLETE
  }

  // ==========================================================================
  // Search Configuration
  // ==========================================================================

  /** Sort modes for interactive TEXT searches (used for pagination stability). */
  public enum RuntimeSearchSort {
    RELEVANCE("relevance"),
    MODIFIED_DESC("modified_desc"),
    MODIFIED_ASC("modified_asc"),
    SIZE_DESC("size_desc"),
    SIZE_ASC("size_asc"),
    PATH_ASC("path_asc"),
    PATH_DESC("path_desc");

    private final String key;

    RuntimeSearchSort(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }

    public static RuntimeSearchSort fromKey(String key) {
      if (key == null || key.isBlank()) return null;
      String k = key.trim().toLowerCase(java.util.Locale.ROOT);
      for (RuntimeSearchSort s : values()) {
        if (s.key.equals(k)) return s;
      }
      return null;
    }
  }

  /** Query parsing modes for TEXT/HYBRID search queries. */
  public enum QuerySyntax {
    /** Treat user input as plain text (escape Lucene operators). */
    SIMPLE,
    /** Interpret user input as Lucene QueryParser syntax (phrases/boolean/field qualifiers). */
    LUCENE,
  }

  /**
   * Filter spec for interactive file search (Worker-side).
   *
   * <p>Implements the generated {@code With} interface (tempdoc 811 D-2) so a caller that needs to
   * override ONE component copies the rest by construction: {@code f.withIncludeChunks(false)}.
   * Hand-rolled rebuilds silently dropped {@code collection} and {@code docIds} — an explicit scope
   * vanished on the RAG path — and would drop the next component added here just as silently.
   */
  @RecordBuilder
  public record RuntimeSearchFilters(
      List<String> mime,
      List<String> language,
      List<String> fileKind,
      List<String> mimeBase,
      String pathPrefix,
      Long modifiedFromMs,
      Long modifiedToMs,
      boolean includeChunks,
      List<String> entityPersons,
      List<String> entityOrganizations,
      List<String> entityLocations,
      List<String> metaSource,
      List<String> metaAuthor,
      List<String> metaCategory,
      Long metaPublishedFromMs,
      Long metaPublishedToMs,
      List<String> docIds,
      // Tempdoc 585 §D Phase 4 (D4b) — scope to Lucene collection tag(s) (e.g. "agent-history").
      List<String> collection)
      implements LuceneRuntimeTypesRuntimeSearchFiltersBuilder.With {}

  // ==========================================================================
  // Search Results
  // ==========================================================================

  // ==========================================================================
  // Typed per-hit provenance (tempdoc 549 Slice 3c, U2)
  //
  // Built at the orchestrator (SearchExecutor) from the typed pre-fusion leg
  // results, NOT reconstructed downstream from the stringly-typed debugScores
  // map. adapters-lucene carries no proto dependency, so these plain records
  // mirror the `io.justsearch.ipc.HitProvenance` leg shapes; worker-services
  // (which sees both) maps them to the proto. Nullable sub-records model the
  // proto's optional/`has*` leg semantics (leg absent == null).
  // ==========================================================================

  /** One retrieval leg's per-doc placement: 1-based rank + raw leg score. */
  public record RetrieverSignal(int rank, float rawScore) {}

  /** A fusion stage's per-doc fused score and the method that produced it ("rrf" | "cc"). */
  public record FusionSignal(float score, String method) {}

  /** The 3-leg chunk-fusion per-doc signal (ranks/scores per leg; optional CC fused score). */
  public record ChunkMergeSignal(
      int sparseRank,
      int denseRank,
      int spladeRank,
      float sparseScore,
      float denseScore,
      float spladeScore,
      Float ccScore) {}

  /** The whole-vs-chunk branch-fusion per-doc signal (optional fused score + method). */
  public record BranchFusionSignal(
      float wholeBranchScore, float chunkBranchScore, Float fusionScore, String method) {}

  /** Typed per-hit provenance: any leg may be null (it didn't run for this hit). */
  public record HitProvenanceSignals(
      RetrieverSignal bm25,
      RetrieverSignal splade,
      RetrieverSignal dense,
      FusionSignal fusion,
      ChunkMergeSignal chunkMerge,
      BranchFusionSignal branchFusion) {

    public static final HitProvenanceSignals EMPTY =
        new HitProvenanceSignals(null, null, null, null, null, null);

    public boolean isEmpty() {
      return bm25 == null
          && splade == null
          && dense == null
          && fusion == null
          && chunkMerge == null
          && branchFusion == null;
    }

    /** Returns a copy with the chunk-merge leg set, preserving the others. */
    public HitProvenanceSignals withChunkMerge(ChunkMergeSignal cm) {
      return new HitProvenanceSignals(bm25, splade, dense, fusion, cm, branchFusion);
    }

    /** Returns a copy with the branch-fusion leg set, preserving the others. */
    public HitProvenanceSignals withBranchFusion(BranchFusionSignal bf) {
      return new HitProvenanceSignals(bm25, splade, dense, fusion, chunkMerge, bf);
    }
  }

  /** A single search hit with optional debug scores and optional typed provenance. */
  public record SearchHit(
      String docId,
      float score,
      Map<String, String> fields,
      Map<String, Float> debugScores,
      HitProvenanceSignals provenance) {
    /** Constructor without typed provenance (provenance defaults to null). */
    public SearchHit(
        String docId, float score, Map<String, String> fields, Map<String, Float> debugScores) {
      this(docId, score, fields, debugScores, null);
    }

    /** Constructor without debug scores (backward compatible). */
    public SearchHit(String docId, float score, Map<String, String> fields) {
      this(docId, score, fields, Map.of(), null);
    }

    /** Returns a copy of this hit carrying the given typed provenance. */
    public SearchHit withProvenance(HitProvenanceSignals p) {
      return new SearchHit(docId, score, fields, debugScores, p);
    }
  }

  /** Search result containing hits and metadata (plus optional nextCursor for pagination). */
  public record SearchResult(List<SearchHit> hits, long totalHits, long tookMs, String nextCursor) {
    /** Backward-compatible constructor (no pagination cursor). */
    public SearchResult(List<SearchHit> hits, long totalHits, long tookMs) {
      this(hits, totalHits, tookMs, null);
    }
  }

  /**
   * Result of facet computation for a query (counts may be truncated by a safety cap).
   *
   * <p>Tempdoc 597: {@code matchedDocs} is the number of documents the scan iterated (the matched
   * population the facet values are tallied from). It is the true result-count "M" the headline binds
   * to — every per-value facet count is {@code <= matchedDocs} by construction, so the headline can
   * never read below a facet chip. Capped at the scan's {@code maxDocsScanned} (then {@code truncated}).
   *
   * <p>Tempdoc 821 §L.3 — two honesty contracts the producer
   * ({@link FacetingEngine#computeFacets}) upholds: a requested field the schema cannot facet is
   * ABSENT from {@code facets} (a present key with no counts means "faceted, matched nothing"), and
   * {@code truncated} is {@code true} for a scan that was capped OR that failed partway (partial
   * counts, flagged) — {@code false} means the scan genuinely completed.
   */
  public record FacetsResult(
      Map<String, Map<String, Long>> facets, boolean truncated, long matchedDocs) {}

  /** Result of paginated corpus iteration (all doc IDs in the index). */
  public record ListAllDocumentIdsResult(
      List<String> docIds, long totalCount, long tookMs, long readerVersion) {}

  /**
   * Low-level term statistics for a set of query terms, used to compute QPP signals.
   *
   * @param fieldDocCount number of non-deleted documents that contain the requested field
   * @param docFreqs per-term document frequency (number of docs containing the term)
   * @param termCollFreqs per-term collection frequency (total occurrences across all docs)
   * @param sumTotalTermFreq total term occurrences for the field across the whole collection
   */
  public record QppSignals(
      long fieldDocCount,
      Map<String, Integer> docFreqs,
      Map<String, Long> termCollFreqs,
      long sumTotalTermFreq) {}

  // ==========================================================================
  // Folder Browse
  // ==========================================================================

  /** A single folder entry with aggregate metadata from indexed files. */
  public record FolderInfo(
      String path, String name, long fileCount, long totalSizeBytes, long lastIndexedAt) {}

  /** Result of folder enumeration under a parent path. */
  public record FolderBrowseResult(List<FolderInfo> folders, long tookMs, boolean truncated) {}

  /** Result of listing files directly within a folder. */
  public record FolderFilesResult(List<SearchHit> files, long totalCount, long tookMs) {}

  // ==========================================================================
  // Embedding Status
  // ==========================================================================

  /**
   * Counts of chunk documents by embedding status, used for RAG readiness checks.
   *
   * @param total total number of chunk documents
   * @param completed chunks with COMPLETED embedding status
   * @param pending chunks with PENDING embedding status
   * @param failed chunks with FAILED status
   */
  public record ChunkEmbeddingCounts(int total, int completed, int pending, int failed) {
    /** Coverage percentage (0-100), or 0 if no chunks. */
    public double coveragePercent() {
      return total > 0 ? (completed * 100.0) / total : 0.0;
    }

    /** True if coverage >= threshold (typically 95% for RAG readiness). */
    public boolean isReady(double thresholdPercent) {
      return total > 0 && coveragePercent() >= thresholdPercent;
    }
  }

  /**
   * Live-artifact count of chunk vectors actually present in the index (tempdoc 717) — the
   * artifact-truthful complement to {@link ChunkEmbeddingCounts}, which counts the {@code
   * chunk_embedding_status} bookkeeping field. A chunk can read {@code chunk_embedding_status=
   * COMPLETED} while its non-stored {@code chunk_vector} {@code KnnFloatVectorField} is absent (the
   * F-032 "status lies" class); readiness/serve gates that trust the status can therefore certify a
   * dead chunk leg. This record verifies the vector itself so those gates cannot be fooled.
   *
   * @param totalChunks live chunk documents (IS_CHUNK=true)
   * @param vectorsPresent live chunk documents that actually carry a {@code chunk_vector} value
   */
  public record ChunkVectorPresence(int totalChunks, int vectorsPresent) {
    /** Presence coverage percentage (0-100), or 0 if no chunks. */
    public double coveragePercent() {
      return totalChunks > 0 ? (vectorsPresent * 100.0) / totalChunks : 0.0;
    }

    /** True if presence coverage >= threshold (typically 95% for RAG readiness). */
    public boolean isReady(double thresholdPercent) {
      return totalChunks > 0 && coveragePercent() >= thresholdPercent;
    }
  }

  /**
   * One enrichment stage's completeness counts (tempdoc 821 §3-C3). Every component is counted
   * over the SAME population — the documents in scope that carry the stage's status field — which
   * is what makes them subtractable.
   *
   * @param expected documents in scope that carry the stage's status field at all — an absent
   *     status field means the stage does not apply to that document (post-798), so it must not
   *     sit in a denominator forever
   * @param settledSuccess documents whose status holds a terminal SUCCESS value (FAILED excluded —
   *     it is reported separately so a consumer can tell "not done yet" from "gave up")
   * @param failed documents whose status holds the terminal FAILED value
   * @param artifactPresent documents that carry the stage's actual artifact and are NOT failed;
   *     {@code 0} for a stage whose artifact is not countable (SPLADE's feature field is
   *     {@code docValues:false}; NER writes no per-document artifact). The FAILED exclusion is
   *     load-bearing: a FAILED write can leave the vector in place, so without it {@code
   *     artifactPresent} and {@code failed} would overlap and the remainder would understate the
   *     repair backlog
   */
  public record StageCounts(int expected, int settledSuccess, int failed, int artifactPresent) {
    public static final StageCounts EMPTY = new StageCounts(0, 0, 0, 0);
  }

  /**
   * Index-wide per-stage completeness counts for the four enrichment stages (tempdoc 821 §3-C3).
   * Computed in a single searcher acquisition so every stage's numbers — status counts and
   * artifact counts alike — come from one reader snapshot; {@code embedding}/{@code splade}/{@code
   * ner} are scoped to whole (non-chunk) documents, {@code chunkEmbedding} to chunk documents.
   */
  public record StageCompletenessCounts(
      StageCounts embedding, StageCounts splade, StageCounts ner, StageCounts chunkEmbedding) {
    public static final StageCompletenessCounts EMPTY =
        new StageCompletenessCounts(
            StageCounts.EMPTY, StageCounts.EMPTY, StageCounts.EMPTY, StageCounts.EMPTY);
  }

  /**
   * Counts of whole documents by embedding status (doc-level vector embeddings).
   *
   * @param total total number of whole (non-chunk) documents
   * @param completed documents with COMPLETED embedding status
   * @param pending documents with PENDING embedding status
   * @param failed documents with FAILED embedding status
   */
  public record EmbeddingCounts(int total, int completed, int pending, int failed) {
    /** Coverage percentage (0-100), or 0 if no documents. */
    public double coveragePercent() {
      return total > 0 ? (completed * 100.0) / total : 0.0;
    }
  }

  /**
   * Counts of whole documents by SPLADE feature extraction status.
   *
   * @param total total number of whole (non-chunk) documents
   * @param completed documents with COMPLETED SPLADE status
   * @param pending documents with PENDING SPLADE status
   * @param failed documents with FAILED SPLADE status
   */
  public record SpladeFeatureCounts(int total, int completed, int pending, int failed) {
    /** Coverage percentage (0-100), or 0 if no documents. */
    public double coveragePercent() {
      return total > 0 ? (completed * 100.0) / total : 0.0;
    }
  }

  /**
   * Per-watched-root enrichment coverage (tempdoc 813 §1c/§13) — the same numerator/denominator
   * shapes the index-wide {@link EmbeddingCounts} / {@link SpladeFeatureCounts} report, restricted
   * to documents under one root's path prefix so a Library folder row can say "N% enriched".
   *
   * <p>Denominator discipline: the parent-stage totals count only NON-chunk documents; the chunk
   * tier is counted separately over chunk documents, never mixed into a "N of M files" ratio.
   * Each stage carries its OWN denominator: a document is in a stage's denominator only when it
   * actually carries that stage's status field. An absent status field means the stage does not
   * apply to that document (the post-798 convention) — counting every non-chunk document instead
   * would put documents indexed before a stage existed into a denominator no backfill can ever
   * drain (the backfill selects by status VALUE, so a document with no status field is never
   * picked up), pinning the folder below 100% forever.
   *
   * <p>Numerator discipline: "settled" means the stage reached a TERMINAL state, not specifically
   * success — {@code COMPLETED} + {@code COMPLETED_EMPTY} (where the stage defines it) + {@code
   * FAILED}. A stage that failed permanently will never leave PENDING, so counting only COMPLETED
   * would pin a folder below 100% forever. Mirrors the COMPLETED_EMPTY reasoning already applied to
   * the index-wide SPLADE coverage count.
   *
   * @param parentDocsTotalEmbedding parent docs under the prefix that carry {@code
   *     embedding_status}
   * @param parentDocsSettledEmbedding parent docs whose {@code embedding_status} is terminal
   * @param parentDocsTotalSplade parent docs under the prefix that carry {@code splade_status}
   * @param parentDocsSettledSplade parent docs whose {@code splade_status} is terminal
   * @param parentDocsTotalNer parent docs under the prefix that carry {@code ner_status}
   * @param parentDocsSettledNer parent docs whose {@code ner_status} is terminal
   * @param chunkDocsTotal chunk docs under the prefix that carry {@code chunk_embedding_status}
   * @param chunkDocsSettled chunk docs whose {@code chunk_embedding_status} is terminal
   */
  public record RootCoverageCounts(
      int parentDocsTotalEmbedding,
      int parentDocsSettledEmbedding,
      int parentDocsTotalSplade,
      int parentDocsSettledSplade,
      int parentDocsTotalNer,
      int parentDocsSettledNer,
      int chunkDocsTotal,
      int chunkDocsSettled) {

    /** All-zero sentinel for the unavailable / blank-prefix path. */
    public static final RootCoverageCounts EMPTY =
        new RootCoverageCounts(0, 0, 0, 0, 0, 0, 0, 0);
  }

  // ==========================================================================
  // Runtime Telemetry Hooks
  // ==========================================================================

  /**
   * Tempdoc 406 runtime gauge snapshot — atomic-read view of the per-session counters
   * exposed for status / observability consumers (e.g., {@code /api/status} via the
   * Worker's {@code IndexStatusOps}). Each field is a single volatile read taken
   * independently; consumers should treat this as a "best-effort consistent" view,
   * not a transactional snapshot.
   *
   * <p>Tempdoc 885 item 19 added {@code reopenCount} and {@code segmentsSinceReopen} here rather
   * than in a new catalog, because {@code commitCount} — the other half of the cadence pair — was
   * already this record's field, and a second commit counter would be a fork of it.
   *
   * @param reopenCount count of searcher reopens that swapped in a new reader, across every
   *     reopen path (the background {@code ControlledRealTimeReopenThread}, {@code
   *     CommitOps.maybeRefresh*}, and the reopen-on-demand seam in {@code SearcherBridge}).
   *     <b>PER SESSION, not monotonic for the process:</b> it lives on {@code RuntimeSession} and
   *     therefore RESETS whenever a new session is built — {@code DeferredRuntime.prepareWriterUpgrade},
   *     a blue/green re-open, or the corruption-recovery rebuild. A run that swapped sessions
   *     under-reports against the reason-tagged {@code index.runtime.commit_ms} histogram, which
   *     accumulates across them (885 measured 46 here against 114 there). Prefer the histogram
   *     where one exists; use these for within-run, within-session comparison
   * @param segmentsSinceReopen segments the writer has created since the last reopen — the backlog
   *     the next reopen must open, and therefore what the "first search after N new segments"
   *     column is measuring
   */
  public record RuntimeGaugesSnapshot(
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs,
      long reopenCount,
      long segmentsSinceReopen) {

    public static final RuntimeGaugesSnapshot EMPTY =
        new RuntimeGaugesSnapshot(0L, 0L, 0L, 0L, 0L, 0L);
  }

  /** Optional telemetry hooks. */
  public interface TelemetryEvents {
    default void onHardDelete() {}

    default void onHardDelete(int count) {}

    default void onSoftDelete(int count) {}

    default void onBackpressure() {}

    default void onCommit(long latencyMs) {}

    /** Commit with caller attribution. Default delegates to {@link #onCommit(long)}. */
    default void onCommit(long latencyMs, CommitReason reason) {
      onCommit(latencyMs);
    }

    default void onValidationFailure(ValidationReason reason) {}

    /**
     * Dense-vector fields dropped from a write because their value could not be normalized — a
     * zero-magnitude or non-finite embedding (tempdoc 931 §C.3). The document itself is still
     * written; this counts the fields lost, not the documents.
     */
    default void onVectorFieldDropped(int count) {}

    /**
     * Chunk reads whose text could NOT be reconstructed because the parent document is no longer
     * at the revision the chunk's offsets address (tempdoc 931 §E item 5). The read returns no
     * text for that chunk rather than a slice of the newer revision, so a non-zero trend means
     * reads are landing inside the parent-rewrite / chunk-regeneration window, not that anything
     * is corrupt.
     */
    default void onChunkRevisionMismatch(int count) {}

    // ==========================================================================
    // Tempdoc 406 substrate observability — drain / swap / lock contention.
    // All default no-op; production exporter (WorkerLuceneTelemetryAdapter)
    // bridges to LocalTelemetry counters/timers/histograms.
    // ==========================================================================

    /**
     * Fired at the start of a holder swap or drain. {@code reason} identifies the call site;
     * see {@link SwapReason} for the bounded set of values.
     */
    default void onSwapStart(SwapReason reason) {}

    /**
     * Fired after the swap's old runtime is closed. {@code durationMs} covers the
     * full swap (drain + final commit + close).
     */
    default void onSwapComplete(long durationMs, SwapReason reason) {}

    /**
     * Fired when {@code RunningRuntime.drainAndClose} cannot acquire the
     * writeBarrier write-lock before the supplied timeout. {@code writesStillPending}
     * is the queueDepth at the timeout instant.
     */
    default void onDrainTimeout(long elapsedMs, long writesStillPending) {}

    /**
     * Fired on every readLock acquire on the writeBarrier in IndexingCoordinator.
     * {@code waitNanos} is the time spent blocked. Consumers histogram this; high
     * percentile values indicate either a swap in progress (writeLock held) or
     * write contention.
     */
    default void onWriteBarrierContention(long waitNanos) {}
  }

  public interface SoftDeletesMetrics {
    void onDocsKept(long count);

    void onDocsPurged(long count);
  }
}
