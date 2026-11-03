---
classification: declared-growth
tempdoc: redesign-o1
---

`src/api/generated/schema-types/status-response.ts`: **pin advanced 6 → 8.**

The status response's migration group gains `cleanupWarnings`, a list of index-directory entries
that cleanup left untouched because their ownership is unproven. `gen-wire-schema-types.mjs` emits
the named `IndexCleanupWarning` interface and its `indexCleanupWarningSchema` Zod const for the
list's element and exports both. The UI reads the warnings through the generated `StatusResponse`
type, not through the named element helpers, so outside this generated file those two exports have no consumer. This is
generated output whose single authority is the generator, as `ingfix-wp11-indexed-root-view-schema.md`
records for the same shape, not a hand-written export that lost its consumer.
