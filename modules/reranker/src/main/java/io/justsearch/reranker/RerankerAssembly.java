/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link CrossEncoderReranker} and {@link CitationScorer}.
 * Tempdoc 397 §14.24 FD — produced by {@code InferenceCompositionRoot.composeRerankAssembly}
 * / {@code composeCitationAssembly} or by the static {@code buildAssembly} helpers on each
 * encoder (dev-mode fallback).
 *
 * <p>Citation and reranker share the same assembly shape because they have identical
 * construction inputs (cross-encoder model, tokenizer, input-schema facts). The caller
 * determines which concrete encoder to build.
 *
 */
public final class RerankerAssembly implements AutoCloseable {
  private final SessionHandle sessions;
  private final RerankerShape shape;
  private final RerankerTokenizer tokenizer;
  private boolean tokenizerClosed;

  /**
   * @param sessions ORT session handle
   * @param shape model-intrinsic facts (input-name detection + max sequence length)
   * @param tokenizer pre-loaded reranker tokenizer
   */
  public RerankerAssembly(SessionHandle sessions, RerankerShape shape, RerankerTokenizer tokenizer) {
    this.sessions = sessions;
    this.shape = shape;
    this.tokenizer = tokenizer;
  }

  public SessionHandle sessions() { return sessions; }
  public RerankerShape shape() { return shape; }
  public RerankerTokenizer tokenizer() { return tokenizer; }

  public synchronized boolean tokenizerClosed() { return tokenizerClosed; }

  /** Retires sessions before the tokenizer; a refused or failed close retains retry ownership. */
  @Override
  public synchronized void close() {
    if (!tryClose()) {
      throw new IllegalStateException("Assembly sessions have not retired: " + sessions.retirementStatus());
    }
  }

  /**
   * Attempts retirement without adding an exception when the handle returns normally but has
   * not confirmed retirement. Standalone wrappers preserve that close behaviour and retain this
   * owner for a retry. Exceptions from session or tokenizer cleanup still propagate.
   *
   * @return true only after the tokenizer has been released
   */
  public synchronized boolean tryClose() {
    if (tokenizerClosed) return true;
    if (sessions != null && sessions.retirementStatus() != SessionHandle.RetirementStatus.RETIRED) {
      sessions.close();
    }
    if (sessions != null && sessions.retirementStatus() != SessionHandle.RetirementStatus.RETIRED) {
      return false;
    }
    if (tokenizer != null) tokenizer.close();
    tokenizerClosed = true;
    return true;
  }
}
