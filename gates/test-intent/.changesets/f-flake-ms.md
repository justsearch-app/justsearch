---
schema: test-intent.v1
task: t-20261112-toolstate
author-role: builder
author-session: e1fa567e-cbd9-4710-b416-dfd645a2f832
---

# Test-intent entries

KnowledgeServerModelStartCutoverTest failed at random on CI, always at `migrateLive`'s
"Green opens live ==> expected: <true> but was: <false>" (lines 528 and 140 of the base). The cause
is a product race, not a test defect. A NATIVE boot that owns no candidate started a migration
cutover monitor anyway. When that thread first read the generation pointer only after a request
had created a migration candidate (a loaded runner deschedules it for the rest of `start()`), it
saw MIGRATING and waited for an enumeration that only the live start would begin. The live start
waits 10 s for previous migration threads to exit, so it gave up and requested an Engine restart.

The boot now starts that monitor only when it owns a candidate (`buildingIndexPath != null`). The
live start starts its own monitor after publication, as before. The migration assertions in the
existing tests are unchanged: the race they hit is gone.

One check is added to the existing file. It forces the bad order: the test holds the generation
pointer lock (IndexGenerationManager's process-wide STATE_CONTROL) from boot until the request has
created the candidate, and waits until any boot-time monitor is queued on that lock. On the base
(b28e33f26f) it fails by assertion with the CI message; with the fix it passes.

Under CPU load the new check passed its migration assertions but once failed during shutdown:
`Application services still own resources`, caused by
`Indexing loop still running; resources retained for shutdown retry`. The shared `Forced.close()`
path and this class's background-close helper now retry that exact IOException pair up to six
close calls. Each attempt uses the product's own 5 s join; there is no added sleep. A different
exception, an additional suppressed failure, or the sixth failure is rethrown. Existing checks
using these helpers therefore have a longer teardown allowance; their inputs, fault injection,
migration assertions and requirement that shutdown succeeds are retained. QueryPrewarmFixture's
separate Boot.close() path is unchanged.

The delegation quote is the owner's own words about a red main CI test (2026-10-24), the standing
preference behind fixing recurring CI flakes without asking.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerModelStartCutoverTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A migration requested right after a NATIVE boot that owns no candidate, before any boot-time cutover monitor first reads the generation pointer, still opens Green live: beginUnrecordedBuildingLiveAsync completes true within its existing 30 s wait, the switch then ends, the successor generation becomes active and the Engine restart fallback is never called. Existing migration oracles are retained; shared teardown now retries the exact retained-loop shutdown IOException at most six times, as described below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you really shouldnt have needed to ask me for this",
          "delegationLocation": "task t-20261103-shell agreement (the owner's standing preference, 2026-10-24, about a red main CI test)",
          "delegationScope": "fixing a recurring CI flake, including a product race the flaky check exposes",
          "choice": "Fix the race in the product rather than relaxing the check: a NATIVE boot starts the migration cutover monitor only when it owns a candidate, and a new check forces the order CI hit and keeps the file's existing oracle that a requested migration opens Green live with no Engine restart (migrateLive's 'Green opens live' and the class's 'no Engine restart'). Its product basis predates the PR: docs/design/lane-f-engine-jvm/design.md (stage D1, mechanisms built new) names 'live activation without the Worker restart'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-flake-ms.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerModelStartCutoverTest.java#aMigrationRequestedBeforeTheBootMonitorFirstReadsStillOpensGreenLive"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerModelStartCutoverTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Forced.close() and closeInBackground() now share a bounded server-close helper: at most six calls, retrying only IOException('Application services still own resources') caused solely by IOException('Indexing loop still running; resources retained for shutdown retry'), without extra sleeps. Other errors, additional suppressed errors and exhaustion still fail. This changes the teardown timing and close-call count of existing checks using those helpers; it retains their inputs, preconditions, observations, fault injection, assertions, tags and shutdown-success requirement. The separate QueryPrewarmFixture.Boot teardown is unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1d2dc-5fe7-7262-bbbc-7527a9291730",
  "verdict": "accept",
  "digest": "sha256:a007faf2dd9a5a15a6d832f04521e8e0d9bdf5d07cffcb41b82a854a93ecccde"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "dac3dd20-1a95-4d11-8484-8455938902a1",
  "verdict": "accept",
  "digest": "sha256:228bd7f43b665abe6c812ac387811b0033dfb56e422e786a03212c8e6de88d71"
}
```
