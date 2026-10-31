---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: codex-ingfix-wp4-test-intent-builder-20261030-c336oneu
---

# Test-intent entries

These entries cover only the 20 flagged items in the diff from merge-base
`9eb36cc7923b530624f7ced8ea0bc678f0011b31` to candidate `77077d824c`.
The eight policy-only files share one entry; the two format-capability oracle files
share one entry. The four isolated new files reported as exempt are not added here.

Execution evidence is pending. Every `Defect pin` entry and every `New` entry with
`repaired: true` requires assertion fail-before evidence using `--before
9eb36cc7923b530624f7ced8ea0bc678f0011b31`. The policy-only `New` entry needs passing
candidate execution evidence, without a repair fail-before requirement. The
protocol-signature `Adaptation` keeps its existing oracle.

The format-oracle repair witness needs both covered oracle files passed as
`--overlay` inputs when preparing its before-state. Its JSON/Java projection
consistency check is
`modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#checkedJsonProjectionMatchesEveryFieldOfTheTypedJavaOracle`;
that preservation check must pass on both trees and is not listed as a repair
check. The capture-failure cause observation in `AsciiFirstUtf8PreservationTest`
and the legacy-schema injection in `PersistentExtractionSandboxTest` are
adaptations within files that also contain repair checks; their unchanged
preservation expectations still require independent review.

The two complete-table checks added for the P1/R3 repair are preservation checks: they pass on
the before-state and on the candidate, and are included as `--check` arguments in the same
evidence capture without a fail-before requirement:

- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java#completeBlankBodyTableKeepsBaselineOutput`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java#completeMixedBodyTableKeepsBaselineOutput`

