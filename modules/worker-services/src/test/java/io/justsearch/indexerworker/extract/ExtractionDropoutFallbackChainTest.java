/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor.OcrAttemptDecision;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ordering and budget law of the dropout fallback chain (tempdoc 790 item 2) at the tier-1 (OCR)
 * gate. The chain's tier-2 (VDU/VLM) gate is covered by {@code VisualRoutingDecisionTest}; its
 * end-to-end shape on real PDFs by {@code ExtractionDropoutPdfFixturesTest}.
 */
@DisplayName("Extraction dropout fallback chain (tier-1 gate)")
final class ExtractionDropoutFallbackChainTest {

  private static final StructuredDocumentSummary ONE_PAGE =
      new StructuredDocumentSummary(1, 0, 0, 0, 0, 0, 0, 0, 1);

  @Test
  @DisplayName("a dropout escalates to the OCR tier")
  void dropoutEscalatesToOcr() {
    PolicyDrivenTikaExtractor extractor = extractorWithBudget(ExtractionFallbackBudget.defaults());

    OcrAttemptDecision decision =
        extractor.evaluateOcrAttemptForTesting(
            Path.of("scan.pdf"), "application/pdf", "", ONE_PAGE, 0L);

    // OCR is disabled by default in this construction, so the decision is a skip — but the skip
    // reason proves the routing reached the OCR tier's own gate rather than being short-circuited
    // as "text is already good enough".
    assertEquals(OcrSkipReason.DISABLED, decision.skipReason());
    assertFalse(decision.shouldAttempt());
  }

  @Test
  @DisplayName("healthy text never reaches the OCR tier")
  void healthyTextSkipsAsTextual() {
    PolicyDrivenTikaExtractor extractor = extractorWithBudget(ExtractionFallbackBudget.defaults());

    OcrAttemptDecision decision =
        extractor.evaluateOcrAttemptForTesting(
            Path.of("scan.pdf"),
            "application/pdf",
            "Readable extracted text that is long enough to be judged good. ".repeat(20),
            new StructuredDocumentSummary(1, 1200, 1, 0, 0, 12, 0, 0, 0),
            0L);

    assertEquals(OcrSkipReason.TEXTUAL, decision.skipReason());
    assertFalse(decision.shouldAttempt());
  }

  @Test
  @DisplayName("a spent wall-clock budget stops the OCR tier before it starts")
  void spentWallClockBudgetStopsTheChain() {
    PolicyDrivenTikaExtractor extractor =
        extractorWithBudget(new ExtractionFallbackBudget(2, 1_000L));

    OcrAttemptDecision decision =
        extractor.evaluateOcrAttemptForTesting(
            Path.of("scan.pdf"), "application/pdf", "", ONE_PAGE, 1_500L);

    assertEquals(OcrSkipReason.BUDGET, decision.skipReason());
    assertFalse(decision.shouldAttempt());
  }

  @Test
  @DisplayName("a zero-tier budget stops the chain at tier 0")
  void zeroTierBudgetStopsTheChain() {
    PolicyDrivenTikaExtractor extractor =
        extractorWithBudget(new ExtractionFallbackBudget(0, 30_000L));

    OcrAttemptDecision decision =
        extractor.evaluateOcrAttemptForTesting(
            Path.of("scan.pdf"), "application/pdf", "", ONE_PAGE, 0L);

    assertEquals(OcrSkipReason.BUDGET, decision.skipReason());
  }

  @Test
  @DisplayName("the budget gate precedes the eligibility gates it could otherwise mask")
  void budgetIsCheckedBeforeQualityGates() {
    // Precision guard: with a spent budget AND healthy text, the reason must still be BUDGET —
    // otherwise a later refactor could reorder the gates and silently change which one reports.
    PolicyDrivenTikaExtractor extractor =
        extractorWithBudget(new ExtractionFallbackBudget(2, 1_000L));

    OcrAttemptDecision decision =
        extractor.evaluateOcrAttemptForTesting(
            Path.of("scan.pdf"),
            "application/pdf",
            "Readable extracted text that is long enough to be judged good. ".repeat(20),
            new StructuredDocumentSummary(1, 1200, 1, 0, 0, 12, 0, 0, 0),
            5_000L);

    assertEquals(OcrSkipReason.BUDGET, decision.skipReason());
  }

  @Test
  @DisplayName("non-eligible files never enter the chain at all")
  void ineligibleFileIsNotACandidate() {
    PolicyDrivenTikaExtractor extractor = extractorWithBudget(ExtractionFallbackBudget.defaults());

    OcrAttemptDecision decision =
        extractor.evaluateOcrAttemptForTesting(Path.of("notes.txt"), "text/plain", "", null, 0L);

    assertFalse(decision.shouldAttempt());
    assertTrue(decision.skipReason() == null, "ineligibility is not an OCR skip statistic");
  }

  private static PolicyDrivenTikaExtractor extractorWithBudget(ExtractionFallbackBudget budget) {
    return new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(),
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), OcrMetricCatalog.noop(), budget);
  }
}
