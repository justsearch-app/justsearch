---
title: Health Readiness Contract v1
type: contract
status: stable
updated: 2026-08-12
description: Additive typed readiness envelope for /api/status with legacy boolean aliases.
---

# Health Readiness Contract v1

## Scope

This contract defines `/api/status.readiness` semantics and migration rules.
`/api/health` lifecycle semantics remain unchanged.

## Canonical Surface

`GET /api/status` includes additive field:

```json
{
  "readiness": {
    "schemaVersion": 1,
    "observedAt": "2026-02-19T08:00:00Z",
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

This sample shows a **reachable Worker**, which is why every component carries `stale: false` and
`stalenessMs: 0`; it also abbreviates `components` to a representative subset. See
[Staleness Semantics](#staleness-semantics) for what these fields carry when Worker contact is lost.

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

## State Mapping Rules (v1)

1. Worker lifecycle (`/api/health` component state) maps to `readiness.components.workerControlPlane.state`.
2. Worker index status maps to `readiness.components.indexServing.state`.
3. Inference lifecycle state maps to `readiness.components.ai.state` with source `lifecycle_inference`.
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

## Reason Code Taxonomy (v1)

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

1. Do not change `/api/health` HTTP status mapping.
2. Do not remove legacy readiness booleans in v1.
3. Do not introduce breaking schema changes on existing status fields.
