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
      "behaviour": "An interrupted or failed removal retains a durable retry entry, does not claim live watching, fences stale and later root producers, and does not let an overlapping duplicate Add recreate a removed root; a deliberate later Add remains possible.",
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
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootRemovalIncompleteTest.java#eachFailureKeepsAnIncompleteEntryAndRetry",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootRemovalIncompleteTest.java#crashAfterStoppingWatcherRestartsAsIncompleteAndCanBeRetried",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootRemovalIncompleteTest.java#concurrentDuplicateAddCannotRecreateTheRootItWasRemoving",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootRemovalIncompleteTest.java#incompleteRemovalFencesOldAndNewRootProducersAndKeepsItsReason"
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/RootRemovalVisibilityTest.java#completedDeletionIsAbsentFromFreshServingReads",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/RootRemovalVisibilityTest.java#readOnlyServingViewCannotConfirmRemovalAfterFailedMigration"
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
      "behaviour": "An ordinary single-runtime switch returns an explicitly buffered durable receipt; deferred switching without a writer refuses completion and adds no receipt.",
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
      "evidence": "gates/test-intent/evidence/uiux-removal-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/SwitchingRootRemovalVisibilityTest.java#switchingWithoutWritableServingTargetNeverConfirmsCompletion"
      ]
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RetainedRootRemovalVisibilityTest.java#retainedGenerationCannotResurrectRemovedSearchResults"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "New",
      "behaviour": "A prefix removal commits and refreshes the serving reader after the existing exactly-once journal and delete effects. All existing ordered and no-extra-interactions assertions remain; the refresh is now explicitly required by S35.2.",
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
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java#sameRuntimePreGreenJournalsBeforeExactlyOneServingEffectForEachDelete"
      ],
      "repaired": true
    }
  ]
}
```
