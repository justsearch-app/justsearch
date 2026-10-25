---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-uiux-removal-builder
---

# Test-intent entries

WP3.5 scenarios are agent-adopted under delegated design choice. They are not owner-agreed scenarios. Quotes below preserve the original scenario wording and scope. No acceptance record is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootRemovalIncompleteTest.java"
      ],
      "class": "New",
      "behaviour": "Failed or interrupted removal retains a durable retry entry, does not claim live watching, fences root producers and overlapping duplicate Add, and preserves a plain explanation of unavailable or buffered deletion for the caller.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S351",
          "agreedScenario": "S35.1",
          "quote": "If removal fails at any step (stopping the watcher, deleting, saving the folder list), each forced separately, the folder stays listed as incomplete with a retry path, and is either still watched live or visibly marked as not watched live; it is never shown as normally watched while no watcher covers it.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "If removal fails at any step (stopping the watcher, deleting, saving the folder list), each forced separately, the folder stays listed as incomplete with a retry path, and is either still watched live or visibly marked as not watched live; it is never shown as normally watched while no watcher covers it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S353",
          "agreedScenario": "S35.3",
          "quote": "A stop or crash during removal leaves, after restart, either no folder and no documents, or a listed folder that can be removed again; never a removed-looking folder whose documents remain searchable.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "A stop or crash during removal leaves, after restart, either no folder and no documents, or a listed folder that can be removed again; never a removed-looking folder whose documents remain searchable.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S354",
          "agreedScenario": "S35.4",
          "quote": "A file of the folder that is mid-indexing or mid-enrichment when removal starts, a Reindex or folder check that started just before, and a concurrent Add or second removal do not bring any of its documents back.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "A file of the folder that is mid-indexing or mid-enrichment when removal starts, a Reindex or folder check that started just before, and a concurrent Add or second removal do not bring any of its documents back.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-removal-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootRemovalIncompleteTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/RootRemovalVisibilityTest.java"
      ],
      "class": "New",
      "behaviour": "A completed prefix deletion is absent from fresh serving and future-serving searches ordinarily and during migration. A distinct read-only serving view cannot acknowledge deletion, including after migration has failed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S352",
          "agreedScenario": "S35.2",
          "quote": "Removal during a rebuild or an index switch (normal and deferred-switch paths) is reported complete only when its documents are gone from fresh searches; they do not return when the switch completes, after cutover, or after a crash during it.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "Removal during a rebuild or an index switch (normal and deferred-switch paths) is reported complete only when its documents are gone from fresh searches; they do not return when the switch completes, after cutover, or after a crash during it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-removal-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/RootRemovalVisibilityTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/folderRemovalStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "The Library keeps an incomplete-removal reason visible during rebuild and when a folder becomes unavailable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S351",
          "agreedScenario": "S35.1",
          "quote": "If removal fails at any step (stopping the watcher, deleting, saving the folder list), each forced separately, the folder stays listed as incomplete with a retry path, and is either still watched live or visibly marked as not watched live; it is never shown as normally watched while no watcher covers it.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "If removal fails at any step (stopping the watcher, deleting, saving the folder list), each forced separately, the folder stays listed as incomplete with a retry path, and is either still watched live or visibly marked as not watched live; it is never shown as normally watched while no watcher covers it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-removal-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/folderRemovalStatus.test.ts#S35.1 incomplete removal remains visible during rebuild and with a missing folder"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/SwitchingRootRemovalVisibilityTest.java"
      ],
      "class": "New",
      "behaviour": "An ordinary single-runtime switch returns an explicitly buffered durable receipt; deferred switching without a writer returns a plain typed unavailable refusal, confirms no completion and adds no receipt.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S352",
          "agreedScenario": "S35.2",
          "quote": "Removal during a rebuild or an index switch (normal and deferred-switch paths) is reported complete only when its documents are gone from fresh searches; they do not return when the switch completes, after cutover, or after a crash during it.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "Removal during a rebuild or an index switch (normal and deferred-switch paths) is reported complete only when its documents are gone from fresh searches; they do not return when the switch completes, after cutover, or after a crash during it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-removal-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/SwitchingRootRemovalVisibilityTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RetainedRootRemovalVisibilityTest.java"
      ],
      "class": "New",
      "behaviour": "Probe actual Worker boot with retained predecessor and intact or torn current pointer. A completed removal cannot resurrect search results; an honest incomplete removal can be retried after predecessor retirement. Only a generation-ownership refusal counts as safe failed startup.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S355",
          "agreedScenario": "S35.5",
          "quote": "Removed documents do not come back after restart, Reindex or rebuild. (Resurrection from a retained index generation was not shown: boot inspection never reads `state.json.prev`, `IndexGenerationManager.java:505-522`; the verifier probes any recovery path it finds before calling it a defect.)",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "Removed documents do not come back after restart, Reindex or rebuild. (Resurrection from a retained index generation was not shown: boot inspection never reads `state.json.prev`, `IndexGenerationManager.java:505-522`; the verifier probes any recovery path it finds before calling it a defect.)",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-removal-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RetainedRootRemovalVisibilityTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Adaptation",
      "behaviour": "Preserve the existing journal-before-mutation and exactly-once serving-effect oracle while permitting at most one read refresh.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S352",
          "agreedScenario": "S35.2",
          "quote": "Removal during a rebuild or an index switch (normal and deferred-switch paths) is reported complete only when its documents are gone from fresh searches; they do not return when the switch completes, after cutover, or after a crash during it.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "Removal during a rebuild or an index switch (normal and deferred-switch paths) is reported complete only when its documents are gone from fresh searches; they do not return when the switch completes, after cutover, or after a crash during it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-removal-adaptations-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "repaired": false,
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing ordered journal and exactly-once mutation assertions, plus verifyNoMoreInteractions, remain. A bounded optional reader-refresh observation is now explicitly verified; it is not another mutation effect. Required fresh-reader visibility is checked separately by RootRemovalVisibilityTest.",
      "preservationBeforeEvidence": "gates/test-intent/evidence/uiux-removal-adaptations-before.json",
      "preservationBase": "8292c62e381684eb506c96f50990b212ea1fb879"
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java"
      ],
      "class": "Adaptation",
      "behaviour": "Preserve all existing real-producer retirement, stale watcher, queue and declaration assertions with an explicitly writable mocked running index.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S354",
          "agreedScenario": "S35.4",
          "quote": "A file of the folder that is mid-indexing or mid-enrichment when removal starts, a Reindex or folder check that started just before, and a concurrent Add or second removal do not bring any of its documents back.",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Remaining UI/UX work; WP3.5 removal from search only, preserving declared nested roots and uncertainty safeguards.",
          "choice": "A file of the folder that is mid-indexing or mid-enrichment when removal starts, a Reindex or folder check that started just before, and a concurrent Add or second removal do not bring any of its documents back.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-removal-adaptations-preserved.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java"
      ],
      "repaired": false,
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing RunningRuntime mock now explicitly accepts writes. No expectation, verification, task boundary or producer gate changes. Real read-only serving refusal is required separately by RootRemovalVisibilityTest.",
      "preservationBeforeEvidence": "gates/test-intent/evidence/uiux-removal-adaptations-before.json",
      "preservationBase": "8292c62e381684eb506c96f50990b212ea1fb879"
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-8ve7myn3",
  "verdict": "accept",
  "digest": "sha256:8cd89b689e2756e55b98d97b6326d6525cdd0f4418cb746d44f710bfd3eae08d",
  "note": "Delegated WP3.5 intent reviewed; 17 repair cases pass with intended before failures; 29 preservation cases pass both."
}
```
