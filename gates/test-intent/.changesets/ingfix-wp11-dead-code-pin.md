---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: orchestrator
author-session: 36cedc77-c806-45bd-af3b-93bd126c9108
---

# WP11 dead-code pin

The dead-code ratchet is unchanged; one pin is added for generated output, declared in
`gates/dead-code/.changesets/ingfix-wp11-indexed-root-view-schema.md`. The source quotes the task
request; the independent acceptor verifies it.

```json
{
  "entries": [
    {
      "items": [
        "gates/dead-code/baseline.txt"
      ],
      "class": "Structural rule kept",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "location": "task t-20261030-ingfix request (2026-10-30)"
        }
      ],
      "rule": "The dead-code ratchet rejects measured unused counts above the pinned per-path baseline and rejects undeclared upward baseline shifts.",
      "whySurvives": "The predicate is unchanged (scripts/governance/gates/dead-code/enforcer.mjs and truth-table.mjs are not in the diff). One pin is added: src/api/generated/schema-types/indexed-root-view.ts 0 -> 2, the MapStringBoolean type and mapStringBooleanSchema const that gen-wire-schema-types.mjs emits for the two new Map<String, Boolean> fields of IndexedRootView (failedStages, missingArtifactStages), declared as growth in gates/dead-code/.changesets/ingfix-wp11-indexed-root-view-schema.md in the same commit. Every other pin is unchanged; the dead-code gate passes on the candidate after npm run knip:report and failed on the previous head (silent-growth for that file)."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a193f7-3c14-7813-8e9f-83a7da90847d",
  "verdict": "accept",
  "digest": "sha256:6e2e91dafa54b032620249989a2ee4e426cb3f804f3d44766c0db75321d4ab59",
  "note": "Verified generated-only pin addition, unchanged predicate and all other pins, baseline failure and candidate pass."
}
```
