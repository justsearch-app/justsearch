---
schema: test-intent.v1
task: t-20261023-565299
author-role: builder
author-session: 01a18478-e8f2-7430-958e-f020c905f952
---

# Test-intent entries

Skeleton written by the test-intent gate. Rules: docs/reference/testing/test-intent.md.
Give every entry a class and the fields that class needs; group items that share one warrant.
The acceptor (not the author) appends the acceptance record under the heading at the end.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/NestedRootLifecycleTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ProfilingResetRemovesRootsTest.java"
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
          "choice": "M1-M7: explicit root removal during migration must be replayable, preserve independently retained roots, keep incomplete removals retryable and retain refusal for unrecorded watcher changes. M6: an acknowledged SWITCHING refusal has zero effects; cancel only this attempt’s fresh removal intent, preserve an older incomplete intent, complete cutover and permit post-switch removal retry.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Removing P while a distinct candidate writer exists sets the unrecorded-watcher latch before the scoped deletion receipt; the migration refuses cutover. Retained nested roots and siblings must survive removal and replay (task t-20261023-565299 M1-M7)."
        }
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The Worker now retires an explicitly removed root inside the deletion call. Move the fake Worker retirement (including its injected failures) into that call before the simulated index effect; keep every existing input, assertion, fault, retry bound and timeout."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java"
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
          "choice": "M1-M7: explicit root removal during migration must be replayable, preserve independently retained roots, keep incomplete removals retryable and retain refusal for unrecorded watcher changes. M6: an acknowledged SWITCHING refusal has zero effects; cancel only this attempt’s fresh removal intent, preserve an older incomplete intent, complete cutover and permit post-switch removal retry.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Removing P while a distinct candidate writer exists sets the unrecorded-watcher latch before the scoped deletion receipt; the migration refuses cutover. Retained nested roots and siblings must survive removal and replay (task t-20261023-565299 M1-M7)."
        }
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The Worker now retires an explicitly removed root inside the deletion call. Move the fake Worker retirement (including its injected failures) into that call before the simulated index effect; keep every existing input, assertion, fault, retry bound and timeout."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsFolderActionsTest.java"
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
          "choice": "M1-M7: explicit root removal during migration must be replayable, preserve independently retained roots, keep incomplete removals retryable and retain refusal for unrecorded watcher changes. M6: an acknowledged SWITCHING refusal has zero effects; cancel only this attempt’s fresh removal intent, preserve an older incomplete intent, complete cutover and permit post-switch removal retry.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Removing P while a distinct candidate writer exists sets the unrecorded-watcher latch before the scoped deletion receipt; the migration refuses cutover. Retained nested roots and siblings must survive removal and replay (task t-20261023-565299 M1-M7)."
        }
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The Worker now retires an explicitly removed root inside the deletion call. Move the fake Worker retirement (including its injected failures) into that call before the simulated index effect; keep every existing input, assertion, fault, retry bound and timeout."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsIdempotencyTest.java"
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
          "choice": "M1-M7: explicit root removal during migration must be replayable, preserve independently retained roots, keep incomplete removals retryable and retain refusal for unrecorded watcher changes. M6: an acknowledged SWITCHING refusal has zero effects; cancel only this attempt’s fresh removal intent, preserve an older incomplete intent, complete cutover and permit post-switch removal retry.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Removing P while a distinct candidate writer exists sets the unrecorded-watcher latch before the scoped deletion receipt; the migration refuses cutover. Retained nested roots and siblings must survive removal and replay (task t-20261023-565299 M1-M7)."
        }
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The Worker now retires an explicitly removed root inside the deletion call. Move the fake Worker retirement (including its injected failures) into that call before the simulated index effect; keep every existing input, assertion, fault, retry bound and timeout."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootRemovalIncompleteTest.java"
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
          "choice": "M1-M7: explicit root removal during migration must be replayable, preserve independently retained roots, keep incomplete removals retryable and retain refusal for unrecorded watcher changes. M6: an acknowledged SWITCHING refusal has zero effects; cancel only this attempt’s fresh removal intent, preserve an older incomplete intent, complete cutover and permit post-switch removal retry.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Removing P while a distinct candidate writer exists sets the unrecorded-watcher latch before the scoped deletion receipt; the migration refuses cutover. Retained nested roots and siblings must survive removal and replay (task t-20261023-565299 M1-M7)."
        }
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The Worker now retires an explicitly removed root inside the deletion call. Move the fake Worker retirement (including its injected failures) into that call before the simulated index effect; keep every existing input, assertion, fault, retry bound and timeout."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/NestedWatchedRootTest.java"
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
          "choice": "M1-M7: explicit root removal during migration must be replayable, preserve independently retained roots, keep incomplete removals retryable and retain refusal for unrecorded watcher changes. M6: an acknowledged SWITCHING refusal has zero effects; cancel only this attempt’s fresh removal intent, preserve an older incomplete intent, complete cutover and permit post-switch removal retry.",
          "label": "agent choice"
        },
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "Removing P while a distinct candidate writer exists sets the unrecorded-watcher latch before the scoped deletion receipt; the migration refuses cutover. Retained nested roots and siblings must survive removal and replay (task t-20261023-565299 M1-M7)."
        }
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Bind the production mutation-admission fence in the Worker fixture. The migration fixture establishes its initial registrations before binding admission; its requests run under admission. All existing scope, document, chunk, queue, replay and watcher assertions remain."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-565299-codex-r2",
  "verdict": "accept",
  "digest": "sha256:9f907d23ab9ce5240d53a802c1512a7159c622ea87273fa5f71335e6a338ca40",
  "note": "Real Engine: baseline fails on unrecorded watcher mutation; candidate preserves nested collection and watcher through cutover. Controls, 16 component cases, exact refusal text and SWITCHING guard mutation verified."
}
```
