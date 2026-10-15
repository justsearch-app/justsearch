---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b54-vdu-chat-activity
---

Common admitted requests now publish activity to the VDU source. Independent acceptance is pending; the earlier b49 rejection is preserved for the verifier to reassess.

Evidence regenerated on b599d4d7e (phase B). VduRequestActivityTest now registers its routes with ActivityRole, which 164a24b1a lacks, so the check did not compile there. The compat before-patch gates/test-intent/evidence/b54-vdu-request-activity-compat.patch adds only the ActivityRole enum and its three constants, with no methods, and no production code in that state reads it or any route role. With it the check fails before by assertion.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Real HTTP UI/API/MCP-attributed/OpenAI chat, native MCP answer/search/resource/prompt, search, reported typing suggestions, preview, folder browsing, and a newly mounted action all register activity through common admission. A request after pass capture prevents the next document unit, leaves it pending, and a later quiet pass completes it. A request during the first document leaves the second pending. Prior requests and observer polling do not interrupt; quiet passes complete both documents. Build cleanup asserts active bootstrap mocks and the constructed controller adapter prerequisite in VduRequestActivityTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "fix it at the cause so that every user-facing request kind (UI chat, API and MCP chat, search, typing signals the app already reports) feeds the same activity source by construction, rather than by adding chat as one more call site.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "chat during an accepted pass interrupts it at the next unit boundary and the document stays pending; each listed request kind registers activity; prior activity before the pass still does not interrupt it", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"},
        {"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "a pass with no activity completes its units.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-164a24b1a-ce8ef6ef27.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"]
    },
    {
      "items": ["modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/VduActivityCheckpointTest.java"],
      "class": "Adaptation",
      "moved": ["input re-encoding", "instrumentation"],
      "whatMoved": "The same prior/new activity, reduced-energy and bulk-hold inputs and unchanged interruption oracle now additionally stub the shared activity version. Reflection allows the identical test to compile against the old timestamp implementation for fail-before evidence; the prior timestamp mocks remain. No expected outcome, condition or tag was removed. Refreshed b49 evidence replays both original repair checks. Build cleanup uses unnamed lifetime-only resources in VduActivityCheckpointTest, retaining original inputs, resource lifetimes and every existing assertion."
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerRefusalTest.java"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The admission interface mock now calls real default methods so admitRequest forwards into its existing stubbed admit. All 18 producer-refusal inputs, status/error/header assertions and downstream invocation checks remain unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu2-codex-20261010",
  "verdict": "reject",
  "digest": "sha256:d10dd81395ea6c4cd77608336a5024fcd89d137fc39b5a929d7beaaf136ffbde",
  "note": "FAIL LIVE-UNTYPED-TIMER and LIVE-IDLE-UI at exact 7d536ae4f: no-input GET inference/transitions interrupts before the first unit and leaves the document PENDING; 420 seconds of Brain polling prevents automatic capture. Baseline 164a24b1a completes its unit and automatically captures under the same effective UI attribution. Bare-Origin native GET also fails. Real chat interruption/resume now passes. Seven unattended request kinds fail independent bare HTTP/admission/feed checks. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-vdu2/report.md; candidate-live-bare-timer-summary.json; candidate-auto-brain-summary.json. Actual open GUI remains unverified."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:1473e5fea0030d037d2e86907bcf66fca2f851314236f86df03ebca18cd6ed5b",
  "note": "Current entries, evidence, patch and test source match the independent 75917a073 review, including the 164a24b1a assertion rerun and add-only scaffolding. Own candidate HTTP activity checks also passed."
}
```
