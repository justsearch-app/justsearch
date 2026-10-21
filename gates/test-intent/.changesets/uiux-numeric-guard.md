---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-numeric-guard
---

# Numeric consistency: contract recorded before implementation

Started 2026-10-21 09:41 CEST. Estimate 4–8 h; return for a decision by
2026-10-21 21:41 CEST (the assignment's approximately 12 h limit).

The following literal pairs fix the comparison contract (NG1, NG3, NG4, NG7;
D8 amended by F1 and F3). “Accept” means eligible for the original score and
threshold, never a promise of verification. Every distinct claimed value must
occur in the selected passage. Units and leading zeros are ignored; explicit
plus/minus and percentage notation are retained. A hyphen between digits is a
range/date separator, not a unary minus. Decimal point is the only decimal
separator. Only complete groups of three after a one-to-three-digit first group
are grouping; malformed groups remain separate values. Comma, ordinary space,
NBSP and narrow NBSP are the grouping formats. No decimal rounding, arithmetic,
unit conversion or number-word interpretation occurs.

| Sentence (literal) | Passage (literal) | Eligible |
| --- | --- | --- |
| Vespera Observatory opened in 2099. | Vespera Observatory opened in 1883. | Reject |
| Vespera Observatory opened in 1923. | Vespera Observatory opened in 1883. | Reject |
| The value is 99. | The value is 2099. | Reject |
| The value is 2099. | The value is 99. | Reject |
| The value is 1.5. | The value is 15. | Reject |
| The value is -15. | The value is 15. | Reject |
| The value is 15. | The value is -15. | Reject |
| The value is +15. | The value is 15. | Reject |
| The value is −15. | The value is -15. | Accept |
| The value is 15%. | The value is 15. | Reject |
| The value is 15. | The value is 15%. | Reject |
| The value is 15 %. | The value is 15%. | Accept |
| The value is 0015 kg. | The value is 15 m. | Accept |
| The value is 1.50. | The value is 1.5. | Reject |
| The value is 1,500. | The value is 1500. | Accept |
| The value is 1 500. | The value is 1500. | Accept |
| The value is 1 500. | The value is 1500. | Accept |
| The value is 1 500. | The value is 1500. | Accept |
| The value is 1,500,000. | The value is 1500000. | Accept |
| The value is 12,34. | The values are 12 and 34. | Accept |
| The value is 12,34. | The value is 1234. | Reject |
| The value is 1.500. | The value is 1500. | Reject |
| The interval is 10-20. | The endpoints are 10 and 20. | Accept |
| The interval is 10–20. | The endpoints are 10 and 20. | Accept |
| The interval is 10-20. | The endpoint is 10. | Reject |
| The interval is -20 to -10. | The values are -10 and -20. | Accept |
| The date is 2026-10-21. | The parts are 21, 10 and 2026. | Accept |
| The date is 2026-10-21. | The parts are 21 and 2026. | Reject |
| افتتح المرصد في ١٨٨٣. | Opened in 1883. | Accept |
| افتتح المرصد في ٢٠٩٩. | Opened in 1883. | Reject |
| 値は１５です。 | The value is 15. | Accept |
| The engine owns the index [4]. | The engine owns the index. | Accept |
| The engine owns the index [4][7]. | The engine owns the index. | Accept |
| 4. The engine owns the index. | The engine owns the index. | Accept |
| The array is `[4]`. | The array is empty. | Reject |
| The array is `[4]`. | The array contains 4. | Accept |
| The array is [4, 7]. | The array contains 4. | Reject |
| The equation is x = [4]. | The equation is unspecified. | Reject |
| The equation is x = [4]. | The value is 4. | Accept |
| The constant is `2099`. | The constant is 1883. | Reject |
| Value is 2099 (a table cell). | Value is 1883. | Reject |

Reference syntax: positive integer bracket markers following prose, before a
clause boundary or another marker, are citations. Brackets in code, comma
arrays, and brackets following mathematical operators retain their values.
CommonMark list ordinals are structure. Segmentation must retain numeric claim
metadata for code while keeping existing sentence keys unchanged.

Late preservation counterexamples, recorded before their corrective edit:
`索引归引擎所有 [4]。` / `Some passage.` and
`索引归引擎所有。 [4]` / `Some passage.` must remain eligible.
Recognized references use Unicode punctuation boundaries as well as ASCII ones.
These add no numeric equivalence; they pin NG4/NG7/GN1 for the existing marker rule.

# Performance protocol recorded before implementation (NG8, NG9; F5)

Baseline: 76efc1c10. Candidate: this uncommitted worktree, whose evidence captures
the working-tree hash. Scorer fixtures: the literal table above plus a fixed
number-free corpus in the new scorer tests. No mutable live index: Worker
fixtures construct a fresh Lucene index from literal sources per test; supplied
and lookup texts are identical between revisions. Scorer timing uses the same
fixed answer/source suite on both revisions, including both WP4.4 examples,
a compatible alternate source, a numeric-free answer and Unicode/code cases.
The exact fixture lists and hashes accompany the measurement output.

Model: read-only models/onnx/citation-scorer/{model.onnx,tokenizer.json,config.json}
in the main checkout (MiniLM cross-encoder); record SHA-256 of all three, Java,
ORT version, CPU identity and available memory. CPU-only, one measurement lane,
no dev stack; record competing load before each run. Warm up the same scorer
with two complete unmeasured suite runs per JVM. Measure System.nanoTime around
scoreAll for every fixture, including numeric preparation and all sentence /
window scoring, excluding session/tokenizer startup and warm-up. Run five
complete suites; the median applies to the SUM of fixture intervals per suite,
not separately per fixture. Compare baseline/candidate medians (candidate at
most 110% of baseline) and each fixture's sentencesScored on identical inputs
and the same production deadline. Report every run, medians, matched/scored
counts, and any newly unscored sentence. A deterministic stub timing is not an
ONNX or production Ask performance claim.

Live follow-up (unavailable to this builder): pin a rag-qa corpus/index snapshot
and its content hash, question IDs, chat model/version/hash, generation settings
and retrieved source/answer fixtures. Use the production Ask entry point and
persist its events/claimMatches; cold-reload the same records. Run baseline and
candidate under identical CPU/memory/load and deadlines with two warm-up suites
and five measured suites; measure from citation scoring dispatch through its
completed response, suite median as above. Compare scored coverage per sentence
and report before/after verified counts for every rag-qa answer and totals.
RagQualityEvalTest's direct scoreAll count is supplementary and cannot satisfy
NG9. No live Ask, browser cold reload or chat generation result is claimed here.

# Test-intent entries

The shared helper is used at candidate selection in both existing best-of loops.
Every window is still scored; numeric compatibility only changes eligibility for
the winner. Counts are derived after selection, not corrected by post-filtering.
Parsed claims carry numeric metadata alongside unchanged visible sentence keys;
inline code brackets are protected in that metadata. The existing segmentation
claim floor, producer gate, store format, and frontend are unchanged. The direct
RAG evaluator now carries that metadata too, and its unused string-only wrapper
is removed. The reranker unit suite reuses the existing ORT fixture dependency
and its existing native runtime; no dependency lock or frontend manifest changes.

```json
{
  "entries": [
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java"
      ],
      "class": "New",
      "behaviour": "The real cross-encoder best-of loop, with deterministic ONNX outputs, rejects every incompatible literal pair, reselects a compatible passage across sub-batches, and retains the original threshold and scored count.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG5",
          "quote": "When the highest-scoring passage lacks the sentence's value and another passage containing it clears the threshold, the outcome follows decision D9 identically on both scorers; under reselection the mark opens the passage that contains the value.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-repaired.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#rejectsLiteralIncompatiblePairs",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#reselectsCompatibleWindowAcrossSubBatches",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#neverPromotesAnAlternativeBelowThreshold"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java"
      ],
      "class": "New",
      "behaviour": "Compatible literal pairs and numeric-free sentences retain exact matches, scores, thresholds and counts; citation markers and structural ordinals introduce no numeric claim.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN1",
          "quote": "Sentences without numbers get exactly the same matches as before on a fixed corpus.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#preservesLiteralCompatiblePairs",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#preservesNumberFreeCorpusAndThreshold"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java"
      ],
      "class": "New",
      "behaviour": "Raw cosine matcher results reject the missing values on supplied and indexed passages, including long-document windows; reselection addresses the compatible source. Code, tables, arrays, mathematical brackets and Unicode retain their numeric claims through segmentation. Rejections remain scored.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG5",
          "quote": "When the highest-scoring passage lacks the sentence's value and another passage containing it clears the threshold, the outcome follows decision D9 identically on both scorers; under reselection the mark opens the passage that contains the value.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#rejectsBothCounterexamplesOnSuppliedAndLookupPaths",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#rejectsMissingYearInLongLookupDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#reselectsCompatibleSourceOnSuppliedAndLookupPaths",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#retainsNumericClaimsInCodeTablesArraysAndMathematicsAfterSegmentation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java"
      ],
      "class": "New",
      "behaviour": "Existing plain sentence keys and exact raw cosine matches survive reference/list structure, numeric-free multilingual text, and compatible normalized values, with no threshold promotion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN1",
          "quote": "Sentences without numbers get exactly the same matches as before on a fixed corpus.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#preservesReferencesOrdinalsAndNumberFreeCorpus",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#preservesCompatibleNumbersAndOriginalThreshold"
      ]
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "With the explicitly configured read-only CPU model, both wrong-year counterexamples lose their matches but remain scored; evidence executes this check without a model skip.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-repaired.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java#rejectsRealModelCounterexamplesAndKeepsCoverage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "A fixed literal scorer suite reports model/fixture hashes, resource facts, two warm-ups and five timed suites, and asserts every fixture remains scored. Paired isolated XML artifacts below carry the actual before/after timing comparison; this check makes no production Ask or rag-qa claim.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG8",
          "quote": "On a fixed question set, the guard causes no sentence to go unscored for lack of time that was scored before, and scoring time stays within the bound of decision D10.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java#reportsFiveRunScorerSuiteMeasurement"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/NumericCitationHistoryTest.java"
      ],
      "class": "New",
      "behaviour": "An empty authoritative result remains fully scored in Ask events, the persisted claimMatches payload and a fresh FileConversationStore reload on both producers. A legacy wrong-year verified record retains its original verdict and sources.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN3",
          "quote": "Conversations saved before the change load and render as before (no re-judging).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/NumericCitationHistoryTest.java#rejectedSentenceStaysScoredInLiveAndColdReloadedAskPayload",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/NumericCitationHistoryTest.java#legacyNumericVerdictLoadsWithoutRejudging"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentNumericCitationRejectionTest.java"
      ],
      "class": "New",
      "behaviour": "A fully scored rejection retains the producer stamp and no verified citation through Delegate resolver, done payload and history mapping, on both producers.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN3",
          "quote": "Conversations saved before the change load and render as before (no re-judging).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentNumericCitationRejectionTest.java#rejectedSentenceKeepsProducerAndEmptyCitesThroughDelegateEventsAndHistory"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextNumericRejectionTest.java"
      ],
      "class": "New",
      "behaviour": "The raw citation HTTP handler publishes zero matches/matched sentences beside complete sentence/text coverage and the actual producer on both paths.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextNumericRejectionTest.java#rawApiReportsRejectionAsScoredWithZeroMatches"
      ]
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/ai/RagQualityEvalTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Faithfulness passes the production segmentation keys together with their numeric metadata instead of discarding code-versus-reference distinctions and passing only strings. The model inputs, passages, threshold, deadline, coverage denominator and matched/total calculation remain the same; the shared scorer now applies the separately warranted numeric guard. The unused string-only wrapper is retired. No rag-qa production Ask result is inferred from this direct-scorer metric.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

# Measurement and verification notes

The first real-model integration-source attempt did not execute: that suite
lacked the ONNX runtime. Adding it there required a new dependency lock entry,
so the new model check instead lives in the existing unit suite, which already
has that runtime. Only the local ORT test-fixture dependency was added. The
fail-before runner overlays that dependency-only build file; no production
repair is overlaid onto the baseline.

The first timing pair overlapped other checks and had different competing load;
it is not used to judge the bound. The isolated pair runs only the timing method
in sequence, with the protocol above, on the same i7-12700K / Java 25.0.4.1 /
ORT 1.24.3 and identical fixture/model hashes. Baseline five suite intervals:
206537468, 262109017, 259216830, 234322166, 209981205 ns (median 234322166).
Candidate: 156463941, 158093332, 179336034, 174737044, 182375029 ns
(median 174737044; ratio 0.7457). Every run scores all six fixture sentences;
matched counts change from [1,1,1,1,0,1] to [0,0,1,1,0,1]. Both measurements
explicitly force fresh execution with --rerun; cached test reports are not new
timing runs. Shared-machine load varied (baseline 9.23–10.90, candidate
4.11–4.11), so this is a recorded scorer-level bound observation,
not a causal speedup or a live Ask claim.

Raw test-generated timing artifacts:
[before](../evidence/uiux-numeric-guard-performance-before.xml) and
[after](../evidence/uiux-numeric-guard-performance-after.xml).

Scenario evidence map (builder observations, not independent acceptance):

| Scenario | Evidence / remaining limit |
| --- | --- |
| NG1 | Repaired JSON: both real and deterministic cross-encoder checks; raw cosine supplied/lookup and long-document checks. Ask/Delegate transport and record behavior use the preservation checks. Running-system/browser combinations remain for the verifier. |
| NG2 | Preserved JSON: literal compatibility, exact scores, thresholds; repaired JSON: below-threshold alternate cannot gain a match. |
| NG3 | Literal table above; cross-encoder table and cosine segmentation checks in both JSON files. |
| NG4 | Reference/ordinal preservation and code/array/math rejection checks. |
| NG5 | Both repaired best-of checks; returned window/source indices address the compatible passage. |
| NG6 | Raw Worker counts plus Ask live/store reload, Delegate events/history, and HTTP-handler preservation checks; existing frontend coverage/projection suite checks its consumers. Browser/live Engine verification remains unrun. |
| NG7 | Arabic and fullwidth numeric cases in the literal and Worker checks; numeric-free Han/German corpus preserved. |
| NG8 | Isolated model timings above, no newly unscored fixture. Production Ask fixed-question timing remains unverified without the dev stack. |
| NG9 | Unverified: no production Ask rag-qa run was available. The live protocol above describes the required remaining measurement. Direct-scorer counts are not substituted. |
| GN1 | Exact numeric-free cross-encoder and raw cosine corpus checks pass before and after. |
| GN2 | Existing RAGContextVerificationSourcesTest (S7.1/S7.3/S7.8), ShortDocumentPassageVerificationTest (S7.2), and literal passage tests pass in focused baseline/candidate runs. |
| GN3 | Old wrong-year record and new rejection survive a fresh store reload; Delegate mapping preserves the stamp. Existing frontend restored-citation projection passes. Browser cold-start comparison remains unrun. |
| GN4 | Existing anchoring/projection/coverage/source-resolution frontend tests pass before (190 checks) and the full candidate suite passes (7110 checks); MCP answer checks are recorded with the final validation. |

All added test files are durable. The five-run method is durable measurement
instrumentation; its local timing XML is supporting measurement evidence. No
acceptance record is authored here. The orchestrator/verifier owns acceptance.

The test-intent CLI currently reports only the missing independent acceptance
record, which this builder was explicitly forbidden to write. It also reports
`WARNING test-intent/reconsider-rate`: 1 of 8 audited PRs required third-reviewer
tie-breaks in the last 28 days (12.5%, above one in ten). This is a finding for the
orchestrator's delivery, not a failure of the implementation checks.

Tests added or rewritten:

| File | Scenarios | Status |
| --- | --- | --- |
| CitationScorerNumericTest.java | NG1–NG5, NG7, GN1 | durable |
| CitationMatchOpsNumericTest.java | NG1–NG7, GN1 | durable |
| NumericCitationScorerIntegrationTest.java (unit source set) | NG1, NG6, NG8 scorer measurement | durable |
| NumericCitationHistoryTest.java | NG6, GN3 | durable |
| AgentNumericCitationRejectionTest.java | NG6, GN3 | durable |
| RetrieveContextNumericRejectionTest.java | NG6 | durable |
| RagQualityEvalTest.java | NG1/NG3 metadata input adaptation | existing durable evaluator; live rag-qa not run |

Before-failure examples (the repaired JSON records every selected check):

- `rejectsLiteralIncompatiblePairs`: incompatible 2099/1883 pair expected no
  match, got `ScoredMatch(... score=0.9760000109672546)`.
- `reselectsCompatibleWindowAcrossSubBatches`: expected window 20, got 0.
- `neverPromotesAnAlternativeBelowThreshold`: expected no match, got the
  incompatible window at 0.9760000109672546.
- `rejectsRealModelCounterexamplesAndKeepsCoverage`: 2099/1883 expected no
  match, got a real-model match at 0.9978494048118591.
- Raw cosine counterexample and long-lookup checks: expected zero matches, got
  one; cosine reselection expected source 1, got 0.
- Segmentation check: inline-code `[4]` expected zero matches, got one.

The manual before checkout was a shared clone of 76efc1c10 under the assigned
TMPDIR and was removed after the comparisons; the evidence tool's managed before checkouts were removed by that tool.
No model, corpus or installer was copied into the checkouts. No dev stack was
started, no process owned by another session was stopped, and no commit was made.

The late Unicode-reference preservation cases passed on the unchanged baseline,
failed by assertion on the first guard candidate (cross-encoder expected the
original match but got none; cosine expected one match but got zero), and passed
after reference boundaries were defined using Unicode punctuation categories.
The added corpus cases retain both sentence keys and exact raw scores. These
cases add no numeric representation equivalence.

Final validation commands and observations are recorded in the builder handoff;
McpAnswerLegibilityTest passes on the baseline and candidate with fresh test
execution. The full frontend suite and all 28 UI gates passed. Live NG8/NG9 and
browser cold-start checks remain explicitly unverified as stated above.

Final command results:

- `js-gradle-slot ./gradlew build -x test -PskipWebBuild=true`: BUILD SUCCESSFUL
  on the final production code (1m 21s; 332 actionable tasks).
- Focused reranker, Worker, Ask, Delegate and raw HTTP-handler tests: passed;
  `pmdMain` for reranker, worker-services, app-services, app-agent and ui: passed.
- New reference-preservation corpus: baseline passed, first candidate assertion
  failures recorded, corrected candidate passed; no scenario or threshold weakened.
- Script-generated evidence: 17 new checks executed/passed; all eight repair
  checks have assertion fail-before evidence on 76efc1c10.
- `npm run lint`, `npm run typecheck`, `npx vitest run`: exit 0; 538 files /
  7110 frontend tests passed. `node scripts/ci/run-ui-web-gates.mjs`: 28/28 passed.
- `McpAnswerLegibilityTest`: fresh baseline and candidate runs passed.
- Test-intent: only independent acceptance is missing; the rolling reconsider-rate
  warning is recorded above. The builder has not authored an acceptance record.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp4.5-verifier-dvn67dxr",
  "verdict": "reject",
  "digest": "sha256:f8b76f3d681d78b7a5058cb34c8b0f870c159ba6328168132c50049320da968e",
  "note": "NG1/NG3 and D8/F1 fail for decimals without a leading zero: .5, -.5 and +.5% are treated as 5, 5 and 5%. Both scorers accept the mismatch; real HTTP Ask and Delegate publish verified citations, and both browser windows render the mark. The 41 literal pairs pass, but these extra equivalences were not agreed. See verifier evidence dvn67dxr."
}
```
