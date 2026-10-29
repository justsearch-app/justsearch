---
schema: test-intent.v1
task: t-20261023-565299
author-role: builder
author-session: 01a186e5-e131-7d90-a7c1-e21967e47360
---

# Test-intent entries

R2 fixture adaptation. The isolated new checks in EngineUnconfirmedRootRemovalTest and
EngineMigrationWatcherRemovalTest use the R1/R4 delegated choices cited below; the independent
verifier checks their assertions and before/after execution. No acceptance or execution record
is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootRetirementTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 agreement",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "R1-R4: successful declared-folder removal cannot leave a watcher registered even without confirmed Worker removal intent; an unconfirmed attempt may refuse retryably. Bind the retirement fixture to the production authority directory, retain its existing assertions, and check both missing wiring and absent intent separately. Retain Engine per-root producer ordering and verify that removal during a live migration prevents new parent files from indexing while a separately declared nested folder keeps indexing.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no leak across a requested scope",
          "reproduction": "On 9f2af625c8, EngineRootRetirementTest watcherDeadlineCannotReleaseRemovalBeforeRegistryEntry reports successful removal for direct/add/restored entry points while the Worker registry still holds the removed root. Without the declared-roots directory, pendingRemovals is empty and deleteByPath skips watcher retirement."
        }
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Wire WorkerIngestService.setDeclaredRootsDirectory to the fixture authority folder as production does. All original inputs, barriers, timeouts, retry bounds and assertions remain unchanged. EngineUnconfirmedRootRemovalTest separately preserves coverage without that wiring and with missing pending intent; its retry must drain the watcher after wiring is repaired."
    }
  ]
}
```

## Acceptance records
