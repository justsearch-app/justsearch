---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b50-status-timeout-builder-20261010
---

The builder brief requires status to return within the unchanged five-second client deadline
while producers cannot complete. Real HTTP checks hold each blocking dependency, cover boot,
asynchronous fresh requests, failed observations and recovery. The one-second assertion is a
deliberately tighter server budget; no request retry or client-timeout increase is introduced.
The before-state is f6d579a86. Independent intent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/WorkerStatusSamplerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Real GET /api/status stays below one second and returns existing stale/unknown markers with an unchanged five-second client deadline while disk, inference, GPU capabilities/native memory, GPL, conversation protection, telemetry, VDU, device plan, bulk gate, registry, assembly, Worker, RRD, agent availability, online AI, GPU saturation, model-file path resolution or admission cannot answer. Boot returns unknown without joining its first observation; fresh=true schedules rather than joins observation. Failed producers retain the last response as stale; release and successful sampling restore freshness. The existing sampler checks retain RPC counts, sampling cadence and tap ownership; their boot and fresh observations now await the off-request producer.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "status is computed from current state, cheaply, and is never blocked behind inference, I/O or another component's lock; a component that cannot answer in time is reported as such (existing unknown/stale states), not waited for.",
          "location": "task t-20261005-b6d536 request"
        },
        {
          "kind": "owner-words",
          "quote": "Do not raise the client timeout or add retries.",
          "location": "task t-20261005-b6d536 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/b50-status-timeout.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/WorkerStatusSamplerTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusOperatingStateTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusReadinessStalenessTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Fixtures explicitly invoke the existing sampler entry point before reading the completed observation, including after a settings mutation, GPU reader closure and GPU refresh. Before-state status reads implicitly performed those producer calls; the repaired read path only projects their publication. Original producer inputs, component transitions, staleness clock boundaries, settings persistence, lifecycle/readiness/GPU/degradation expectations and real HTTP assertions are retained. Health contract fixtures are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Preservation: status contents and the status-surface checks (specC), lane F status consumers, frontend status tests.",
          "location": "task t-20261005-b6d536 request"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review. The builder cannot self-accept or delegate.
