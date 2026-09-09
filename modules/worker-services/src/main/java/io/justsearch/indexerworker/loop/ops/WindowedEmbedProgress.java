/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-cycle per-window progress for long documents (round-15 post-round finding).
 *
 * <p><b>The defect this closes.</b> A document longer than the encoder's context window is embedded
 * as N overlapping windows that are mean-pooled into one vector. Every path that produced that
 * vector did all N windows inside ONE call and materialised the pooled result only at the end
 * ({@code OnnxEmbeddingEncoder.embedBatchWithChunking} Phase 2/3) — so the per-window work existed
 * only as call-local state. When the scheduler's cycle budget ended the batch before a document's
 * windows were finished, every window already computed was discarded and the next cycle re-selected
 * the same head of the queue and started that document again at window 0. Live evidence: 54
 * consecutive cycles with a byte-identical work-set line, ~56-80 successful window embeds per cycle,
 * and the document-level pending count never moving.
 *
 * <p><b>What is persisted.</b> Mean-pooling is a running sum over an unordered set, so the only
 * state a resume needs is {@code sum} + {@code nextWindow} + {@code totalWindows}. The accumulator
 * mirrors {@code OnnxEmbeddingEncoder.meanPoolChunks} exactly — {@code double} sum, divide by the
 * window count, then L2-normalize — so a document assembled across five cycles gets the vector it
 * would have got from one uninterrupted call.
 *
 * <p><b>Deliberately in-memory, not in the index.</b> A partial vector is not a searchable artifact
 * and must never reach a document: writing one would be the "status lies" class (a {@code VECTOR}
 * field that is not the document's embedding). The document stays {@code PENDING} until every
 * window is in, and a worker restart simply re-does the windows — a bounded, correct loss, unlike
 * the unbounded restart-every-cycle loss this class removes.
 *
 * <p>Not thread-safe: it is owned by {@code BackfillScheduler} and only ever touched from the single
 * indexing-loop thread.
 */
public final class WindowedEmbedProgress {

  /**
   * How many partially-embedded documents may be tracked at once. One entry is {@code
   * dimension} doubles (~6 KB at 768), so this caps the accumulator at a few MB. Eviction is LRU:
   * the evicted document simply restarts its windowing later, which is the pre-fix behaviour for
   * that one document rather than a new failure mode.
   */
  private static final int MAX_TRACKED_DOCUMENTS = 256;

  private final Map<String, Partial> byDocId =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Partial> eldest) {
          return size() > MAX_TRACKED_DOCUMENTS;
        }
      };

  /**
   * The fingerprint identifies the content the partial sum was computed from — a document whose
   *     content changed underneath a partial must restart, never blend windows from two revisions
   */
  private static final class Partial {
    private final long fingerprint;
    private int totalWindows;
    private int nextWindow;
    private double[] sum;

    private Partial(long fingerprint) {
      this.fingerprint = fingerprint;
    }
  }

  /** Content identity for a partial: length plus hash, so a same-length edit still invalidates. */
  private static long fingerprintOf(String content) {
    return ((long) content.length() << 32) ^ (content.hashCode() & 0xFFFFFFFFL);
  }

  /**
   * The window index this document should resume from — {@code 0} when nothing is tracked, or when
   * the tracked partial belongs to different content.
   */
  public int nextWindow(String docId, String content) {
    Partial partial = byDocId.get(docId);
    if (partial == null) {
      return 0;
    }
    if (partial.fingerprint != fingerprintOf(content)) {
      byDocId.remove(docId);
      return 0;
    }
    return partial.nextWindow;
  }

  /**
   * Folds one embedded window slice into this document's running sum.
   *
   * @param fromWindow the window index the slice started at; a slice that does not start exactly at
   *     the tracked resume point is ignored, so a caller bug cannot double-count windows into a
   *     vector that would then be silently wrong
   * @return the resume point after this slice
   */
  public int record(
      String docId, String content, int totalWindows, int fromWindow, List<float[]> windowVectors) {
    long fingerprint = fingerprintOf(content);
    Partial partial = byDocId.get(docId);
    if (partial == null || partial.fingerprint != fingerprint) {
      partial = new Partial(fingerprint);
      byDocId.put(docId, partial);
    }
    if (fromWindow != partial.nextWindow) {
      return partial.nextWindow;
    }
    partial.totalWindows = Math.max(totalWindows, partial.totalWindows);
    for (float[] vector : windowVectors) {
      if (vector == null || vector.length == 0) {
        continue;
      }
      if (partial.sum == null) {
        partial.sum = new double[vector.length];
      }
      int dim = Math.min(vector.length, partial.sum.length);
      for (int i = 0; i < dim; i++) {
        partial.sum[i] += vector[i];
      }
      partial.nextWindow++;
    }
    return partial.nextWindow;
  }

  /** Whether every window of this document has been embedded and the vector can be pooled. */
  public boolean isComplete(String docId) {
    Partial partial = byDocId.get(docId);
    return partial != null
        && partial.sum != null
        && partial.totalWindows > 0
        && partial.nextWindow >= partial.totalWindows;
  }

  /**
   * Pools the accumulated windows into the document vector without dropping the entry. Mirrors {@code
   * OnnxEmbeddingEncoder.meanPoolChunks}: mean over windows, then L2-normalize.
   * The owning RMW path calls {@link #forget(String)} only after a successful write.
   *
   * @return the document vector, or {@code null} if nothing usable was accumulated
   */
  public float[] complete(String docId) {
    Partial partial = byDocId.get(docId);
    if (partial == null || partial.sum == null || partial.nextWindow <= 0) {
      return null;
    }
    float[] pooled = new float[partial.sum.length];
    for (int i = 0; i < pooled.length; i++) {
      pooled[i] = (float) (partial.sum[i] / partial.nextWindow);
    }
    double norm = 0;
    for (float v : pooled) {
      norm += (double) v * v;
    }
    if (norm > 0) {
      float inv = (float) (1.0 / Math.sqrt(norm));
      for (int i = 0; i < pooled.length; i++) {
        pooled[i] *= inv;
      }
    }
    return pooled;
  }

  /** Drops any partial for this document (failure escalation, or content no longer eligible). */
  public void forget(String docId) {
    byDocId.remove(docId);
  }

  /** How many documents currently hold partial window progress — diagnostics and tests. */
  public int trackedDocuments() {
    return byDocId.size();
  }

  /** Drops every partial. Called from {@code resetForProfiling}. */
  public void clear() {
    byDocId.clear();
  }
}
