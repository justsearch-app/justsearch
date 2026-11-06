---
schema: test-intent.v1
task: redesign-e4d
author-role: builder
author-session: redesign-e4d-builder
---

# Test-intent entries

Source for all scenario IDs below: **task redesign-e4d request**, adopted S1–S19
(the adopted list supersedes the earlier numbering). D1 and the saved-value contract
separate permission to execute from the generation's representation identity.

Superseded explicitly: SpladeConfigTest's tempdoc 374 alpha.17 R4 path-auto-enable
expectation, and the dev isolated-model test's expectation that discovery injects
JUSTSEARCH_SPLADE_ENABLED=true. Decision quoted: "Installed model files, the default
sparse-provider string ("splade"), an enabled MCP/agent service and one-off
REST/MCP/custom-pipeline SPLADE requests are not demand and never latch enablement."
S3 further says "installed files or explicit model paths alone do not permit it."

No scheduler policy, stored status vocabulary, inference retry policy, download list,
search fallback policy or generation identity is changed. Existing sparse postings,
payloads, statuses, retries, model paths and saved token counts remain owned by their
existing writers. The runtime-only token counter has producer lifetime, is loaded
before indexing starts, and cannot supply a query encoder (including IDF).

Follow-ups retained from the brief: E4e (omit the download / first-enable identity),
agent mode-string parsing, old chunks omitted from enrollment, and BGE-M3 completion
flags. None is claimed repaired here. Live chunk enrollment and querying retain their
current gates; changing a chunk flag does not create a missing inference role.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeConfigTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S3",
          "quote": "Parent true permits SPLADE; unset parent plus chunk true permits it; parent false overrides chunk true; neither permits nothing; installed files or explicit model paths alone do not permit it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4d request",
          "quote": "existing expectations that encode the old auto-enable (for example SpladeConfigTest's explicit-path auto-enable cases) are replaced deliberately and classed as superseded in the changeset with the decision quoted."
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none overriding the task. Superseded: the tempdoc 374 alpha.17 R4 explicit-path auto-enable oracle and its explanatory comment. Adopted D1: Installed model files and explicit model paths are not demand. Explicit disable and all other config oracles are preserved."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DevLauncherSpladeOptInTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInTokenCountTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SpladeOptInStatusTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOptInMcpHintsTest.java"
      ],
      "class": "New",
      "behaviour": "SPLADE inference requires persistent explicit demand; a generation binding is retained but never supplies permission. Inactive roles reserve no GPU memory and appear neither requested nor missing. A separately owned tokenizer supplies immediate ordinary-ingestion counts and retires with its producer. Serving applicability suppresses pending-work hints without falsifying coverage. The launcher never supplies permission and documentation explains next-start opt-in. Existing fixture now keeps the mocked client for the new MCP case; prior assertions are unchanged. Chunk applicability captures permission from the serving service snapshot and distinguishes unavailable requested work from intentionally disabled work; BGE provider presence retains its existing independent chunk applicability.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S1",
          "quote": "With a normal SPLADE-bound installation, files present and both switches unset, create no SPLADE session or inference provider; sparse lanes receive no turns; primary indexing and enabled dense/NER work complete; AUTO/HYBRID remain usable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S3",
          "quote": "Parent true permits SPLADE; unset parent plus chunk true permits it; parent false overrides chunk true; neither permits nothing; installed files or explicit model paths alone do not permit it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S4",
          "quote": "REST, MCP and agent custom-pipeline requests, installed files, the default sparse-provider string, and enabled agent/MCP services never enable or latch background SPLADE work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S7",
          "quote": "Chunk true with no global veto enables parent SPLADE and existing enrolled PENDING chunks; global false prevents both separate sparse lanes from running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S10",
          "quote": "If BGE initialization fails, separate SPLADE fallback runs only when permitted.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S12",
          "quote": "With permission unchanged off, initial composition, installation completion, service reload, recovery and generation-owner replacement do not recreate SPLADE inference.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S13",
          "quote": "Once other runnable work settles, disabled parent/chunk SPLADE does not block UI/global/per-folder completion, operating IDLE, default readiness or MCP search/list hints.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S15",
          "quote": "With a usable selected tokenizer, ordinary ingestion while off produces the same non-truncated parent/chunk token counts and classification as a ready on-runtime, including immediate ingestion and threshold-adjacent/CJK documents.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S17",
          "quote": "On CPU and available GPU, disabled SPLADE creates no SPLADE session, performs no SPLADE inference and incurs no sparse-lane charges.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S18",
          "quote": "Document the environment variable/system property, precedence, chunk interaction, retained data, parent backfill and next-start application; distinguish installation from execution.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S19",
          "quote": "scripts/dev/dev-runner.cjs no longer injects JUSTSEARCH_SPLADE_ENABLED=true on discovering model files; an explicit ambient value still passes through.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S15",
          "quote": "Missing/corrupt tokenizer uses the existing nonfatal estimate; saved values and VDU\u2019s estimate behavior are preserved. Check tokenizer cleanup across failure, shutdown and owner replacement.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4d-repair.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#installedFilesAndDefaultProviderDoNotRequestInference",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#explicitPathAloneDoesNotRequestInference",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#chunkDemandPermitsParentFromTheSuppliedSnapshot",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#defaultBoundGenerationCreatesNoSparseRoleAcrossRecomposition",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#globalVetoDisablesBothSparseLanesEvenWithChunkDemand",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#discoveredAssetsWithoutDemandHaveZeroGpuReservation",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#bgeFailureCannotRequestUnpermittedSeparateSplade",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInTokenCountTest.java#immediateOrdinaryIngestionUsesSelectedFullTokenizerWhileInferenceIsOff",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DevLauncherSpladeOptInTest.java#modelDiscoveryDoesNotInjectDemandOrOverrideAmbientValues",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DevLauncherSpladeOptInTest.java#documentationExplainsInstalledButOptInExecution",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SpladeOptInStatusTest.java#disabledServingRuntimeMakesChunkWorkInapplicableDespiteLiveDemand",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java#alternateStatusRetainsDisabledApplicabilityAndRealCoverage",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java#disabledSpladeDoesNotHintAtPendingWorkOrLatchDemand",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOptInMcpHintsTest.java#disabledSparseCoverageDoesNotAdvertisePendingEnrichment"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeOptInResumeTest.java"
      ],
      "class": "New",
      "behaviour": "Existing backfill resumes PENDING parents across reopen, reuses completed and completed-empty states, retains terminal failures, resets successful retry counts according to the existing rule and exhausts failing retries according to their existing budget. Only the controlled successful corpus claims full coverage.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S5",
          "quote": "New parents remain PENDING; unrelated metadata and dense/NER writes preserve sparse artifacts/status/retries; content revisions invalidate stale sparse artifacts; deletion removes parents/chunks without resurrection.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S6",
          "quote": "On a generation binding the same usable SPLADE model, enable and restart; PENDING parents resume, completed and completed-empty rows are reused, and existing retry budgets/terminal failures remain intact.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4d-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeOptInResumeTest.java"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The three-role physical-cap fixture now explicitly sets justsearch.splade.enabled=true, restored by its existing property owner. Previously its model path implicitly enabled the SPLADE role. Physical caps, role sets, warm-up, retirement, rollback, persistence and timeout assertions are unchanged. The implicit path-auto-enable precondition is superseded by D1/S3; no expectation is weakened.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4d",
          "scenario": "S3",
          "quote": "Parent true permits SPLADE; unset parent plus chunk true permits it; parent false overrides chunk true; neither permits nothing; installed files or explicit model paths alone do not permit it.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Scenario coverage

