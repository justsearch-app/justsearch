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
      "behaviour": "After a real Engine kill-on-close job crash, a successor writes its crash-consequence receipt at least 2.5 seconds later. The final stop census keeps OWNER_DIED, ENGINE_CRASH reason, unknown exit time/code and expectedTermination=false for the exact witnessed helper and console hosts, with no accounting gap for them.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 request (b35 builder, user turn 2026-10-08)", "quote": "A receipt's observation time is never used as the helper's exit time or lifetime bound."}],
      "evidence": "gates/test-intent/evidence/b6d536-delayed-crash-receipt.json",
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
