/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
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
  public enum Route { WINDOW, ORDINARY }

  /** Runtime authority also distinguishes index generations and every provider publication. */
  public record Identity(Object authority, long sourceEpoch, Object provider, int dimension, String uid,
      String contentRevision, String sourceRevision) {}

  /** Bind before reading routing or sums; identical text is insufficient proof of ownership. */
  public void identify(String docId, String content, Identity identity) {
    nextWindow(docId, content);
    Partial partial = byDocId.get(docId);
    if (partial == null || (partial.identity != null && !partial.identity.equals(identity))) {
      if (!canAdmit(docId)) return;
      partial = new Partial(fingerprintOf(content));
      byDocId.put(docId, partial);
    }
    partial.identity = identity;
  }

  public Route route(String docId, String content) {
    nextWindow(docId, content);
    Partial partial = byDocId.get(docId);
    return partial == null ? null : partial.route;
  }

  public void route(String docId, String content, Route route) {
    nextWindow(docId, content);
    if (!canAdmit(docId)) return;
    byDocId.computeIfAbsent(docId, ignored -> new Partial(fingerprintOf(content))).route = route;
  }

  public void restartWindows(String docId, String content) {
    if (!canAdmit(docId)) return;
    Partial previous = byDocId.get(docId);
    Partial restart = new Partial(fingerprintOf(content));
    restart.identity = previous == null ? null : previous.identity;
    restart.route = Route.WINDOW;
    byDocId.put(docId, restart);
  }

  /**
   * How many partially-embedded documents may be tracked at once. One entry is {@code
   * dimension} doubles (~6 KB at 768), so this caps the accumulator at a few MB. New owners
   * wait for a slot; admitted progress and route-only decisions are never evicted.
   */
  private static final int MAX_TRACKED_DOCUMENTS = 256;

  private final Map<String, Partial> byDocId = new LinkedHashMap<>();

  public boolean canAdmit(String docId) {
    return byDocId.containsKey(docId) || byDocId.size() < MAX_TRACKED_DOCUMENTS;
  }

  public List<String> owners() { return List.copyOf(byDocId.keySet()); }

  /** Binding identity alone is not resumable work and must not reserve a slot next turn. */
  void releaseUnused() {
    byDocId.values().removeIf(partial -> partial.sum == null && partial.route == null);
  }

  /** Retained owners are bounded; reap through metadata without retaining their text. */
  void reconcile(DocumentFieldOps fields,
      Object authority, long sourceEpoch, EmbeddingProvider provider) throws IOException {
    if (byDocId.isEmpty()) return;
    var current = fields.getDocumentFieldsBatchOrThrow(owners(), Set.of(
        SchemaFields.DOC_UID,
        SchemaFields.CONTENT_SHA256,
        SchemaFields.SOURCE_SHA256,
        SchemaFields.CHUNK_PARENT_CONTENT_SHA256,
        SchemaFields.IS_CHUNK,
        SchemaFields.EMBEDDING_STATUS,
        SchemaFields.CHUNK_EMBEDDING_STATUS));
    byDocId.entrySet().removeIf(entry -> {
      Identity identity = entry.getValue().identity;
      var row = current.get(entry.getKey());
      if (row == null) return true;
      boolean chunk = "true".equals(row.get(SchemaFields.IS_CHUNK));
      String status = row.get(chunk ? SchemaFields.CHUNK_EMBEDDING_STATUS
          : SchemaFields.EMBEDDING_STATUS);
      return !"PENDING".equals(status) || (identity != null && (
          identity.authority() != authority || identity.sourceEpoch() != sourceEpoch
          || identity.provider() != provider || (provider != null && identity.dimension() != provider.dimension())
          || !Objects.equals(identity.uid(), row.get(SchemaFields.DOC_UID))
          || !Objects.equals(identity.sourceRevision(), row.get(SchemaFields.SOURCE_SHA256))
          || !Objects.equals(identity.contentRevision(), row.get(chunk
              ? SchemaFields.CHUNK_PARENT_CONTENT_SHA256
              : SchemaFields.CONTENT_SHA256))));
    });
  }

  /**
   * The fingerprint identifies the content the partial sum was computed from — a document whose
   *     content changed underneath a partial must restart, never blend windows from two revisions
   */
  private static final class Partial {
    private final String fingerprint;
    private int totalWindows;
    private int nextWindow;
    private double[] sum;
    private Route route;
    private Identity identity;

    private Partial(String fingerprint) {
      this.fingerprint = fingerprint;
    }
  }

  /** Content identity for a partial, including equal-length Java hash collisions. */
  private static String fingerprintOf(String content) {
    return io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(content);
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
    if (!partial.fingerprint.equals(fingerprintOf(content))) {
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
    String fingerprint = fingerprintOf(content);
    Partial partial = byDocId.get(docId);
    if (partial == null || !partial.fingerprint.equals(fingerprint)) {
      if (!canAdmit(docId)) return 0;
      partial = new Partial(fingerprint);
      byDocId.put(docId, partial);
    }
    int dimension = partial.sum != null ? partial.sum.length
        : partial.identity == null ? 0 : partial.identity.dimension();
    boolean valid = totalWindows > 0
        && (partial.totalWindows == 0 || totalWindows == partial.totalWindows)
        && windowVectors != null && !windowVectors.isEmpty()
        && fromWindow >= 0 && fromWindow <= totalWindows
        && windowVectors.size() <= totalWindows - fromWindow;
    if (valid) {
      for (float[] vector : windowVectors) {
        if (vector == null || vector.length == 0) { valid = false; break; }
        if (dimension == 0) dimension = vector.length;
        if (dimension != vector.length) { valid = false; break; }
        for (float value : vector) {
          if (!Float.isFinite(value)) { valid = false; break; }
        }
        if (!valid) break;
      }
    }
    if (!valid) {
      restartWindows(docId, content);
      return 0;
    }
    // A replay or skip says nothing about the valid sum already retained.
    if (fromWindow != partial.nextWindow) return partial.nextWindow;
    partial.totalWindows = totalWindows;
    partial.route = Route.WINDOW;
    for (float[] vector : windowVectors) {
      if (partial.sum == null) {
        partial.sum = new double[vector.length];
      }
      for (int i = 0; i < vector.length; i++) {
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

  /** Computed window progress only; route-only entries share the cap but are not window work. */
  public int trackedDocuments() {
    return (int) byDocId.values().stream().filter(partial -> partial.sum != null).count();
  }

  /** Drops every partial. Called from {@code resetForProfiling}. */
  public void clear() {
    byDocId.clear();
  }
}
