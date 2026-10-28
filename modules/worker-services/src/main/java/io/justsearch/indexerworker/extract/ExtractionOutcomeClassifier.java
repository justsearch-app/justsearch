/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/**
 * Pure, total mapping from an extraction attempt's content, truncation and decoding-loss facts to the
 * {@link ExtractionStatus} that describes it (tempdoc 671, Long-term design part 2). The
 * functional-core extraction mirrors {@code OcrOutcomeClassifier} (itself mirroring
 * {@code io.justsearch.indexerworker.loop.AdmissionPolicy}) for the same reason: this is a
 * second, more foundational instance of the same failure mode — a status field that only tracked
 * "did the pipeline run without erroring" silently claimed full success for empty output.
 *
 * <p>The LAW: blank/empty {@code content} maps to {@link ExtractionStatus#SUCCESS_EMPTY} — "the
 * pipeline completed cleanly but produced nothing usable" — regardless of truncation or loss.
 * Non-blank content maps to
 * {@link ExtractionStatus#SUCCESS_PARTIAL} when {@code truncated} or decoding was lossy, otherwise
 * {@link ExtractionStatus#SUCCESS_FULL}. This mapping is total over the (content, truncated, lossy)
 * input space and injective across its three outcomes. {@link ExtractionStatus#FAILED},
 * {@link ExtractionStatus#TIMED_OUT}, and {@link ExtractionStatus#BUDGET_EXCEEDED} are a
 * different layer (sandbox/exception-driven) and are out of this classifier's scope.
 */
final class ExtractionOutcomeClassifier {

  private ExtractionOutcomeClassifier() {}

  static ExtractionStatus classify(String content, boolean truncated) {
    return classify(content, truncated, false);
  }

  static ExtractionStatus classify(String content, boolean truncated, boolean lossy) {
    if (content == null || content.isBlank()) {
      return ExtractionStatus.SUCCESS_EMPTY;
    }
    return truncated || lossy ? ExtractionStatus.SUCCESS_PARTIAL : ExtractionStatus.SUCCESS_FULL;
  }
}
