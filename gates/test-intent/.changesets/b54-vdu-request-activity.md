---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b54-vdu-chat-activity
---

Common admitted requests now publish activity to the VDU source. Independent acceptance is pending; the earlier b49 rejection is preserved for the verifier to reassess.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Real HTTP UI/API/MCP-attributed/OpenAI chat, native MCP answer/search/resource/prompt, search, reported typing suggestions, preview, folder browsing, and a newly mounted action all register activity through common admission. A request after pass capture prevents the next document unit, leaves it pending, and a later quiet pass completes it. A request during the first document leaves the second pending. Prior requests and observer polling do not interrupt; quiet passes complete both documents.",
      "sources": [
        {"kind":"owner-words","quote":"fix it at the cause so that every user-facing request kind (UI chat, API and MCP chat, search, typing signals the app already reports) feeds the same activity source by construction, rather than by adding chat as one more call site.","location":"task t-20261005-b6d536 request"},
        {"kind":"owner-words","quote":"chat during an accepted pass interrupts it at the next unit boundary and the document stays pending; each listed request kind registers activity; prior activity before the pass still does not interrupt it","location":"task t-20261005-b6d536 request"},
        {"kind":"owner-words","quote":"a pass with no activity completes its units.","location":"task t-20261005-b6d536 request"}
      ],
      "evidence": "gates/test-intent/evidence/b54-vdu-request-activity.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"]
    },
    {
      "items": ["modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/VduActivityCheckpointTest.java"],
      "class": "Adaptation",
      "moved": ["input re-encoding", "instrumentation"],
      "whatMoved": "The same prior/new activity, reduced-energy and bulk-hold inputs and unchanged interruption oracle now additionally stub the shared activity version. Reflection allows the identical test to compile against the old timestamp implementation for fail-before evidence; the prior timestamp mocks remain. No expected outcome, condition or tag was removed. Refreshed b49 evidence replays both original repair checks."
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
