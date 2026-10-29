---
schema: test-intent.v1
task: t-20261019-91c3ab
author-role: builder
author-session: 01a1838f-fb6a-75f1-a1c8-8f980b792103
---

# Test-intent entries

The orchestrator generates execution evidence after committing this candidate; the builder does not run run-evidence or write acceptance records.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LedgerReconcilerTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4d: owner_died_alive remains open across a second boot; the reconciler never kills a survivor."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "First reconciliation records the live survivor; a second boot previously treated that receipt as closed and lost its ownership identity."
      },
      "reproduction": "First reconciliation records the live survivor; a second boot previously treated that receipt as closed and lost its ownership identity.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LedgerReconcilerTest.java"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4b: failed spawn and terminal exit cleanup leave a live owner's helpers and manifest untouched. Existing dead-owner cleanup oracles remain; those fixtures now encode their Engine owner."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no leak across a requested scope",
        "reproduction": "A losing shell reads a manifest whose Engine owner is still alive, then terminal spawn-failure/exit cleanup terminates that owner's identity-matched helper."
      },
      "reproduction": "A losing shell reads a manifest whose Engine owner is still alive, then terminal spawn-failure/exit cleanup terminates that owner's identity-matched helper.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#tests::losing_shell_cleanup_refuses_a_live_owners_helpers"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4/R4b: the real shell actuator passes predecessor manifest evidence through successful and failed spawn attempts. A preservation check also pins EngineHost's existing refusal to reuse a PID-less predecessor's port or token."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "spawn_engine unlinks manifest.json before invoking the spawn closure, losing crash ownership even when the replacement cannot launch."
      },
      "reproduction": "spawn_engine unlinks manifest.json before invoking the spawn closure, losing crash ownership even when the replacement cannot launch.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs#shell_actuator::commit_transport_tests::spawn_keeps_predecessor_evidence_even_when_admission_fails"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The manifest fixture now includes the actual supervised Engine owner PID. The same real helper, coordinator stop, cleanup, and installer-launch assertions are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "R4b/R6: terminal cleanup requires a dead Engine owner; updater cleanup still ends the predecessor helper before launching the installer."
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
  "session": "verifier-91c3ab-b-claude",
  "verdict": "accept",
  "digest": "sha256:08f1cc1c6b08376983fca248b7bf1c51d6ae09eea8b49c5815246d9a262cfcc7",
  "note": "Claude verifier on 5dcc5db048: D scenarios R4,R4b,R4c,R4d,R4e,R5,R7 fail on b744db8744 by assertion and pass on candidate (JUnit, cargo, real Engine harness on Linux, conformance tauri+dev-runner); K R6,R8,R11-Linux pass on both; manual SIGKILL crash classified crashed and helper reconciled; mutations M1-M5b caught. Windows unverified locally; F3 real trigger unproven (synthetic conformance only)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b3-claude",
  "verdict": "accept",
  "digest": "sha256:0e44cf93311b07214fd4d96935be24f437f3089a55d5f9d3b4e181ae835db430",
  "note": "Claude verifier on fe4172eab2: re-accepted after regenerated evidence; :modules:ui:test and :modules:app-api:test pass, shell cargo test 266 pass, tauri 62/62 and dev-runner 52/52 conformance, real-Engine harness children/handoff/boot-quit/boot-failure PASS."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b4-claude",
  "verdict": "accept",
  "digest": "sha256:2bb6b97cfb21527cb9f3614a4e641a4bc5361b0f89ce83a6cf33c7b685074424",
  "note": "Re-accepted at 753deb42cb: only the evidence digests moved; no Java or product change outside modules/shell and the conformance fake. ui 2280 tests pass (rerun, quarantine checks included), LedgerSurvivorRotationTest passes, real-Engine harness children/handoff/boot-quit/boot-failure pass, tauri 62/62 and dev-runner 52/52 conformance pass on both trees."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-91c3ab-b5-claude",
  "verdict": "accept",
  "digest": "sha256:42bc588f8c97434aa6f6e105fa9a43ef4fab3f35d029039d53a7d4036ae7184a",
  "note": "Re-accepted at 5f6bb672c9: with the bound-exec helper overlaid on b744db8744, losing_shell_cleanup_refuses_a_live_owners_helpers fails by assertion (after_exit_cleanup: exit cleanup killed the live owner's helper) and passes on the candidate; a verifier variant exercising only the spawn-failure path also fails before and passes after. All four failBefore checks in crash-runtime-part-b.json fail by assertion for the reason under test. Full shell lib suite 269/269 (1 ignored live test run separately, passed), RuntimeManifestRecoveryTest 6/6, LedgerReconcilerTest 2/2, tauri conformance 62/62."
}
```
