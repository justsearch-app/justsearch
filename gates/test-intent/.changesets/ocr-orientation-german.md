---
schema: test-intent.v1
task: t-20261111-readfollow
author-role: builder
author-session: 01a1cc1e-7372-7991-b60c-4e31c3a985ca
---

# Test-intent entries

OCR uses Tesseract page segmentation mode 1 when orientation data is available and
mode 6 otherwise. JPEG EXIF orientation is read without decoding; values 2-8 send a
derived upright image to OCR, with the original used when the tag is absent or
image decoding fails. Implicit languages are German then English, filtering missing
default data and recording that evidence; explicit languages retain all-or-nothing
availability. Runtime packaging pins, stages and names German and orientation data.
Generated default and configured extraction policies advance from v10 to v11 so
libraries re-extract once.

Policy-id pins are `New`, following the rules and pdf-word-boundaries precedent:
they change expected outcomes and are not oracle-preserving adaptations. All
non-policy text and provenance expectations remain unchanged.

ExifImageOrientationTest, OcrDefaultLanguageTest and PdfOcrOrientationTest are
isolated new test sources, confirmed exempt by the gate. They need no entries or
separate acceptance records; the independent verifier still checks their sources,
execution, before-state repair failures, input dependencies and global-state isolation.
The new upright-page assertions compare all words in order, allowing paragraph blank
lines from mode 1; that is an orchestrator choice within the delegation, not owner words.
The gen-notices.test.mjs addition is a script self-test outside gate scope; it checks
that the generated notice names German, English and orientation tessdata_fast data.

