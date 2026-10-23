---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-wp4.5b-numeric-windows
---

# Test-intent entries

Design (a): numeric eligibility reads complete source tokens intersecting each admitted window.
The model receives exactly the existing window text, preserving its token budget, scores,
admission and source mapping. This handles a number longer than one window without enlarging
model input. Existing window readings are retained when the complete source contains that whole value.
Other source values contribute only where their spans intersect the window. The shared tokenizer
keeps the accepted NX3–NX3d form rules.

The D10 comparison uses the unchanged six-fixture CPU ONNX workload. Following the owner's
2026-10-23 measurement instruction, it alternates six independent JVM runs in **B A B A B A**
order. Each run has the same two warm-up suites and five measured suites, identical Gradle test
JVM settings, `--max-workers=1`, CPU execution, private per-side ONNX caches, and read-only models.
The per-side statistic is the median of its three five-suite medians. Every raw suite duration,
model/fixture hash, verdict, scored count, resource report and run median is retained.

> Measure in a way that cancels drift: alternate before and after at least three times each (B A B A B A), same JVM settings, --max-workers=1, and report every run plus the median of each side.

> NX4.4 Scoring time stays within decision D10 (no measurable change on the D10 workload).

The unchanged timing check is `NumericCitationScorerIntegrationTest#reportsFiveRunScorerSuiteMeasurement`.
The [comparison producer](../evidence/uiux-numeric-windows-performance-comparison.py) validates
all six XML reports and writes the [complete comparison](../evidence/uiux-numeric-windows-controlled-performance-comparison.json).
The earlier sequential measurement is preserved as diagnostic evidence:
[before XML](../evidence/uiux-numeric-windows-controlled-performance-before.xml),
[after XML](../evidence/uiux-numeric-windows-controlled-performance-after.xml), and
[failed sequential comparison](../evidence/uiux-numeric-windows-sequential-performance-comparison.json).
Its 228.77 ms to 335.98 ms result failed the bound; a single sequential pair cannot separate
candidate cost from load drift on the shared machine. Production code is unchanged during
this alternating comparison.

The alternating comparison passes without a production change: 163.428188 ms before, 161.744798 ms after (ratio 0.989700). All 180 measured fixture checks remained scored, with identical verdicts, model hashes and fixture hashes. The larger last-cycle times occurred on both sides, consistent with changing load rather than a repeatable candidate penalty. The sequential pair did not reproduce under the agreed alternating method.

| Run | Five measured suite times (ms) | Run median (ms) |
| --- | --- | --- |
| [B1](../evidence/uiux-numeric-windows-controlled-performance-before-1.xml) | 161.997, 162.582, 163.715, 160.174, 164.202 | 162.582232 |
| [A1](../evidence/uiux-numeric-windows-controlled-performance-after-1.xml) | 160.182, 160.477, 163.579, 162.584, 161.745 | 161.744798 |
| [B2](../evidence/uiux-numeric-windows-controlled-performance-before-2.xml) | 163.428, 159.429, 165.455, 163.671, 162.110 | 163.428188 |
| [A2](../evidence/uiux-numeric-windows-controlled-performance-after-2.xml) | 161.444, 163.916, 161.619, 165.751, 159.980 | 161.618622 |
| [B3](../evidence/uiux-numeric-windows-controlled-performance-before-3.xml) | 164.387, 171.176, 180.713, 182.053, 173.573 | 173.572697 |
| [A3](../evidence/uiux-numeric-windows-controlled-performance-after-3.xml) | 169.968, 175.563, 170.278, 180.207, 171.498 | 171.498121 |

The [baseline preservation XML](../evidence/uiux-numeric-windows-preservation-before.xml) records four passing preservation methods on `8592620d0`.

The [private live observations](../evidence/uiux-numeric-windows-live-observations.json) record 20 raw API checks, four generated Ask/Delegate attempts, unchanged cold history, re-scoring, and all 1,696 observations of the 424-pair literal table. Checks used private CPU Engine data, HOME, ports and a fake generation server. All recorded private processes stopped; no native fatal report appeared in this final run.

