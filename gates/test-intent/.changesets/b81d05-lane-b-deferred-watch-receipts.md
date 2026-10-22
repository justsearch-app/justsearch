---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-deferred-watch-receipts-20261021
---

# A restored watch that reaches the read-only view still gets its pending receipts applied

The builder does not supply acceptance. At a restart, the automatic restore's watch call can reach
the read-only (deferred) view after the first writer's upgrade has already run its receipt
reconcile, before the root's subscription existed. The view cannot apply receipts, so the receipt
stayed pending until a later trigger: the restore check failed intermittently in the suite
("reloadobligationgone expected 0 but was 1"). Forcing that order (a 300 ms pause in the deferred
view's watch, removed after) reproduced the failure on every run. A watch on a service that cannot
apply receipts now queues them on the watcher's reconcile thread, which routes them to the writer
that is current when it runs; with the same forced order the restore check passes.

The new check is a unit check on the watch call itself: a service with no writable lifecycle, a
registered root, and the watcher's queue observed. Fail-before ran against the previous candidate,
92e8a1a4b.

```json
{
  "entries": [{
    "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerWatcherEventOwnershipTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "A watch call that registers a root on an ingest service with no writable lifecycle (the read-only view that serves before the first writer) queues the pending watcher receipts on the watcher's reconcile thread instead of skipping them.",
    "sources": [
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I8",
        "quote": "Covers a failed opener, a failed service reconstruction, consecutive swaps, and process death before reconciliation finishes. Obligations are kept until their effects are confirmed. Root removal and collection attribution are respected, and an unwatched root is never revived.",
        "label": "agent-drafted, owner-adopted"
      }
    ],
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-deferred-watch-receipts.json",
    "newChecks": [
      "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerWatcherEventOwnershipTest.java#watchOnAServiceThatCannotApplyReceiptsQueuesThemForTheCurrentWriter"
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
  "digest": "sha256:0c0a8b9f2e85675ffd55c8edd81116ce948db2efafdd7c0a93d994b5b2a58ad8",
  "note": "Round7: 60/60 producer cases, 10 outcome failures on recorded before-states, and reload/restart gap probes verified; lint passes; JVM run has only the predeclared long-path socket exception. Round6 product passes carried forward; scoped exclusions retained."
}
```