The verbatim owner quote delegates work. Every concrete choice below is labelled
as an agent choice, not as an owner-adopted scenario.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java"
      ],
      "class": "New",
      "behaviour": "Advance generated null/blank fallback, default, null-worker and configured policy identity expectations from v10 to v11. Preserve all limits and explicit caller-owned identity assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance generated null/blank fallback, default, null-worker and configured policy identity expectations from v10 to v11. Preserve all limits and explicit caller-owned identity assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance generated default/configured identity expectations and current-generation comments from v10 to v11. Keep deterministic default-equivalent and nonpositive fallback identities, distinct configured chars/bytes, numeric limits, office cap, clamp and factory independence assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance generated default/configured identity expectations and current-generation comments from v10 to v11. Keep deterministic default-equivalent and nonpositive fallback identities, distinct configured chars/bytes, numeric limits, office cap, clamp and factory independence assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only generated default, null/blank fallback and configured policy identity expectations from v10 to v11. Preserve all UTF-16 text, metadata, status and truncation expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only generated default, null/blank fallback and configured policy identity expectations from v10 to v11. Preserve all UTF-16 text, metadata, status and truncation expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only expected default/configured artifact identities from v10 to v11 across real child reuse/replacement and Hebrew formats/lengths. Preserve exact text, status, child PID, spawn/restart and all non-policy expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only expected default/configured artifact identities from v10 to v11 across real child reuse/replacement and Hebrew formats/lengths. Preserve exact text, status, child PID, spawn/restart and all non-policy expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only the expected child artifact identity from tika-default-v10 to tika-default-v11. Preserve every encoding attribution, exact structured/flat/policy/child text, SUCCESS_FULL and child spawn/restart expectation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only the expected child artifact identity from tika-default-v10 to tika-default-v11. Preserve every encoding attribution, exact structured/flat/policy/child text, SUCCESS_FULL and child spawn/restart expectation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only the in-process current policy expectation from tika-default-v10 to tika-default-v11. Preserve empty-child-command acceptance solely for IN_PROCESS and rejection for PROCESS/AUTO, with all routing expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only the in-process current policy expectation from tika-default-v10 to tika-default-v11. Preserve empty-child-command acceptance solely for IN_PROCESS and rejection for PROCESS/AUTO, with all routing expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only the real extraction-child artifact identity expectation from tika-default-v10 to tika-default-v11. Preserve the child content assertion and every protocol, security, lifecycle and request-budget expectation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only the real extraction-child artifact identity expectation from tika-default-v10 to tika-default-v11. Preserve the child content assertion and every protocol, security, lifecycle and request-budget expectation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only current fresh/indexed/artifact/successful-ledger identities and the current-policy unchanged-family seed from v10 to v11. Historical v5 documents and ledger events keep v5. Preserve text, metadata, source hash, stable UID, chunk revisions, family reuse and restart expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only current fresh/indexed/artifact/successful-ledger identities and the current-policy unchanged-family seed from v10 to v11. Historical v5 documents and ledger events keep v5. Preserve text, metadata, source hash, stable UID, chunk revisions, family reuse and restart expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only the expected newly extracted parent identity from tika-default-v10 to tika-default-v11. Preserve older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced/ordinary work and reopen expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only the expected newly extracted parent identity from tika-default-v10 to tika-default-v11. Preserve older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced/ordinary work and reopen expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only the typed format-capability oracle policyId from tika-default-v10 to tika-default-v11. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v11; Java/JSON projections must still agree.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only the typed format-capability oracle policyId from tika-default-v10 to tika-default-v11. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v11; Java/JSON projections must still agree.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only format-capability JSON policyId expectations from tika-default-v10 to tika-default-v11. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v11; Java/JSON projections must still agree.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only format-capability JSON policyId expectations from tika-default-v10 to tika-default-v11. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v11; Java/JSON projections must still agree.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only current default/configured maintenance target policy expectations from v10 to v11. Historical v8 policies remain v8. Preserve case-distinct PDF edges, repeated-edge removal, source identity, successful-history provenance, queued repair, complete-family reuse and reopen assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only current default/configured maintenance target policy expectations from v10 to v11. Historical v8 policies remain v8. Preserve case-distinct PDF edges, repeated-edge removal, source identity, successful-history provenance, queued repair, complete-family reuse and reopen assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Advance only expected default/configured child artifact identities from v10 to v11. Preserve six physical PDF pages, exact case-distinct headers/footers, removal of exact repeated edges, direct/child text equality, structured parser identity, SUCCESS_FULL, no truncation and actual child reuse without restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Advance only expected default/configured child artifact identities from v10 to v11. Preserve six physical PDF pages, exact case-distinct headers/footers, removal of exact repeated edges, direct/child text equality, structured parser identity, SUCCESS_FULL, no truncation and actual child reuse without restart.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-policy.json",
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
      "behaviour": "Add a harness check that a fake Tesseract --list-langs probe exits successfully, reports eng, and leaves its working directory empty. The existing fake executable answers --version and --list-langs without OCR output, rejects an invocation missing its output-base argument, and writes OCR output only to the supplied test-owned output path. Keep all existing OCR text, page, cap, coverage, failure, routing and lifecycle assertions. The removed Windows string branch was unreachable after the existing early NativeTesseractFixture return; that return is preserved. This is a new test-harness obligation, not a repaired production behaviour pin: overlaying the complete candidate test file also overlays its repaired fake executable, so the before-state can pass this harness check.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Keep the existing PdfOcrEngineTest scenarios isolated when runtime language discovery probes the fake Tesseract executable: answer language and version probes without creating OCR files, and add an explicit check of the fake language-probe contract.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ocr-orientation-german-fixture.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#fakeTesseractLanguageProbeDoesNotWriteOcrOutput"
      ]
    },
    {
      "items": [
        "modules/ui/build.gradle.kts"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Download, stage and verify every pinned Tesseract tessdata entry, including German and orientation data, instead of staging only English.",
        "callersAndStateOwners": "downloadTesseractRuntimeArtifacts, stageTesseractRuntime and verifyTesseractRuntime own packaging files below the module build directory. The added local name variables are filenames scoped to packaging loops. The test-selection use it.name at line 145 is a Gradle Test task property and cannot read those locals.",
        "indirectBehaviours": "Windows package staging now includes the manifest-declared language data and validates the manifest requiredLanguages list. Existing Test tasks, suite selection, filters, JVM arguments and classpaths remain unchanged.",
        "evidence": "Compare the base and candidate Gradle script outside the Tesseract packaging section (downloadTesseractRuntimeArtifacts through verifyTesseractRuntime): byte-identical. Within that section the new local name variables are used only as downloaded/staged filenames; the entire test task configuration preceding that section is identical. The gate reports +1368 declares name, which test configuration reads, conflating these different lexical scopes.",
        "unresolvedRisks": "Windows runtime staging and execution require separate platform verification; this incidental entry does not claim they were run on Linux."
      },
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow agreement",
          "delegationScope": "Proceed with the OCR orientation and German-default follow-ups after considering their value and cost, including the extraction-policy generation change that makes existing libraries re-extract once.",
          "choice": "Stage and validate the pinned German, English and orientation data from the Windows Tesseract runtime manifest; retain the existing test execution configuration.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Evidence commands

Run from the candidate checkout. These commands use the script to generate evidence;
no evidence content is hand-written. The policy run overlays both fixture projections
so its real extraction check sees the new v11 oracle on the v10 before-state.
The fixture run also requests the before-state, but its new harness-only assertion
may pass there because the script transplants the complete fake implementation.
That is not fail-before proof of a production repair, and its entry does not claim one.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY -u TESSDATA_PREFIX js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --before 07769e24e2 \
  --out gates/test-intent/evidence/ocr-orientation-german-policy.json \
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
env -u DISPLAY -u WAYLAND_DISPLAY -u TESSDATA_PREFIX js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --before 07769e24e2 \
  --out gates/test-intent/evidence/ocr-orientation-german-fixture.json \
  --check 'modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfOcrEngineTest.java#fakeTesseractLanguageProbeDoesNotWriteOcrOutput'
```

A bounded admission probe for two representative policy checks is:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY -u TESSDATA_PREFIX js-gradle-slot -w 10 node scripts/governance/gates/test-intent/run-evidence.mjs \
  --before 07769e24e2 \
  --out gates/test-intent/evidence/ocr-orientation-german-policy.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesCurrentGeneration \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseCurrentGeneration
```

## Acceptance records
