---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b35-crash-receipt-20261008
---

# Delayed crash receipts retain their owner classification through final census

Evidence and cleanup audit: F:/agent-sandbox/design-b6d536/codex/b35-crash-receipt/report.md.
The existing immediate receipt assertions remain. A second native integration case delays the
successor's receipt by 2.5 seconds after a real kill-on-close Engine job crash, then runs the actual
Node final census and checks every witnessed helper and console host. OWNER_DIED remains distinct
from intended termination; exit time and code remain unknown, without an accounting gap.

The new deterministic script, scripts/dev/test-dev-runner-delayed-crash-receipt.mjs, is discovered
by the existing dev-runner test runner. It covers sampled and unsampled delayed receipts, a delayed
ordinary receipt, and preservation controls for fast receipts, exact helper birth, unauthorized
writer, post-job live sightings, actual crash status during intent, and failed identity lookup.
Script checks are outside this gate's scope and evidence runner, so their real before/after logs
are retained in the external report, separately from the canonical JVM evidence.

The unchanged canonical producer records a candidate pass and an assertion failure on 738c61aa4.
Its TEMP/TMP worktree root was set to the mandated worktrees directory. The existing regular
node_modules directory was temporarily retained inside the workspace's ignored build directory,
so the producer created no junction and its worktree removal traversed none. node_modules was
restored and its Vite package checked afterwards. Independent acceptance remains pending. No
governance tooling, old evidence, or acceptance is rewritten.

```json
{
  "entries": [
    {
      "items": ["modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/CrashedEngineHelperReceiptTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "After a real Engine kill-on-close job crash, a successor writes its crash-consequence receipt at least 2.5 seconds later. The final stop census keeps OWNER_DIED, ENGINE_CRASH reason, unknown exit time/code and expectedTermination=false for the exact witnessed helper and console hosts, with no accounting gap for them. Build cleanup asserts child-control acquisition prerequisites in CrashedEngineHelperReceiptTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "A receipt's observation time is never used as the helper's exit time or lifetime bound.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b35 builder, user turn 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-738c61aa4-d07287285a.json",
      "newChecks": ["modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/CrashedEngineHelperReceiptTest.java#delayedSuccessorReceiptSurvivesFinalStopCensus"]
    }
  ]
}
```

## Acceptance records

Pending independent verification. The builder does not accept its own check changes.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-census6-independent-20261008",
  "verdict": "accept",
  "digest": "sha256:1667219acb83740693de672e5878acd06a95f1eeb3a938bcd7ec8eba0a63d73a",
  "note": "Independent before/after: the added JVM assertion fails on 738c61aa4 for UNKNOWN instead of OWNER_DIED and passes on e882e30f7. Real kill-on-close jobs with 2.5 and 8 second receipt delays retain helper and console-host crash receipts on the candidate. Timing preservation controls and seven module suites pass. This accepts test intent; live delivery checks remain pending and older owner-coverage evidence is still stale."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4a-20261015",
  "verdict": "accept",
  "digest": "sha256:349423666987c04df2cd4824b4d3da45a419ed4df0e2d7958b058886804aafec",
  "note": "Reviewed supervisor re-encoding and the add-only census lookup compatibility exports; the historical census ignores the added context, so the stubs cannot create this failure. Current check passed and the declared delayed-receipt assertion failure reproduced on 738c61aa4."
}
```
