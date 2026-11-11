---
schema: test-intent.v1
task: t-20261110-pdfwords
author-role: builder
author-session: 01a1c8b1-0856-70b1-9a55-4e71b880d94d
---

# Test-intent entries

PDF extraction reads text without following structure tags, preserves line breaks within
PDF text elements, and rejoins line-end hyphen splits before lowercase continuations,
including separately on each OCR page. Non-PDF text expectations remain unchanged.
Generated default and configured extraction policies advance from v9 to v10 so libraries
re-extract once. Policy-id pins change expected outcomes and therefore use `New`, as in
redesign-c10-page-edges; they are not oracle-preserving adaptations.

PdfWordBoundaryTest.java is an isolated new test source, confirmed exempt by the gate;
its expectations and execution remain for the independent verifier to check.
The owner quote below delegates work; each concrete choice is labelled as an agent choice.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java"
      ],
      "class": "New",
      "behaviour": "Advance generated null/blank fallback, default, null-worker and configured policy identity expectations from v9 to v10. Preserve all limits and explicit caller-owned identity assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance generated null/blank fallback, default, null-worker and configured policy identity expectations from v9 to v10. Preserve all limits and explicit caller-owned identity assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance generated default/configured identity expectations and current-generation comments from v9 to v10. Keep deterministic default-equivalent and nonpositive fallback identities, distinct configured chars/bytes, numeric limits, office cap, clamp and factory independence assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance generated default/configured identity expectations and current-generation comments from v9 to v10. Keep deterministic default-equivalent and nonpositive fallback identities, distinct configured chars/bytes, numeric limits, office cap, clamp and factory independence assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only generated default, null/blank fallback and configured policy identity expectations from v9 to v10. Preserve all UTF-16 text, metadata, status and truncation expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only generated default, null/blank fallback and configured policy identity expectations from v9 to v10. Preserve all UTF-16 text, metadata, status and truncation expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only expected default/configured artifact identities from v9 to v10 across real child reuse/replacement and Hebrew formats/lengths. Preserve exact text, status, child PID, spawn/restart and all non-policy expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only expected default/configured artifact identities from v9 to v10 across real child reuse/replacement and Hebrew formats/lengths. Preserve exact text, status, child PID, spawn/restart and all non-policy expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only the expected child artifact identity from tika-default-v9 to tika-default-v10. Preserve every encoding attribution, exact structured/flat/policy/child text, SUCCESS_FULL and child spawn/restart expectation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only the expected child artifact identity from tika-default-v9 to tika-default-v10. Preserve every encoding attribution, exact structured/flat/policy/child text, SUCCESS_FULL and child spawn/restart expectation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only the in-process current policy expectation from tika-default-v9 to tika-default-v10. Preserve empty-child-command acceptance solely for IN_PROCESS and rejection for PROCESS/AUTO, with all routing expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only the in-process current policy expectation from tika-default-v9 to tika-default-v10. Preserve empty-child-command acceptance solely for IN_PROCESS and rejection for PROCESS/AUTO, with all routing expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only the real extraction-child artifact identity expectation from tika-default-v9 to tika-default-v10. Preserve the child content assertion and every protocol, security, lifecycle and request-budget expectation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only the real extraction-child artifact identity expectation from tika-default-v9 to tika-default-v10. Preserve the child content assertion and every protocol, security, lifecycle and request-budget expectation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only current fresh/indexed/artifact/successful-ledger identities and the current-policy unchanged-family seed from v9 to v10. Historical v5 documents and ledger events keep v5. Preserve text, metadata, source hash, stable UID, chunk revisions, family reuse and restart expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only current fresh/indexed/artifact/successful-ledger identities and the current-policy unchanged-family seed from v9 to v10. Historical v5 documents and ledger events keep v5. Preserve text, metadata, source hash, stable UID, chunk revisions, family reuse and restart expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only the expected newly extracted parent identity from tika-default-v9 to tika-default-v10. Preserve older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced/ordinary work and reopen expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only the expected newly extracted parent identity from tika-default-v9 to tika-default-v10. Preserve older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced/ordinary work and reopen expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only the typed format-capability oracle policyId from tika-default-v9 to tika-default-v10. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v10; Java/JSON projections must still agree.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only the typed format-capability oracle policyId from tika-default-v9 to tika-default-v10. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v10; Java/JSON projections must still agree.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
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
      "behaviour": "Advance only format-capability JSON policyId expectations from tika-default-v9 to tika-default-v10. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v10; Java/JSON projections must still agree.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only format-capability JSON policyId expectations from tika-default-v9 to tika-default-v10. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v10; Java/JSON projections must still agree.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CaseDistinctPageEdgesQueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only current default/configured maintenance target policy expectations from v9 to v10. Historical v8 policies remain v8. Preserve case-distinct PDF edges, repeated-edge removal, source identity, successful-history provenance, queued repair, complete-family reuse and reopen assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only current default/configured maintenance target policy expectations from v9 to v10. Historical v8 policies remain v8. Preserve case-distinct PDF edges, repeated-edge removal, source identity, successful-history provenance, queued repair, complete-family reuse and reopen assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CaseDistinctPageEdgesQueuedRestartTest.java#maintenanceRepairsSuccessfulOldPolicyAndReusesCompleteFamilyAfterReopen"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only expected default/configured child artifact identities from v9 to v10. Preserve six physical PDF pages, exact case-distinct headers/footers, removal of exact repeated edges, direct/child text equality, structured parser identity, SUCCESS_FULL, no truncation and actual child reuse without restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Advance only expected default/configured child artifact identities from v9 to v10. Preserve six physical PDF pages, exact case-distinct headers/footers, removal of exact repeated edges, direct/child text equality, structured parser identity, SUCCESS_FULL, no truncation and actual child reuse without restart.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-policy.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java#caseDistinctPdfEdgesSurviveRealStructuredParserAndReusedChild"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java"
      ],
      "class": "New",
      "behaviour": "Join PDF OCR line-end splits separately within each page when a hyphen is followed across LF or CRLF (with surrounding whitespace) by a lowercase letter, including the Unicode hyphen in Anträge. Keep E- followed by uppercase Mail split, and never join Ab- at one page end to schluss at the next page start. Count the normalized stored text for the exact cap and a cap three characters shorter; preserve page markers/order, both covered pages and two processed pages.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Join PDF OCR line-end splits separately within each page when a hyphen is followed across LF or CRLF (with surrounding whitespace) by a lowercase letter, including the Unicode hyphen in Anträge. Keep E- followed by uppercase Mail split, and never join Ab- at one page end to schluss at the next page start. Count the normalized stored text for the exact cap and a cap three characters shorter; preserve page markers/order, both covered pages and two processed pages.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/pdf-word-boundaries-ocr.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#rejoinsHyphenatedOcrPerPageBeforeCappingAndCoverage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "Follow the private parse-context method rename from parseContextWithMarkedPdfContent to parseContextWithPdfText in the reflection probe. Keep the same twenty invocations, child isolation, process/window observations, timeout, OS conditions and zero-launch/zero-window assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed autonomously with designing implementation and your remaining work",
          "delegationLocation": "task t-20261110-pdfwords agreement",
          "delegationScope": "Design and implement the agreed fix for PDF reading and the remaining work, including its extraction-policy generation and checks.",
          "choice": "Follow the private parse-context method rename from parseContextWithMarkedPdfContent to parseContextWithPdfText in the reflection probe. Keep the same twenty invocations, child isolation, process/window observations, timeout, OS conditions and zero-launch/zero-window assertions.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Evidence commands