| Scenario | Committed checks / remaining real-system work |
| --- | --- |
| S1 | SpladeOptInTest; SpladeOptInCompositionTest; FairEnrichmentSchedulerTest. Real AUTO/HYBRID and primary-stage timing: orchestrator. |
| S2 | permissionChangeKeepsCommittedGenerationParityAfterReopen; GenerationModelSelectionTest; sparse preservation classes. Full E4b install restart: orchestrator. |
| S3 | SpladeOptInTest; globalVetoDisablesBothSparseLanesEvenWithChunkDemand; superseded SpladeConfigTest. |
| S4 | disabledSpladeDoesNotHintAtPendingWorkOrLatchDemand; discovery/composition absence. REST/agent real-system check: orchestrator. |
| S5 | SparsePayloadPreservationTest; SparseRmwPreservationTest; SourceEnvelopeSparsePreservationTest; EnrichmentPreservationTest; token-count ingestion stays PENDING. VDU/deletion end-to-end: module suites and orchestrator. |
| S6 | SpladeOptInResumeTest; generation path/identity checks. Enable-and-restart real installation: orchestrator. |
| S7 | SpladeOptInTest; SpladeOptInStatusTest; FairEnrichmentSchedulerTest; SpladePendingSelectionTest; SpladeBackfillOpsTest. |
| S8 | existing WorkerSearchServiceSpladeOnlyFallbackTest and SpladeOnlyBoundaryFallbackTest; real mixed pipeline and agent comparison: orchestrator. |
| S9 | existing SpladeIdfQueryEncoderTest, SearchExecutorChunkSpladeFlagTest, generation parity tests; EncoderSet still requires a live SPLADE wrapper for IDF. |
| S10 | bgeFailureCannotRequestUnpermittedSeparateSplade; FairEnrichmentSchedulerTest BGE case; BgeM3BackfillOpsTest. Existing completion flags deliberately unchanged. |
| S11 | FairEnrichmentSchedulerTest (whole class, including parameterized cases), existing provider/persistence checks. |
| S12 | defaultBoundGenerationCreatesNoSparseRoleAcrossRecomposition; requested/missing-role and estimate checks. Full service/recovery/install lifecycle: orchestrator. |
| S13 | SpladeOptInStatusTest; WorkerStatusCacheContextTest addition; MCP boundary addition. UI/default readiness real-system check: orchestrator. |
| S14 | readiness.py coverage helper unchanged; real query fallback is checked separately. The helper cannot establish query availability. |
| S15 | SpladeOptInTokenCountTest: immediate ingestion, threshold-adjacent and CJK text, missing/corrupt files, producer shutdown; same selected tokenizer in a ready wrapper. Saved counts and chunk copies stay with existing writers. Full replacement/failure lifecycle: orchestrator. |
| S16 | download/completeness code unchanged; existing installation suite and generation identity checks. Real Install AI completion: orchestrator. |
| S17 | CPU/CUDA-policy recomposition creates no separate role, handles or policy; zero reservation; scheduler provider-absence preservation. CPU/GPU timings: orchestrator. |
| S18 | documentationExplainsInstalledButOptInExecution; help, environment reference, ownership matrix and throughput guidance. |
| S19 | modelDiscoveryDoesNotInjectDemandOrOverrideAmbientValues executes the real launcher function in an isolated Node VM; isolated-model script suite. |

