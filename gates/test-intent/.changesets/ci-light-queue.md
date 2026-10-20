---
schema: test-intent.v1
task: ci-light-queue
author-role: builder
author-session: ci-light-queue-builder-20261020
---

# CI event selection

```json
{
  "entries": [
    {
      "items": [".github/workflows/ci.yml"],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "github CI currently is for development. this needs to be meaningfully cut.",
          "location": "task ci-light-queue request; owner words quoted in the builder assignment, 2026-10-20"
        },
        {
          "kind": "owner-words",
          "quote": "well then propose the new rule for agents and implement everything end to end",
          "location": "task ci-light-queue request; owner words quoted in the builder assignment, 2026-10-20"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then propose the new rule for agents and implement everything end to end",
          "delegationLocation": "task ci-light-queue request; owner words quoted in the builder assignment, 2026-10-20",
          "delegationScope": "Propose the agent CI rule and implement the development CI cost reduction end to end, following the owner's request that GitHub CI be meaningfully cut.",
          "choice": "The orchestrator chose to keep merge queue settings and required contexts unchanged, retain full required work on pull_request and push to main, and execute only Public claims, Build (no model blobs) and Secret scan on merge_group. Other non-matrix jobs and aggregates skip at job level; matrix legs retain expanded names and skip every step. Measured axe, Integration tests and CI wall-clock attribution run only on workflow_dispatch, with attribution still using always(). Runtime, unit and Windows tests no longer execute on the combined tree. This event selection is the orchestrator's delegated choice, not a verbatim owner scenario.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: ADR-0044's prior queue integration description is superseded by this delegated event-selection choice; no stronger conflicting owner source found. CLA and required-context contracts are retained."
    }
  ]
}
```

This entry retires the prior automatic suite repetition and advisory scheduling only.
Product test assertions and task membership are retained. Independent acceptance is pending.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-ci-light-queue-codex",
  "verdict": "accept",
  "digest": "sha256:c76eaa2a47e9facb672afbc7822e8524803b4d9859f0f9f62d4becddf31327ab",
  "note": "Independent diff review: only the delegated merge-group test skips and dispatch-only advisory scheduling change execution; required contexts and PR/main required test commands are retained."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "auditor-ci-light-queue-codex",
  "verdict": "accept",
  "digest": "sha256:c76eaa2a47e9facb672afbc7822e8524803b4d9859f0f9f62d4becddf31327ab",
  "note": "Independent audit: event conditions match the delegated choice; commands, selections, required contexts and strict aggregate results are preserved; before/after checks pass as expected."
}
```
