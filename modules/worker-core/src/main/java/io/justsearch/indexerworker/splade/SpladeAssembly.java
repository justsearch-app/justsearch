/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import io.justsearch.ort.SessionHandle;
import java.nio.file.Path;

/**
 * Complete pre-built inputs for {@link SpladeEncoder}. Produced by
 * {@code InferenceCompositionRoot.composeSpladeAssembly(...)} (tempdoc 397 §14.24 FD-SPLADE) or
 * by {@link SpladeEncoder#buildAssembly} (dev-mode fallback). Centralises every SPLADE
 * metadata I/O (tokenizer, vocabulary, output-format detection, evidence-path resolution) at
 * boot time.
 *
 */
public final class SpladeAssembly implements AutoCloseable {
  private final SessionHandle sessions;
  private final SpladeShape shape;
  private final HuggingFaceTokenizer tokenizer;
  private final Vocabulary vocabulary;
  private final Path truncationEvidencePath;
  private boolean tokenizerClosed;

  /**
   * @param sessions ORT session handle
   * @param shape model-intrinsic facts (input + output names + max sequence length)
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer
   * @param vocabulary pre-loaded WordPiece vocabulary (from {@code vocab.txt})
   * @param truncationEvidencePath path for truncation-evidence sidecar writes; null = disabled
   */
  public SpladeAssembly(SessionHandle sessions, SpladeShape shape, HuggingFaceTokenizer tokenizer, Vocabulary vocabulary, Path truncationEvidencePath) {
    this.sessions = sessions;
    this.shape = shape;
    this.tokenizer = tokenizer;
    this.vocabulary = vocabulary;
    this.truncationEvidencePath = truncationEvidencePath;
  }

  public SessionHandle sessions() { return sessions; }
  public SpladeShape shape() { return shape; }
  public HuggingFaceTokenizer tokenizer() { return tokenizer; }
  public Vocabulary vocabulary() { return vocabulary; }
  public Path truncationEvidencePath() { return truncationEvidencePath; }

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
