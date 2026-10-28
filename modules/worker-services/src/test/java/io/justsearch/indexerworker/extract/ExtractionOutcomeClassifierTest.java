package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link ExtractionOutcomeClassifier} seam (governance/logic-seams.v1.json) — the
 * pure, total content/truncation-to-status mapping extracted from {@code ExtractionArtifact}
 * (tempdoc 671, Long-term design part 2). Asserts all three outcomes are reachable at their
 * boundaries and that the mapping is injective, so a swapped or reused arm (silently conflating
 * "produced nothing" with "produced everything") is caught. Plain JUnit; no jqwik (tempdoc 555
 * §10).
 */
class ExtractionOutcomeClassifierTest {

  @Test
  void intentionalReplacementAndControlsDoNotImplyLoss() {
    assertEquals(ExtractionStatus.SUCCESS_FULL, ExtractionOutcomeClassifier.classify("\uFFFD", false));
    assertEquals(ExtractionStatus.SUCCESS_FULL, ExtractionOutcomeClassifier.classify("valid words\u0000", false));
    assertEquals(ExtractionStatus.SUCCESS_EMPTY, ExtractionOutcomeClassifier.classify(" \n", true));
  }

  @Test
  @DisplayName("blank content maps to SUCCESS_EMPTY regardless of truncated")
  void blankContentMapsToSuccessEmpty() {
    assertEquals(ExtractionStatus.SUCCESS_EMPTY, ExtractionOutcomeClassifier.classify("", false));
    assertEquals(ExtractionStatus.SUCCESS_EMPTY, ExtractionOutcomeClassifier.classify("   \n\t", false));
    assertEquals(ExtractionStatus.SUCCESS_EMPTY, ExtractionOutcomeClassifier.classify(null, false));
    assertEquals(
        ExtractionStatus.SUCCESS_EMPTY,
        ExtractionOutcomeClassifier.classify("", true),
        "an empty result was never meaningfully truncated");
  }

  @Test
  @DisplayName("non-empty content maps to SUCCESS_PARTIAL when truncated")
  void nonEmptyTruncatedMapsToSuccessPartial() {
    assertEquals(
        ExtractionStatus.SUCCESS_PARTIAL, ExtractionOutcomeClassifier.classify("some text", true));
  }

  @Test
  @DisplayName("non-empty content maps to SUCCESS_FULL when not truncated")
  void nonEmptyNotTruncatedMapsToSuccessFull() {
    assertEquals(
        ExtractionStatus.SUCCESS_FULL, ExtractionOutcomeClassifier.classify("some text", false));
  }

  @Test
  @DisplayName("the three outcomes are pairwise distinct (mapping is injective across the boundaries)")
  void outcomesArePairwiseDistinct() {
    ExtractionStatus empty = ExtractionOutcomeClassifier.classify("", false);
    ExtractionStatus partial = ExtractionOutcomeClassifier.classify("x", true);
    ExtractionStatus full = ExtractionOutcomeClassifier.classify("x", false);
    assertNotEquals(empty, partial);
    assertNotEquals(partial, full);
    assertNotEquals(empty, full);
  }
}
