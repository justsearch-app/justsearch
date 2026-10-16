/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Core index status snapshot exposed via {@code /api/status}. Tempdoc 406
 * extended with 4 runtime gauges ({@code writerQueueDepth},
 * {@code writerPendingDocs}, {@code commitCount}, {@code refreshLagMs}) so
 * jseval's timeline can observe the Lucene runtime's internal state without
 * needing the OpenTelemetry metrics stream.
 *
 * <p>Tempdoc 419 C3 V1 (2026-04-26) extended with {@code recentJobQueueDepth}:
 * a 30-min trend of {@code worker.job_queue.depth} (curated RRD metric) so
 * frontend HealthView can render a sparkline next to the existing
 * {@code index-throughput-stalled} / {@code index-throughput-degraded}
 * events. Empty array when the RRD store hasn't accumulated data yet.
 *
 * <p>Tempdoc 419 C3 V2 P2 (2026-04-28) added {@code recentDocsPerSec}: a 30-min
 * trend of {@code worker.documents.indexed.rate_per_sec} (curated RRD metric).
 * Complementary to {@code recentJobQueueDepth} — depth answers "is the backlog
 * draining," rate answers "is the indexer making progress." Both render when a
 * throughput-stalled / throughput-degraded event is firing.
 *
 * <p>Tempdoc 813 Slice B (2026-08-06) added {@code pendingBytes} +
 * {@code pendingUnknownSizeJobs}: the byte weight of remaining
 * (PENDING+PROCESSING) indexing work, summed from the size recorded at
 * enqueue time, so remaining-work estimates can be byte-weighted instead of
 * file-counted. {@code pendingUnknownSizeJobs} counts the remaining jobs that
 * carry no recorded size — a nonzero value means {@code pendingBytes}
 * understates the real backlog, and a consumer must not present it as a
 * complete denominator. {@code pendingUnknownSizeJobs == -1} is the distinct
 * "the queue could not compute the aggregate at all" marker
 * ({@code JobQueue.PendingBytes.UNAVAILABLE}); {@code pendingBytes} is 0 there,
 * so a consumer gated on {@code pendingBytes > 0} renders nothing either way.
 *
 * <p>Tempdoc 811 C-4 (2026-08-06) added {@code searchableDocuments}: the population a DEFAULT-scope
 * search can actually return — {@code indexedDocuments} minus the collections the default search
 * scope excludes (today exactly {@code agent-history}). {@code indexedDocuments} deliberately keeps
 * its meaning (the whole non-chunk index) because Health, jseval and sandbox evidence describe the
 * index itself; a user-facing "Searching N documents" string must read {@code searchableDocuments}
 * instead, which is the only one of the two the user could enumerate.
 *
 * <p>Tempdoc 931 §E item 8 (2026-09-05) added {@code indexMaxDoc} + {@code indexNumDocs}: the ACTIVE
 * (search-serving) reader's raw Lucene counters. {@code indexMaxDoc} includes deleted-but-unmerged
 * documents, {@code indexNumDocs} does not, so their difference is the deleted backlog no merge has
 * reclaimed yet — the signal that separates "the corpus shrank" from "the index has not merged yet".
 * {@code indexedDocuments} is not a substitute: it subtracts chunk documents and can be counted on
 * the ingest reader during a rebuild.
 *
 * <p>Spec E step E1 (task b6d536, §1.7) added {@code vduAvailable}: whether a VDU backlog
 * ({@code pendingVduCount}) can drain right now. The Head sets it (VDU is wired, a vision-capable
 * chat model is configured and no capability block is standing); the Worker cannot know it. Null
 * (not reported) normalizes to true, so a reported backlog is read as being worked on, never as
 * idle. Operating status skips a backlog in an unavailable stage when deciding IDLE.
 *
 * <p>Task t-20261013-ef3c41 added {@code indexedDocumentsUnknown}: true when {@code
 * indexedDocuments} and {@code searchableDocuments} are not counts — the index could not be counted
 * and no earlier count exists. Both then carry 0 for older readers; a surface that knows this field
 * shows the count as checking or unknown, never as an empty index. False means the counts are known.
 */
