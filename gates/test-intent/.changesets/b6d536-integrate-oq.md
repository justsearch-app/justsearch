---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b29-integrate-oq-20261008
---

# Integration writer checks

Q2 already uses the shared addressed shell writer. Source contracts follow that existing design.
Historical one-quit legacy expectations are superseded by the owner Q2 ruling in the original changeset; the corresponding stale evidence is regenerated using the repository runner. No acceptance record is added.

```json
{
  "entries": [
    {
      "items": [
        "scripts/dev/test-dev-runner-quit.mjs"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "split"
      ],
      "whatMoved": "The old three Rust writer source checks now inspect the production shared ShellActuator, host admission and the graphical/conformance callers. Identity equality, pre-spawn environment installation and fresh UUID nonce assertions remain explicit. The relocated writer oracle is shared and each caller is separately checked; no duplicate legacy writer is accepted. Existing Rust files are unchanged from 9e8c7fae2.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-08 integration builder brief",
          "quote": "If the newer shell already addresses its requests, keep its mechanism and drop the duplicate."
        }
      ]
    }
  ]
}
```
