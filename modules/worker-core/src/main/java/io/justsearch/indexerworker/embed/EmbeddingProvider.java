/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import java.util.List;

/**
 * Operational interface for embedding generation. Consumers hold this type instead of the concrete
 * {@link EmbeddingService}, enabling the Null Object pattern ({@link NoOpEmbeddingProvider}) for
 * graceful degradation when no embedding model is loaded.
 *
 * <p>Lifecycle methods (initialize, close, factory) remain on {@link EmbeddingService}. This
 * interface covers only the methods consumers call during normal operation.
 */
public interface EmbeddingProvider {

  /** Embeds a document text for indexing. */
  float[] embedDocument(String text);

  /** Embeds a candidate document within an admitted query's authority. */
  default float[] embedDocument(String text, io.justsearch.ort.SessionAcquisitionRequest acquisition) {
    acquisition.remainingNanos();
    return embedDocument(text);
  }

  /** Embeds a query text for search. */
  float[] embedQuery(String text);

  /** Embeds a query with admitted native acquisition authority. */
  default float[] embedQuery(String text, io.justsearch.ort.SessionAcquisitionRequest acquisition) {
    acquisition.remainingNanos();
    return embedQuery(text);
  }

  /** Batch-embeds multiple document texts. */
  List<float[]> embedDocumentBatch(List<String> texts);

  /**
   * Late chunking (tempdoc 691 Phase 1): embeds {@code content} once and derives the whole-document
   * vector plus one vector per character span from the same forward pass, instead of embedding the
   * parent and each chunk independently. Callers must gate on the {@code
   * justsearch.embed.late_chunking_enabled} flag themselves — this method is unconditional.
   *
   * @param content the full document text (unprefixed)
   * @param charSpans {@code [startCharInclusive, endCharExclusive)} ranges into {@code content}
   * @return the doc vector plus one chunk vector per span, or {@code null} if late chunking is
   *     unsupported by this provider/backend, or {@code content} exceeds the model's context window
   * @throws RuntimeException if the underlying inference call fails — treat like any other
   *     embedding failure (do not mark complete; let the doc retry/escalate)
   */
  EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans);

  /**
   * One resumable slice of a single document's encoder windows.
   *
   * @param vectors one vector per window actually embedded, in window order starting at {@code
   *     fromWindow}
   * @param fromWindow the window index this slice started at
   * @param totalWindows how many windows the whole document needs — the resumption bound
   */
  record WindowSlice(List<float[]> vectors, int fromWindow, int totalWindows) {}

  /**
   * How many encoder windows this document text needs (round-15 post-round finding; the
   * head-of-line-blocking half of the {@code 813 §18} starvation residual).
   *
   * <p>A document longer than the model's context window is embedded as several overlapping
   * windows that are mean-pooled into one vector. {@link #embedDocumentBatch} does all of a
   * document's windows inside one uninterruptible call and only materialises the pooled vector at
   * the end, so a document whose windowing cannot finish inside one scheduler cycle restarts from
   * window 0 forever. Callers that want to resume ask for the count here and then drive {@link
   * #embedDocumentWindows} a slice at a time.
   *
   * @return the window count, or {@code 1} when this provider does not expose window granularity —
   *     which keeps every such caller on the historical whole-document batch path unchanged
   */
  default int documentWindowCount(String text) {
    return 1;
  }

  /**
   * Embeds windows {@code [fromWindow, fromWindow + maxWindows)} of one document.
   *
   * <p>Deliberately returns the RAW per-window vectors rather than a pooled document vector: the
   * caller accumulates them across cycles and pools only once every window has been embedded, which
   * is what makes a long document's progress survive a cycle boundary.
   *
   * @return the slice, or {@code null} when this provider does not expose window granularity
   * @throws RuntimeException if the underlying inference call fails — treat like any other
   *     embedding failure (do not mark complete; let the doc retry/escalate)
   */
  default WindowSlice embedDocumentWindows(String text, int fromWindow, int maxWindows) {
    return null;
  }

  /** Returns the embedding dimension (e.g., 768 for nomic-embed-text). */
  int dimension();

  /** Returns true if the embedding model is loaded and ready for inference. */
  boolean isAvailable();

  /** Returns true if the embedding model is using GPU acceleration. */
  boolean isUsingGpu();
}
