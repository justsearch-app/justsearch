---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-restore-receipts-20261021
---

# A restart's automatic root restore applies pending watcher receipts

The builder does not supply acceptance. A restart starts the Worker read-only, and production's
automatic restore watches the persisted roots before the first writer serves. A pending watcher
receipt for such a root was skipped and stayed pending until an explicit watch call; the deleted
file left search only through a later periodic sync, about 59 s after the restart, with the
receipt still pending. The first-writer upgrade now applies pending receipts once it serves.

The new check extends the real Engine reload fixture (RuntimeReloadProducersHttpIntegrationTest),
which can now run the production restore path: root membership persisted in the data folder and the
bootstrap's automatic root producers. Fail-before ran against the previous candidate, 583d7e6e3.

The check was intermittent in the suite: the restore's watch could reach the read-only view after
the upgrade's reconcile had run. That order is fixed and checked under
b81d05-lane-b-deferred-watch-receipts; this check then passed in 10 of 10 executed suite runs.

```json
{
  "entries": [{
    "items": ["modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "Through the real Engine, its admin reload HTTP handler, native filesystem watching, SQLite, Lucene search and the Head bootstrap: a person adds a root, so its membership persists; a watched file is deleted and its DELETE settles while a successful native reload holds the mutation fence; the Engine process dies as the old owner's close returns. A new Engine with automatic root producers restores the persisted root by itself, with no watch call, and within the fixture's 15 s bound the deleted file is absent from search, its sibling stays searchable and no receipt remains.",
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
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-restore-applies-receipts.json",
    "newChecks": [
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#automaticRootRestoreAppliesTheWaitingDeleteWithoutAWatchCall"
    ]
  }]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-848-5ead93951",
  "verdict": "accept",
  "digest": "sha256:11d4de6e371cadb036fb8460dea92e49d7428c020e5db4ae4ba5daaaa39a8b72",
  "note": "Round7: 60/60 producer cases, 10 outcome failures on recorded before-states, and reload/restart gap probes verified; lint passes; JVM run has only the predeclared long-path socket exception. Round6 product passes carried forward; scoped exclusions retained."
}
```
