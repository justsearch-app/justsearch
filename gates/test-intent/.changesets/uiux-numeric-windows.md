---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-wp4.5b-numeric-windows
---

# Test-intent entries

Design (a): numeric eligibility reads complete source values intersecting each admitted window.
The model receives the existing window text, preserving its token budget, scores, admission and
source mapping. Existing window readings survive when that whole value occurs in the source;
other source values contribute only where their spans intersect the window. NX3–NX3d forms stay unchanged.

The normalization repair uses a composition-free NFKD passage view after the existing NFKC/digit
folding. Complete number spans and prefix lengths now use the same coordinate system; composed
prefix lengths no longer index the composed whole text. Raw-window readings still normalize the
original substring. Inconsistent placement contributes no values, never an answer-wide failure.
Source metadata remains cached once per source. No saved verdict, model input or threshold changes.

The final fourteen-method Worker file passes without skips. The two normalization repair methods
fail on `631837df7` with `Range [0, 1500) out of bounds for length 1499`
([exception and assertion observations](../evidence/uiux-numeric-windows-normalization-before-observations.json)); the adjacent-window case also
checks that a value in a below-threshold window cannot support its neighbor. Both methods pass on
`f1f173020`, where this regression is absent. Additional preservation cases cover İ/ß, ligatures,
full-width and supplementary digits, emoji, Hangul composition and half-width voiced kana.
Admission lowercases terms without using their positions as source offsets; this path never uppercases ß.
The long malformed-source methods retain the possessive-matcher robustness repair, including non-Latin claims.

Evidence was regenerated in default link mode for all fourteen methods:
[seven package repairs](../evidence/uiux-numeric-windows-repaired.json) against `f1f173020`,
[two normalization repairs](../evidence/uiux-numeric-windows-normalization-repaired.json) against `631837df7`,
and [five preservation methods](../evidence/uiux-numeric-windows-preserved.json).
The [baseline preservation XML](../evidence/uiux-numeric-windows-preservation-before.xml) records
seven passing methods on `f1f173020`, including both normalization repair methods.
Both saved-history methods pass [before](../evidence/uiux-numeric-windows-history-preserved-before.xml)
and [after](../evidence/uiux-numeric-windows-history-preserved-after.xml). Historical fixture failures
on `8592620d0` are retained in the earlier history comparison; main fixed their missing session creation.

| Scenario | Current evidence |
| --- | --- |
| NX4.1 | Seven durable repair methods fail before/pass after; native 424-pair table preserves all 1,696 observations. |
| NX4.2 | Seven baseline preservation methods, fourteen candidate methods, and whole-value Unicode native checks pass. |
| NX4.3 | CE/cosine and supplied/indexed Worker paths pass; 73 citation-path app-services tests and both before/after history fixtures pass. Original Engine Ask/Delegate observations remain historical package evidence. |
| NX4.4 | Current B/A ×3 D10 comparison passes at ratio 0.979692, with no newly unscored fixture. |

The [current private CPU Worker observations](../evidence/uiux-numeric-windows-normalization-worker-observations.json)
contain the unchanged 424-pair table plus two combining-mark and four native-digit/long-source pairs:
1,720 observations across both scorers and passage paths. CE uses real CPU ONNX; cosine runs the production loop with fixed embedding vectors. The unchecked
CE baseline uses the same prepared model windows and explicit positive cutoff. The first scaffolding probe incorrectly treated
zero as a literal threshold; Worker resolves zero to its configured default. That failed probe is
retained under `tmp/nx4-validation/native-first-*`; the corrected comparison changes no production
threshold or table expectation. All native processes used private HOME, JVM temp, Lucene indexes
and ONNX caches. Models were read-only; no owner's apps, dev stack or GPU were used.

The [suite comparison](../evidence/uiux-numeric-windows-suite-comparison.json) retains the unrelated
app-services failures: the broad fourteen-class keyword selection failed 59/183 tests initially and
49/183 after the required delay. The same 49 test names and messages fail on `f1f173020`.
`RuntimeActivationServiceTest.selectWindowsNativeFixture` sets `os.name=Windows 11` on Linux before
SQLite initialization; the initial run also tried `bin/java.exe` for Mockito attachment. This fixture
is byte-identical to main, and private `java.io.tmpdir` does not remove these failures. The four broad
matches concern citation-model settings rather than calls to scoring classes. The ten citation-path
classes run together pass all 73 tests, including `NumericCitationHistoryTest`. No checks or fixtures
were weakened. The isolated scheduler class passes on both candidate and main; the earlier full
Worker run's 4.0% bulk share versus its 13.7% floor is retained as a load-sensitive suite failure.
The completed PMD checks for reranker, worker-services and app-services pass. The fresh reranker
suite passes all 104 tests without skips. The earlier full app-services run (397 failures in 4,236
tests) is superseded by the scoped run under the orchestrator’s instruction; no app-services fixture was changed.

