---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: orchestrator
author-session: 8c330fb4-0ab8-439d-8de2-6a42f0669e2b
---

# Forget dead-code pin

The dead-code ratchet is unchanged; one pin advances for generated output, declared in
`gates/dead-code/.changesets/uiux-forget-schema-types.md`. The source quotes the task request; the
independent acceptor verifies it.

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
          "quote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "location": "task t-20261019-bd718f request (2026-10-19)"
        }
      ],
      "rule": "The dead-code ratchet rejects measured unused counts above the pinned per-path baseline and rejects undeclared upward baseline shifts.",
      "whySurvives": "The predicate is unchanged (scripts/governance/gates/dead-code/enforcer.mjs and truth-table.mjs are not in the diff). One pin moves: src/api/generated/schema-types/index.ts 53 -> 55, the two barrel re-exports gen-wire-schema-types.mjs emits for the new forget-outcome.v1 and forget-page.v1 schemas, declared as growth in gates/dead-code/.changesets/uiux-forget-schema-types.md in the same commit. src/api/domains/forget.ts stays unpinned at 0 because readForget is no longer exported. Every other pin is unchanged; the dead-code gate passes on the candidate after npm run knip:report."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1813f-fc57-7f32-bd00-90a178fbf102",
  "verdict": "accept",
  "digest": "sha256:66e37043d7dcdb77eca97cbedc9732d5ba9a7bbdf96fb50a50063793c919c0e7",
  "note": "Structural rule kept: the sole pin increase covers two generated Forget barrel exports; both ratchet protections remain effective."
}
```
