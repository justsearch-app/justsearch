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
      "behaviour": "The real boot fallback writes CRASH_RECOVERY stop intent before terminating a surviving uncontained predecessor, then writes its observed final child exit under the original ledger child identity. The next boot adds nothing. A failed kill retains a live survivor, an already-gone process records UNKNOWN code/time, and a different actor ending it during the intended stop remains UNEXPECTED.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2",
          "quote": "The owner that kills it must record it: a stop intent before the kill, and a child exit after it with the observed outcome, intended with the fallback reason from the vocabulary (pick the existing token that fits; add one only through the vocabulary register and schema)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2",
          "quote": "If the kill fails or the process is already gone, record that truthfully: unknown time reads as unknown."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-census-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackRecordsIntentAndFinalExitAndNextBootAddsNothing",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackAlreadyGoneRecordsUnknownFactsWithoutInventingATime",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackFailedKillKeepsTheSurvivorAndRecordsNoFictitiousExit",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildBootFallbackTest.java#bootFallbackCrashDuringStopStaysUnexpectedAndUnknownTimeStaysUnknown"
      ]
    }
  ]
}
```

## Acceptance records

Independent verifier acceptance is pending; the builder has not judged its own test intent.