## Verification commands

The orchestrator runs these after the builder's commit. No evidence JSON is authored
by the builder. Repair selectors are plain method names; parameterized/display-named
preservation checks select their whole classes. Run Gradle through the slot wrapper
with DISPLAY and WAYLAND_DISPLAY unset, one background command at a time.

```sh
node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4d-repair.json --before 911102bd9f \
  --check modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#installedFilesAndDefaultProviderDoNotRequestInference \
  --check modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#explicitPathAloneDoesNotRequestInference \
  --check modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#chunkDemandPermitsParentFromTheSuppliedSnapshot \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#defaultBoundGenerationCreatesNoSparseRoleAcrossRecomposition \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#globalVetoDisablesBothSparseLanesEvenWithChunkDemand \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#discoveredAssetsWithoutDemandHaveZeroGpuReservation \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#bgeFailureCannotRequestUnpermittedSeparateSplade \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInTokenCountTest.java#immediateOrdinaryIngestionUsesSelectedFullTokenizerWhileInferenceIsOff \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DevLauncherSpladeOptInTest.java#modelDiscoveryDoesNotInjectDemandOrOverrideAmbientValues \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DevLauncherSpladeOptInTest.java#documentationExplainsInstalledButOptInExecution \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SpladeOptInStatusTest.java#disabledServingRuntimeMakesChunkWorkInapplicableDespiteLiveDemand \
  --check modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusCacheContextTest.java#alternateStatusRetainsDisabledApplicabilityAndRealCoverage \
  --check modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java#disabledSpladeDoesNotHintAtPendingWorkOrLatchDemand \
  --check modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOptInMcpHintsTest.java#disabledSparseCoverageDoesNotAdvertisePendingEnrichment

node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4d-preservation.json \
  --check modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#explicitParentTrueAndFalseKeepTheirMeaning \
  --check modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeOptInTest.java#resolvedSourcePrecedenceKeepsAnExplicitGlobalVeto \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#permissionDoesNotChangeSelectedIdentityOrPath \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInCompositionTest.java#permissionChangeKeepsCommittedGenerationParityAfterReopen \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInTokenCountTest.java#corruptTokenizerKeepsTheNonfatalEstimate \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInTokenCountTest.java#missingTokenizerKeepsTheNonfatalEstimate \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/SpladeOptInTokenCountTest.java#unavailableTokenizerNativeLibraryKeepsTheNonfatalEstimate \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeOptInResumeTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationModelSelectionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOpsTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparsePayloadPreservationTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SourceEnvelopeSparsePreservationTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java \
  --check modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOptInMcpHintsTest.java#runnableSparseAndDenseBacklogsKeepTheirHints \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SpladeOptInStatusTest.java#bgeSparseProviderKeepsExistingChunkApplicabilityOutsideTheGlobalVeto \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SpladeOptInStatusTest.java#permittedButUnavailableSparseWorkKeepsItsExistingChunkBacklog \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SpladeOptInStatusTest.java#unavailableBgeSelectionKeepsItsApplicabilityAcrossServingSuccessors \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java

```

The superseded SpladeConfigTest class is run in both trees as a whole because its
methods have display names. Its one changed oracle fails on the base; the other
expectations pass in both trees.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1ae94-d4b6-7690-8035-cf490bc84f3b",
  "verdict": "accept",
  "digest": "sha256:53fb7fdf8cd25d662e8d1c3d59132cfbfec7f75cf73298de5170be9562ba43c8",
  "note": "Entry 1 Obsolete accept; entry 2 New repair accept; entry 3 New preservation accept; entry 4 Adaptation accept. Transcribed from the verifier's result by the orchestrator."
}
```
