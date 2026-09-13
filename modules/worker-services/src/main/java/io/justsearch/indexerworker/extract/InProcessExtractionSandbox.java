/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Compatibility sandbox adapter that preserves current in-process extraction behavior. */
public final class InProcessExtractionSandbox implements ExtractionSandbox {
  private final ContentExtractorProvider delegate;

  public InProcessExtractionSandbox(ContentExtractorProvider delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public ExtractionArtifact extract(Path file) throws IOException, ContentExtractor.ExtractionException {
    if (delegate instanceof PolicyDrivenTikaExtractor policyDriven) {
      return policyDriven.extractArtifact(file);
    }
    if (delegate instanceof ExtractorContributionRegistry registry) {
      return registry.extractArtifact(file);
    }
    String parserId = delegate.getClass().getSimpleName();
    if (parserId == null || parserId.isBlank()) {
      parserId = delegate.getClass().getName();
    }
    return ExtractionArtifact.full(delegate.extract(file), parserId);
  }

  @Override
  public TikaExtractionPolicy policy() {
    return delegate instanceof PolicyDrivenTikaExtractor policyDriven
        ? policyDriven.policy()
        : TikaExtractionPolicy.defaults();
  }

  @Override
  public void close() {
    if (delegate instanceof AutoCloseable owner) {
      try { owner.close(); }
      catch (RuntimeException failure) { throw failure; }
      catch (Exception failure) { throw new IllegalStateException("Extractor close failed", failure); }
    }
  }
}
