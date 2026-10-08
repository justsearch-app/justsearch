---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b27-job-witness-20261008
---

# Job witness test intent

The new Windows probe uses the real Engine job, native process handles, finite PowerShell children,
and console hosts. A pre-job child gets no witness. All children exit naturally. Reflection lets
this exact check compile on e546e9e54 and fail by assertion for absent witnesses. The final probe
also exercises the writer fence before data-directory lock release.

```json
{
  "entries": [
    {
      "items": ["modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The Engine job owner records identity-bound NEW and EXIT evidence for helpers and console hosts, preserves nonzero unexpected outcomes during its natural-completion await, excludes a real out-of-job child, and fences its writer before releasing app.lock.",
      "sources": [{"kind":"owner-words","location":"task t-20261005-b6d536 request","quote":"Java tests for the witness thread with real processes where the platform allows (spawn a short-lived child inside a test job, assert the NEW and EXIT records carry the right PID, birth and exit code), plus a breakaway or out-of-job child that gets no witness."}],
      "evidence": "gates/test-intent/evidence/b6d536-job-witness.json",
      "newChecks": ["modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessTest.java#recordsExactBirthAndExitCodeAndNeverClaimsAnOutsideChild"]
    }
  ]
}
```

## Acceptance records

Independent acceptance is pending; the builder does not approve its own test intent.
