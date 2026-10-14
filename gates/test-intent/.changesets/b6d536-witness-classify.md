---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b31-witness-classify-20261008
---

# Witness classification and retention intent

Sources are the explicit builder brief. The detached fail-before checks use cd406ca13; the original candidate is never reverted. Node script checks and verifier harness results are linked from the external report, because the evidence producer supports Java, frontend and Rust checks.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperReceiptTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An owner-awaited nonzero helper completion has its purpose; interruption, repeated cancellation and cooperative EOF record intent before acting; an NTSTATUS crash after intent stays unexpected.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "a helper exit the owner awaited and handled is intended, with a reason naming the helper's purpose",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "a helper the owner terminated on purpose (timeout, interruption, cancel) is intended with that reason, recorded by the owner before it acts",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "crash-class exit codes (NTSTATUS failure codes such as 0xC0000005) stay UNEXPECTED even when awaited",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-witness-classify.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperReceiptTest.java#awaitedNonzeroProbeHasItsPurpose",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperReceiptTest.java#interruptionWritesIntentBeforeNativeTermination",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperReceiptTest.java#awaitedCrashStatusRemainsUnexpected",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperReceiptTest.java#realCrashAfterIntentBeforeTerminateStaysUnexpected",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperReceiptTest.java#cooperativeInputCloseRecordsIntentBeforeEof"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerRetentionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Five incarnations retain all witnesses across rotation; deleting evidence at the total retention bound first persists identity-specific evidence-lost receipts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Retain the witness and owner evidence the census needs for the whole run, until the run's stop report is written, within an explicit size bound.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-witness-classify.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerRetentionTest.java#allFiveIncarnationsSurvivePerFileRotation",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerRetentionTest.java#boundWritesLostIdentitiesBeforeDroppingEvidence"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Real Tika availability probes that normally return code 1 execute inside a closed image-specific owner-declared intent window.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "For spawns inside third-party code we cannot wrap (Tika's own Tesseract probes), use an owner-declared, time-bounded intent record that names the expected helper image and reason, written just before the call and closed after it.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-witness-classify.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java#normalTesseractAvailabilityIsCoveredByClosedOwnerIntent"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The real job witness records exact native membership and exit facts; a zero-code exit without owner intent is unexpected; pre-job outsiders remain unclaimed and the writer fence remains effective.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "a witnessed exit with no owner record at all stays UNEXPECTED or unknown, never silently intended.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-witness-classify.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessTest.java#recordsExactBirthAndExitCodeAndNeverClaimsAnOutsideChild"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerWriterTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Rotating at session start retains earlier evidence, including reported corrupt history, rather than silently replacing the previous archive.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Retain the witness and owner evidence the census needs for the whole run, until the run's stop report is written, within an explicit size bound.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-witness-classify.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerWriterTest.java#sessionRotation"
      ]
    },
    {
      "items": [
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/EngineJobHelperContainmentTest.java",
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/EngineJobChildLedgerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Abrupt Engine fixture death still uses code 1 and preserves every containment/ledger oracle. Termination now uses a retained native handle with PID and creation time recorded at start; cleanup never follows descendants to terminate them.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Never stop a process by PID alone, by name, or by following a parent PID, because Windows reuses PIDs.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (builder brief b31, user message 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    }
  ]
}
```

## Acceptance records

Independent verifier acceptance is pending. The builder does not accept its own test intent.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-census4-independent-20261008",
  "verdict": "accept",
  "digest": "sha256:465226ba8c61de8c1840f6e4bec39e3b938fc464f50c9d5845370c739093cab9",
  "note": "Accept test intent only. Independent fail-before assertions and candidate reruns are recorded in F:/agent-sandbox/design-b6d536/codex/verify-census4/report.md. The two containment preservation checks were rerun with native birth rechecks; the initial legacy PID-only baseline result is excluded. Delivered behavior fails S15 in native sibling-owner and live clean-OCR cases."
}
```