The [history comparison](../evidence/uiux-numeric-windows-history-baseline-comparison.json) records two pre-existing failures, with identical exception types/messages and first five stack lines on baseline and candidate. Both `NumericCitationHistoryTest` and `FileConversationStore` are byte-identical to `8592620d0`. `legacyNumericVerdictLoadsWithoutRejudging` fails with `No such conversation: legacy`; `rejectedSentenceStaysScoredInLiveAndColdReloadedAskPayload` fails with `No such conversation: CROSS_ENCODER`. The fixtures append before creating their sessions. Neither fixture was changed.

Execution evidence was regenerated for all eleven methods in the tool's default link mode,
with `--before 8592620d0` for the seven repair methods. The run used a private Gradle user home
containing copies of the installed distribution/dependency cache and a test-only init script
setting `java.io.tmpdir` inside this session's temporary folder. Client JVM options and heap
settings were unchanged. The private daemon was stopped by recorded identity afterward.
The shared baseline clone and generated evidence worktree were removed after their comparisons.

The two long-source methods passed on baseline and failed with `StackOverflowError` on the initial
source-aware implementation before its terminal repetitions were made possessive. Both now pass.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java"
      ],
      "class": "New",
      "behaviour": "NX4.1–NX4.3: the shared Worker preparation keeps whole source values across scoring-window edges, without inventing prefixes or suffixes, through the production CE and cosine loops with fixed producer outputs, on supplied/indexed paths. Complete intersecting values remain eligible, including compatibility forms and supplementary digits.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.5b window-boundary design and NX4 scenarios, adopted under the user's delegation; existing NX3–NX3d form rules stay unchanged.",
          "choice": "NX4.1 Splitting a passage into scoring windows never creates a whole value the source passage does not contain. A sentence stating 1200 is not verified against a source whose text has only 1 200 000 (or 1,200,000, 1200000, 1.200), wherever the window boundary falls: at a group space, a group comma, a decimal point, or a hard cut inside an unbroken digit run.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.5b window-boundary design and NX4 scenarios, adopted under the user's delegation; existing NX3–NX3d form rules stay unchanged.",
          "choice": "NX4.2 A sentence whose value the source does contain as a whole value keeps today's verdict, also when that value lies next to or across a window boundary (no new false rejections; S13.2 still holds).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.5b window-boundary design and NX4 scenarios, adopted under the user's delegation; existing NX3–NX3d form rules stay unchanged.",
          "choice": "NX4.3 Applies to both scorers (cross-encoder and cosine), supplied passages and indexed chunks, Ask and Delegate, live and history re-scoring; the source reported for a match stays the source whose text holds the value.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-windows-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#rejectsGroupedSpacePrefixInventedByWindowing",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#rejectsCommaPrefixInventedByHardCut",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#rejectsDecimalSuffixInventedByHardCut",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#rejectsDigitPrefixInventedByHardCut",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#rejectsDigitSuffixFromNumberLongerThanWindow",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#retainsWholeValuesAcrossWindowBoundaries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#retainsCompatibilityAndSupplementaryDigitsAcrossCuts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java"
      ],
      "class": "New",
      "behaviour": "NX4.2–NX4.3 preservation: values beside a cut retain their verdict and source identity; a window cannot borrow a value from another window, scores below threshold stay below threshold, and numeric-free claims keep their match. Long malformed document runs never cause recursive matcher failure, including non-Latin digit claims.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.5b window-boundary design and NX4 scenarios, adopted under the user's delegation; existing NX3–NX3d form rules stay unchanged.",
          "choice": "NX4.2 A sentence whose value the source does contain as a whole value keeps today's verdict, also when that value lies next to or across a window boundary (no new false rejections; S13.2 still holds).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.5b window-boundary design and NX4 scenarios, adopted under the user's delegation; existing NX3–NX3d form rules stay unchanged.",
          "choice": "NX4.3 Applies to both scorers (cross-encoder and cosine), supplied passages and indexed chunks, Ask and Delegate, live and history re-scoring; the source reported for a match stays the source whose text holds the value.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.5b window-boundary design and robust whole-source numeric extraction, adopted under the user's delegation; existing NX3–NX3d form rules stay unchanged.",
          "choice": "citation scoring must not fail on any document text.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-windows-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#preservesWholeValuesAndWindowLocalEligibility",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#retainsExistingWindowVerdictWhenGroupingFollowsAnUngroupedValue",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#rejectsLongMalformedSourceWithoutRecursion",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#preservesNonLatinClaimAgainstLongMalformedSource"
      ]
    }
  ]
}
```
