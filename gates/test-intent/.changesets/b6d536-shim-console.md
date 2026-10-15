---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b37-shim-console-20261009
---

# Exact launcher-child and Engine-console owner receipts

The new native integration class exercises four repaired behaviors without a dev stack or GPU:
a real owned Java launcher creates a real Java child; a pre-interrupted caller starts and interrupts
a real PowerShell probe; and CREATE_NEW_CONSOLE starts an Engine fixture before that Engine installs
its kill-on-close job. The last check crashes the Engine through its birth-bound retained handle,
then checks the successor's OWNER_DIED/ENGINE_CRASH receipt and sampled and unsampled final census.
It also arms an unsampled native console before a clean quit and compares the final census with
actual Engine and console exit codes and times from retained native handles, then checks the
successor's confirmed-gone planned-exit receipt for historical census. Creation and exit
times come from native handles; reconciliation leaves missing exit times unknown.
Every explicit test stop records and rechecks the creation time of its held process.

The deterministic script scripts/dev/test-dev-runner-shim-console.mjs covers direct launcher
inheritance, reversed record order, sibling and recycled creator identities, impossible births,
orphan/grandparent windows, crash codes during intent, a self-contained final receipt after a
missing helper-start, pre-job console identity, failed console identity lookup, clean console
arming without sampler coverage, and a console crash during quit. The existing
dev-runner discovery executes it. These script checks are outside the canonical JVM producer.
Saved verifier L1/L5 raw ledger and OS-exit replay is separate evidence, without invented receipts.

Evidence, cleanup audit and limitations: F:/agent-sandbox/design-b6d536/codex/b37-shim-console/report.md.
Independent acceptance is pending; the builder does not accept its own checks.

```json
{
  "entries": [
    {
      "items": ["modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/LauncherConsoleReceiptTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "A real launcher child inherits only its exact witnessed owned helper's intent; an interrupted real probe keeps its self-contained owner receipt; and a real crashed Engine's pre-job console host gets a surviving-owner OWNER_DIED/ENGINE_CRASH receipt with unknown exit time and expectedTermination=false, retained in sampled and unsampled final census.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "a check that fails on e882e30f7 by assertion for the reason under test and passes after", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b37 builder, user turn 2026-10-09)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-shim-console.json",
      "newChecks": [
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/LauncherConsoleReceiptTest.java#realLauncherChildInheritsOwnerIntent",
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/LauncherConsoleReceiptTest.java#preInterruptedRealProbeKeepsStartAndFinalOwnerReceipt",
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/LauncherConsoleReceiptTest.java#crashedEnginesOwnConsoleGetsSuccessorCrashReceipt",
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/LauncherConsoleReceiptTest.java#unsampledEnginesOwnConsoleIsAccountedOnCleanQuit"
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
  "session": "verify-census7-independent-20261009",
  "verdict": "accept",
  "digest": "sha256:2923bfbfe3bd588b128a8fc330da1efdaa4109cda676c252920e7dd9309e85cd",
  "note": "Independent unchanged-source comparison: four native launcher/console checks fail on 351d0f22e by intended assertions and pass without skips on 4d02d4879. Six new Node assertions fail before; nine cases pass after. Preservation passes both. Evidence F:/agent-sandbox/design-b6d536/codex/verify-census7/. Acceptance covers test intent, not unfinished live behavior."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4a-20261015",
  "verdict": "accept",
  "digest": "sha256:e96371096cfee7319014aa3d30cefab9a589feb4e63dfc311179692dc3f72d0f",
  "note": "Reviewed all four checks and the supervisor/lookup adaptation; the old census ignores lookup context, so the additive no-op exports do not determine the asserted outcomes. All four current checks passed and all four declared before assertions reproduced on e882e30f7."
}
```
