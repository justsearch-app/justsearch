/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.metrics;

import io.justsearch.telemetry.RollingMonitorWindow;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight operational metrics for the Knowledge Server.
 *
 * <p>Tracks key performance indicators without external dependencies.
 * Metrics can be exposed via an in-process port or logged periodically.
 *
 * <p>All operations are thread-safe using atomic primitives.
 */
public final class OperationalMetrics {
  private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);

  // Counters (monotonically increasing)
  private final LongAdder documentsIndexed = new LongAdder();
  private final LongAdder documentsFailedTotal = new LongAdder();
  private final ConcurrentHashMap<String, LongAdder> failedByFileKind = new ConcurrentHashMap<>();
  private final LongAdder searchesTotal = new LongAdder();
  private final LongAdder searchesZeroResultTotal = new LongAdder();
  private final LongAdder searchesFailedTotal = new LongAdder();
  private final LongAdder batchesSubmitted = new LongAdder();
  private final LongAdder batchesRejected = new LongAdder();
  private final LongAdder commitsTotal = new LongAdder();

  // RAG retrieval counters
  private final LongAdder ragRetrievalsTotal = new LongAdder();
  private final LongAdder ragRetrievalsBm25 = new LongAdder();
  private final LongAdder ragRetrievalsHybrid = new LongAdder();
  private final LongAdder ragRetrievalsFallback = new LongAdder();

  // Map-based enrichment backfill counters (354 Phase 2, replacing per-field from 335 §10).
  // Keys are BatchTimingKeys constants (embed, splade, ner).
  private final ConcurrentHashMap<String, LongAdder> enrichmentCompleted =
      new ConcurrentHashMap<>();

  // Map-based batch timing accumulators (354, replacing per-field pattern from 350).
  // Keys are BatchTimingKeys constants. Per-stage timing only accumulates when the
  // stage processed > 0 docs, so totalMs / batchCount gives meaningful per-batch averages.
  private final ConcurrentHashMap<String, LongAdder> batchTimingMs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, LongAdder> batchTimingCount =
      new ConcurrentHashMap<>();

  // Search pipeline fallback counters (250 Phase 5a)
  private final LongAdder hybridFallbackTotal = new LongAdder();
  private final LongAdder vectorBlockedTotal = new LongAdder();
  private final LongAdder spladeSkippedTotal = new LongAdder();

  // Gauges (current values)
  private final AtomicLong queueDepth = new AtomicLong(0);
  private final AtomicLong lastSearchLatencyMs = new AtomicLong(0);
  private final AtomicLong lastIndexLatencyMs = new AtomicLong(0);

  // Histograms (simple min/max/sum for latency)
  private final AtomicLong searchLatencySum = new AtomicLong(0);
  private final AtomicLong searchLatencyMin = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong searchLatencyMax = new AtomicLong(0);
  private final AtomicLong indexLatencySum = new AtomicLong(0);
  private final AtomicLong indexLatencyMin = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong indexLatencyMax = new AtomicLong(0);

  // Content length histogram (character counts for document length telemetry — SRQ-001)
  private final LongAdder contentLengthCount = new LongAdder();
  private final AtomicLong contentLengthSum = new AtomicLong(0);
  private final AtomicLong contentLengthMin = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong contentLengthMax = new AtomicLong(0);

  // Throughput monitoring (W5)
  private final ThroughputMonitor throughputMonitor = new ThroughputMonitor();

  // Encoder profile accumulators (357 — pull model, registered at encoder construction)
  private final ConcurrentHashMap<String, EncoderProfileAccumulator> encoderAccumulators =
      new ConcurrentHashMap<>();

  // Singleton instance
  private static final OperationalMetrics INSTANCE = new OperationalMetrics();

  private OperationalMetrics() {}

  public static OperationalMetrics getInstance() {
    return INSTANCE;
  }

  // ========== Recording Methods ==========

  public void recordDocumentIndexed(long latencyMs) {
    documentsIndexed.increment();
    lastIndexLatencyMs.set(latencyMs);
    indexLatencySum.addAndGet(latencyMs);
    updateMin(indexLatencyMin, latencyMs);
    updateMax(indexLatencyMax, latencyMs);
  }

  /**
   * Records a document indexing failure, categorized by file kind.
   *
   * @param fileKind the file kind bucket (from {@code classifyFileKind}), or null to skip
   *     per-kind tracking (only increments the total failure counter)
   */
  public void recordDocumentFailed(String fileKind) {
    documentsFailedTotal.increment();
    if (fileKind != null) {
      failedByFileKind.computeIfAbsent(fileKind, k -> new LongAdder()).increment();
    }
  }

  public void recordSearch(long latencyMs, long totalHits) {
    searchesTotal.increment();
    if (totalHits == 0) {
      searchesZeroResultTotal.increment();
    }
    lastSearchLatencyMs.set(latencyMs);
    searchLatencySum.addAndGet(latencyMs);
    updateMin(searchLatencyMin, latencyMs);
    updateMax(searchLatencyMax, latencyMs);
  }

  public void recordSearchFailed() {
    searchesFailedTotal.increment();
  }

  public void recordBatchSubmitted(int size) {
    batchesSubmitted.increment();
  }

  public void recordBatchRejected() {
    batchesRejected.increment();
  }

  public void recordCommit() {
    commitsTotal.increment();
  }

  public void setQueueDepth(long depth) {
    queueDepth.set(depth);
  }

  /**
   * Records the extracted content length (in characters) for a successfully indexed document.
   *
   * @param charCount character count of extracted content
   */
  public void recordContentLength(long charCount) {
    contentLengthCount.increment();
    contentLengthSum.addAndGet(charCount);
    updateMin(contentLengthMin, charCount);
    updateMax(contentLengthMax, charCount);
  }

  /**
   * Records a RAG retrieval operation.
   *
   * @param mode the retrieval mode used (e.g. "BM25", "HYBRID", "CHUNK_HYBRID")
   * @param chunksFound number of chunks retrieved
   * @param latencyMs retrieval latency in milliseconds
   */
  public void recordRagRetrieval(String mode, int chunksFound, long latencyMs) {
    ragRetrievalsTotal.increment();
    String normalized = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("hybrid")) {
      ragRetrievalsHybrid.increment();
    } else {
      ragRetrievalsBm25.increment();
    }
  }

  /**
   * Records when RAG retrieval falls back to full document search.
   */
  public void recordRagFallback() {
    ragRetrievalsFallback.increment();
  }

  /**
   * Records enrichment backfill doc-count completions, keyed by stage (354 Phase 2).
   *
   * <p>Units: {@code count} is a document count (documents completed this call), NOT a batch/call
   * count — contrast {@link #recordStageTiming}'s {@code batchTimingCount} accumulator below,
   * which counts calls/cycles, not documents (tempdoc 691 A-5 ambiguity; see {@link
   * #getBatchTimingCount} for the full unit note). Tempdoc 710 Move 2 item 4: as of this move, the
   * caller is {@link io.justsearch.indexerworker.loop.BackfillScheduler} for every path (combined
   * AND individual) — previously only the combined pass called this, so individual-mode counters
   * froze (710 S-B3 finding).
   */
  public void recordEnrichmentCompleted(String stage, int count) {
    if (count > 0) {
      enrichmentCompleted.computeIfAbsent(stage, k -> new LongAdder()).add(count);
    }
  }

  /**
   * Record timing for a per-stage operation that may be skipped (354). Only accumulates when
   * docsProcessed > 0, so totalMs / batchCount gives meaningful per-batch averages.
   *
   * <p>Units (tempdoc 691 A-5): {@code ms} is added to {@code batchTimingMs} directly (a document
   * count is never summed into it — the timing is per CALL, i.e. per batch/cycle), while {@code
   * batchTimingCount} increments by exactly 1 per call regardless of how many documents that call
   * processed. So {@code batchTimingMs / batchTimingCount} is "average ms per batch-cycle", NOT
   * "average ms per document" — divide by {@link #getEnrichmentCompleted}'s count for the latter.
   * Tempdoc 710 Move 2 item 4: as of this move, called by {@link
   * io.justsearch.indexerworker.loop.BackfillScheduler} after every stage call (combined and
   * individual paths alike) — see {@link #recordEnrichmentCompleted}'s note.
   */
  public void recordStageTiming(String stage, int docsProcessed, long ms) {
    if (docsProcessed > 0) {
      batchTimingMs.computeIfAbsent(stage, k -> new LongAdder()).add(ms);
      batchTimingCount.computeIfAbsent(stage, k -> new LongAdder()).increment();
    }
  }

  /**
   * Record timing for a whole-batch operation that always runs (354). Unconditionally accumulates.
   * Same batch-cycle unit convention as {@link #recordStageTiming} — see that method's note.
   */
  public void recordBatchTiming(String key, long ms) {
    batchTimingMs.computeIfAbsent(key, k -> new LongAdder()).add(ms);
    batchTimingCount.computeIfAbsent(key, k -> new LongAdder()).increment();
  }

  // Tempdoc 710 Move 2 item 4: last-known backfill mode ("combined" | "individual" | "idle"),
  // written once per BackfillScheduler.runIdleCycle() call. Exposed on the enrichment status
  // surface (EnrichmentProgressView.backfillMode) so operators can see WHICH pass is running
  // without grepping worker.log — previously observable nowhere (710 S-B3 finding).
  private volatile String backfillMode = "idle";

  /** Records which backfill pass ran this idle cycle. See {@link #getBackfillMode}. */
  public void recordBackfillMode(String mode) {
    this.backfillMode = mode == null ? "idle" : mode;
  }

  /**
   * The most recently observed backfill mode: {@code "combined"} (single-RMW-per-doc pass across
   * embed+SPLADE+NER), {@code "individual"} (per-stage passes), or {@code "idle"} (no backfill
   * work was available/eligible last cycle, or no cycle has run yet).
   */
  public String getBackfillMode() {
    return backfillMode;
  }

  /** Records when HYBRID mode fell back to TEXT (e.g., embeddings blocked). */
  public void recordHybridFallback() {
    hybridFallbackTotal.increment();
  }

  /** Records when vector queries were blocked (fingerprint mismatch, legacy index, etc.). */
  public void recordVectorBlocked() {
    vectorBlockedTotal.increment();
  }

  /** Records when SPLADE was expected but did not execute (encoder unavailable, encoding failed). */
  public void recordSpladeSkipped() {
    spladeSkippedTotal.increment();
  }

  /** Registers an encoder's profiling accumulator. Called once per encoder at construction. */
  public void registerEncoder(String name, EncoderProfileAccumulator accumulator) {
    encoderAccumulators.put(name, accumulator);
  }

  /** Removes an encoder registration. Package-private — for testing only. */
  void deregisterEncoder(String name) {
    encoderAccumulators.remove(name);
  }

  // ========== Reading Methods ==========

  public long getDocumentsIndexed() {
    return documentsIndexed.sum();
  }

  public long getDocumentsFailed() {
    return documentsFailedTotal.sum();
  }

  public Map<String, Long> getFailedByFileKind() {
    return failedByFileKind.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
  }

  public long getSearchesTotal() {
    return searchesTotal.sum();
  }

  public long getSearchesZeroResultCount() {
    return searchesZeroResultTotal.sum();
  }

  public long getSearchesFailed() {
    return searchesFailedTotal.sum();
  }

  public long getBatchesSubmitted() {
    return batchesSubmitted.sum();
  }

  public long getBatchesRejected() {
    return batchesRejected.sum();
  }

  public long getCommitsTotal() {
    return commitsTotal.sum();
  }

  public long getRagRetrievalsTotal() {
    return ragRetrievalsTotal.sum();
  }

  public long getRagRetrievalsBm25() {
    return ragRetrievalsBm25.sum();
  }

  public long getRagRetrievalsHybrid() {
    return ragRetrievalsHybrid.sum();
  }

  public long getRagRetrievalsFallback() {
    return ragRetrievalsFallback.sum();
  }

  public long getHybridFallbackTotal() {
    return hybridFallbackTotal.sum();
  }

  public long getVectorBlockedTotal() {
    return vectorBlockedTotal.sum();
  }

  public long getSpladeSkippedTotal() {
    return spladeSkippedTotal.sum();
  }

  /** Polled by IndexStatusOps — always returns fresh snapshots from live accumulators. */
  public Map<String, EncoderProfileSnapshot> getEncoderProfiles() {
    var result = new LinkedHashMap<String, EncoderProfileSnapshot>();
    encoderAccumulators.forEach(
        (name, acc) -> {
          var snap = acc.snapshot();
          if (snap != null) {
            result.put(name, snap);
          }
        });
    return result;
  }

  /**
   * Snapshot of cumulative enrichment DOCUMENT counts, keyed by stage (354 Phase 2). Units:
   * documents completed — NOT batch/call counts (contrast {@link #getBatchTimingCount}, tempdoc
   * 691 A-5).
   */
  public Map<String, Long> getEnrichmentCompleted() {
    return enrichmentCompleted.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
  }

  /**
   * Snapshot of cumulative batch timing in milliseconds, keyed by BatchTimingKeys (354). Units:
   * total wall-clock ms summed across every recorded CALL (batch/cycle) for that key — divide by
   * {@link #getBatchTimingCount} for "ms per batch-cycle", or by {@link #getEnrichmentCompleted}
   * for "ms per document" (these are different denominators; 691 A-5).
   */
  public Map<String, Long> getBatchTimingMs() {
    return batchTimingMs.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
  }

  /**
   * Snapshot of cumulative BATCH-CYCLE counts, keyed by BatchTimingKeys (354). Units: number of
   * {@link #recordStageTiming}/{@link #recordBatchTiming} calls — NOT documents processed
   * (tempdoc 691 A-5: this is the field that was previously easy to misread as a document count
   * because it sits next to {@link #getEnrichmentCompleted}, which IS a document count).
   */
  public Map<String, Long> getBatchTimingCount() {
    return batchTimingCount.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
  }

  public long getQueueDepth() {
    return queueDepth.get();
  }

  public long getLastSearchLatencyMs() {
    return lastSearchLatencyMs.get();
  }

  public long getLastIndexLatencyMs() {
    return lastIndexLatencyMs.get();
  }

  public double getAverageSearchLatencyMs() {
    long count = searchesTotal.sum();
    return count > 0 ? (double) searchLatencySum.get() / count : 0.0;
  }

  public double getAverageIndexLatencyMs() {
    long count = documentsIndexed.sum();
    return count > 0 ? (double) indexLatencySum.get() / count : 0.0;
  }

  public long getContentLengthCount() {
    return contentLengthCount.sum();
  }

  public double getAverageContentLengthChars() {
    long count = contentLengthCount.sum();
    return count > 0 ? (double) contentLengthSum.get() / count : 0.0;
  }

  public long getContentLengthMinChars() {
    long min = contentLengthMin.get();
    return min == Long.MAX_VALUE ? 0 : min;
  }

  public long getContentLengthMaxChars() {
    return contentLengthMax.get();
  }

  // ========== Logging ==========

  /**
   * Logs a summary of current metrics at INFO level.
   * Useful for periodic status logging.
   */
  public void logSummary() {
    log.info("Metrics: indexed={}, failed={}, failedByKind={}, searches={}, zeroResult={}, searchFailed={}, batches={}, rejected={}, commits={}, queueDepth={}, avgSearchMs={:.1f}, avgIndexMs={:.1f}",
        documentsIndexed.sum(),
        documentsFailedTotal.sum(),
        getFailedByFileKind(),
        searchesTotal.sum(),
        searchesZeroResultTotal.sum(),
        searchesFailedTotal.sum(),
        batchesSubmitted.sum(),
        batchesRejected.sum(),
        commitsTotal.sum(),
        queueDepth.get(),
        getAverageSearchLatencyMs(),
        getAverageIndexLatencyMs());
  }

  /**
   * Returns a formatted string of all metrics for debugging.
   */
  public String toDebugString() {
    return String.format(
        "OperationalMetrics{indexed=%d, failed=%d, failedByKind=%s, " +
            "searches=%d, zeroResult=%d, searchFailed=%d, " +
            "batches=%d, rejected=%d, commits=%d, queueDepth=%d, " +
            "lastSearchMs=%d, lastIndexMs=%d, avgSearchMs=%.1f, avgIndexMs=%.1f, " +
            "ragTotal=%d, ragBm25=%d, ragHybrid=%d, ragFallback=%d}",
        documentsIndexed.sum(),
        documentsFailedTotal.sum(),
        getFailedByFileKind(),
        searchesTotal.sum(),
        searchesZeroResultTotal.sum(),
        searchesFailedTotal.sum(),
        batchesSubmitted.sum(),
        batchesRejected.sum(),
        commitsTotal.sum(),
        queueDepth.get(),
        lastSearchLatencyMs.get(),
        lastIndexLatencyMs.get(),
        getAverageSearchLatencyMs(),
        getAverageIndexLatencyMs(),
        ragRetrievalsTotal.sum(),
        ragRetrievalsBm25.sum(),
        ragRetrievalsHybrid.sum(),
        ragRetrievalsFallback.sum());
  }

  public ThroughputMonitor throughputMonitor() {
    return throughputMonitor;
  }

  // ========== ThroughputMonitor ==========

  /** Rolling-window throughput estimator for the indexing pipeline. */
  public static final class ThroughputMonitor {
    // Window trio authority: io.justsearch.telemetry.RollingMonitorWindow (shared with the
    // head-side GpuSaturationMonitor).
    private static final long WINDOW_MS = RollingMonitorWindow.WINDOW_MS;
    private static final long MAX_GAP_MS = RollingMonitorWindow.MAX_GAP_MS;
    private static final int MAX_SAMPLES = RollingMonitorWindow.MAX_SAMPLES;

    private record Sample(long timeMs, long docs) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>(MAX_SAMPLES + 1);

    public synchronized void recordSample(long documentsIndexed) {
      long now = System.currentTimeMillis();
      samples.addLast(new Sample(now, documentsIndexed));
      while (samples.size() > MAX_SAMPLES) {
        samples.removeFirst();
      }
    }

    public synchronized ThroughputResult compute(long processingJobsCount) {
      if (samples.size() < 2) {
        return new ThroughputResult(0.0, "UNKNOWN");
      }
      Sample oldest = samples.getFirst();
      Sample newest = samples.getLast();
      long deltaTimeMs = newest.timeMs - oldest.timeMs;
      if (deltaTimeMs > MAX_GAP_MS) {
        Sample last = samples.getLast();
        samples.clear();
        samples.addLast(last);
        return new ThroughputResult(0.0, "UNKNOWN");
      }
      if (deltaTimeMs < WINDOW_MS) {
        return new ThroughputResult(0.0, "UNKNOWN");
      }
      long deltaCount = newest.docs - oldest.docs;
      double docsPerSec = deltaCount / (deltaTimeMs / 1000.0);
      String state;
      if (processingJobsCount <= 0) {
        state = "HEALTHY";
      } else if (docsPerSec < 1.0) {
        state = "STALLED";
      } else if (docsPerSec < 5.0) {
        state = "DEGRADED";
      } else {
        state = "HEALTHY";
      }
      return new ThroughputResult(docsPerSec, state);
    }

    /** Clears all samples. Used by profiling reset. */
    public synchronized void reset() {
      samples.clear();
    }

    public record ThroughputResult(double docsPerSec, String state) {}
  }

  // ========== Reset (profiling) ==========

  /** Zeroes all counters, maps, gauges, and histograms. Used by profiling reset. */
  public void resetAll() {
    // LongAdder counters
    documentsIndexed.reset();
    documentsFailedTotal.reset();
    searchesTotal.reset();
    searchesZeroResultTotal.reset();
    searchesFailedTotal.reset();
    batchesSubmitted.reset();
    batchesRejected.reset();
    commitsTotal.reset();
    ragRetrievalsTotal.reset();
    ragRetrievalsBm25.reset();
    ragRetrievalsHybrid.reset();
    ragRetrievalsFallback.reset();
    hybridFallbackTotal.reset();
    vectorBlockedTotal.reset();
    spladeSkippedTotal.reset();
    contentLengthCount.reset();

    // Map-based accumulators
    failedByFileKind.clear();
    enrichmentCompleted.clear();
    batchTimingMs.clear();
    batchTimingCount.clear();
    backfillMode = "idle";

    // Gauges
    queueDepth.set(0);
    lastSearchLatencyMs.set(0);
    lastIndexLatencyMs.set(0);

    // Histograms (sum/max to 0, min to MAX_VALUE)
    searchLatencySum.set(0);
    searchLatencyMin.set(Long.MAX_VALUE);
    searchLatencyMax.set(0);
    indexLatencySum.set(0);
    indexLatencyMin.set(Long.MAX_VALUE);
    indexLatencyMax.set(0);
    contentLengthSum.set(0);
    contentLengthMin.set(Long.MAX_VALUE);
    contentLengthMax.set(0);

    // Throughput monitor
    throughputMonitor.reset();

    // Encoder profile accumulators (reset counters, keep registrations)
    encoderAccumulators.values().forEach(EncoderProfileAccumulator::reset);

    log.info("All operational metrics reset");
  }

  // ========== Helpers ==========

  private static void updateMin(AtomicLong min, long value) {
    long current;
    do {
      current = min.get();
      if (value >= current) return;
    } while (!min.compareAndSet(current, value));
  }

  private static void updateMax(AtomicLong max, long value) {
    long current;
    do {
      current = max.get();
      if (value <= current) return;
    } while (!max.compareAndSet(current, value));
  }
}
