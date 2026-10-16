---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b29-integrate-oq-20261008
---

# Integration writer checks

Q2 already uses the shared addressed shell writer. Source contracts follow that existing design.
Historical one-quit legacy expectations are superseded by the Q2 ruling of the 2026-10-08 integration brief recorded in the original changeset (orchestrator wording under the owner's end-to-end delegation, not the owner's words); the corresponding stale evidence is regenerated using the repository runner. No acceptance record is added.

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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "If the newer shell already addresses its requests, keep its mechanism and drop the duplicate.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-08 integration builder brief'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:7693bf2e212105557a4763ce57240c10b50ba1b5a661f6acb4954606e503aa4c"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:3db032103aa1d3d95ebf880d8d48cee9d7a2e7de1faa2140c508d150421e118a",
  "note": "Reviewed the fixture cleanup import as an adaptation retaining the addressed quit/ownership oracles. Own node --test scripts/dev/test-dev-runner-quit.mjs run passed all 12 cases; no Engine or dev stack was started."
}
```
