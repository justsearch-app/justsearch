/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

/**
 * Closed reason vocabulary for the {@code cross-encoder} stage of the search trace (register
 * F-052, production side).
 *
 * <p>The Head owns this vocabulary: the cross-encoder is orchestrated in {@code
 * KnowledgeSearchEngine}, so unlike the Worker-owned {@code SearchReasonCode} (which populates the
 * three query-level degradation fields) these codes are emitted head-side. Three of them
 * ({@link #DEADLINE_EXCEEDED}, {@link #MODEL_NOT_LOADED}, {@link #INFERENCE_FAILED}) originate in
 * the Worker's {@code RerankResponse.skip_reason} and are normalised in through {@link
 * #fromWorkerSkipReason(String)} — an unrecognised Worker string resolves to {@link #UNKNOWN}
 * rather than passing through raw, so a code with no FE wording can never reach a user.
 *
 * <p>Members split into two classes, and {@link #isDrop()} is the split:
 *
 * <ul>
 *   <li><b>By-design skips</b> — the pipeline decided not to rerank (config, query type, corpus
 *       shape, the 643 fusion-confidence shortcut). Nothing degraded; these stay diagnostic-tier
 *       and are deliberately unworded on the FE.
 *   <li><b>Drops</b> — the relevance model was supposed to run and did not (budget pre-check miss,
 *       RPC failure, model absent at runtime, inference failure, unstated or unrecognised cause).
 *       Results are still returned, ranked
 *       by fusion/LambdaMART instead. This is a degradation and is worded at the user tier by
 *       {@code CROSS_ENCODER_SKIP_WORDING} in {@code searchTraceExplain.ts}; {@code
 *       searchTraceExplain.test.ts} pins that map against a mirror of this enum's drop members
 *       (the offline correspondence check was retired in tempdoc 930 — it ran in no workflow).
 * </ul>
 */
public enum CrossEncoderSkipReason {
  // === by-design skips (not a degradation) ===
  /** The query classified as navigational; reranking would not help. */
  NAVIGATIONAL_QUERY,
  /** Reranking is switched off in configuration. */
  DISABLED,
  /** Fewer candidates than the configured minimum for reranking to be worthwhile. */
  BELOW_MIN_THRESHOLD,
  /** Average document length exceeds the configured cross-encoder ceiling. */
  DOCS_TOO_LONG,
  /** The active pipeline preset does not include the cross-encoder stage. */
  PIPELINE_NOT_ELIGIBLE,
  /** No reranker model is configured on this host — a capability gap, surfaced by readiness. */
  MODEL_NOT_CONFIGURED,
  /** Tempdoc 643 perf-skip: leg agreement alone was decisive, so the RPC was not worth paying. */
  FUSION_CONFIDENT,

  // === drops (the relevance model should have run) ===
  /**
   * The reranker declined to start inference because a budget pre-check said the latency budget
   * was already spent. Register F-054: this now means ONLY that — an inference failure used to be
   * stamped with it too, naming a knob (the deadline) that cannot fix an OOM.
   */
  DEADLINE_EXCEEDED,
  /** The rerank RPC threw — transport, Worker error, or circuit breaker. */
  RPC_FAILED,
  /** The Worker is configured for reranking but the model was not loaded when the RPC arrived. */
  MODEL_NOT_LOADED,
  /**
   * Inference was attempted and the runtime threw — ONNX Runtime memory-arena exhaustion, a dead
   * session, a bad output shape. Register F-054: the deadline is irrelevant to this class, and the
   * Worker log at the failure site names the actual remedy.
   */
  INFERENCE_FAILED,
  /** Fall-through for an unrecognised or unstated Worker skip reason. */
  UNKNOWN;

  /** Wire string form — the value carried by the {@code cross-encoder} trace stage's reason. */
  public String wire() {
    return name();
  }

  /**
   * True when the cross-encoder was expected to run and did not, so the returned ranking is a
   * degradation rather than the pipeline's intended shape.
   */
  public boolean isDrop() {
    return switch (this) {
      case DEADLINE_EXCEEDED, RPC_FAILED, MODEL_NOT_LOADED, INFERENCE_FAILED, UNKNOWN -> true;
      case NAVIGATIONAL_QUERY,
          DISABLED,
          BELOW_MIN_THRESHOLD,
          DOCS_TOO_LONG,
          PIPELINE_NOT_ELIGIBLE,
          MODEL_NOT_CONFIGURED,
          FUSION_CONFIDENT -> false;
    };
  }

  /**
   * Normalise the Worker's {@code RerankResponse.skip_reason} into this vocabulary.
   *
   * <p>Register F-054: a blank reason is {@link #UNKNOWN}, not a deadline. The Worker now names
   * every skip it reports ({@code WorkerSearchService.wireSkipReason}), so an unstated cause means
   * the Worker could not say — guessing "deadline" is exactly the mislabel F-054 removes.
   * Anything unrecognised becomes {@link #UNKNOWN} too, so no unworded code can reach the FE.
   */
  public static CrossEncoderSkipReason fromWorkerSkipReason(String workerSkipReason) {
    if (workerSkipReason == null || workerSkipReason.isBlank()) {
      return UNKNOWN;
    }
    try {
      return valueOf(workerSkipReason);
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
  }
}
