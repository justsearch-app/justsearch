---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-waiting-delete-durable-20261021
---

# A DELETE waiting on a successful native reload is durable before the old owner closes

The builder does not supply acceptance. A settled watcher DELETE waiting behind a successful native
reload's mutation fence was held only by its waiting callback after the watcher and admission
handoff and the old owner's close, so a process death before the successor admitted it left the
deleted file searchable. Before the old owner can close, each such DELETE now becomes the drained
old owner's durable root receipt, through the same call a failed handoff's released callback makes,
and the successor's reconciliation settles it.

The two new checks extend the real Engine reload fixture (RuntimeReloadProducersHttpIntegrationTest)
and replace its earlier seeded-receipt crash check. Each kills a separate Engine JVM at a lifecycle
method's exit that exists before and after the change: the old owner's close, or the successor's
loop activation, both still inside the fence. Fail-before ran against the previous candidate,
8d675b6af.

```json
{
  "entries": [{
    "items": ["modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "Through the real Engine, its admin reload HTTP handler, native filesystem watching, SQLite and Lucene search: a watched file is deleted and its DELETE settles while a successful native reload holds the mutation fence. The Engine process dies as the old owner's close returns, before the successor reconciles, or once the successor's loop is active, before the fence admits the waiting callback. After a restart and the root's watch call, the deleted file is absent from search, its sibling stays searchable and no receipt remains.",
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
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-waiting-delete-durable.json",
    "newChecks": [
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#watcherObligationSurvivesProcessDeathAfterSuccessfulHandoff",
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#watcherObligationSurvivesProcessDeathAfterNativeReconciliation"
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
  "digest": "sha256:b619f0073ba6cbfefd6c9c23af0f2634ac8ce88fd2cfadf10c5687d52de35a20",
  "note": "Round7: 60/60 producer cases, 10 outcome failures on recorded before-states, and reload/restart gap probes verified; lint passes; JVM run has only the predeclared long-path socket exception. Round6 product passes carried forward; scoped exclusions retained."
}
```
