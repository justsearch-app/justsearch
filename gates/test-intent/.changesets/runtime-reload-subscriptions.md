---
schema: test-intent.v1
task: t-20261020-712d3e
author-role: builder
author-session: reload-holders-http-repair
---

# Runtime reload with the built-in indexing observer

The independent real-system rejection identified missing composition coverage. This new check includes the production bridge wiring and actual HTTP handler. Acceptance remains for an independent acceptor.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadHttpIntegrationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The actual DebugRoutes registration and LocalApiServer admin handler call the real Engine client/server while the production IndexingJobsBridgeWiring holds its idle subscription lease. Two HTTP reloads succeed and the bridge reconnects with fresh snapshots after each; a separate actual holder still forces the bounded refusal without runtime close, serving capture restores, and HTTP retry succeeds after release. Native runtime IO and service construction are mocked; live Engine HTTP ingestion/restart receipts are retained in tmp/712d3e/repair-http. Retirement callbacks run outside the publication and lifecycle locks; migration, shutdown and a successor published while callbacks run are rechecked before the HTTP reload can perform destructive work. An ordinary search called by a retirement callback acquires its own serving view; its timed-out but still-running worker prevents runtime closure until actual exit.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261020-712d3e request",
          "quote": "Add a check that drives the real HTTP route (or the exact handler chain it uses) and fails today with this refusal."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R1",
          "quote": "On a real Engine with no outstanding work, POST /api/admin/runtime/reload succeeds and reports the swap duration, and a second reload right after it also succeeds.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R4",
          "quote": "If a real holder does not drain within the 5-second holder bound, the reload fails with the existing refusal before any destructive work. Serving admission is then restored, and after the holders release, a second reload succeeds.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R3",
          "quote": "A reload during a migration (MIGRATING with separate serving and writer runtimes, SWITCHING, or a concurrent start or cutover) is refused with a clear error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R7",
          "quote": "Two concurrent reloads, latched around publication: each executes against a valid lifecycle owner, none touches closed services, and neither overwrites the newer publication.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R8",
          "quote": "a queued reload never resurrects serving;",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R2",
          "quote": "Covers forked children and cancelled-but-running work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R5",
          "quote": "Admission, cancellation, deadlines and worker ownership of ordinary RPCs are unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/runtime-reload-subscriptions.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadHttpIntegrationTest.java"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-c93a81d9c-20261021",
  "verdict": "accept",
  "digest": "sha256:cd77bbd04fdb28881a696ebf38018112764fe1601ffd35cfa3b76aad75d0b2b3",
  "note": "Verified c93a81d9c: 216 candidate checks, 167 predecessor preservation checks, native HTTP reload/ingestion/backfill/restart/migration checks; tmp/verify-http/c93a81d9c/report.md"
}
```
