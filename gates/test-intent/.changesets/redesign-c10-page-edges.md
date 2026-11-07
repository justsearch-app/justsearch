---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: codex-c10-page-edges-builder-20261107
---

# Test-intent entries

Draft for independent acceptance. All evidence paths below are explicitly PENDING; no execution
receipt or acceptance verdict is claimed by this file. The builder raw logs/XML and exact Before
overlay inventory live in ignored tmp/c10-evidence. The independent evidence owner must generate
the named JSON receipts using the repository runner and exact committed candidate; no frozen
historical evidence or quality provenance is rewritten.

Every existing changed test file has a precise current expectation entry. Literal v9 policy
requirements are new expectations, not adaptations. New methods that pin case retention must fail
exact Before a687aaf2537fae7465b8d851be9ee12be485980b for lost text; policy identity methods must
fail Before for actual v8 identity. Preservation methods and unchanged old assertions pass both.
The explicit custom-ID preservation check and format Java/JSON projection check must pass both.
No removed/skipped test, changed cap, tolerance or test selection is part of this change.

The isolated new CaseDistinctPageEdgesQueuedRestartTest additionally supplies narrow persisted
legacy-fixture support: unchanged PDF source, actual old v8 index and successful ledger identities,
real DONE SQLite job, durable identity, non-force maintenance admission, real process parsing,
Lucene parent/child replacement, exact index-backed content/preview/slices and same-policy no-call
reconciliation/reopen. Its synthetic old indexed state does not substitute for independent
actual-old-binary or installed HTTP proof. Its fixture exemption is not behavioral evidence.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexing/src/test/java/io/justsearch/indexing/extraction/StructuredDocumentFidelityTest.java"
      ],
      "class": "New",
      "behaviour": "Retain the exact stripped case identity of first and last page-edge whole elements for paragraph, heading, multi-item list and multi-cell table, including later item/cell differences, three ASCII/Unicode case variants and long common prefixes. Repeated exact variants still disappear independently while minority case spellings and interior copies remain. Keep strip-only comparison, separate header/footer inventories, default two-of-four threshold, element order and physical page breaks. No canonical, dotted-I or internal-space normalization is introduced. Existing long-suffix/ragged-table/limit assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Retain the exact stripped case identity of first and last page-edge whole elements for paragraph, heading, multi-item list and multi-cell table, including later item/cell differences, three ASCII/Unicode case variants and long common prefixes. Repeated exact variants still disappear independently while minority case spellings and interior copies remain. Keep strip-only comparison, separate header/footer inventories, default two-of-four threshold, element order and physical page breaks. No canonical, dotted-I or internal-space normalization is introduced. Existing long-suffix/ragged-table/limit assertions remain unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-page-edges.json",
      "newChecks": [
        "modules/indexing/src/test/java/io/justsearch/indexing/extraction/StructuredDocumentFidelityTest.java#caseDistinctPageEdgesSurviveForEveryWholeElementKind",
        "modules/indexing/src/test/java/io/justsearch/indexing/extraction/StructuredDocumentFidelityTest.java#repeatedVariantDoesNotRemoveMinorityCaseSpelling",
        "modules/indexing/src/test/java/io/justsearch/indexing/extraction/StructuredDocumentFidelityTest.java#edgeComparisonOnlyStripsAndKeepsHeaderFooterInventoriesSeparate"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "A six-page real PDF retains three differently cased header/footer variants, each below the existing threshold, while three exactly repeated header/footer controls disappear. Direct structured extraction must report six physical pages, excluding flat fallback as evidence. Two real persistent-child requests under default and configured built-in policies preserve the literal same output and parser identity, return SUCCESS_FULL without clipping and reuse the same live child PID without restart. The current generated IDs are tika-default-v9 and tika-config-v9-100000-1000000. Existing PDF long-suffix and other format assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "A six-page real PDF retains three differently cased header/footer variants, each below the existing threshold, while three exactly repeated header/footer controls disappear. Direct structured extraction must report six physical pages, excluding flat fallback as evidence. Two real persistent-child requests under default and configured built-in policies preserve the literal same output and parser identity, return SUCCESS_FULL without clipping and reuse the same live child PID without restart. The current generated IDs are tika-default-v9 and tika-config-v9-100000-1000000. Existing PDF long-suffix and other format assertions remain unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-page-edges.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java#caseDistinctPdfEdgesSurviveRealStructuredParserAndReusedChild"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java"
      ],
      "class": "New",
      "behaviour": "Advance null/blank fallback, defaults, null config and configured factories to generated v9 identities, preserving current limit values. New explicitCustomIdentityRemainsCallerOwned preservation check pins exact trimmed caller-owned tika-default-v8 rather than automatically versioning arbitrary explicit IDs.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance null/blank fallback, defaults, null config and configured factories to generated v9 identities, preserving current limit values. New explicitCustomIdentityRemainsCallerOwned preservation check pins exact trimmed caller-owned tika-default-v8 rather than automatically versioning arbitrary explicit IDs.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseCurrentGeneration"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only literal current generated policy identity expectations and explanatory current-generation comments from default/config v8 to v9. Equal/default/nonpositive limits still identify the deterministic default; configured limits still identify distinct stable chars/bytes; office cap, clamp, factory independence and all numeric expectations remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only literal current generated policy identity expectations and explanatory current-generation comments from default/config v8 to v9. Equal/default/nonpositive limits still identify the deterministic default; configured limits still identify distinct stable chars/bytes; office cap, clamp, factory independence and all numeric expectations remain unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultEquivalentLimitsKeepDefaultIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#differingLimitsGetADistinctIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#nonPositiveLimitsFallBack",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultsRemainStable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only current generated default/null-blank/configured identity assertions from v8 to v9, and rename these three previously stale V5 method names to CurrentGeneration. All UTF-16/UTF-8 decoding, fallback, exact text and limit preservation oracles remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only current generated default/null-blank/configured identity assertions from v8 to v9, and rename these three previously stale V5 method names to CurrentGeneration. All UTF-16/UTF-8 decoding, fallback, exact text and limit preservation oracles remain unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankOrNullPolicyIdentityFallsBackToCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsCurrentGeneration"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only actual current default/configured child policy assertions from v8 to v9, including policy sent across persistent-child reuse and replacement. Preserve all literal Hebrew text, status, format/length, child PID/spawn/restart and fallback assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only actual current default/configured child policy assertions from v8 to v9, including policy sent across persistent-child reuse and replacement. Preserve all literal Hebrew text, status, format/length, child PID/spawn/restart and fallback assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childReuseAndReplacementKeepUtf8TextAndPolicyIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childExtractsAllHebrewFormatsAndLengths"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected actual child extraction policy from tika-default-v8 to tika-default-v9. Every parser encoding attribution, exact structured/flat/policy/child text, SUCCESS_FULL and persistent-child spawn/restart assertion remains unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only the expected actual child extraction policy from tika-default-v8 to tika-default-v9. Every parser encoding attribution, exact structured/flat/policy/child text, SUCCESS_FULL and persistent-child spawn/restart assertion remains unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstTextAcrossFormatsAndRoutes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the current in-process extraction policy expectation from tika-default-v8 to tika-default-v9. Preserve empty child command acceptance solely for IN_PROCESS and rejection for PROCESS/AUTO, and all other routing assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only the current in-process extraction policy expectation from tika-default-v8 to tika-default-v9. Preserve empty child command acceptance solely for IN_PROCESS and rejection for PROCESS/AUTO, and all other routing assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java#modeSwitchSelectsTheSandbox"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the current real extraction-child artifact policy expectation from tika-default-v8 to tika-default-v9. Keep actual child file-content assertion, protocol validation, security, lifecycle and request-budget checks unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only the current real extraction-child artifact policy expectation from tika-default-v8 to tika-default-v9. Keep actual child file-content assertion, protocol validation, security, lifecycle and request-budget checks unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildExtractsFileContent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only current fresh/indexed/artifact/successful ledger identities from v8 to v9, including current seed used to prove unchanged-family reuse. Historical mojibake v5 documents and ledger events remain v5; text, metadata, source hash, stable UID, chunk revisions and restart/preservation assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only current fresh/indexed/artifact/successful ledger identities from v8 to v9, including current seed used to prove unchanged-family reuse. Historical mojibake v5 documents and ledger events remain v5; text, metadata, source hash, stable UID, chunk revisions and restart/preservation assertions remain unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#newlyIndexedAsciiFirstUtf8SurvivesRestart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected newly extracted current parent policy from tika-default-v8 to tika-default-v9. Preserve actual older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced/ordinary work and reopen assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only the expected newly extracted current parent policy from tika-default-v8 to tika-default-v9. Preserve actual older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced/ordinary work and reopen assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "class": "New",
      "behaviour": "Advance only current owned format-capability policyId expectations from tika-default-v8 to tika-default-v9. Keep every recipe byte hash, literal annotated text, required/absent marker, MIME/parser identity, embedded counts, structure counts and known-gap classification unchanged. productionExtractorMatchesVersionedCapabilityOracle must observe the v9 artifact; checkedJsonProjectionMatchesEveryFieldOfTheTypedJavaOracle preserves Java/JSON projection agreement on both arms.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only current owned format-capability policyId expectations from tika-default-v8 to tika-default-v9. Keep every recipe byte hash, literal annotated text, required/absent marker, MIME/parser identity, embedded counts, structure counts and known-gap classification unchanged. productionExtractorMatchesVersionedCapabilityOracle must observe the v9 artifact; checkedJsonProjectionMatchesEveryFieldOfTheTypedJavaOracle preserves Java/JSON projection agreement on both arms.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json"
      ],
      "class": "New",
      "behaviour": "Advance only current owned format-capability policyId expectations from tika-default-v8 to tika-default-v9. Keep every recipe byte hash, literal annotated text, required/absent marker, MIME/parser identity, embedded counts, structure counts and known-gap classification unchanged. productionExtractorMatchesVersionedCapabilityOracle must observe the v9 artifact; checkedJsonProjectionMatchesEveryFieldOfTheTypedJavaOracle preserves Java/JSON projection agreement on both arms.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining agreed JustSearch ingestion redesign, including adopted C10 case-sensitive page-edge comparison and built-in effective extraction-policy generation; independent verification and existing stop rules retained.",
          "choice": "Advance only current owned format-capability policyId expectations from tika-default-v8 to tika-default-v9. Keep every recipe byte hash, literal annotated text, required/absent marker, MIME/parser identity, embedded counts, structure counts and known-gap classification unchanged. productionExtractorMatchesVersionedCapabilityOracle must observe the v9 artifact; checkedJsonProjectionMatchesEveryFieldOfTheTypedJavaOracle preserves Java/JSON projection agreement on both arms.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c10-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
