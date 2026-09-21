---
title: Health Readiness Contract
type: contract
status: stable
updated: 2026-09-21
description: Schema-2 Engine component observations plus diagnostic readiness dimensions and composites.
---

# Health Readiness Contract

The filename is retained as the stable link created with the original readiness
envelope. The current wire contract is schema 2. Keeping the path avoids breaking
canonical, historical, and embedded-skill links merely to restate the schema number.

## Scope

This contract defines the schema-2 lifecycle projection shared by `/api/health`
and `/api/status`, plus `/api/status.readiness` diagnostics and migration rules.
The Engine component registry is the lifecycle authority. Health, status, the
runtime manifest, and compatibility `Capability` readers are projections of one
immutable registry observation; none owns a mutable lifecycle copy.

## Canonical Surface

`GET /api/status` includes additive field:

```json
{
  "readiness": {
    "schemaVersion": 2,
    "observedAt": "2026-02-19T08:00:00Z",
    "engineComponents": {
      "api": { "state": "READY", "stateSince": "2026-02-19T07:59:30Z", "appliedVersion": "...", "desiredVersion": "...", "mode": "IN_PLACE", "deadlineMs": 0, "recoveryAttempts": 0, "evidence": "boundPort=8080" },
      "index": { "state": "READY", "stateSince": "2026-02-19T07:59:45Z", "appliedVersion": "...", "desiredVersion": "...", "mode": "BESIDE", "deadlineMs": 30000, "recoveryAttempts": 0, "evidence": "Worker serving" },
      "encoders": { "state": "READY", "stateSince": "2026-02-19T07:59:40Z", "appliedVersion": "...", "desiredVersion": "...", "mode": "BESIDE", "deadlineMs": 30000, "recoveryAttempts": 0, "evidence": "encoders ready" },
      "generative": { "state": "ABSENT", "stateSince": "2026-02-19T07:59:30Z", "deadlineMs": 30000, "recoveryAttempts": 0 }
    },
    "components": {
      "workerControlPlane": { "state": "READY", "reasonCode": null, "source": "lifecycle_snapshot", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "indexServing": { "state": "READY", "reasonCode": null, "source": "worker_status", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "ai": { "state": "DEGRADED", "reasonCode": "inference.offline", "source": "lifecycle_inference", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "embedding": { "state": "UNKNOWN", "reasonCode": "worker.health.embedding_probe_missing", "source": "worker_health_check", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "visualTextExtraction": { "state": "READY", "reasonCode": null, "source": "worker_status", "observedAt": "...", "stale": false, "stalenessMs": 0 },
      "visualDocumentUnderstanding": { "state": "READY", "reasonCode": null, "source": "head_vdu_status", "observedAt": "...", "stale": false, "stalenessMs": 0 }
    },
    "composites": {
      "retrieval": { "state": "READY", "reasonCodes": [] },
      "aiFeatures": { "state": "UNKNOWN", "reasonCodes": ["inference.offline", "worker.health.embedding_probe_missing"] }
    }
  }
}
```

