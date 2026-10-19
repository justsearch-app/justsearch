---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b49-vdu
---

The verifier's mixed PDF, search, 45-second cooldown and explicit enrichment pass reproduce zero completed units on origin/main and f6d579a86. The production composition applies the automatic idle threshold at the accepted pass's first interruption checkpoint. An accepted pass needs a fresh activity boundary; automatic acceptance retains its existing idle gate. This entry awaits independent acceptance.

```json
{
  "entries": [
    {
      "items": ["modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/VduActivityCheckpointTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The production-composed interruption signal lets an accepted VDU pass run despite the earlier 45-second-old search, still interrupts for current energy reduction or a bulk hold, clears with those holds, and interrupts for activity during that pass. Build cleanup uses unnamed lifetime-only resources in VduActivityCheckpointTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Find the cause from the Engine and llama-server logs of the failing run (why each unit does not complete: projector not loaded or on a different device than the plan says, a request rejected, a deadline, a gate that never opens, a status that is never written) and fix it at the cause.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind":"owner-adopted-scenario","task":"t-20261005-b6d536","scenario":"S7","quote":"while a game runs or adapter memory is low, no new bulk GPU batch starts","label":"agent-drafted, owner-adopted"}
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-f6d579a86-0b7289066b.json",
      "newChecks": ["modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/VduActivityCheckpointTest.java"]
    },
    {
      "items": ["modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaProjectorPolicyTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "An administrator GPU-off policy keeps the vision projector and llama-server operations on CPU despite the default projector-offload preference, and the managed configuration identity reflects that changed placement.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "live: the verifier's VDU operation completes its units on the candidate with GPU on, and with GPU off either completes on the CPU or reports the existing unavailable/unmet reason (no silent zero-unit completion).", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}
      ],
      "evidence": "gates/test-intent/evidence/b49-vdu-activity.json",
      "newChecks": ["modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaProjectorPolicyTest.java"]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu-codex-20261010",
  "verdict": "reject",
  "digest": "sha256:779354885809c97eecbd5e1aa6612ed75714f7f1a6f09119210a5c6b2d0dd1e1",
  "note": "FAIL LIVE-CHAT-ACTIVITY: real accepted USER chat after capture did not interrupt VDU; both documents completed (2 units, zero failed). Quiet PDF completes on GPU-on and administrator GPU-off CPU, raster-search proof and search interruption/resume pass. Independent/new-check before assertions and candidate passes are recorded. Limits and exclusions: F:/agent-sandbox/design-b6d536/codex/verify-vdu/report.md. Evidence: candidate-chat-activity/user-activity-chat.json, candidate-chat-activity-summary.json, candidate-after-chat; candidate-cpu-proof.json."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu2-codex-20261010",
  "verdict": "reject",
  "digest": "sha256:0f9a83abaf416640de8ed886c933df0f2dcb280194189d03789738c7f51c7af3",
  "note": "Verified exact 7d536ae4f against 164a24b1a. Quiet GPU-on, administrator GPU-off CPU, real chat interruption and later pending-document completion pass. Reject integrated quiet-pass/idle behavior: unattended Brain polling interrupts an accepted pass and blocks automatic capture, while baseline completes and auto-starts. Native bare-Origin GET reproduces interruption. Full checks, exclusions, scope and unverified native cases: F:/agent-sandbox/design-b6d536/codex/verify-vdu2/report.md. Evidence: candidate-live-bare-timer-summary.json; candidate-auto-brain-summary.json; candidate-cpu-proof.json."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:52fe0e803dcde349aee1e5f1e37aa96d78726f7864182b67642ef0e32bd4878b",
  "note": "Reviewed the delegated-choice relabel and both production checkpoint/projector oracles against current code. Candidate checks passed, with unchanged assertion before evidence at f6d579a86."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5a-20261016",
  "verdict": "accept",
  "digest": "sha256:10a24e281b86b9f84296c979f7f891302df1a4f772fb147c1e11decbdb0f07a9",
  "note": "Re-bound to origin/main 298354254 after confirming unnamed resources preserve scope, lifetime and assertions. Both listed candidate checks pass and f6d579a86 reproduces the checkpoint and projector assertion failures."
}
```