Run from the real repository checkout. The policy run overlays only the two policy-id
fixtures onto the before-state, retaining their text expectations and the old production
policy. Both runs require the repository git metadata to create and remove the script's
before-state worktree. Evidence is written only by run-evidence.mjs.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/pdf-word-boundaries-policy.json \
  --before 9868861448 \
  --overlay modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java \
  --overlay modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesCurrentGeneration' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseCurrentGeneration' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultEquivalentLimitsKeepDefaultIdentity' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#differingLimitsGetADistinctIdentity' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#nonPositiveLimitsFallBack' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultsRemainStable' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsCurrentGeneration' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankOrNullPolicyIdentityFallsBackToCurrentGeneration' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsCurrentGeneration' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childReuseAndReplacementKeepUtf8TextAndPolicyIdentity' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childExtractsAllHebrewFormatsAndLengths' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstTextAcrossFormatsAndRoutes' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java#modeSwitchSelectsTheSandbox' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildExtractsFileContent' \
  --check 'modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart' \
  --check 'modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart' \
  --check 'modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#newlyIndexedAsciiFirstUtf8SurvivesRestart' \
  --check 'modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart' \
  --check 'modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle' \
  --check 'modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CaseDistinctPageEdgesQueuedRestartTest.java#maintenanceRepairsSuccessfulOldPolicyAndReusesCompleteFamilyAfterReopen' \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java#caseDistinctPdfEdgesSurviveRealStructuredParserAndReusedChild'
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/pdf-word-boundaries-ocr.json \
  --before 9868861448 \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#rejoinsHyphenatedOcrPerPageBeforeCappingAndCoverage'
```

## Acceptance records
