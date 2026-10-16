---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b16-census-repair-20261007
---

# Test-intent entries

S15 census/boot-accounting repair. CRASH_RECOVERY is already in the vocabulary; no token or schema change.
The four Java regression checks execute against native Windows processes or injected stop outcomes.
Each fails by assertion on 46c74f725 for absent fallback intent/final accounting and passes on the candidate.
The test class probes the actual boot factory when available and the baseline factory otherwise,
so baseline failures are assertions about missing records, not missing APIs or compilation.
Existing reconciler tests are unchanged.

The script census self-tests are out of scope for this gate. Their three-generation fixture fails
on 46c74f725 with a nested tesseract gap and orphan console host, then passes on the candidate,
including outside-job, unknown-parent and late-descendant negatives. Evidence and full check logs:
F:\agent-sandbox\design-b6d536\codex\b16-census-repair\report.md.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The real boot fallback writes CRASH_RECOVERY stop intent before terminating a surviving uncontained predecessor, then writes its observed final child exit under the original ledger child identity. The next boot adds nothing. The original successful native REQUESTED/code assertions execute under an explicit Windows prerequisite in the Windows-native UI task. An unconditional real-stop seam withholds FILETIME and unavailable exit code/time, proves the real captured JDK identity was checked, and asserts UNKNOWN facts, preserved intent linkage, a cleared registry and an idempotent next boot. Scripted predecessor cases forward the actual held JDK identity and executable checks, preserving the ownership fence on POSIX as well as Windows. A failed kill retains a live survivor, an already-gone process records UNKNOWN code/time, and a different actor ending it during the intended stop remains UNEXPECTED. All original assertions remain; five-check evidence uses the original before-state 46c74f725 without overlays or compatibility patches.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "The owner that kills it must record it: a stop intent before the kill, and a child exit after it with the observed outcome, intended with the fallback reason from the vocabulary (pick the existing token that fits; add one only through the vocabulary register and schema).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "If the kill fails or the process is already gone, record that truthfully: unknown time reads as unknown.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-census-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackRecordsIntentAndFinalExitAndNextBootAddsNothing",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackAlreadyGoneRecordsUnknownFactsWithoutInventingATime",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackFailedKillKeepsTheSurvivorAndRecordsNoFictitiousExit",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackCrashDuringStopStaysUnexpectedAndUnknownTimeStaysUnknown",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackWithoutExitFactsRecordsUnknownAndNextBootAddsNothing"
      ]
    }
  ]
}
```

## Acceptance records

Independent verifier acceptance is pending; the builder has not judged its own test intent.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-census-independent-20261007",
  "verdict": "accept",
  "digest": "sha256:fcdbedd79eac686c395b053988359f7baa6199a80faa2180711349b4b5ed9148",
  "note": "The four new fallback checks follow S15 and the owner-quoted obligations, fail by assertion on 46c74f725, and pass on 0318c9235 with no skips. This accepts test intent only. Independent census PID-reuse and lifetime cases fail; live verification remains pending."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-census2-independent-20261007",
  "verdict": "accept",
  "digest": "sha256:fcdbedd79eac686c395b053988359f7baa6199a80faa2180711349b4b5ed9148",
  "note": "The four Java fallback checks follow S15, fail by assertion on 46c74f725 for missing intent/final observations, and pass on 2f7f2bac8 without skips. This accepts test intent only. The census O2 and historical L5 replacement assertions fail on 50127b3f5 and pass on the candidate; O3 still fails independently."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-census3-independent-20261008",
  "verdict": "accept",
  "digest": "sha256:fcdbedd79eac686c395b053988359f7baa6199a80faa2180711349b4b5ed9148",
  "note": "Independently reran the four real boot fallback checks: all fail by assertion on 46c74f725 for absent intent/final records, and all pass without skips on 6f78776b6. The revised census negatives reject ancestry-only N4/O3 claims. This accepts test intent only, not behavior."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "accept",
  "digest": "sha256:9eaf2a899382a1f2f7b297761494b3baafd78a4d62579e827c97512aabfb4b3e",
  "note": "Reviewed the source-only correction against item 6 and the exit-ledger classification/timestamp oracles. Candidate checks passed; test items and execution evidence are unchanged since cd406ca13."
}
```
