---
schema: test-intent.v1
task: runtime-reload-http-timeouts
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Runtime reload fixture follows both built-in subscriptions

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadHttpIntegrationTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "#841 (0c826d98e, merged later the same day) added the outcomes subscription, and CI never runs modules/ui integrationTest, so nothing caught the break.",
          "location": "task runtime-reload-http-timeouts user turn 2"
        },
        {
          "kind": "owner-words",
          "quote": "Production now has two built-in subscriptions, each holding a serving view while idle. The test exists to prove that an idle runtime reload retires the built-in subscriptions and reconnects them without leaking the old view. With two subscriptions, that now means both.",
          "location": "task runtime-reload-http-timeouts user turn 2"
        },
        {
          "kind": "owner-words",
          "quote": "Where a case asserts the idle built-in holder count, expect two, and say in the message that they are the rows and outcomes subscriptions.",
          "location": "task runtime-reload-http-timeouts user turn 2"
        }
      ],
      "assessment": {
        "purpose": "Restore the existing real HTTP reload checks after #841 (0c826d98e) added an independent outcomes subscription. Before #841, #850's fixture only needed to script rows; now its unstubbed outcomes method omits the registration snapshot and blocks startup before HTTP starts.",
        "callersAndStateOwners": "JUnit owns the fixture, ephemeral Javalin listener, HttpClient, executor registry, mock workers and TempDir. Production IndexingJobsBridgeWiring.wire in modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiring.java starts the outcomes stream before rows. EngineKnowledgeClient retains a serving view for each stream. WorkerIngestService.subscribeIndexingJobOutcomes in modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerIngestService.java installs its consumer before emitting the empty registration snapshot. No product or saved-state code changes.",
        "indirectBehaviours": "The mock installs the outcomes consumer before acknowledging registration, releases its reference on cancellation and counts outcomes separately from rows. Idle checks require exactly two serving holders for rows and outcomes. The two-reload case requires exact row and outcome registration totals after each reload and zero holders on each retired view. All refusal bodies/statuses, opens, drainAndClose, actual-holder retention, migration/shutdown/successor callbacks, timed-out search ownership, reconnect and bounded-wait checks remain. POST elapsed-time output supplies local timing evidence without increasing any deadline or changing selection.",
        "evidence": "tmp/evidence/before-alone.xml and before-alone-summary.json: baseline 3f1ff3f18 fails all seven cases in Fixture.<init>:234 before HTTP starts, six at 3.006-3.017 s and first at 4.343 s. Five candidate runs pass 7/7 without skips: tmp/evidence/after-1.log through after-5.log, matching XML and repeated-runs-summary.json. tmp/evidence/ui-integration.log, ui-integration-results/ and ui-integration-summary.json record the complete task: 42 pass, one pre-existing skip, no failing classes, including 7/7 for this class. tmp/evidence/source-findings.md traces the registration barrier added by #841. Logs/evidence remain in tmp as required by the builder brief.",
        "unresolvedRisks": "Independent verifier acceptance is required and must be supplied by a different session. Candidate/full-suite/lint outcomes are recorded in tmp/evidence/check-summary.json; no skipped or unavailable check is a pass. The before-state exposes a fixture setup TimeoutException, not a new product assertion failure. These are existing checks restored to the owner's explicitly adopted two-subscription topology. Any unrelated full-suite failures remain outside this change."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "claude-verifier-reload-b99be3af6",
  "verdict": "accept",
  "digest": "sha256:baba939abc8116002eb7b4e7b2d7124b0863a3dea115c336dbea9fd79e8cdeae",
  "note": "Base fails 7/7 at Fixture start().get (unstubbed outcomes never registers); candidate 7/7 x5; two production mutations (outcomes keeps old-view hold; outcomes never re-subscribes) both caught; no assertion weakened or deadline raised."
}
```
