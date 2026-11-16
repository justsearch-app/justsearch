---
classification: declared-growth
tempdoc: fm-library-6c
---

`src/api/generated/schema-types/status-response.ts`: **pin advanced 9 → 11.**

`operating.indexing` gains `readingRuleReasons` (slice 6c, "Read waiting files now"), a second list
of the same `IndexingReason` vocabulary that `reasons` already uses. The Java schema generator now
emits the shared enum once under `$defs`, so `gen-wire-schema-types.mjs` exports the named
`IndexingReason` type and its `indexingReasonSchema` Zod const. The UI reads both lists through the
generated `StatusResponse` type, not through the named helpers, so outside this generated file those
two exports have no consumer. This is generated output whose single authority is the generator, as
`redesign-o1-cleanup-warning-schema.md` records for the same shape, not a hand-written export that
lost its consumer.