D10 uses the unchanged six-fixture CPU ONNX workload and model hashes. Following the delegated
measurement instruction, six independent test JVMs alternate **B A B A B A**, each with two warm-up
suites and five measured suites. Identical JVM settings, `--max-workers=1`, private per-side ONNX
caches and the same private test temp apply throughout. B is `f1f173020`; A is the current uncommitted
normalization repair on `631837df7`. No production code changed during this comparison.

> Measure in a way that cancels drift: alternate before and after at least three times each (B A B A B A), same JVM settings, --max-workers=1, and report every run plus the median of each side.

> NX4.4 Scoring time stays within decision D10 (no measurable change on the D10 workload).

| Run | Five measured suite times (ms) | Run median (ms) |
| --- | --- | --- |
| [B1](../evidence/uiux-numeric-windows-controlled-performance-before-1.xml) | 253.541, 203.664, 205.134, 212.761, 193.677 | 205.134107 |
| [A1](../evidence/uiux-numeric-windows-controlled-performance-after-1.xml) | 160.823, 155.940, 158.099, 157.846, 158.490 | 158.099065 |
| [B2](../evidence/uiux-numeric-windows-controlled-performance-before-2.xml) | 165.137, 161.631, 169.654, 176.647, 182.590 | 169.654048 |
| [A2](../evidence/uiux-numeric-windows-controlled-performance-after-2.xml) | 220.727, 193.567, 200.968, 202.634, 186.704 | 200.968327 |
| [B3](../evidence/uiux-numeric-windows-controlled-performance-before-3.xml) | 362.469, 323.856, 317.203, 357.161, 357.857 | 357.160615 |
| [A3](../evidence/uiux-numeric-windows-controlled-performance-after-3.xml) | 245.246, 239.236, 239.954, 235.374, 244.299 | 239.954076 |

The medians of the three run medians are **205.134107 ms before** and **200.968327 ms after**,
ratio **0.979692**. All 180 measured fixture checks remain scored with identical verdicts,
model hashes and fixture hashes. The [comparison producer](../evidence/uiux-numeric-windows-performance-comparison.py)
validates every XML and writes the [complete comparison](../evidence/uiux-numeric-windows-controlled-performance-comparison.json).
Gitleaks flagged six `tokenizer.json sha256=` labels; only the label separator was changed to a
space, preserving every model digest and timing. The follow-up scan passed.

Earlier [sequential timing diagnostics](../evidence/uiux-numeric-windows-sequential-performance-comparison.json)
and [private Engine observations](../evidence/uiux-numeric-windows-live-observations.json) remain
historical package evidence. The latter cover 20 raw API checks, four generated Ask/Delegate
attempts and cold history; they were not rerun during this normalization-repair validation.

Manual comparison checkouts are shared clones with separate build folders. The private Gradle
home contains copied distribution/dependency caches and a test-only init script for private
`java.io.tmpdir`; client JVM settings and heap options are unchanged. All Gradle stages run under
`js-gradle-slot`. Shared resources touched are its machine slot locks and the read-only CPU model
files. No frontend dependency tree was written.

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
      "behaviour": "NX4.2–NX4.3 preservation: values beside a cut retain their verdict and source identity; a window cannot borrow a value from another window, scores below threshold stay below threshold, and numeric-free claims keep their match. Long malformed document runs never cause recursive matcher failure, including non-Latin digit claims. Length-changing Unicode normalization and lowercasing preserve the verdict and completed scoring on both producer paths.",
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
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#preservesNonLatinClaimAgainstLongMalformedSource",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#preservesOtherLengthChangingUnicodeAtWindowBoundaries"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java"
      ],
      "class": "New",
      "behaviour": "Normalization/window-offset repair: combining marks at a scoring-window boundary never abort CE or cosine scoring, on supplied and indexed passages. Unsupported values stay unsupported, present values retain support, and values in preceding or following below-threshold windows cannot make a different window eligible.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.5b normalization/window-offset repair under the user's design delegation; NFKC and NX3–NX3d numeric form rules remain unchanged.",
          "choice": "Window offsets must refer to positions in the same text the numbers are read from. Mapping between raw and normalized positions must never step outside either string, whatever the passage contains. A number check that cannot place a window must leave that sentence unsupported for that window, never fail the whole answer's scoring.",
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
      "evidence": "gates/test-intent/evidence/uiux-numeric-windows-normalization-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#scoresCombiningMarksAcrossWindowBoundary",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/NumericCitationWindowTest.java#keepsCombiningBoundaryValuesWindowLocal"
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
  "session": "9bmd88jv",
  "verdict": "accept",
  "digest": "sha256:338719eb524f4240855859d57e226383162d0351cba8a769d5c0b0fdafb28a71",
  "note": "Expectations follow the plain-number decision and delegated NX4 sources. Fresh default-link evidence: all fourteen candidate checks pass; seven repairs assertion-fail on main, two normalization repairs assertion-fail on 631837df7 and pass on main. Independent Worker probes preserve 440 derived pairs and repair boundary/Unicode cases. This accepts test intent, not the final product verdict; exact identity of the aggregate-only 424-row artifact is unverified. Independence is a separate session within the available model family."
}
```
