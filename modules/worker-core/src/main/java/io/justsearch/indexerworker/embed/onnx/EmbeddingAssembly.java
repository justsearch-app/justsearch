/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link OnnxEmbeddingEncoder}. Produced by
 * {@code InferenceCompositionRoot.composeEmbedAssembly(...)} (tempdoc 397 §14.24 FD-Embedding).
 * Centralises all embedding metadata I/O (tokenizer, pooling config, input-name detection) at
 * boot time; encoder constructor does no filesystem I/O.
 *
 */
public final class EmbeddingAssembly implements AutoCloseable {
  private final SessionHandle sessions;
  private final EmbeddingShape shape;
  private final HuggingFaceTokenizer tokenizer;
  private final ModelCapabilities capabilities;
  private boolean tokenizerClosed;

  /**
   * @param sessions ORT session handle
   * @param shape model-intrinsic facts (input-name detection + max sequence length + pooling)
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer (owned by this assembly)
   * @param capabilities the full resolved model-capability contract (tempdoc 710 Wave 2 Move 1) —
   *     {@code shape.poolingStrategy()} is already projected from {@code
   *     capabilities.poolingMode()}; this field carries the rest (prefixes, dimension, precision)
   *     for consumers downstream of the composition root (e.g. {@code EmbeddingService}'s prefixes)
   */
  public EmbeddingAssembly(SessionHandle sessions, EmbeddingShape shape, HuggingFaceTokenizer tokenizer, ModelCapabilities capabilities) {
    this.sessions = sessions;
    this.shape = shape;
    this.tokenizer = tokenizer;
    this.capabilities = capabilities;
  }

  public SessionHandle sessions() { return sessions; }
  public EmbeddingShape shape() { return shape; }
  public HuggingFaceTokenizer tokenizer() { return tokenizer; }
  public ModelCapabilities capabilities() { return capabilities; }

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