This sample shows a **reachable index**, which is why every diagnostic component carries `stale: false` and
`stalenessMs: 0`; it also abbreviates `components` to a representative subset. See
[Staleness Semantics](#staleness-semantics) for what these fields carry when Worker contact is lost.

`readiness.engineComponents` always contains `api`, `index`, `encoders`, and
`generative`. Each value is the registry observation: six-state `state`, optional
`reasonCode`, wall-clock state epoch `stateSince`, applied and desired
configuration digests, last compose `mode`, start `deadlineMs`,
`recoveryAttempts`, and optional diagnostic `evidence`. Nullable fields are
omitted by the JSON serializer.

The top-level lifecycle subset on both `/api/health` and `/api/status` is a
smaller projection of that same snapshot. It uses snake-case wire fields
`schema_version`, `observed_at`, and four fixed `components` slots. Each slot has
only `state`, `reason_code`, and `state_since`. The camel-case
`readiness.engineComponents.*.stateSince` and snake-case
`components.*.state_since` values identify the same state epoch; consumers must
not treat them as two clocks or two authorities.

## Engine Component States

Registry components use exactly six states:

1. `ABSENT`: intentionally not requested. Optional absence does not degrade the aggregate.
2. `STARTING`: requested and starting.
3. `READY`: serving its owned responsibility.
4. `RELOADING`: replacing or reconfiguring the owned runtime.
5. `UNAVAILABLE`: requested but presently unable to serve.
6. `FAILED`: activation, composition, or runtime failure.

The overall lifecycle prioritizes essential `FAILED`/`RELOADING`, then essential
`STARTING`, then unavailable essential components, then requested optional
components. An optional component in `ABSENT` preserves retrieval readiness.
Reason retention belongs to decorated component handles; compatibility
`Capability` objects are read-only adapters over the registry.

Recovery occurrences come from the health monitor's recovery decision, separately
from coalesced state observations. Shutdown revokes recovery before stopping the
local API or Head owners. A physical start can outlive the monitor's bounded close
wait; its client remains bootstrap-owned, and its late completion cannot publish
recovery or rebind the stopped API.

## Typed States

Allowed values:
1. `READY`
2. `DEGRADED`
3. `NOT_READY`
4. `NOT_CONFIGURED`
5. `UNKNOWN`

Interpretation:
1. `READY`: dependency is serving for expected path.
2. `DEGRADED`: serving but with known reduced capability.
3. `NOT_READY`: expected dependency exists but is currently unavailable/failing.
4. `NOT_CONFIGURED`: dependency intentionally absent in this runtime configuration.
5. `UNKNOWN`: status cannot be established (for example missing probe signal).

## Migration and Compatibility

1. `aiReady` and `embeddingReady` remain exposed as legacy aliases.
2. New consumers must prefer `readiness.components.ai.state` and `readiness.components.embedding.state`.
3. `aiReady` is derived from canonical AI readiness (`state == READY`).
4. `embeddingReady` is derived from canonical embedding readiness (`state == READY`).
5. Legacy aliases can be removed only after a versioned contract migration with dual-read window.
6. Schema-2 consumers read lifecycle ownership from `engineComponents`; they do
   not reconstruct it from legacy booleans or diagnostic dimensions.
7. Unknown component fields remain forward-compatible. Missing component
   entries or unknown states fail closed for host supervision.

## State Mapping Rules

1. Registry `index` state maps to `readiness.components.workerControlPlane.state`.
2. Registry `index` state supplies the essential part of `readiness.components.indexServing`; compatibility, embedding and throughput observations can still degrade that diagnostic while the registry component remains `READY`.
3. Registry `generative` state maps to `readiness.components.ai.state` with source `lifecycle_inference`: `READY` → `READY`, `ABSENT` → `NOT_CONFIGURED`, `STARTING` → `NOT_READY`, and `RELOADING`/`FAILED`/`UNAVAILABLE` → `DEGRADED`.
4. Worker embedding probe maps to `readiness.components.embedding.state` with source `worker_health_check`.
5. Worker visual extraction status maps missing baseline readable visual text to `readiness.components.visualTextExtraction` with source `worker_status`.
6. Head VDU capability status maps enrichment-only visual understanding blockers to `readiness.components.visualDocumentUnderstanding` with source `head_vdu_status`.
7. OCR and VDU blockers degrade `retrieval` only while baseline visual text is still missing. VDU enrichment-only blockers degrade `aiFeatures`, not `retrieval`.
8. Missing embedding probe boolean maps to `UNKNOWN` with reason code:
- `worker.health.embedding_probe_missing`
9. Composite state precedence:
- `NOT_READY`
- `UNKNOWN`
- `NOT_CONFIGURED`
- `DEGRADED`
- `READY`
10. A `READY` component MAY carry a reason code. Such a code is *informational* — it names a
   condition that is present but is not degradation (e.g. an optional component that is absent by
   design). Consumers must read **state** as the degradation signal: the presence of a reason code
   on a component or on a composite's `reasonCodes` list is not, on its own, evidence of
   degradation.

## Reason Code Taxonomy

Common reason codes:
1. `worker.not_configured`
2. `worker.not_started`
3. `worker.starting`
4. `worker.unavailable`
5. `index.not_healthy`
6. `inference.starting`
7. `inference.offline`
8. `worker.health.embedding_not_ready`
9. `worker.health.embedding_probe_missing`
10. `worker.status_missing`
11. `ocr.disabled`
12. `ocr.engine_missing`
13. `ocr.language_missing`
14. `vdu.ai_offline`
15. `vdu.insufficient_vram`
16. `vdu.missing_mmproj`
17. `vdu.circuit_open`

Worker `health_check.ai_ready` remains worker-local telemetry and is non-authoritative for governance readiness.

## Host Essential-Readiness Gate

The development runner and native shell gate restart-budget stability on exactly
`readiness.engineComponents.index.state == "READY"` with a valid
`readiness.engineComponents.index.stateSince` UTC epoch. They do not substitute
the `indexServing` diagnostic: that dimension may correctly be `DEGRADED` while
keyword search remains available.

`stateSince` is an epoch token, not an elapsed-time clock shared with the JVM.
Each host measures the stability interval with its own monotonic clock. A
non-READY state, missing or malformed epoch, failed liveness probe, or a changed
READY epoch restarts that local interval. The JVM's internal monotonic state
timestamp never crosses the process boundary.

## Staleness Semantics

For each readiness component:
1. `observedAt` is ISO-8601 and means *when the fact behind this component was last observed from
   its source*. It is omitted when there is no such observation (see rule 5).
2. `stale` is a boolean freshness flag: `true` means this component's verdict was derived without a
   fresh observation of its source.
3. `stalenessMs` is a non-negative age. It is `0` whenever `stale` is `false`.

Head-local dimensions read head-side supervisor, capability, or monitor state that is current at
response-build time. They always report `stale=false`, `stalenessMs=0`, and the response-build
`observedAt` — a Worker outage does not make them stale.

Worker-observed dimensions are those whose verdict reads the index half's status view. That view
is **not** fetched on the request thread: an internal sampler on the Head's health-monitor schedule
performs the `indexStatus` port call (10 s while idle, 2 s while indexing/backfill/AI activation is in
flight, plus one sample on every capability transition), and a request reports what the last sample
found. Consequently `meta.workerRpcAtMs` is the **sample's** observation time, not a per-request
timestamp, and successive responses within one sampling period carry the same value by design.
`GET /api/status?fresh=true` forces one synchronous sample.

When the sample failed — the call threw, or the worker capability was unavailable — the Head
substitutes a fallback view, so those arms answer from placeholder data. A sample older than three
sampling periods is treated the same way, so a stalled sampler surfaces as loss of contact rather
than as a frozen snapshot presented as fresh. Then:

4. `stale` is `true`, and `observedAt` carries the epoch of the newest *successful* Worker
   observation in this Head process — not the response-build time, which would be a false freshness
   claim over a fallback-derived verdict. `stalenessMs` is the gap from that observation to now.
5. If the Worker has never been reached in this Head process, there is no observation to timestamp:
   `observedAt` is **omitted** rather than fabricated, and `stalenessMs` measures from Head start as
   a lower bound on the out-of-contact gap.

Which dimensions are worker-observed is decided by what each one reads, not by its `source` label:

| Dimension | Worker-observed | Note |
|---|---|---|
| `indexServing`, `embedding`, `chunkEmbedding`, `visualTextExtraction` | yes | read the Worker status/health view directly |
| `visualDocumentUnderstanding` | yes | `source` is `head_vdu_status`, but its gate is the Worker's `visualEnrichmentNeededCount` |
| `gpu` | yes | the NVML sample is head-local, but its saturation-suppression gate reads the Worker's `processingJobsCount` |
| `workerControlPlane`, `ai`, `lambdamartModel`, `telemetry` | no | head-side capability / supervisor / monitor state |

For a dimension mixing head-local and Worker inputs (`gpu`, `visualDocumentUnderstanding`), the
oldest input governs the freshness claim: the timestamp under-claims the freshness of the head-local
part rather than over-claiming the freshness of the Worker part.

Composites aggregate their members' freshness: `stale` is `true` when **any** member component is
stale, and `maxStalenessMs` is the **maximum** `stalenessMs` over the stale members (`0` when
`stale` is `false`). The aggregate is derived from the same member component views in the same
response — it is a projection, not a second observation, so a composite can never disagree with its
members. A composite whose members are all head-local (`telemetry`) therefore stays `stale=false`
through a Worker outage, while `retrieval` and `aiFeatures` go stale as soon as one worker-observed
member does. A consumer may still read the member components' `stale` for per-dimension detail, or
the process-wide `meta.workerRpcStale` for the contact fact itself.

Example of the same envelope after Worker contact is lost (components abbreviated):

```json
{
  "readiness": {
    "components": {
      "indexServing": { "state": "NOT_READY", "reasonCode": "worker.unavailable", "source": "worker_status", "observedAt": "2026-02-19T07:59:12Z", "stale": true, "stalenessMs": 48000 },
      "workerControlPlane": { "state": "NOT_READY", "reasonCode": "worker.spawn_failed", "source": "lifecycle_snapshot", "observedAt": "2026-02-19T08:00:00Z", "stale": false, "stalenessMs": 0 }
    },
    "composites": {
      "retrieval": { "state": "NOT_READY", "reasonCodes": ["worker.unavailable", "worker.spawn_failed"], "stale": true, "maxStalenessMs": 48000 },
      "telemetry": { "state": "READY", "reasonCodes": [], "stale": false, "maxStalenessMs": 0 }
    }
  },
  "meta": { "workerRpcAtMs": 1771488000000, "workerRpcStale": true }
}
```

## Non-Goals

1. Do not change `/api/health` HTTP status mapping: READY and DEGRADED are 200; other lifecycle states are 503.
2. Do not remove legacy readiness booleans without a versioned migration.
3. Do not introduce breaking schema changes on existing status fields.
