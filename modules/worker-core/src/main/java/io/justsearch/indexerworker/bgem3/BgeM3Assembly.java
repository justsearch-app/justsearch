/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.bgem3;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link BgeM3Encoder}. Tempdoc 397 §14.24 FD-BgeM3 — produced
 * by {@code InferenceCompositionRoot.composeBgeM3Assembly(...)} or by
 * {@link BgeM3Encoder#buildAssembly}. Centralises every BGE-M3 metadata I/O (tokenizer,
 * vocabulary parse) at boot time.
 *
 */
public final class BgeM3Assembly implements AutoCloseable {
  private final SessionHandle sessions;
  private final BgeM3Shape shape;
  private final HuggingFaceTokenizer tokenizer;
  private boolean tokenizerClosed;

  /**
   * @param sessions ORT session handle
   * @param shape model-intrinsic facts (max sequence length + vocabulary)
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer
   */
  public BgeM3Assembly(SessionHandle sessions, BgeM3Shape shape, HuggingFaceTokenizer tokenizer) {
    this.sessions = sessions;
    this.shape = shape;
    this.tokenizer = tokenizer;
  }

  public SessionHandle sessions() { return sessions; }
  public BgeM3Shape shape() { return shape; }
  public HuggingFaceTokenizer tokenizer() { return tokenizer; }

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
