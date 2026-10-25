/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link NerService}. Produced by
 * {@code InferenceCompositionRoot.composeNerAssembly(...)} (tempdoc 397 §14.24 FD-NER); replaces the
 * previous pattern where NerService loaded the tokenizer and label mapping lazily from disk
 * inside {@code ensureInitialized()}.
 *
 * <p>Centralising construction in the composition root means every consumer (production path,
 * dev-mode fallback, integration tests) produces the same shape — §7.5's "encoders as pure
 * inference transformers" landed for NER.
 *
 */
public final class NerAssembly implements AutoCloseable {
  private final SessionHandle sessions;
  private final NerShape shape;
  private final HuggingFaceTokenizer tokenizer;
  private final BioTagDecoder.LabelMapping labelMapping;
  private final ModelCapabilities capabilities;
  private boolean tokenizerClosed;

  /**
   * @param sessions ORT session handle (owns GPU/CPU session lifecycle)
   * @param shape model-intrinsic facts (input-name detection + max sequence length)
   * @param tokenizer loaded DJL HuggingFace tokenizer
   * @param labelMapping BIO→entity-type mapping loaded from the model's label-config JSON
   * @param capabilities the full resolved model-capability contract (tempdoc 710 Wave 2 Move 1)
   */
  public NerAssembly(SessionHandle sessions, NerShape shape, HuggingFaceTokenizer tokenizer, BioTagDecoder.LabelMapping labelMapping, ModelCapabilities capabilities) {
    this.sessions = sessions;
    this.shape = shape;
    this.tokenizer = tokenizer;
    this.labelMapping = labelMapping;
    this.capabilities = capabilities;
  }

  public SessionHandle sessions() { return sessions; }
  public NerShape shape() { return shape; }
  public HuggingFaceTokenizer tokenizer() { return tokenizer; }
  public BioTagDecoder.LabelMapping labelMapping() { return labelMapping; }
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
