---
schema: test-intent.v1
task: extraction-unicode
author-role: builder
author-session: 01a17892-14c6-7dd1-b9cc-6b69726362d0
---

# Unicode extraction test intent

All listed additions are durable component/integration tests; existing checks remain in place.
Repair checks and pass-before preservation controls are listed separately, including within the same file.
The sources below are delegated agent choices; the settled scenario wording is not represented as owner-authored words.

The independent verifier ran the unchanged canonical producer on committed candidate a588476ad3:
15 repaired methods (24 cases) fail the intended assertions before the change and pass after;
seven preservation methods (seven cases) pass on both revisions. The referenced JSON files are
unedited stock producer outputs. Independent judgments are recorded in the acceptance section below.

The component checks inspect the constructed IndexDocument score; they do not prove that scalar
is persisted. Real Engine verification found the unchanged Lucene mapper ignores catalog type
double, so extraction_quality_score is absent even for a healthy positive baseline control.
The saved-scalar portion of UQ6 remains failed and is a separate repair. Numeric extraction evidence
is distinct from the absent scalar. Existing PDF phantom-page behavior also remains a separate limit.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java"
      ],
      "class": "New",
      "behaviour": "UQ1/UQ3/UQ4/UQ8/UQC1: whole supplementary Han/Deseret/decimal-digit values, independent 29/30/31/49/50/51 ratios, valid pairs beside lone units, finite long inputs, corrected quality retaining UTF-16 floors and density. An explicit reversed-unit repetition counterexample recognizes a valid pair created at concatenation, rather than classifying all reversed-input repetitions as isolated units.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQ1/UQ3/UQ4/UQ8/UQC1: whole supplementary Han/Deseret/decimal-digit values, independent 29/30/31/49/50/51 ratios, valid pairs beside lone units, finite long inputs, corrected quality retaining UTF-16 floors and density. An explicit reversed-unit repetition counterexample recognizes a valid pair created at concatenation, rather than classifying all reversed-input repetitions as isolated units.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#supplementaryLettersAndDecimalDigitsUseWholeCodePoints",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#mixedCodePointRatiosStraddleQualityBoundaries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#validPairsBesideLoneSurrogatesRemainTotal",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#supplementaryQualityRetainsUtf16LengthAndDensityFloors",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#longSupplementaryAndInvalidInputsStayFiniteAndBounded",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#reversedSurrogateRepetitionCanCreateAValidPairAtTheBoundary"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java"
      ],
      "class": "New",
      "behaviour": "UQP1/UQP5/UQC1/UQC2: independently observable preservation of BMP 99/100 and density 49/50/199/200, isolated/reversed/separated surrogates with punctuation guarding repetition boundaries, Java categories/blankness/markers and existing normalization differences; symbol emoji remain zero and digit-bearing keycaps retain decimal digit credit.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQP1/UQP5/UQC1/UQC2: independently observable preservation of BMP 99/100 and density 49/50/199/200, isolated/reversed/separated surrogates with punctuation guarding repetition boundaries, Java categories/blankness/markers and existing normalization differences; symbol emoji remain zero and digit-bearing keycaps retain decimal digit credit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#symbolEmojiAndDigitKeycapCategoryRulesRemainStable",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#isolatedReversedAndSeparatedSurrogatesRemainTotal",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#bmpLengthAndDensityNeighborsRemainUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/text/TextQualityAnalyzerTest.java#existingCategoriesMarkersAndDecompositionRulesRemainStable"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "UQ2/UQ4/UQ8: supplementary threshold counts zero/one/two occurrences rather than UTF-16 units or distinct values, including BMP mixes and valid pairs beside lone units; long suffix and malformed inputs terminate under the existing default test budget.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQ2/UQ4/UQ8: supplementary threshold counts zero/one/two occurrences rather than UTF-16 units or distinct values, including BMP mixes and valid pairs beside lone units; long suffix and malformed inputs terminate under the existing default test budget.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutPolicyTest.java#supplementaryThresholdCountsOccurrencesNotUtf16UnitsOrDistinctValues",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutPolicyTest.java#validPairsBesideLoneUnitsStillReachTheOccurrenceThreshold",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutPolicyTest.java#longSuffixAndMalformedInputsTerminate"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "UQP1/UQP5/UQC2: punctuation, symbol emoji, isolated/reversed/separated surrogates, NBSP versus Java blankness, nondecimal numbers, combining marks/ZWJ/selectors, keycaps and BMP multilingual content retain their existing dropout category rules.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQP1/UQP5/UQC2: punctuation, symbol emoji, isolated/reversed/separated surrogates, NBSP versus Java blankness, nondecimal numbers, combining marks/ZWJ/selectors, keycaps and BMP multilingual content retain their existing dropout category rules.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutPolicyTest.java#surrogateAndCategoryBoundariesRetainTheExistingRules"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java"
      ],
      "class": "New",
      "behaviour": "UQ9/UQ6/UQC3: actual rounded v1 evidence producer plus routing and document-marker consumers prove all three intended OCR transitions: 2 ASCII+33 Han+65 punctuation becomes ratio .35/score .56/PENDING visual_enrichment; 100 Han becomes ratio 1/score 1/NOT_NEEDED; 40 ASCII+20 symbol emoji+20 punctuation becomes ratio .5/score .8/NOT_NEEDED. No independent confidence/layout/table/mixed-PDF signals in these three cases; content and UTF-16 evidence units remain intact.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQ9/UQ6/UQC3: actual rounded v1 evidence producer plus routing and document-marker consumers prove all three intended OCR transitions: 2 ASCII+33 Han+65 punctuation becomes ratio .35/score .56/PENDING visual_enrichment; 100 Han becomes ratio 1/score 1/NOT_NEEDED; 40 ASCII+20 symbol emoji+20 punctuation becomes ratio .5/score .8/NOT_NEEDED. No independent confidence/layout/table/mixed-PDF signals in these three cases; content and UTF-16 evidence units remain intact.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java#correctedOcrEvidenceCreatesVisualEnrichmentDemand",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java#correctedSupplementaryOcrRemovesFalseBaselineDemand",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java#correctedEmojiDenominatorRemovesOldEnrichmentDemand"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java"
      ],
      "class": "New",
      "behaviour": "UQP3/UQC3: a sufficient BMP OCR document with independent mixed-PDF evidence still queues visual_enrichment under the existing policy, using the actual evidence producer and routing/marker consumers.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQP3/UQC3: a sufficient BMP OCR document with independent mixed-PDF evidence still queues visual_enrichment under the existing policy, using the actual evidence producer and routing/marker consumers.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java#independentMixedPdfSignalStillQueuesEnrichment"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutFallbackChainTest.java"
      ],
      "class": "New",
      "behaviour": "UQ5: sufficient supplementary structured text with no missing-page evidence skips OCR as TEXTUAL instead of false quality-driven recovery.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQ5: sufficient supplementary structured text with no missing-page evidence skips OCR as TEXTUAL instead of false quality-driven recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutFallbackChainTest.java#sufficientSupplementaryStructuredTextSkipsOcrAsTextual"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutFallbackChainTest.java"
      ],
      "class": "New",
      "behaviour": "UQP3: independent missing-page evidence and spent tier budget remain enforced even alongside sufficient supplementary text; disabled OCR still reports DISABLED and spent budget reports BUDGET.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQP3: independent missing-page evidence and spent tier budget remain enforced even alongside sufficient supplementary text; disabled OCR still reports DISABLED and spent budget reports BUDGET.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionDropoutFallbackChainTest.java#supplementaryQualityDoesNotEraseMissingPagesOrBudgetGates"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/ExtractionDropoutMarkerTest.java"
      ],
      "class": "New",
      "behaviour": "UQ5/UQ6/UQP4: actual UTF-8 text bytes independently decode the complete supplementary sequence before quality/routing assertions; nonvisual text retains structured method and no unrecovered marker, score 1, unchanged raw bytes and selected content.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQ5/UQ6/UQP4: actual UTF-8 text bytes independently decode the complete supplementary sequence before quality/routing assertions; nonvisual text retains structured method and no unrecovered marker, score 1, unchanged raw bytes and selected content.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/ExtractionDropoutMarkerTest.java#utf8SupplementaryTextKeepsItsStructuredMethodAndNoDropoutMarker"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionDropoutPdfFixturesTest.java"
      ],
      "class": "New",
      "behaviour": "UQ5/UQ6/UQP3/UQC3: deterministic physical one-page PDF independently decodes its complete supplementary sequence in PDFBox and production Tika, including the ordinary structured separator. Actual evidence retains the known separate phantom-page defect (reported 2 pages/1 missing), so OCR stays DISABLED and VDU stays PENDING baseline_text. The counting repair changes actual page-aware quality from 0 to .8, page-unaware quality in the constructed IndexDocument to 1 (actual scalar persistence remains failed because the unchanged mapper ignores double fields) and ratio to .96, removes the false dropout reason and retains structured method, selected content and raw source bytes. Whole-Tika-PDF no-demand proof is limited by the existing page-summary defect; no-missing-pages TEXTUAL proof is separate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, only use codex agents",
          "delegationLocation": "task extraction-unicode request",
          "delegationScope": "The settled first forward-only supplementary Unicode counting repair and its proof, retaining the existing thresholds, UTF-16 length/density/offset rules, missing-page/confidence/layout/budget gates and v1 shape. No production changes beyond TextQualityAnalyzer and ExtractionDropoutPolicy; no publication/global changes.",
          "choice": "UQ5/UQ6/UQP3/UQC3: deterministic physical one-page PDF independently decodes its complete supplementary sequence in PDFBox and production Tika, including the ordinary structured separator. Actual evidence retains the known separate phantom-page defect (reported 2 pages/1 missing), so OCR stays DISABLED and VDU stays PENDING baseline_text. The counting repair changes actual page-aware quality from 0 to .8, page-unaware quality in the constructed IndexDocument to 1 (actual scalar persistence remains failed because the unchanged mapper ignores double fields) and ratio to .96, removes the false dropout reason and retains structured method, selected content and raw source bytes. Whole-Tika-PDF no-demand proof is limited by the existing page-summary defect; no-missing-pages TEXTUAL proof is separate.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionDropoutPdfFixturesTest.java#supplementaryPdfTextIsExtractedBeforeQualityAndRoutingAreMeasured"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a178c5-becd-7d60-88aa-eebb5f61f6f1",
  "verdict": "accept",
  "digest": "sha256:7949f64706ce8f1cd809e8cefb6a90a2e45a7336f8b8daf92f36d960529edc99",
  "note": "Accept declared test-intent/component expectations and stock proof only. Full UQ6 FAIL: stored scalar absent (d0f5c2). Retain PDF no-demand limit 43e7a2, split-offset503 6aa9d4, unverified positive chunk/embedding proof, model availability-only/incomplete historical exit census, and all three original unit failures. No full-scenario or publication approval."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17d59-afbd-7b73-ba56-4fcf5711fa43",
  "verdict": "accept",
  "digest": "sha256:f97ef009a6fb32bde298de3249af0f94c3b5c1857ebdc1cc32abd553e139caaf"
}
```
