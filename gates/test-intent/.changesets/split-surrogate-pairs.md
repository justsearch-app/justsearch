---
schema: test-intent.v1
task: t-20261026-8f2d61
author-role: builder
author-session: uq-split
---

# Test-intent entries

Review repairs seed CONTENT_SHA256 from the declared chunk plan revision so stored-state checks
reach the production unchanged-admission decision. Flat extraction now publishes its write-limit
flag through a fresh ParseRecord, and the structured fallback carries that flag into its result.
Both flat-limit checks select the production fallback and assert pair-safe content, truncation and
SUCCESS_PARTIAL, including a helper child using real sandbox request/response frames. Differential
captures now observe stream closure, with explicit ordinary, complete-override and CSV-failure cases.

The review-repair comparisons use separate shared clones inside the session temporary folder:
`before` at e5d7d12f980767c1b88a84e3918de6e9059bb57b for the corrected restart fixture, and
`before-review` at 3fb878fe6efe1d6323a182e2f796b8462f6d2581 for the strengthened flat/status and
stream-ownership checks. Only their test sources are changed; production sources remain committed
baseline versions. Each checkout builds into its own folders. Real Engine and performance checks
remain reserved for independent verification. No evidence generator is run or evidence file edited.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Keep supplementary characters intact at decoded offsets 4095, 8191 and 20479 across the listed encodings, both extraction implementations and the real child route, with a Lucene query depending on the character. Cover adjacent pairs, every 4096 boundary in a 100 KB file, EOF pairs, arbitrary decoder short reads, Markdown/JSON/Python/JavaScript, pair-safe plain/Markdown previews, flat fallback limits and SUCCESS_PARTIAL in-process and in a framed helper child, fresh per-parse write-limit records, and owned parser routing identity. The corrected queued restart/force fixture reaches unchanged admission and checks repaired stored content, hash and preview across restart.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "On main, plain text with an emoji whose high surrogate is at decoded offset 4095 is stored with two U+FFFD in place of the emoji, for UTF-8 and UTF-16 with BOM, on both routes."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "label": "agent choice",
          "choice": "Register a worker-owned plain-text parser as an explicit sibling after DefaultParser, routing exactly TextAndCSVParser media types while retaining Tika's original class for inline mail bodies. Reuse the owned encoding detector and preserve BOM handling, CSV/TSV sniffing, output and metadata; adapt only text reads to carry a trailing high surrogate to the next read and emit a pending high at EOF as before. Guard the adaptation with a differential SAX/metadata corpus. Cut previews and every content-limit path before a surrogate pair. Advance default, blank-fallback and configured policy identities from v3 to v4 on this baseline, coordinating merge order with PR #989. Retain stored data until a file change, collection change or forced reindex."
        }
      ],
      "evidence": "gates/test-intent/evidence/split-surrogate-pairs-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#utf8PairsAtReadBoundariesSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#utf8BomPairsAtReadBoundariesSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#utf16LeBomPairsAtReadBoundariesSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#utf16BeBomPairsAtReadBoundariesSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#utf16LeWithoutBomPairsAtReadBoundariesSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#utf16BeWithoutBomPairsAtReadBoundariesSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#gb18030PairsAtReadBoundariesSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#childProcessPairsAtReadBoundariesSurviveAndAreSearchable",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#consecutivePairsAndEveryBoundaryInLargeFileSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#pairEndingAtEndOfInputSurvives",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#markdownJsonPythonAndJavascriptPairsSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#plainPreviewCutsBeforePair",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#markdownPreviewCutsBeforePair",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#flatContentLimitCutsBeforePair",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#flatChildContentLimitCutsBeforePair",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#flatParseRecordDoesNotRetainPriorWriteLimit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#arbitraryShortReaderCallsKeepPairsTogether",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#supportedTypesAndParserIdentityFollowOwnedRouting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java#forcedReextractionRepairsStoredContentHashAndPreviewAcrossRestart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve exact SAX events and metadata (apart from parser identity) across the fixed encoding/text/CSV/TSV/override corpus, malformed bytes/reader surrogates and control filtering, whole supplementary CSV cells, HTML/XML/Java/C++/Groovy/PDF/Office, zero-embedded inline mail and sequential/concurrent shared graph parsing. Artifact and structured/child caps already cut safely before pairs on baseline and are preservation checks. The corrected legacy queued restart fixture checks preservation without a user action. Tracked input streams also compare ownership for ordinary parsing, a complete encoding override and a CSV failure.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "label": "agent choice",
          "choice": "Register a worker-owned plain-text parser as an explicit sibling after DefaultParser, routing exactly TextAndCSVParser media types while retaining Tika's original class for inline mail bodies. Reuse the owned encoding detector and preserve BOM handling, CSV/TSV sniffing, output and metadata; adapt only text reads to carry a trailing high surrogate to the next read and emit a pending high at EOF as before. Guard the adaptation with a differential SAX/metadata corpus. Cut previews and every content-limit path before a surrogate pair. Advance default, blank-fallback and configured policy identities from v3 to v4 on this baseline, coordinating merge order with PR #989. Retain stored data until a file change, collection change or forced reindex."
        }
      ],
      "evidence": "gates/test-intent/evidence/split-surrogate-pairs-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#structuredAndChildContentLimitsCutBeforePair",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#artifactContentLimitKeepsWholePairs",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#sharedGraphDoesNotLeakCarryBetweenSequentialOrConcurrentDocuments",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/SplitSurrogateExtractionTest.java#inlineMailWithZeroEmbeddedAllowanceKeepsOriginalParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#saxAndMetadataMatchTikaForFixedEncodingAndTextCorpus",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#csvAndTsvSniffingMetadataAndCellsMatchTika",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#malformedUtf16AndUtf8AndControlsMatchTika",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#otherParserFamiliesKeepSaxAndMetadata",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#malformedReaderSurrogatesKeepTikaReplacementBehaviour",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextParserDifferentialTest.java#streamOwnershipMatchesTikaForOrdinaryOverrideAndCsvFailure",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SplitSurrogateQueuedRestartTest.java#restartKeepsLegacyReplacementsAndPolicyWithoutUserAction"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java",
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json",
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "class": "New",
      "behaviour": "Default and blank policy IDs read tika-default-v4; configured limits read tika-config-v4-<chars>-<bytes>. Existing expectations change only v3 to v4 and the three plain named identity method names follow that version. PR #989 status could not be checked because GitHub authentication returned HTTP 401; merge order must still be coordinated.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "label": "agent choice",
          "choice": "Register a worker-owned plain-text parser as an explicit sibling after DefaultParser, routing exactly TextAndCSVParser media types while retaining Tika's original class for inline mail bodies. Reuse the owned encoding detector and preserve BOM handling, CSV/TSV sniffing, output and metadata; adapt only text reads to carry a trailing high surrogate to the next read and emit a pending high at EOF as before. Guard the adaptation with a differential SAX/metadata corpus. Cut previews and every content-limit path before a surrogate pair. Advance default, blank-fallback and configured policy identities from v3 to v4 on this baseline, coordinating merge order with PR #989. Retain stored data until a file change, collection change or forced reindex."
        }
      ],
      "evidence": "gates/test-intent/evidence/split-surrogate-pairs-policy-id.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsV4",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankPolicyIdentityFallsBackToV4",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsV4"
      ],
      "repaired": true
    }
  ]
}
```

## Initial builder execution record (superseded by review repairs below)

Baseline: `e5d7d12f980767c1b88a84e3918de6e9059bb57b`. Before checkout is a shared clone in the
session temporary directory, with its own build folders. All repair failures below are assertions;
compile/setup failures from preparing the clone are not counted as fail-before evidence.
The two stored-state failures stop at fixture admission and are not valid fail-before evidence
for the content/hash/preview repair they intend to check.

| Plain named check | Before | After |
| --- | --- | --- |
| SplitSurrogateExtractionTest.utf8PairsAtReadBoundariesSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.utf8BomPairsAtReadBoundariesSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.utf16LeBomPairsAtReadBoundariesSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.utf16BeBomPairsAtReadBoundariesSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.utf16LeWithoutBomPairsAtReadBoundariesSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.utf16BeWithoutBomPairsAtReadBoundariesSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.gb18030PairsAtReadBoundariesSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.childProcessPairsAtReadBoundariesSurviveAndAreSearchable | assertion fail | pass |
| SplitSurrogateExtractionTest.consecutivePairsAndEveryBoundaryInLargeFileSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.pairEndingAtEndOfInputSurvives | assertion fail | pass |
| SplitSurrogateExtractionTest.markdownJsonPythonAndJavascriptPairsSurvive | assertion fail | pass |
| SplitSurrogateExtractionTest.plainPreviewCutsBeforePair | assertion fail | pass |
| SplitSurrogateExtractionTest.markdownPreviewCutsBeforePair | assertion fail | pass |
| SplitSurrogateExtractionTest.flatContentLimitCutsBeforePair | assertion fail | pass |
| SplitSurrogateExtractionTest.flatChildContentLimitCutsBeforePair | assertion fail | pass |
| OwnedTextParserDifferentialTest.arbitraryShortReaderCallsKeepPairsTogether | assertion fail | pass |
| OwnedTextParserDifferentialTest.supportedTypesAndParserIdentityFollowOwnedRouting | assertion fail | pass |
| SplitSurrogateQueuedRestartTest.forcedReextractionRepairsStoredContentHashAndPreviewAcrossRestart | assertion fail | assertion fail |
| SplitSurrogateExtractionTest.structuredAndChildContentLimitsCutBeforePair | pass | pass |
| SplitSurrogateExtractionTest.artifactContentLimitKeepsWholePairs | pass | pass |
| SplitSurrogateExtractionTest.sharedGraphDoesNotLeakCarryBetweenSequentialOrConcurrentDocuments | pass | pass |
| SplitSurrogateExtractionTest.inlineMailWithZeroEmbeddedAllowanceKeepsOriginalParser | pass | pass |
| OwnedTextParserDifferentialTest.saxAndMetadataMatchTikaForFixedEncodingAndTextCorpus | pass | pass |
| OwnedTextParserDifferentialTest.csvAndTsvSniffingMetadataAndCellsMatchTika | pass | pass |
| OwnedTextParserDifferentialTest.malformedUtf16AndUtf8AndControlsMatchTika | pass | pass |
| OwnedTextParserDifferentialTest.otherParserFamiliesKeepSaxAndMetadata | pass | pass |
| OwnedTextParserDifferentialTest.malformedReaderSurrogatesKeepTikaReplacementBehaviour | pass | pass |
| SplitSurrogateQueuedRestartTest.restartKeepsLegacyReplacementsAndPolicyWithoutUserAction | assertion fail | assertion fail |
| Utf16WithoutBomExtractionTest.defaultPolicyIdentityIsV4 | assertion fail | pass |
| Utf16WithoutBomExtractionTest.blankPolicyIdentityFallsBackToV4 | assertion fail | pass |
| Utf16WithoutBomExtractionTest.configuredPolicyIdentityIsV4 | assertion fail | pass |

The structured and artifact limit checks pass before and after because those production paths
already capped safely on this baseline. The UTF-8 boundary fixtures include UTF-8 evidence early
in the sample: ASCII-only prefixes with the first non-ASCII bytes beyond Tika's detection sample
can still be decoded as Windows-1252, as before; changing detection is outside this assignment.
The flat child checks real flat parsing and sandbox framing, but does not exercise selecting the
flat fallback from a failing structured parse in the shipped child. Real Engine verification,
performance P8, and a working stored-state fixture remain open.

Shared resources touched by checks: Gradle dependency caches and the machine-wide Gradle slots,
plus ordinary CPU/RAM/disk for these test processes. No owner Engine, models, GPU, shared dev stack,
or owner application configuration was deliberately used. `.agents/preflight` is absent.

### Requested task results

The sanitized invocation was `js-gradle-slot ./gradlew :modules:worker-services:test
:modules:indexer-worker:test :modules:worker-services:pmdMain :modules:worker-services:pmdTest
:modules:indexer-worker:pmdTest --continue -PskipWebBuild=true` (one Gradle invocation).
It exited 1 after 27m 46s. Worker-services reported 2498 tests, 4 failures and 23 skips;
its XML additionally represents a disabled container as a skipped test. Indexer-worker reported
2018 tests, 62 failures and 38 skips. None of the 29 new extraction/differential/policy checks was
skipped or failed. Both new stored-state methods failed at the unchanged-admission assertion.
The three requested PMD tasks passed (main/indexer test analysis had already run against these
unchanged sources and were up-to-date in the full invocation; worker test analysis executed).
`git diff --check` passed. No `.agents/preflight` exists.
Builder session: 2026-10-28 01:28 UTC to 02:14 UTC (UTC read from `date -u`), including the full
27m 46s verification invocation and queue waits. All changes remain uncommitted.

The four worker failures are LinuxRootMountInfoTest (missing `/dev/disk/by-uuid`) and three
WatcherUnavailabilityScopeTest methods. The remaining 60 indexer failures are in existing
ReturnedRootCurrentByteReplayTest (34), WatcherMigrationReplayTest (24), and NestedWatchedRootTest (2).
An auxiliary comparison selecting representative mount/watcher/root-replay methods was queued
behind the shared Gradle slots. It was interrupted through its own recorded exec session (66471),
which exited 130 before any Gradle output or test execution, to return the blocked candidate without
further shared-machine load. Those 64 existing failures have not been reproduced on baseline in
this session, and their classification remains unverified. No changes to those checks or their
code have been made. The new stored-state fixture is held after two attempts, not weakened or skipped.

## Review-repair execution record

Started 2026-10-28 02:45 UTC (`date -u`); estimate 40–70 minutes, stop by 04:45 UTC.
The original fixture-admission blocker above is superseded by this authorized review-repair run.
Review comparison results:

| Review check | Baseline | Before result | After result |
| --- | --- | --- | --- |
| SplitSurrogateQueuedRestartTest.restartKeepsLegacyReplacementsAndPolicyWithoutUserAction | e5d7d12f98 | pass | pass |
| SplitSurrogateQueuedRestartTest.forcedReextractionRepairsStoredContentHashAndPreviewAcrossRestart | e5d7d12f98 | assertion fail at stored content (two U+FFFD), after unchanged admission succeeds | pass |
| SplitSurrogateExtractionTest.flatContentLimitCutsBeforePair | 3fb878fe6e | assertion fail: SUCCESS_FULL instead of SUCCESS_PARTIAL; pair-safe content assertion passes | pass |
| SplitSurrogateExtractionTest.flatChildContentLimitCutsBeforePair | 3fb878fe6e | assertion fail: SUCCESS_FULL instead of SUCCESS_PARTIAL; pair-safe content assertion passes | pass |
| SplitSurrogateExtractionTest.flatParseRecordDoesNotRetainPriorWriteLimit | 3fb878fe6e | assertion fail: prior write-limit flag remains true | pass |
| OwnedTextParserDifferentialTest.streamOwnershipMatchesTikaForOrdinaryOverrideAndCsvFailure | 3fb878fe6e | pass | pass |

The first ownership fixture used an unterminated CSV field, which Tika accepts without throwing;
its failure-path assertion correctly rejected that fixture. The replacement injects a downstream
SAX failure while emitting the first CSV cell and retains explicit failure and closure assertions.
The valid before comparison uses that replacement; no assertion was weakened.

Before commands (all sanitized and serialized through `js-gradle-slot ./gradlew ... -PskipWebBuild=true`):

- `:modules:indexer-worker:test --tests '*SplitSurrogate*'` in `before`: exit 1, 8 tests,
  1 expected repaired-content assertion failure; restart preservation passes.
- `:modules:worker-services:test` selecting both flat-limit methods, the fresh-record method and
  the first ownership fixture in `before-review`: exit 1, 4 assertion failures. The three flat
  failures are valid before evidence; the ownership fixture failure is excluded.
- `:modules:worker-services:test --tests '*OwnedTextParserDifferentialTest.streamOwnershipMatchesTikaForOrdinaryOverrideAndCsvFailure'`
  in `before-review`: exit 0 with the corrected ownership fixture.

Candidate acceptance commands (the same sanitized wrapper, one Gradle invocation at a time):

- `:modules:worker-services:test --tests 'io.justsearch.indexerworker.extract.*'`: exit 0,
  BUILD SUCCESSFUL in 1m 30s; XML reports 562 tests, 0 failures/errors and 6 skips. All 20
  SplitSurrogateExtractionTest methods and all 8 OwnedTextParserDifferentialTest methods pass,
  with no skips in either class.
- `:modules:indexer-worker:test --tests '*SplitSurrogate*'`: exit 0,
  BUILD SUCCESSFUL in 31s; 8 tests, 0 failures/errors/skips. Both corrected restart methods pass.
- `:modules:worker-services:pmdMain :modules:worker-services:pmdTest
  :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest`: exit 0,
  BUILD SUCCESSFUL in 19s; all four PMD tasks executed.
- `git diff --check`: exit 0. `.agents/preflight` is absent.

The six extraction-suite skips are three platform-specific RecordedTesseractExecutableTest
methods, optionalLocalPreparationTiming, and the TikaProductionOwnerCoverageTest and
TikaProbeOwnerIntentTest methods. Skips are not counted as passes.

Each Gradle invocation unsets CI, DISPLAY, JUSTSEARCH_*, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS,
JAVA_OPTS and _JAVA_OPTIONS; GRADLE_OPTS is retained. Shared resources touched: machine-wide
Gradle slots and dependency/build caches, plus ordinary CPU/RAM/disk. Checks use their own
Lucene/SQLite stores, snapshots and child processes. No owner Engine, models, GPU or shared dev
stack was used; no other session's process was stopped. No GitHub access was performed.

Finished 2026-10-28 03:03 UTC; elapsed about 18 minutes including shared-slot waits (estimate was
40–70 minutes, two-hour stop was not reached). Candidate changes remain uncommitted. Real Engine
and performance verification remain outside this builder assignment. No run-evidence.mjs was run
and no evidence files were edited. This record supersedes the initial fixture blocker above.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18081-908d-7380-92e7-b4d2041ce378",
  "verdict": "accept",
  "digest": "sha256:85a98beae907407ad99693904da509e44e41148c9a1a3e30c1ee36dd20700cc4"
}
```
