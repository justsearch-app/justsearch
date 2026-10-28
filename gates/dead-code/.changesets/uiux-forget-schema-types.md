---
classification: declared-growth
tempdoc: 884
---

`src/api/generated/schema-types/index.ts`: **pin advanced 53 → 55.**

Forget adds two wire schemas, `SSOT/schemas/forget-outcome.v1.json` and
`SSOT/schemas/forget-page.v1.json`. `gen-wire-schema-types.mjs` re-exports each generated target's
Zod const from the barrel it also generates, so the barrel gains `forgetOutcomeSchema` and
`forgetPageSchema`, two re-exports without a consumer. This is the generated-barrel growth tempdoc
884 recorded (`884-surface-projection-plus-preexisting-drift.md`) and 911 repeated: growth in
generated output whose generator is the single authority, not a hand-written export that lost its
consumer. The pin moves in the same commit as the growth, as `915-schema-types-index-repin-after-614.md`
explains.

The hand-written side adds nothing: `src/api/domains/forget.ts` keeps `readForget` module-private,
since only that module calls it.
