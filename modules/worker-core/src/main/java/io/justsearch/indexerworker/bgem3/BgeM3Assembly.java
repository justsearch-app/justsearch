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
    if (tokenizerClosed) return;
    if (sessions != null && sessions.retirementStatus() != SessionHandle.RetirementStatus.RETIRED) {
      sessions.close();
    }
    if (sessions != null && sessions.retirementStatus() != SessionHandle.RetirementStatus.RETIRED) {
      throw new IllegalStateException("Assembly sessions have not retired: " + sessions.retirementStatus());
    }
    if (tokenizer != null) tokenizer.close();
    tokenizerClosed = true;
  }
}
