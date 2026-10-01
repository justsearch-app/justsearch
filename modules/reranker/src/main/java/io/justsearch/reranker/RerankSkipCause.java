/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

/**
 * Why a {@link CrossEncoderReranker.RerankedResult} carries the original order instead of a
 * cross-encoder ranking (register F-054).
 *
 * <p>The reranker has two structurally different ways to not rerank, and before this enum both
 * collapsed into one {@code skipped} boolean that the Worker's rerank RPC then stamped
 * {@code DEADLINE_EXCEEDED} on. A measured campaign found 199/200 of those "deadline misses" were
 * ONNX Runtime BFCArena exhaustion — unfixable by any deadline value, fixed instantly by the arena
 * size. The guard failed closed correctly but named the wrong knob, so the cause is carried
 * structurally now:
 *
 * <ul>
 *   <li><b>Budget pre-checks</b> ({@link #TOKENIZE_BUDGET_EXCEEDED}, {@link
 *       #PREP_BUDGET_EXCEEDED}) — the reranker measured its own elapsed time against the caller's
 *       budget and declined to start inference. A larger deadline genuinely fixes these.
 *   <li><b>Inference failure</b> ({@link #INFERENCE_FAILED}) — inference was attempted and ORT
 *       threw. The deadline is irrelevant; the remedy is whatever the ORT error names (for arena
 *       exhaustion: the {@code JUSTSEARCH_RERANK_GPU_MEM_MB} arena size).
 * </ul>
 *
 * <p>This enum is deliberately reranker-local: the wire vocabulary the Head consumes
 * ({@code CrossEncoderSkipReason}) is owned head-side, and the Worker's rerank RPC maps this cause
 * onto it at the in-process port boundary, so the inference component carries no wire strings.
 */
public enum RerankSkipCause {
  /** Not a skip — the cross-encoder scored the documents (or there were none to score). */
  NONE,
  /** Pre-check: tokenization alone consumed over half the budget ({@code rerank_skipped_tokenize_budget}). */
  TOKENIZE_BUDGET_EXCEEDED,
  /** Pre-check: tokenization + tensor prep left too little budget for inference ({@code rerank_skipped_prep_budget}). */
  PREP_BUDGET_EXCEEDED,
  /** Inference was attempted and ONNX Runtime threw — an OOM, a bad output shape, a dead session. */
  INFERENCE_FAILED;

  /** True when the cross-encoder did not score the documents. */
  public boolean isSkip() {
    return this != NONE;
  }

  /**
   * True when the skip was a budget pre-check — the one class a larger {@code deadlineMs} can
   * actually fix. Inference failures are not, which is the whole point of register F-054.
   */
  public boolean isBudgetPrecheck() {
    return this == TOKENIZE_BUDGET_EXCEEDED || this == PREP_BUDGET_EXCEEDED;
  }
}
