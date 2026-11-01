---
classification: declared-growth
tempdoc: 884
---

`src/api/generated/schema-types/indexed-root-view.ts`: **pin advanced 0 → 2.**

`IndexedRootView` gains two per-stage maps, `failedStages` and `missingArtifactStages`
(`Map<String, Boolean>`), so a folder can say which enrichment stages failed or lack their
artifacts. `gen-wire-schema-types.mjs` emits a named `MapStringBoolean` type and its
`mapStringBooleanSchema` Zod const for that map shape and exports both; the UI reads the maps
through the generated `IndexedRootView` type, not through the named helper, so the two exports
have no consumer. This is generated-output growth whose generator is the single authority, as
`uiux-forget-schema-types.md` and `884-surface-projection-plus-preexisting-drift.md` record, not a
hand-written export that lost its consumer. The growth arrived with the two fields in an
earlier commit of this branch; the pin and this declaration follow in the same pull request, before
merge.