No execution or acceptance is claimed by this changeset. Evidence must be written
by `run-evidence.mjs` at the named path; acceptance belongs to another session.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Change only the current extraction identity oracle from tika-default-v7 / tika-config-v7-<chars>-<bytes> to tika-default-v8 / tika-config-v8-<chars>-<bytes>, including null/blank identity defaults, default-equivalent and configured worker limits, in-process routing, real-child extraction, fresh indexing, forced reindex, restart and new ledger entries. The explicit historical v5 documents and ledger rows remain v5, and their unchanged-work preservation assertions stay intact. All text, metadata, hash, routing, limit and restart expectations in these eight files are unchanged. This is a new versioned identity requirement under P8, not an adaptation claiming that literal expected identities are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P8 Persisted extraction policy identity is bumped for any change in extracted text, so results remain attributable to the policy that produced them.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#oldStoredFieldsAndChunksSurviveRestartWithUnchangedQueuedWork",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#newlyIndexedAsciiFirstUtf8SurvivesRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstTextAcrossFormatsAndRoutes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesVersionSix",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseVersionSix",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java#modeSwitchSelectsTheSandbox",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultEquivalentLimitsKeepDefaultIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#differingLimitsGetADistinctIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#nonPositiveLimitsFallBack",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultsRemainStable",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsV5",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankOrNullPolicyIdentityFallsBackToV5",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsV5",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childReuseAndReplacementKeepUtf8TextAndPolicyIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childExtractsAllHebrewFormatsAndLengths"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OcrComponentOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "childEofClosesTheCachedExtractor submits the same EOF-loop fixture using SandboxExtractionRequest.CURRENT_SCHEMA_VERSION instead of the old literal schema 2. Only the request protocol version follows the current wire signature. The source bytes, OCR-disabled routing, mocked engine, EOF trigger and exact engine-close verification are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. **OCR preservation:** Harvesting changes must preserve output limits, page markers, confidence aggregation, cancellation, actual child cleanup, and pool reuse; these are explicit engine responsibilities; check expiry followed by another document and component close.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Copied and fresh ordinary Metadata detect valid ASCII-first UTF-8 from their own streams, including beside sequential/concurrent prepared roots; pure ASCII still uses windows-1252. Rename embeddedBodiesKeepTodaysText to embeddedBodiesKeepOriginalText and replace the old mojibake/replacement-character assertions with the original UTF-8 text for ZIP, attachment, plain-email and HTML-email bodies through flat and structured extraction. Add repeated extraction of UTF-8, windows-1252 and nested ZIP siblings, requiring each original string and both Hebrew occurrences. The captureReadFailureDeletesSpilledSnapshot diagnostic oracle remains \"capture failed\" and the same snapshot/deletion assertions remain; its observation now follows getCause() through the source-I/O wrapper required by S-D65, rather than reading the wrapper message.",
      "defectSource": {
        "kind": "defect-source",
        "reproduction": "Use 16,384 ASCII characters followed by UTF-8 non-ASCII text with copied/fresh metadata and in the four embedded-body fixtures. Before the repair, ordinary streams choose windows-1252 and the extraction fixtures explicitly accept corrupted suffixes (replacement characters for attachments). Repeat with UTF-8 and legacy siblings, including a nested UTF-8 member; require the original independent strings instead of the previously accepted corruption.",
        "safeguard": "no data loss"
      },
      "reproduction": "Use 16,384 ASCII characters followed by UTF-8 non-ASCII text with copied/fresh metadata and in the four embedded-body fixtures. Before the repair, ordinary streams choose windows-1252 and the extraction fixtures explicitly accept corrupted suffixes (replacement characters for attachments). Repeat with UTF-8 and legacy siblings, including a nested UTF-8 member; require the original independent strings instead of the previously accepted corruption.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D70 (Embedded ASCII-first UTF-8 retains corruption) Require original UTF-8 text in the existing ZIP and email preservation fixtures.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C18. **D70 — Broken:** ASCII-first UTF-8 must survive ZIP members, email attachments, plain email bodies, and HTML email bodies through flat and structured extraction; existing preservation fixtures explicitly assert corruption, including replacement characters for attachments; change those expectations to original text.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C19. **D70 — Implied isolation:** Each embedded resource must use its own bytes and encoding evidence, independently of its container, siblings, previous requests, and concurrent documents; root metadata deliberately drops its fact on copying; check mixed UTF-8/legacy siblings and sequential/concurrent extraction.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. **D70 preservation:** BOMs, supported declarations, UTF-16, pure ASCII, and malformed/legacy tails must retain their applicable precedence and fallback behavior; embedded UTF-8 repair must not blanket-force UTF-8; check declarations and invalid sequences beyond the ASCII window, including split multibyte sequences.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D65 (Sandbox transport erases source-I/O failure identity) Temporarily fail child-side opening and require preserved cause, source-I/O classification and retry eligibility.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#copiedMetadataAndSequentialDocumentsNeverInheritSnapshotFact",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#concurrentDocumentsKeepIndependentFacts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#embeddedBodiesKeepOriginalText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PreservationTest.java#nestedAndSiblingResourcesUseTheirOwnEncodingBytesOnRepeatedExtraction"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java"
      ],
      "class": "New",
      "behaviour": "Add three repaired-behaviour checks: harvest an instant first OCR page when submission of more pages than pool capacity spends the budget, then successfully reuse the pool; retain completed later pages in original order when page zero is slow, including confidence and completed-page count; and preserve page-zero text while reporting truncation and one processed page when page one has a nonzero exit, spawn failure, timeout or execution failure. The budget must stop further OCR submissions and the next one-page document must finish without truncation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D77 (OCR expiry discards already completed pages) Finish page one, exhaust the budget on a later page and require page-one text retained.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D78 (Failed OCR pages can still yield SUCCESS_FULL) Make one of two pages fail and require a partial outcome with visible failed coverage.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C2. **D77 — Broken:** Completed OCR pages must survive budget exhaustion during submission, including when page count exceeds pool capacity; collection currently checks the deadline before harvesting; check an instant first page followed by enough slow pages to exhaust submission time.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. **D77 — Broken:** A slow earlier page must not hide a completed later page when collection expires; the current ordered loop breaks immediately; check slow page one and fast page two, retaining completed text in original page order.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. **D78 — Broken:** Successful OCR on one page plus another page’s nonzero exit, spawn failure, timeout, or execution failure must retain useful text and expose incomplete extraction; current failure aggregation can erase failure or omit truncation; check each failure alongside one success.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C9. **OCR preservation:** Harvesting changes must preserve output limits, page markers, confidence aggregation, cancellation, actual child cleanup, and pool reuse; these are explicit engine responsibilities; check expiry followed by another document and component close.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P5 OCR time budget is still enforced; a document whose budget is spent does not run further OCR pages.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#submissionExpiryRetainsCompletedPagesAndPoolCanBeReused",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#slowFirstPageCannotHideCompletedLaterPages",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#oneFailedPageMakesUsefulOcrPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#laterRenderFailureRetainsCompletedPagesAndPoolCanBeReused"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java"
      ],
      "class": "New",
      "behaviour": "Add a real child -> persistent sandbox -> timebox check for missing-source IO that retains FileSystemException source identity and a NoSuchFileException diagnostic cause, then restores the source and successfully reuses the same child. Add AUTO-isolated image and PDF OCR checks for exact parent success/failure/size-skip counter and duration-sample deltas across requests and child recycling, with no cumulative replay. Update realChildExtractsFileContent policy identity from v7 to v8 under P8. The scripted legacy-schema fault injection now replaces CURRENT_SCHEMA_VERSION rather than literal 2 with schema 1; its malformed response, rejection/retirement expectation and request-correlation checks remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D65 (Sandbox transport erases source-I/O failure identity) Temporarily fail child-side opening and require preserved cause, source-I/O classification and retry eligibility.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C10. **D65 — Broken:** Child-side source opening/read failures must retain diagnostic identity through the real child, parent sandbox, timebox, and `JobBatchExtractor`; generic parser reconstruction currently makes them permanent; check the durable outcome and retry eligibility after a temporary opening failure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C11. **D65 — Implied boundary case:** Source failure must remain identifiable when the parent can read the source again by classification time, including a symlink’s resolved path; `BatchServiceFailures.sourceFailure` examines `FileSystemException` paths and causes; check recovery before parent classification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12. **Sandbox preservation:** Parser failure, budget rejection, shared-service unavailability, timeout, crash, and OOM must keep their distinct retry behavior; valid source-I/O responses must not become protocol failures; check those outcomes plus a subsequent successful request on the persistent child.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D132 (Default isolated OCR loses all OCR metrics) OCR one image through AUTO isolation and require parent OCR counters and duration to advance.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C25. **D132 — Broken:** AUTO-isolated image/PDF OCR must publish the same applicable success, failure, skip-reason, and duration events as in-process extraction; child extraction currently uses a no-op catalog; check the parent registry through the real child.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C26. **D132 — Implied lifetime case:** Metrics must belong to one request, without cumulative replay, duplication, or leakage after child reuse, recycling, or configuration changes; the child caches its extractor; check consecutive requests with different outcomes and compare parent deltas.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P8 Persisted extraction policy identity is bumped for any change in extracted text, so results remain attributable to the policy that produced them.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildSourceIoKeepsCauseAndRemainsReusableAfterSourceRecovery",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#autoOcrMetricsAreRequestLocalAcrossChildReuseAndRecycling",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildExtractsFileContent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "Add real structured PDF extraction of three pages whose headers share 200 h characters and footers share 200 f characters but carry unique HeaderSecret/ FooterSecret page suffixes. Require all six distinct suffixes to survive extraction; existing ordinary DOCX/PDF checks are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D69 (Header/footer detection deletes distinct paragraphs sharing a prefix) Use repeated prefixes with unique page-edge suffixes and require every suffix to survive.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C16. **D69 — Broken:** Distinct page-edge paragraphs sharing 200 characters must retain every unique suffix, for both headers and footers; normalization currently truncates comparison text; check three or more pages through structured extraction.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java#pdfPageEdgesWithLongCommonPrefixesRetainUniqueSuffixes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Pin retained active prefixes for headings, ordered/unordered lists and tables at an exact character boundary and inside a character event; completed table/list members precede the active prefix. Repeated getDocument and later closing/text events must not duplicate or admit text; bounded annotated serialization stays within its limit and reports truncation. Supplementary characters crossing the boundary must not leave a split surrogate in headings, lists, tables or paragraphs, whether the pair arrives in one event or two. Complete uniform-width HTML tables with blank or mixed blank/nonblank body cells retain exact baseline output, including surrounding paragraphs; unrepresented headers are retained only for tables actually cut by the character limit.",
      "defectSource": {
        "kind": "defect-source",
        "reproduction": "Feed a ten-character StructuredContentHandler an open h2, ul, ol or table containing UniqueSecr or UniqueSecretLater, then finalize before closing the structure. Before the repair buffered text is abandoned. Also finish a Done list/table member before the active Unique prefix, and put an emoji after ab under a three-character limit with whole/split SAX events; require retained ordered text, idempotent finalization and no half-surrogate.",
        "safeguard": "no data loss"
      },
      "reproduction": "Feed a ten-character StructuredContentHandler an open h2, ul, ol or table containing UniqueSecr or UniqueSecretLater, then finalize before closing the structure. Before the repair buffered text is abandoned. Also finish a Done list/table member before the active Unique prefix, and put an emoji after ab under a three-character limit with whole/split SAX events; require retained ordered text, idempotent finalization and no half-surrogate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D67 (Character-limit handling abandons buffered structures) Reach a small limit inside a table and require its retained text prefix in output.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C13. **D67 — Broken:** Limits reached inside headings, ordered/unordered lists, and tables must retain previously completed members and the active member’s prefix; closing events and finalization currently abandon their buffers; check each structure at an exact boundary and inside a character event.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C14. **D67 — Implied preservation:** Finalization must emit retained content once, in document order, without admitting later text or exceeding the serialized character budget; check repeated `getDocument`, closing events after the limit, and supplementary characters at the boundary.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P3 The character limit is still enforced: output never exceeds it and truncation is still reported.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java#activeStructureRetainsPrefixAndFinalizesOnce",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java#completedMembersAndActivePrefixSurvive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java#supplementaryCharactersAreNotSplitAtTheBoundary",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java#limitInsideBlankBodyCellKeepsCompletedHeader",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredLimitFidelityTest.java#limitInsideLaterBlankCellKeepsItsHeaderAfterEarlierValue"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "In truncationStillIgnoresLaterPageWrappers, keep the same two-page-wrapper input and limit 5 but require Paragraph(\"Enoug\", 0) instead of Paragraph(\"Enough text\", 0). The one-page count and exclusion of later page wrappers remain unchanged. This corrects an existing oracle that accepted eleven retained characters under a five-character budget.",
      "defectSource": {
        "kind": "defect-source",
        "reproduction": "Parse <div class='page'>Enough text</div><div class='page'>Later</div> with maxChars=5. Before the repair the first characters event retains the entire Enough text paragraph; the new assertion requires only its first five characters while still ignoring page two.",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P3 The character limit is still enforced: output never exceeds it and truncation is still reported.",
          "label": "agent choice"
        }
      },
      "reproduction": "Parse <div class='page'>Enough text</div><div class='page'>Later</div> with maxChars=5. Before the repair the first characters event retains the entire Enough text paragraph; the new assertion requires only its first five characters while still ignoring page two.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P3 The character limit is still enforced: output never exceeds it and truncation is still reported.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C14. **D67 — Implied preservation:** Finalization must emit retained content once, in document order, without admitting later text or exceeding the serialized character budget; check repeated `getDocument`, closing events after the limit, and supplementary characters at the boundary.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java#truncationStillIgnoresLaterPageWrappers"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Rename asciiEmptyAndCutFirstMultibyteSequenceKeepDefault to asciiAndIncompleteSequencesKeepDefaultButCompleteStreamUsesUtf8. Empty/pure-ASCII input and the incomplete stream cut exactly at WINDOW still use the default. A complete valid UTF-8 stream whose first multibyte sequence straddles WINDOW must now detect UTF-8 instead of defaulting; the two/three/four-byte sequence cases are retained.",
      "defectSource": {
        "kind": "defect-source",
        "reproduction": "Place the first multibyte character across the 16,384-byte detector window, followed by valid Hebrew UTF-8. Before the repair the complete stream defaults despite its valid non-ASCII bytes. Require UTF-8 for that complete stream and keep the default assertion for Arrays.copyOf(bytes, WINDOW), which really ends with an incomplete sequence.",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. **D70 preservation:** BOMs, supported declarations, UTF-16, pure ASCII, and malformed/legacy tails must retain their applicable precedence and fallback behavior; embedded UTF-8 repair must not blanket-force UTF-8; check declarations and invalid sequences beyond the ASCII window, including split multibyte sequences.",
          "label": "agent choice"
        }
      },
      "reproduction": "Place the first multibyte character across the 16,384-byte detector window, followed by valid Hebrew UTF-8. Before the repair the complete stream defaults despite its valid non-ASCII bytes. Require UTF-8 for that complete stream and keep the default assertion for Arrays.copyOf(bytes, WINDOW), which really ends with an incomplete sequence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D70 (Embedded ASCII-first UTF-8 retains corruption) Require original UTF-8 text in the existing ZIP and email preservation fixtures.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. **D70 preservation:** BOMs, supported declarations, UTF-16, pure ASCII, and malformed/legacy tails must retain their applicable precedence and fallback behavior; embedded UTF-8 repair must not blanket-force UTF-8; check declarations and invalid sequences beyond the ASCII window, including split multibyte sequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WindowEncodingDetectorTest.java#asciiAndIncompleteSequencesKeepDefaultButCompleteStreamUsesUtf8"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java"
      ],
      "class": "New",
      "behaviour": "Add direct-path and symlink cases that remove an existing source only during real PROCESS extraction, restore it before JobBatchExtractor classifies the exception, and require markClaimFailed with IO_FAILED, RETRY_WITH_BACKOFF and NoSuchFileException diagnostics. The observed failure must still identify the submitted/resolved source after recovery; an ordinary parser ExtractionException must not be classified as source IO.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D65 (Sandbox transport erases source-I/O failure identity) Temporarily fail child-side opening and require preserved cause, source-I/O classification and retry eligibility.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C10. **D65 — Broken:** Child-side source opening/read failures must retain diagnostic identity through the real child, parent sandbox, timebox, and `JobBatchExtractor`; generic parser reconstruction currently makes them permanent; check the durable outcome and retry eligibility after a temporary opening failure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C11. **D65 — Implied boundary case:** Source failure must remain identifiable when the parent can read the source again by classification time, including a symlink’s resolved path; `BatchServiceFailures.sourceFailure` examines `FileSystemException` paths and causes; check recovery before parent classification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12. **Sandbox preservation:** Parser failure, budget rejection, shared-service unavailability, timeout, crash, and OOM must keep their distinct retry behavior; valid source-I/O responses must not become protocol failures; check those outcomes plus a subsequent successful request on the persistent child.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P4 Sandbox isolation stays in force: a hung or crashing parser in the child is still killed at the deadline, and parse failures are still classified as parser failures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/JobBatchExtractorForcedPathTest.java#recoveredChildSourceIoReachesRetryableQueueOutcome"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java"
      ],
      "class": "New",
      "behaviour": "Add equivalent persisted VDU pending/baseline-text demand for empty TIFF extraction under .tif, .tiff, .TIF and .TIFF. Add high-confidence otherwise-sufficient full-PDF OCR cases where either one page lacks readable text or contentTruncated is true: document construction must persist pending baseline-text fallback demand. Keep the new cases bounded by the existing fallback-tier budget: an already-exhausted budget still returns VDU not needed. Existing complete high-confidence, mixed/selective and low-confidence routing checks remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D7 (`.tif` files cannot reach VDU fallback) Require equivalent fallback routing for identical `.tif` and `.tiff` inputs.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C24. **D7 — Broken:** Identical `.tif` and `.tiff` scans, including uppercase extensions, must produce equivalent fallback decisions after baseline/OCR dropout; OCR admits both but VDU omits `.tif`; check extraction through persisted VDU demand fields.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D79 (Partial OCR falsely claims every page readable) Produce high-confidence partial OCR and require missing-page coverage and fallback demand. [Scope per the challenger's correction: the false coverage summary is confirmed. Suppression of VDU applies to otherwise sufficient **full-PDF OCR** without another visual-demand signal. Mixed/selective OCR explicitly requests VDU. Moreover, correcting the missing-page summary alone would not change the current OCR routing branch, which ignores that field and truncation.]",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C6. **D79 — Broken:** High-confidence partial full-PDF OCR must request fallback even without low confidence, tables, or an existing mixed-PDF signal; `VisualRoutingDecision` ignores missing pages and truncation in its OCR branch; check generated evidence through document construction.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C8. **OCR preservation:** Complete high-confidence OCR must continue avoiding unnecessary VDU, while mixed/selective and low-confidence signals remain effective and fallback-tier limits still apply; check existing routing cases alongside the new partial cases.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java#tiffAliasesPersistEquivalentFallbackDemand",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecisionTest.java#highConfidencePartialFullPdfOcrPersistsFallbackDemand"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json",
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "class": "Defect pin",
      "behaviour": "Keep the typed format-capability oracle and its checked JSON projection in agreement. For XLSX_MERGED_HEADERS only, restore the nonempty later-row cells Q2 and 20 to exactAnnotatedText: Q2 follows Quarterly revenue = Q1 and 20 follows the marker row. This is the ragged-table cell-retention obligation, not a snapshot inferred from current output. All format policyId fields and DEFAULT_POLICY advance from tika-default-v7 to tika-default-v8 under P8. Recipe IDs, hashes, MIME types, markers, element counts, classifications and failure classes are kept. Projection consistency is a preservation check, not the fail-before witness.",
      "defectSource": {
        "kind": "defect-source",
        "reproduction": "Extract the existing XLSX_MERGED_HEADERS deterministic fixture through StructuredContentExtractor and compare it to the repaired typed oracle via Windows1252ExtractionTest.enN2OfficeAndContainerTextStayUnchanged. Before the repair the narrow first row bounds serialization and drops the Q2 and 20 cells present in wider rows. Overlay both oracle files onto the before-state so that the unchanged consumer asserts the retained cells; its failure must be the missing-cell text mismatch, not merely the policy-version mismatch. Verify the JSON projection separately against the same typed oracle on both trees.",
        "safeguard": "no data loss"
      },
      "reproduction": "Extract the existing XLSX_MERGED_HEADERS deterministic fixture through StructuredContentExtractor and compare it to the repaired typed oracle via Windows1252ExtractionTest.enN2OfficeAndContainerTextStayUnchanged. Before the repair the narrow first row bounds serialization and drops the Q2 and 20 cells present in wider rows. Overlay both oracle files onto the before-state so that the unchanged consumer asserts the retained cells; its failure must be the missing-cell text mismatch, not merely the policy-version mismatch. Verify the JSON projection separately against the same typed oracle on both trees.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D68 (First-row table width drops later cells) Serialize `[\"Report\"]` followed by `[\"Name\",\"UniqueSecret\"]` and require the secret retained.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C1. **D68 — Broken:** A narrow first row followed by wider or ragged rows must retain every nonempty later cell; `StructuredDocument.appendTable` currently bounds iteration by header width; check title rows, empty headers, and widths that grow across several rows.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P8 Persisted extraction policy identity is bumped for any change in extracted text, so results remain attributable to the policy that produced them.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp4.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Windows1252ExtractionTest.java#enN2OfficeAndContainerTextStayUnchanged"
      ]
    }
  ]
}
```

## Acceptance records
