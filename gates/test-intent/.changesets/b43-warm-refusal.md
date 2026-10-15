---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b43-warm-refusal-20261009
---

# Warm foreground seats and tight deadline preservation

Existing FP1-FP13 expectations are unchanged. The new warm-seat check repairs their previously unexercised timing case. Whole-deadline expiry keeps its existing exception to FP3. Assertion evidence and boundary probe results are recorded in the external b43 report. Independent acceptance is pending.

```json

{
  "entries": [
    {
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "FP1 requires no refusals below the admission limits.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-09 b43 builder request'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP3",
          "label": "agent-drafted, owner-adopted",
          "quote": "A request whose whole deadline passes while queued gets DEADLINE_EXCEEDED as today: the task never runs and its admission slot and serving view are released at once"
        }
      ],
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "class": "New",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "evidence": "gates/test-intent/evidence/b43-warm-refusal-repaired.json",
      "behaviour": "FP1/FP12 repair: sixteen preadmitted HTTP- and MCP-attributed hybrid calls on eight existing idle inference workers must all be accepted before OS scheduling resumes. The seventeenth call keeps INFERENCE_BUSY. Thread and waiting-seat limits remain eight each. The same assertions fail on a37c6ef33 and pass after. Tight 1000 ms whole-deadline preservation: a hybrid seated after its 250 ms semantic deadline answers TEXT with SEMANTIC_BUDGET_EXCEEDED/contended and zero encoder calls. A whole deadline spent queued ends DEADLINE_EXCEEDED without running its body and releases owners. Both outcomes pass on a37c6ef33 and after; no whole-deadline fallback is invented. The supported file-level evidence now includes requestDeadlineWinningBeforeVectorCaptureStaysUntyped and runs all 38 queue cases: before, only the two warm cases fail by assertion and the other 36 pass; after, all 38 pass.",
      "repaired": true
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-fpwarm-20261009",
  "verdict": "accept",
  "digest": "sha256:23e8fd8aab2b6ce11ff55050fd3b09dc9f23459395bb63bdb99dbb0463cd0731",
  "note": "Accept New/repaired test intent: exact baseline has two intended warm assertion failures and 35 queue passes; candidate passes all 37. Tight deadline cases preserve existing outcomes. Independent cancel/purge pool case fails check 2; this acceptance does not accept the implementation. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-fpwarm/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "accept",
  "digest": "sha256:696ea4813b8e948cbb3e83576a670b1f8848107602c70fd955ac2a7dcca8f7a2",
  "note": "Reviewed the corrected source declaration against the unchanged warm-refusal check and before evidence. The current candidate rerun passes and the entry preserves the existing refusal obligation."
}
```
