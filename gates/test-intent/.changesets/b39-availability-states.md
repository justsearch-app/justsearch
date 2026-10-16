---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b39-availability-states-20261009
---

# Explicit embedding availability

Existing behaviour assertions are retained. Fixture changes encode the same intended model presence/readiness in the new interface.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RagRetrievalBudgetInProcessTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsCoverageTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsLiteralPassageTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsDiversifyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsHybridCollectionScopeTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagRetrievalCauseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchLegCauseContractTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceMatchCitationsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedStartTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagBudgetTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRagLoadingObservedTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Provider fakes implement availability instead of the removed abstract boolean. Query mocks return the same ready state as their boolean fixtures. Loading placeholders explicitly say STARTING, including the compatible cold-publication fixture. All old test methods, assertions, deadlines, model latches and inference/wait observations are kept. Non-query isAvailable stubs are unchanged. Build cleanup uses unnamed lifetime-only resources in RagRetrievalCauseTest, SearchLegCauseContractTest, WorkerSearchServiceRagBudgetTest, WorkerSearchServiceSemanticBudgetTest; removes redundant qualification in WorkerSearchServiceMatchCitationsTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Give EmbeddingProvider an explicit availability state that names at least: ready; starting or loading (transient, a model is installed and will become ready); absent (no model configured or installed, permanent); failed (load failed, with its cause).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Four provider states each exercise HYBRID, SPLADE, AUTO, VECTOR and RAG through real WorkerSearchService and a temporary Lucene index. READY/STARTING after CPU semantic expiry return the budget reason/contended, keyword hits/BM25 or typed vector deadline. ABSENT preserves existing no-service outcomes. A timely FAILED provider preserves the unavailable-service outcomes. Every case observes zero inference, and queued cases zero latch access. The exact named preservation checks pass on a600f71b6 and after.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Behaviour must stay exactly as a600f71b6 specifies: queue expiry with a starting provider gives the FP3 outcome (contended); a request that reaches its seat in time and then waits for loading keeps cause loading; an absent provider keeps its existing outcome; a failed provider gets the existing outcome for an unavailable service with its cause.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b39-behaviour.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java#readyAcrossAllModesPreservesQueueExpiry",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java#startingAcrossAllModesPreservesQueueExpiry",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java#absentAcrossAllModesPreservesAbsence",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java#timelyFailedAcrossAllModesPreservesUnavailableOutcome"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "A failed provider after queued semantic expiry returns the existing unavailability outcomes. Aggregate assertions exercise all five modes on both revisions: HYBRID, AUTO, VECTOR and RAG incorrectly classify the failed provider as queue contention on a600f71b6; SPLADE without an encoder preserves no-service. The aggregate check fails before by assertion and passes after, with no waits or inference. Original load failure retention is checked separately at source.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Behaviour must stay exactly as a600f71b6 specifies: queue expiry with a starting provider gives the FP3 outcome (contended); a request that reaches its seat in time and then waits for loading keeps cause loading; an absent provider keeps its existing outcome; a failed provider gets the existing outcome for an unavailable service with its cause.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b39-behaviour.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java#failedAcrossAllModesKeepsUnavailableOutcome"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootEmbeddingFailureTest.java"
      ],
      "class": "New",
      "behaviour": "Real InferenceCompositionRoot captures the exact thrown assembly cause, including suppressed cleanup failure, and carries it through query-role marking and index/query partition. The exact check compiles on the before-state and fails by assertion because the load cause is discarded, then passes after. Existing ownership checks are unchanged. A malformed installed-model manifest also retains its original capture exception instead of only its text, and publishes that exact cause.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Give EmbeddingProvider an explicit availability state that names at least: ready; starting or loading (transient, a model is installed and will become ready); absent (no model configured or installed, permanent); failed (load failed, with its cause).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b39-behaviour.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootEmbeddingFailureTest.java#embeddingFailureRetainsOriginalCause",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootEmbeddingFailureTest.java#inputCaptureFailureRetainsOriginalCause"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Real KnowledgeServer boot with deferred initialization held exposes STARTING for an installed model; composition failure later publishes FAILED and the original cause. An uninstalled model stays ABSENT. Checks use a fake native assembly and no GPU or dev stack. Installed cold/failure checks compile before and fail assertions on the old absent-only projection; absent control passes both revisions. Cold presence uses the captured boot configuration even when the process-wide compatibility fingerprint cache says absent; the test injects that stale-cache condition explicitly.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Give EmbeddingProvider an explicit availability state that names at least: ready; starting or loading (transient, a model is installed and will become ready); absent (no model configured or installed, permanent); failed (load failed, with its cause).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b39-behaviour.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerAvailabilityTest.java#installedModelIsStartingBeforePublication",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerAvailabilityTest.java#failedLoadIsPublishedWithOriginalCause"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation control: a real KnowledgeServer boot with no configured or installed embedding model stays permanently absent, before and after the availability API change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Behaviour must stay exactly as a600f71b6 specifies: queue expiry with a starting provider gives the FP3 outcome (contended); a request that reaches its seat in time and then waits for loading keeps cause loading; an absent provider keeps its existing outcome; a failed provider gets the existing outcome for an unavailable service with its cause.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b39-behaviour.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerAvailabilityTest.java#unconfiguredModelStaysAbsent"
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/EmbeddingProviderAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "New availability API checks all four provider states, isAvailable only for READY, exact FAILED cause retention, valid cause/state combinations, and ready-backend close becoming permanently absent from that provider. No physical native sessions are used.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Give EmbeddingProvider an explicit availability state that names at least: ready; starting or loading (transient, a model is installed and will become ready); absent (no model configured or installed, permanent); failed (load failed, with its cause).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b39-state-api.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/EmbeddingProviderAvailabilityTest.java#placeholderStatesAreExplicitAndRetainLoadFailure",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/EmbeddingProviderAvailabilityTest.java#constructedBackendIsReadyAndClosedBackendIsAbsent"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Removing a provider through the existing null-accepting setter keeps permanent-absence outcomes across HYBRID, SPLADE, AUTO, VECTOR and RAG. The boolean-only before-state incorrectly treats the null RAG provider as present after queue expiry; the check fails by assertion. After normalizing removal to the explicit absent provider every mode returns its existing absence outcome with no inference or latch access. The separate ordinary ABSENT control passes both revisions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Behaviour must stay exactly as a600f71b6 specifies: queue expiry with a starting provider gives the FP3 outcome (contended); a request that reaches its seat in time and then waits for loading keeps cause loading; an absent provider keeps its existing outcome; a failed provider gets the existing outcome for an unavailable service with its cause.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request b39 availability states'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b39-behaviour.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceAvailabilityTest.java#removedProviderAcrossAllModesKeepsAbsence"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:6b82d3be1b4f117e8e858f1caef8b48c881bcf8bfd19a0ea3ae731caba119f8a",
  "note": "Reviewed the availability-state matrix, retained failures and API-only candidate reason against current tests and sources. Candidate state/behavior and adapted provider-fixture checks passed; unchanged before evidence remains truthful."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:6cafded2da64a81c8813dbcec12f6c0e0a3ab1184a64e7e5933a34025005bf6f",
  "note": "Reviewed retained availability expectations and scoped the shared-file timing changes to deadline-load; its two candidate checks pass. The unchanged availability entries and evidence still truthfully describe the current API and fixtures."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5a-20261016",
  "verdict": "accept",
  "digest": "sha256:05e29e671b6d5f24f1795edde03e26fec1ac38701bcf14f3a011bc91bce4f171",
  "note": "Re-bound to origin/main 298354254 after reviewing the resource-name and Mockito qualification cleanup; the existing oracles remain intact. The 13 availability checks and changed test classes pass, and the declared a600f71b6 before outcomes match; the API-only candidate reason remains factual."
}
```
