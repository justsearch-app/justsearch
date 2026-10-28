---
schema: test-intent.v1
task: t-20261028-b4a569
author-role: builder
author-session: 01a180aa-46f4-7dd1-9138-6d7ab1db20c4
---

# Test-intent entries

Entry 1 covers repaired behavior; entry 2 covers preservation; entry 3 covers policy identities.

The builder was explicitly instructed not to run `run-evidence.mjs`. The evidence paths below
are reserved for the independent verifier; no evidence file or acceptance has been fabricated.
Builder before/after Gradle logs and XML reports are in the isolated temporary checkouts,
with results reported in the handback. Formal evidence and acceptance remain the verifier's work.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "H1 and H3: Hebrew UTF-8 text is preserved through all text formats, readers, structured fallback, mail, archive attachment, actual reused/replaced child and native-word search; strict UTF-8 validates complete sequences and continuing window prefixes, with distinct detector attribution. A forced reindex repairs affected stored text and its derived fields/chunks durably.",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "Encode the literal Hebrew prose in Utf8WindowEncodingDetectorTest.universalDetectorReproducesHebrewGb18030Defect as BOM-less UTF-8. Tika DefaultEncodingDetector returns GB18030 and production extraction corrupts the source text."
      },
      "reproduction": "Encode the literal Hebrew prose in Utf8WindowEncodingDetectorTest.universalDetectorReproducesHebrewGb18030Defect as BOM-less UTF-8. Tika DefaultEncodingDetector returns GB18030 and production extraction corrupts the source text.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Text extraction correctness and preservation while repairing misdecoded valid UTF-8.",
          "choice": "Prefer strict valid non-ASCII UTF-8 in the first 16,384 bytes after existing HTML declarations and BOM-less UTF-16; keep the existing chain on rejection.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf8-window-first-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#completeMultibyteCodePointSelectsUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#hebrewAtAllLengthsSelectsUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#continuingWindowMayCutAnyMultibyteSequence",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#shortReadsKeepCompleteAndCutWindowResults",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#malformedCutPrefixRejectsButLookaheadIsNotPartOfWindow",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#hebrewAcrossFormatsAndReaders",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#structuredFailureUsesFlatUtf8Fallback",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childReuseAndReplacementKeepUtf8TextAndPolicyIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childExtractsAllHebrewFormatsAndLengths",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#htmlTitleAndBodyKeepHebrew",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#csvSniffingAndReadingKeepUtf8CellsAndCharset",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#inlineMailBodiesAndArchiveAttachmentKeepHebrew",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#hebrewNativeWordFindsIndexedDocument",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8EncodingPreservationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "P1-P7 preservation: nine other scripts at short/~300B/~2KB/~20KB lengths and text formats; eleven legacy prose samples at two lengths keep charset/text; declarations and BOMs, BOM-less UTF-16 attribution, ASCII/empty, invalid UTF-8 and reset preserve the old path. Existing stored text/hash/preview/title/MIME charset/language/token count/quality/policy/chunks survive reopening, queued unchanged work and a further restart. Existing extraction suite covers MIME routing, PDF/Office/XML. P8 timing is delegated to the verifier, with no timing tests added. The original 40-byte short Hebrew sample already reads correctly on main and is retained as preservation; repaired short cases use a confirmed 44-byte GB18030 reproduction.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Text extraction correctness and preservation while repairing misdecoded valid UTF-8.",
          "choice": "Preserve today's encoding, declaration, routing and old stored-state behavior outside valid non-ASCII UTF-8 windows, as settled in P1-P7.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf8-window-first-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#universalDetectorReproducesHebrewGb18030Defect",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#asciiEmptyAndCutFirstMultibyteSequenceKeepDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#invalidUtf8RestoresStreamAndKeepsDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#utf8ReadProblemRestoresStreamAndKeepsDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#declarationsAndBomlessUtf16KeepPrecedenceAndAttribution",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#nonHtmlMetadataDoesNotChangeTodaysChain",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8EncodingPreservationTest.java#otherUtf8ScriptsKeepCharsetAndText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8EncodingPreservationTest.java#legacyProseKeepsTodaysCharsetAndExactText",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#oldStoredFieldsAndChunksSurviveRestartWithUnchangedQueuedWork",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8EncodingPreservationTest.java#previouslyReadableShortHebrewKeepsText"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "The extraction policy version advances to v5 in default, blank/null/empty fallback and configured-limits identities; literal policy-id expectations and format-capability provenance fixtures advance with it. Existing policy-only files retain every other expectation; entry 1 accounts for the new extraction checks. A real reused and replaced child returns both the default and configured v5 identities and validates against the parent policy.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Text extraction correctness and preservation while repairing misdecoded valid UTF-8.",
          "choice": "Move deterministic extraction policy identities from v4 to v5 for default, blank/null fallback and configured limits because valid UTF-8 is now preferred. Parent and sandbox child must agree; old stored policy identities stay untouched.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/utf8-window-first-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsV5",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankOrNullPolicyIdentityFallsBackToV5",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsV5",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childReuseAndReplacementKeepUtf8TextAndPolicyIdentity"
      ]
    }
  ]
}
```

## Builder execution results

Baseline: `a0022c79f129101c06e47db2dc4c6c3bc69602a7`, checked out in a shared clone under
the assigned temporary folder. Only candidate tests/test fixtures were overlaid there;
its production sources remained unchanged. Candidate changes are uncommitted.

All Gradle commands used `js-gradle-slot ./gradlew ... -PskipWebBuild=true`, one invocation
at a time, with CI, DISPLAY, JUSTSEARCH_* and the requested Java option variables unset;
GRADLE_OPTS was retained. No `.agents/preflight` exists in this checkout.

| Check | Before | After |
| --- | --- | --- |
| `Utf8WithoutBomExtractionTest` | All 52 cases failed by assertion: Hebrew mojibake, missing native-word hit, GB18030 instead of UTF-8, or v4 instead of v5 for the two policy transport cases | All 52 passed |
| `Utf8WindowEncodingDetectorTest` | All 14 repair cases failed by assertion (charset or new-step attribution); all 6 preservation cases passed | All 20 passed |
| `Utf8EncodingPreservationTest` | All 21 passed | All 21 passed |
| `Utf8QueuedRestartTest` | Unchanged queued restart passed; forced reindex failed at literal Hebrew content assertion | Both passed |
| Default, blank/null/empty fallback and configured identity checks in `Utf16WithoutBomExtractionTest` | All 3 methods failed by assertion, v4 versus v5 | All 3 passed |
| Other existing policy expectations/format-capability fixtures | All changed expectations failed by assertion, v4 versus v5 | Passed in the full extraction suite |
| Full `:modules:worker-services:test --tests 'io.justsearch.indexerworker.extract.*'` | Broad baseline run had only the expected repair/policy assertion failures; the final focused baseline run above verified the corrected short fixture | 627 discovered: 621 passed, 6 existing skips, no failures/errors |
| `:modules:indexer-worker:test --tests 'io.justsearch.indexerworker.loop.Utf8QueuedRestartTest'` | Expected forced-reindex assertion failure as above | 2 passed, no skips |
| `:modules:worker-services:pmdMain :modules:worker-services:pmdTest :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest` | Not required/run | All ran, zero violations; worker-services pmdTest reran after the last test edit |
| `git diff --check` | — | Passed |

The final focused baseline command was `:modules:worker-services:test --tests
'io.justsearch.indexerworker.extract.Utf8*' --continue`; all 66 repaired detector/extraction
cases failed by assertion and all 27 preservation cases passed. Including the queued-restart
checks, all 95 new cases passed on the candidate without skips.

The six existing skips are Windows-only Tesseract checks (five discovered items) and the
opt-in preparation timing check. They are not counted as passes. P8 performance measurements,
formal run-evidence files and independent acceptance remain the verifier's work, as instructed.

The historical Hebrew fixture was absent. The replacement literal is 222 UTF-8 bytes and
reproduces GB18030 through Tika's default detector. The short repaired fixture is 44 bytes;
the initially selected 40-byte sample already worked on main and is retained as preservation.

Shared resources touched: Gradle cache and machine-wide slot/queue locks. Each checkout had
its own build directories, tests used temporary indices/queues/files and their own child processes.
Builder execution window: 2026-10-28 04:00:12 UTC to 2026-10-28 04:55:42 UTC, including shared-slot waits.

## Acceptance records