@SuppressWarnings("ArrayRecordComponent") // 419 C3 V1/V2: intentional for API time-series payload
public record CoreIndexView(
    boolean indexHealthy,
    long indexedDocuments,
    long pendingJobs,
    String indexState,
    long indexSizeBytes,
    int pendingVduCount,
    long writerQueueDepth,
    long writerPendingDocs,
    long commitCount,
    long refreshLagMs,
    long[] recentJobQueueDepth,
    double[] recentDocsPerSec,
    long pendingBytes,
    long pendingUnknownSizeJobs,
    long searchableDocuments,
    long indexMaxDoc,
    long indexNumDocs,
    boolean indexedDocumentsUnknown,
    Boolean vduAvailable) {
  public CoreIndexView {
    indexState = indexState == null ? "" : indexState;
    vduAvailable = vduAvailable == null ? Boolean.TRUE : vduAvailable;
    recentJobQueueDepth = recentJobQueueDepth == null ? new long[0] : recentJobQueueDepth;
    recentDocsPerSec = recentDocsPerSec == null ? new double[0] : recentDocsPerSec;
  }

  /**
   * Count-aware ctor from task t-20261013-ef3c41; {@code vduAvailable} is not reported.
   */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs,
      long[] recentJobQueueDepth,
      double[] recentDocsPerSec,
      long pendingBytes,
      long pendingUnknownSizeJobs,
      long searchableDocuments,
      long indexMaxDoc,
      long indexNumDocs,
      boolean indexedDocumentsUnknown) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs, recentJobQueueDepth,
        recentDocsPerSec, pendingBytes, pendingUnknownSizeJobs, searchableDocuments, indexMaxDoc,
        indexNumDocs, indexedDocumentsUnknown, null);
  }

  /**
   * Backward-compatible 6-arg ctor; defaults the 406 runtime gauges + V1/V2 trends to empty.
   *
   * <p>Every short ctor defaults {@code searchableDocuments} to {@code indexedDocuments} — the
   * "nothing is excluded from the default scope" case. Zero would be the wrong default: it would
   * claim the caller's documents are unsearchable rather than that no exclusion was expressed.
   */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        0L, 0L, 0L, 0L, new long[0], new double[0], 0L, 0L, indexedDocuments, 0L, 0L, false, null);
  }

  /** Backward-compatible 10-arg ctor; defaults the 419 V1/V2 trends to empty. */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs,
        new long[0], new double[0], 0L, 0L, indexedDocuments, 0L, 0L, false, null);
  }

  /** Backward-compatible 11-arg ctor; defaults the 419 V2 P2 docs/sec trend to empty. */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs,
      long[] recentJobQueueDepth) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs,
        recentJobQueueDepth, new double[0], 0L, 0L, indexedDocuments, 0L, 0L, false, null);
  }

  /** Backward-compatible 12-arg ctor; defaults the 813 Slice B pending-byte weights to zero. */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs,
      long[] recentJobQueueDepth,
      double[] recentDocsPerSec) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs,
        recentJobQueueDepth, recentDocsPerSec, 0L, 0L, indexedDocuments, 0L, 0L, false, null);
  }

  public static CoreIndexView fallback(String indexState) {
    return new CoreIndexView(
        false, 0, 0, indexState, 0, 0, 0L, 0L, 0L, 0L, new long[0], new double[0], 0L, 0L, 0L, 0L,
        0L, false, null);
  }

  /**
   * Backward-compatible 17-arg ctor (before Spec E step E1); {@code vduAvailable} is not reported.
   */
  public CoreIndexView(
      boolean indexHealthy,
      long indexedDocuments,
      long pendingJobs,
      String indexState,
      long indexSizeBytes,
      int pendingVduCount,
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs,
      long[] recentJobQueueDepth,
      double[] recentDocsPerSec,
      long pendingBytes,
      long pendingUnknownSizeJobs,
      long searchableDocuments,
      long indexMaxDoc,
      long indexNumDocs) {
    this(indexHealthy, indexedDocuments, pendingJobs, indexState, indexSizeBytes, pendingVduCount,
        writerQueueDepth, writerPendingDocs, commitCount, refreshLagMs, recentJobQueueDepth,
        recentDocsPerSec, pendingBytes, pendingUnknownSizeJobs, searchableDocuments, indexMaxDoc,
        indexNumDocs, false, null);
  }

  /** The same view with {@code vduAvailable} set (the Head knows it; the Worker does not). */
  public CoreIndexView withVduAvailable(boolean available) {
    return new CoreIndexView(indexHealthy, indexedDocuments, pendingJobs, indexState,
        indexSizeBytes, pendingVduCount, writerQueueDepth, writerPendingDocs, commitCount,
        refreshLagMs, recentJobQueueDepth, recentDocsPerSec, pendingBytes, pendingUnknownSizeJobs,
        searchableDocuments, indexMaxDoc, indexNumDocs, indexedDocumentsUnknown, available);
  }
}
