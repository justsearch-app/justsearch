---
classification: declared-growth
tempdoc: shell-s1
---

`src/api/generated/schema-types/status-response.ts`: **pin advanced 8 → 9.**

The frame status slice (S1) retires the bottom status bar (`StatusDeck`), the only consumer of the
generated `ComponentState` type (the per-component readiness of the connection dot). The status pill
reads readiness through the shared verdict, not per-component states, so that named export now has
no consumer outside this generated file. The generator is the single authority for this file, so the
export stays (as `redesign-o1-cleanup-warning-schema.md` records for the same shape).

Hand-written exports left without a consumer by the same retirement were removed or made
module-private instead of re-pinned: `statusWithoutVerdictFlavor` (aiStateStore), `orElse` (known),
`ENRICHMENT_BLOCKED_BODY` (enrichmentCoverage), `EnrichingStageRow` (indexingProgress) and
`VerdictKind` (verdict).
