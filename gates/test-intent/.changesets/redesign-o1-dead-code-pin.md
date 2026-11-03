---
schema: test-intent.v1
task: t-20261102-redesign
author-role: orchestrator
author-session: 36cedc77-c806-45bd-af3b-93bd126c9108
---

# O1 dead-code pin

The dead-code ratchet is unchanged. One pin moves for generated output, declared in
`gates/dead-code/.changesets/redesign-o1-cleanup-warning-schema.md`. The source quotes the task
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
          "quote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "location": "task t-20261102-redesign request (2026-11-02)"
        }
      ],
      "rule": "The dead-code ratchet rejects measured unused counts above the pinned per-path baseline and rejects undeclared upward baseline shifts.",
      "whySurvives": "The predicate is unchanged (scripts/governance/gates/dead-code/enforcer.mjs and truth-table.mjs are not in the diff). One pin moves: src/api/generated/schema-types/status-response.ts 6 -> 8. The two new exports are the IndexCleanupWarning interface and the indexCleanupWarningSchema const, which gen-wire-schema-types.mjs emits for the element of the new cleanupWarnings list in the status response. They are declared as growth in gates/dead-code/.changesets/redesign-o1-cleanup-warning-schema.md in the same pull request. Every other pin is unchanged. The dead-code gate passes on the candidate after npm run knip:report, and failed on the previous head with silent-growth for that file."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a061-7837-7570-bbbd-542ef034213f",
  "verdict": "accept",
  "digest": "sha256:4405484bdae1ebf875afd3b1d5ed79a3293737999e2c2f144821e6f10935d950",
  "note": "verifier-6 on 1f593b9625: both new exports are generated cleanup-warning element helpers with no outside consumer; only the named pin changed; dead-code gate fails before (silent growth 6 -> 8) and passes after; generator --check passes."
}
```
