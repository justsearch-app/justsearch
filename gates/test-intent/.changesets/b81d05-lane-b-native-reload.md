---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-native-reload-20261021
---

# The native reload keeps watcher delete obligations

The builder does not supply acceptance. After merging main, the native runtime reload (#850)
bypassed this lane's watcher obligations: a failed reload discarded the settled DELETE waiting
behind its mutation fence, and a successful one left existing reconciliation receipts for a later
watch call. The native handoff now leaves a failed reload's fence on the drained prior owner, so the
waiting DELETE becomes the same durable root obligation the closing path records, and the native
successor discharges pending root receipts before it is published.

The four new checks extend main's real Engine reload fixture (RuntimeReloadProducersHttpIntegrationTest);
its existing checks are unchanged. Fail-before ran against the previous merge, c15478a96. The
earlier fifth check, a crash during native reconciliation from a seeded receipt, is replaced by the
crash checks in b81d05-lane-b-waiting-delete-durable.

```json
{
  "entries": [{
    "items": ["modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "Through the real Engine, its admin reload HTTP handler, native filesystem watching, SQLite and Lucene search: a watched file's DELETE that settles while a native reload holds the mutation fence is kept as a durable root receipt when the reload fails at the opener, or at service reconstruction after the watcher was handed to the successor. The next reload returns 200, the deleted file is gone from search, its sibling stays searchable and the receipt is retired. A successful native reload discharges an existing receipt before it returns, without another watch call. In a separate JVM halted after a failed handoff, the receipt survives the process death and the restarted Engine applies it once the root is watched, keeping the sibling.",
    "sources": [
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I8",
        "quote": "A watcher DELETE settled while the runtime is being swapped is never reported done without its effect. After the swap, the successor reconciles the root and the rows are gone. If the swap fails or is retried, the delete is still applied once a runtime is available. Throwing without a retry is not enough.",
        "label": "agent-drafted, owner-adopted"
      },
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I8",
        "quote": "Covers a failed opener, a failed service reconstruction, consecutive swaps, and process death before reconciliation finishes. Obligations are kept until their effects are confirmed. Root removal and collection attribution are respected, and an unwatched root is never revived.",
        "label": "agent-drafted, owner-adopted"
      }
    ],
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-native-reload.json",
    "newChecks": [
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#failedOpenerKeepsTheWaitingWatcherDeleteUntilRetry",
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#failedReconstructionKeepsTheWaitingWatcherDeleteUntilRetry",
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#successfulNativeReloadDischargesAnExistingWatcherReceipt",
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#watcherObligationSurvivesProcessDeathAfterFailedHandoff"
    ]
  }]
}
```
