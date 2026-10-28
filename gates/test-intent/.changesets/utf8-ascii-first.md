---
schema: test-intent.v1
task: t-20261028-21b100
author-role: builder
author-session: 01a18171-9ecc-75f1-bf6c-9a1aa90607e5
---

# ASCII-first UTF-8 test intent

Evidence paths are reserved for the independent verifier. The builder does not run run-evidence.mjs or write D4 evidence by hand.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8CaptureTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "R1: literal text and parser charset across six formats, five language/script tails and exact 16,384/16,385/65,536/1,048,576-byte ASCII prefixes; structured, flat, policy and real child routes, structured-failure fallback, native-word search and early character cap. R2: explicit zero-based 16 KiB first-sequence edges. R3: state across 8,192-byte reads, three-byte short reads and spill. R4: new correct content/preview/title/frontmatter/status/policy survives restart; forcing a v5 mojibake fixture with its real content SHA repairs text and chunk revision without changing source SHA or historical ledger. I1: v6 default, null/blank fallback and configured identity. P5a repair: unfinished or malformed XML-like prefixes keep the whole-snapshot UTF-8 decision; complete declarations naming UTF-8 or its Java alias utf8 also read as UTF-8, through HTML and plain-text parsers. Reuse one persistent sandbox for the original format/prefix/language cases, asserting one child and no restarts alongside every per-document content/status/policy assertion.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "On main 9faa90b111, 150 of 150 probed cases (Hebrew, Russian, Chinese, emoji, accented Latin × txt/md/csv/json/py/HTML × ASCII prefixes 16,383 B–1 MiB) were mojibake: the 16,384-byte window cannot see subsequent valid UTF-8 and Tika chooses Windows-1252. A window ending inside the first multi-byte sequence also falls back."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "Validate strict UTF-8 during complete top-level snapshot capture, carrying sequence state across reads and spill and rejecting invalid or incomplete suffixes. Carry the valid non-ASCII fact in a private root-only Metadata field. Consult it only for ASCII or valid first-sequence prefixes at the 16 KiB window; preserve declarations, BOMs, BOM-less UTF-16, completed UTF-8 windows and embedded decoding. Keep stored old text unchanged until explicit reindex and bump default, blank/null and configured identities from v5 to v6.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf8-ascii-first-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8CaptureTest.java#firstSequenceAcrossWindowEdgeSelectsUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8CaptureTest.java#captureCarriesSequencesAcrossReadsAndSpill",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8CaptureTest.java#snapshotAcceptsUtf8ScalarBoundaries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstTextAcrossFormatsAndRoutes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstStructuredFailureUsesFlatFallback",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#wholeSnapshotSelectsUtf8EvenWhenCharacterCapStopsBeforeNonAscii",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstNativeWordFindsIndexedDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesVersionSix",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseVersionSix",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#newlyIndexedAsciiFirstUtf8SurvivesRestart",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#unfinishedXmlPrefixUsesSnapshotUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#malformedXmlDeclarationUsesSnapshotUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#completeUtf8XmlDeclarationUsesSnapshotUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#concurrentDocumentsKeepIndependentFacts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "P1–P7/P9: literal pre-change text and charset for ASCII and legacy suffixes, malformed and EOF-truncated suffixes beyond the window and character cap, valid windows despite invalid later bytes, previously complete first-window sequences, declarations/aliases/BOMs/BOM-less UTF-16, embedded ZIP/mail attachment/inline bodies, copied metadata, sequential/concurrent documents, non-enumerable carrier, parser object identity and spill cleanup after capture read failure. Old v5 stored fields, chunks and ledger remain identical across restart and unchanged queued work. Existing #997 and preparation/limit tests are rerun unchanged except the separate policy-id edits. P8 is left to independent measurement. P5a preservation: complete ISO-8859-1 and windows-1252 declarations before ASCII-first UTF-8 bodies keep literal Windows-1252 mojibake and charset in HTML and plain text. Concurrent valid roots must explicitly detect UTF-8 while invalid roots detect Windows-1252; fresh metadata keeps the old decision.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "Validate strict UTF-8 during complete top-level snapshot capture, carrying sequence state across reads and spill and rejecting invalid or incomplete suffixes. Carry the valid non-ASCII fact in a private root-only Metadata field. Consult it only for ASCII or valid first-sequence prefixes at the 16 KiB window; preserve declarations, BOMs, BOM-less UTF-16, completed UTF-8 windows and embedded decoding. Keep stored old text unchanged until explicit reindex and bump default, blank/null and configured identities from v5 to v6.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf8-ascii-first-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#pureAsciiKeepsTextAndCharset",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#legacySuffixKeepsLiteralTextAndCharset",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#invalidSnapshotSuffixKeepsDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#validWindowStillWinsOverInvalidSnapshotSuffix",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#firstCompleteSequenceAndEarlyCaptureBoundaryKeepUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#copiedMetadataAndSequentialDocumentsNeverInheritSnapshotFact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#embeddedBodiesKeepTodaysText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#rootCarrierDoesNotDisplaceDeclarationsOrBoms",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#completedUtf8WindowKeepsTodaysXmlDeclarationOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#parserPassesRootMetadataUncopied",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#captureReadFailureDeletesSpilledSnapshot",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#oldStoredFieldsAndChunksSurviveRestartWithUnchangedQueuedWork",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#completeLegacyXmlDeclarationKeepsTodaysOutcome"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json",
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "class": "New",
      "behaviour": "Change only current-policy literals from tika-default-v5/tika-config-v5 to v6 in existing extraction/routing/sandbox/worker-limit/restart assertions and the format-capability fixture. Content, routing, limits and preservation expectations remain unchanged. New AsciiFirstUtf8PolicyTest checks default, configured, null and blank forms and AsciiFirstUtf8ExtractionTest checks real child agreement.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "Validate strict UTF-8 during complete top-level snapshot capture, carrying sequence state across reads and spill and rejecting invalid or incomplete suffixes. Carry the valid non-ASCII fact in a private root-only Metadata field. Consult it only for ASCII or valid first-sequence prefixes at the 16 KiB window; preserve declarations, BOMs, BOM-less UTF-16, completed UTF-8 windows and embedded decoding. Keep stored old text unchanged until explicit reindex and bump default, blank/null and configured identities from v5 to v6.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf8-ascii-first-policy-id.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesVersionSix",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseVersionSix"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
