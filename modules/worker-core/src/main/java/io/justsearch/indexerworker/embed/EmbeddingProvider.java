/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.core.execution.InferenceRequest;
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
  default float[] embedDocument(String text, InferenceRequest acquisition) {
    acquisition.remainingNanos();
    return embedDocument(text);
  }

  /** Embeds a query text for search. */
  float[] embedQuery(String text);

  /** Embeds a query with admitted native acquisition authority. */
  default float[] embedQuery(String text, InferenceRequest acquisition) {
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
  default boolean isAvailable() {
    return availability().state() == Availability.State.READY;
  }

  /** A snapshot of model presence and readiness, independent of index compatibility. */
  Availability availability();

  /** FAILED retains the original load failure; other states never carry a failure. */
  record Availability(State state, Throwable failure) {
    public enum State {
      /** A published backend can accept inference. */
      READY,
      /** An installed model is being loaded or awaits provider publication. */
      STARTING,
      /** No model is owned by this provider; it will not load one. */
      ABSENT,
      /** Model loading failed; the snapshot carries the original cause. */
      FAILED
    }

    public static final Availability READY = new Availability(State.READY, null);
    public static final Availability STARTING = new Availability(State.STARTING, null);
    public static final Availability ABSENT = new Availability(State.ABSENT, null);

    public Availability {
      java.util.Objects.requireNonNull(state, "state");
      if ((state == State.FAILED) != (failure != null)) {
        throw new IllegalArgumentException("Only FAILED must carry a load failure");
      }
    }
  }

  /** Returns true if the embedding model is using GPU acceleration. */
  boolean isUsingGpu();

  /**
   * The device a query embed would select now (Spec D §1.1); chooses a search's semantic
   * sub-budget cap. Providers without a native session report {@code CPU}.
   */
  default io.justsearch.ort.QueryDevice queryDevice() {
    return io.justsearch.ort.QueryDevice.CPU;
  }

  /**
   * Whether a query embed would run now without creating a session (Spec D §4.2); readiness reads
   * this. Providers without a native session are always warm.
   */
  default boolean isQueryWarm() {
    return true;
  }

  /** Whether lifecycle prewarm should retry the GPU while CPU queries remain ready. */
  default boolean isGpuRetryDue() {
    return false;
  }

  /**
   * Warms the CPU session a query embed runs on when the GPU device turns it away (busy or stuck),
   * so the dense leg is still answered within its budget. Providers without a GPU session have
   * nothing to warm.
   *
   * @return whether that CPU session is warm afterwards
   */
  default boolean prewarmCpuForQueries(InferenceRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    return false;
  }
}
