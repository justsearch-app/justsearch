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
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Wait for all existing positive observations (the normal child's exact NEW/EXIT pair, the crash child's exact NEW/EXIT pair and the console-host start witness) for up to 30 seconds instead of using only the crash receipt as a five-second barrier. The observer and ledger writer advance independently for each process. Three natural combined attempts passed the witness; a fourth unchanged combined attempt with the owned normal helper briefly preempted reproduced witness.normal=false, with its matching PID/birth/code-0 records arriving later in the same printed ledger. This is a test-only exit-order assumption, not a product durability failure. The exact PID, birth, contained flag, exit code, classification and exactly-one-record predicates, the outside-child exclusion, host predicate and writer-fence assertion all remain unchanged. Enclosing bounds become 90 seconds for the probe and 120 seconds for the test. Emit spawned probe/helper PID plus creation instant for process accounting. No product behavior changes; the New entry keeps its original e546e9e54 before-state.",
      "sources": [{
        "kind": "owner-words",
        "quote": "Make the test robust without weakening its assertion. Waiting on the condition with a generous bound is fine; loosening what is asserted is not.",
        "location": "task t-20261005-b6d536 agreement"
      }]
    },
    {
      "items": ["modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The Engine job owner records identity-bound NEW and EXIT evidence for helpers and console hosts, preserves nonzero unexpected outcomes during its natural-completion await, excludes a real out-of-job child, and fences its writer before releasing app.lock.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Java tests for the witness thread with real processes where the platform allows (spawn a short-lived child inside a test job, assert the NEW and EXIT records carry the right PID, birth and exit code), plus a breakaway or out-of-job child that gets no witness.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-job-witness.json",
      "newChecks": ["modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessTest.java#recordsExactBirthAndExitCodeAndNeverClaimsAnOutsideChild"]
    }
  ]
}
```

## Acceptance records

Independent acceptance is pending; the builder does not approve its own test intent.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-census3-independent-20261008",
  "verdict": "accept",
  "digest": "sha256:26135eb5e340eb10af452ebeb0c84d3dad6e76ce47503c693f8a86cbbfc5eb50",
  "note": "The real Windows probe fails by assertion on e546e9e54 because NEW and EXIT witnesses are absent, and passes on 6f78776b6. Independent held-handle probes also match all births and codes for a 25 ms helper and a burst of 64 helpers. This accepts test intent only; live S15/S18 behavior is still under verification."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "reject",
  "digest": "sha256:2b4200130f9f1352b287c8e79b8b237c9209ef6894d4eb6dce8e8836e9545739",
  "note": "Candidate rerun mismatch: recordsExactBirthAndExitCodeAndNeverClaimsAnOutsideChild reports witness.normal=false and fails its exact PID/birth/code assertion, while the other four flags pass. Matching normal records arrive later in the printed ledger, suggesting an observation race; the executed assertion failure still conflicts with the recorded pass."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4a-20261015",
  "verdict": "accept",
  "digest": "sha256:51bf8b5cb13b54a7b96850fef9370c9363f358e671642be13e88752a62f54049",
  "note": "Reviewed the changed wait condition against the prior oracle: every exact identity, exit-code, classification, exclusion and writer-fence assertion is retained. The current native probe passed and its absent-witness assertion failure reproduced on e546e9e54."
}
```
