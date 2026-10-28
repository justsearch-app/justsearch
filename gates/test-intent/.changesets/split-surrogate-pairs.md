---
schema: test-intent.v1
task: t-20261026-8f2d61
author-role: builder
author-session: uq-split
---

# Test-intent entries

Incomplete builder candidate: do not accept the stored-state checks until their fixture is resolved.
Both stored-state methods fail on baseline and candidate at the unchanged-admission assertion.
After two fixture attempts, inspection found that the synthetic parent declares a chunk revision
but omits CONTENT_SHA256. ChunkSetCompleteness.assess requires the declaration revision to equal
that parent hash. No third fixture repair was attempted under the builder's stop rule.
The before-state is the shared clone of e5d7d12f980767c1b88a84e3918de6e9059bb57b in the session temporary folder.
Only test sources, compile-classpath/lock scaffolding, and a test-only owned-parser stub were added there;
its production text read loop and graph remain Tika's baseline implementation. The stub supplies the old
4096-unit loop for the direct short-read checks. No production files were reverted in the working tree.
The flat helper child uses the production ContentExtractor and real sandbox frames; it does not force
the shipped child's structured extractor into its fallback. Real Engine and performance checks are
reserved for independent verification. No evidence generator was run or evidence file edited.

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
      "behaviour": "Keep supplementary characters intact at decoded offsets 4095, 8191 and 20479 across the listed encodings, both extraction implementations and the real child route, with a Lucene query depending on the character. Cover adjacent pairs, every 4096 boundary in a 100 KB file, EOF pairs, arbitrary decoder short reads, Markdown/JSON/Python/JavaScript, pair-safe plain/Markdown previews, flat limits in-process and in a framed helper child, and owned parser routing identity. The queued restart/force check is incomplete: its unchanged-admission assertion fails on the candidate before it reaches the repaired fields. It cannot yet substantiate stored-state repair.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "The extraction, preview, limit, parser-identity and stored-state checks named below.",
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
      "behaviour": "Preserve exact SAX events and metadata (apart from parser identity) across the fixed encoding/text/CSV/TSV/override corpus, malformed bytes/reader surrogates and control filtering, whole supplementary CSV cells, HTML/XML/Java/C++/Groovy/PDF/Office, zero-embedded inline mail and sequential/concurrent shared graph parsing. Artifact and structured/child caps already cut safely before pairs on baseline and are preservation checks. The legacy queued restart check currently fails unchanged-admission on the candidate and remains unverified.",
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

## Builder execution record

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

## Acceptance records
