---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: builder-index-deletes-reload-watch-list-20261021
---

# Every reload keeps the watch list; an unavailable root's receipt waits

The builder does not supply acceptance. A retry after a failed native reload took the closing
reload path, whose new services started with an empty watch list, so a later delete in a watched
root stayed searchable. The closing path now hands every watched root, and every root held for its
volume, to the new registry with its collection; removed roots stay removed. A watcher receipt for
a root that is unavailable during a reload failed that reload; it now stays pending, unapplied,
until the root returns.

The two new checks extend the real Engine reload fixture (RuntimeReloadProducersHttpIntegrationTest).
Fail-before ran against the previous candidate, aa31440a9. The unit check
RootWatcherRegistryTest#handoverKeepsWatchedRootsAndNeverRevivesAnUnwatchedOne covers the
registry handover itself in a second entry; it uses the new handover API, so it cannot compile
before the change and its evidence has no fail-before (`repaired: false`).

```json
{
  "entries": [{
    "items": ["modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"],
    "class": "New",
    "repaired": true,
    "behaviour": "Through the real Engine, its admin reload HTTP handler, native filesystem watching, SQLite and Lucene search: after a native reload fails at the opener and a retry succeeds, the root is still watched, so a file deleted afterwards disappears from search, a file created afterwards becomes searchable and the sibling stays. A pending watcher receipt for a root moved away during a reload does not fail it: the reload returns 200, the receipt stays and nothing is applied; once the root is back, the next reload applies it, retires the receipt and keeps the sibling.",
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
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-reload-watch-list.json",
    "newChecks": [
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#failedNativeReloadAndRetryKeepTheRootWatched",
      "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java#receiptForAnUnavailableRootWaitsWithoutFailingTheReload"
    ]
  }, {
    "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootWatcherRegistryTest.java"],
    "class": "New",
    "repaired": false,
    "behaviour": "A retiring watch registry hands each watched root to its successor with that root's collection. A root unwatched before the handover is not handed over. An unwatch that reaches the retired registry afterwards removes the root from the successor. A root removed before or after the handover is never re-watched through the retired registry, and the retired registry names the successor as the current owner.",
    "sources": [
      {
        "kind": "owner-adopted-scenario",
        "task": "t-20261019-b81d05",
        "scenario": "I8",
        "quote": "Covers a failed opener, a failed service reconstruction, consecutive swaps, and process death before reconciliation finishes. Obligations are kept until their effects are confirmed. Root removal and collection attribution are respected, and an unwatched root is never revived.",
        "label": "agent-drafted, owner-adopted"
      }
    ],
    "evidence": "gates/test-intent/evidence/b81d05-lane-b-registry-handover.json",
    "newChecks": [
      "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootWatcherRegistryTest.java#handoverKeepsWatchedRootsAndNeverRevivesAnUnwatchedOne"
    ]
  }]
}
```
