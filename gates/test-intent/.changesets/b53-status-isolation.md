---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b53-status-isolation-builder-20261010
---

The before-state is ff0c00f5e. The copied independent verifier assertions are retained, with additional per-owner and post-action cases. Script-produced execution evidence binds the exact check sources to candidate execution and assertion failures before. Existing b50 acceptance is not rewritten. Independent intent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui-web/src/shell-v0/utils/pollingLifecycle.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Each status producer publishes independently through real loopback HTTP with production StatusRoutes and security/admission filters. A held or failed producer only invalidates its own status fields; Worker contact and head-local AI stay current. Idle GPU stays fresh through two ten-second periods. A newer healthy Worker observation survives an older pending status-owned failure publication, while external component transitions remain protected by the full-registry conditional update. The real health monitor shortens an already scheduled idle tick when asynchronous reconciliation adopts the busy cadence, before healthy observations expire. The operating response uses the same current component reason and actual assembly time as the top-level response, while preserving measurement timestamps and monotonic content sequences without the sampler lock. Overlapping status occupies and releases its fairness slot and is refused at capacity. Post-action refresh requests fresh=true and observes the changed owner while periodic reconciliation is occupied. Deadlines, coalescing, freeze refusals, recovery and status contents are preserved.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Staleness for each component follows from that component's own sampling period, not a shared constant.",
          "location": "task t-20261005-b6d536 request (b53 builder brief, task item 5)"
        },
        {
          "kind": "owner-words",
          "quote": "Each status component is produced and published independently, so a slow or failed producer marks only its own component stale or unknown and every other component stays current.",
          "location": "task t-20261005-b6d536 request (b53 builder brief, task item 5)"
        },
        {
          "kind": "owner-words",
          "quote": "After a user action, the UI's refresh shows the post-action state. Do not raise the client timeout, add retries or reintroduce blocking.",
          "location": "task t-20261005-b6d536 request (b53 builder brief, task item 5)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b53-status-isolation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui-web/src/shell-v0/utils/pollingLifecycle.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexReadinessPublicationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The coalescing check now observes the actual independent Worker lane and its publication instead of requiring a second sampler thread to contend on the removed sampler-wide RPC lock. It waits for both contact and lifecycle publication before checking recovery. Original contact counts, component states, refusal, conditional-publication ownership, recovery and cached-read assertions remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Preservation: b50's own non-blocking checks, specC status-surface checks, lane F status consumers, frontend status tests.",
          "location": "task t-20261005-b6d536 request (b53 builder brief, task item 5)"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProjectionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleSemanticStuckTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "GPU projection checks pass the same existing six-second window explicitly after the producer now receives its own cadence. Readiness checks explicitly select the same direct-observation mode they previously selected implicitly; production selects cached inputs through the same envelope assembler. Redundant test-only wrapper methods are removed. These three files retain every prior assertion, fixture value, contact provenance and expected reason. Original suites execute on ff0c00f5e; adapted suites execute on the candidate.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Staleness for each component follows from that component's own sampling period, not a shared constant.",
          "location": "task t-20261005-b6d536 request (b53 builder brief, task item 5)"
        },
        {
          "kind": "owner-words",
          "quote": "Preservation: b50's own non-blocking checks, specC status-surface checks, lane F status consumers, frontend status tests.",
          "location": "task t-20261005-b6d536 request (b53 builder brief, task item 5)"
        }
      ]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/StatusReadinessStalenessTest.java"],
      "class": "New",
      "repaired": false,
      "behaviour": "Independent component publications retain their own real observation timestamps. Two real-sampler checks replace shared response-timestamp equality with present observation timestamps bounded by sampling start and response return. Stale flags, zero fresh staleness, and lost-contact provenance assertions remain. Direct envelope calls explicitly retain their former mode. The original common-timestamp oracle passes on ff0c00f5e but is deliberately replaced because independent publications occur at different times; candidate observations are never restamped to imitate a shared pass.",
      "sources": [{"kind": "owner-words", "quote": "Each status component is produced and published independently, so a slow or failed producer marks only its own component stale or unknown and every other component stays current.", "location": "task t-20261005-b6d536 request (b53 builder brief, task item 5)"}],
      "evidence": "gates/test-intent/evidence/b53-status-provenance.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/StatusReadinessStalenessTest.java"]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review; the builder cannot self-accept or delegate.
