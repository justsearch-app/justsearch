---
schema: test-intent.v1
task: t-20261110-childreg
author-role: builder
author-session: codex-child-reg-20261110
---

# Deterministic managed-child registration exit

The loader fixture's assertions, library selection, inherited environment and launch cases are
unchanged. Its child now waits for stdin EOF; the parent closes stdin after managed start returns.
A separate control-level test pins the registration race using independently controlled process
information, JDK exit status and the exit callback. No running application or shared dev stack is used.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxPackChildEnvironmentTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261110-childreg request",
          "delegationScope": "fixing a recurring CI flake and the production defect it exposes",
          "choice": "Keep every loader-environment assertion and launch case, and make the C fixture wait for stdin EOF, closing stdin only after managed start returns. Pin the identity-capture race in a separate deterministic control-level regression rather than relying on fixture exit timing.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Remove process-exit timing from the Linux downloaded-pack loader test while preserving its full environment and library-precedence contract.",
        "callersAndStateOwners": "LinuxPackChildEnvironmentTest starts a fresh parent JVM for each launch case. Its Parent creates a managed child with pipes and closes that child's stdin after start returns. The test owns its temporary pack, host and library directories, parent and managed processes; no shared application state or dependency tree is mutated.",
        "indirectBehaviours": "The C fixture prints the same value and LD_LIBRARY_PATH, then waits for EOF before exit zero. The parent still reads every stdout byte, awaits the exit and prints its own environment. All child-kind, marker-present/absent, inherited-environment, parent-exit, output and timeout assertions remain unchanged. GCC invocation, executable RPATH and test selection are unchanged.",
        "evidence": "Untouched 33671f1dd5 shared-clone baseline: app-api 487 tests with 0 failures/5 skips; app-inference 599 with 0 failures/1 skip; worker-services 4377 with 46 failures/24 skips. Candidate broad suites: app-api 496 with 0 failures/5 skips; app-inference and worker-services have identical totals and identical failing cases to baseline. All three modules pass pmdMain, pmdTest and spotlessCheck. This complete Linux loader test then executed and passed 20 consecutive times, without skips, under one private busy CPU process that was stopped afterwards. Final focused checks pass 10 control, 2 chat/self-test caller and 2 extraction caller cases; PMD and Spotless pass on the final files. In the shared-clone baseline with only the process-start seam added, six control race assertions fail with the original identity-capture refusal, and all four preservation cases pass. Both caller pairs pass before and after. The changed loader file retains every original assertion. Local reports are under build/child-reg-evidence; independent verification remains required.",
        "unresolvedRisks": "Windows native execution is unavailable on this Linux host. Display/AWT, disk UUID and watcher-identity sandbox failures must match the untouched shared-clone baseline; they are not passes. Independent acceptance remains required."
      }
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/api/runtime/child/RegistrationPublicationFixture.java",
        "modules/worker-services/src/test/java/io/justsearch/app/api/runtime/child/RegistrationPublicationFixture.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Write the counterexample as a check first: the self-test with a real child exiting 37 during registration, deterministic through the seam, failing on the candidate. Add the same for chat and extraction where a caller can read the gap. Keep every existing assertion.",
          "location": "task t-20261110-childreg request"
        }
      ],
      "assessment": {
        "purpose": "Instrument a real JVM child exiting 37 during identity capture, with the exit watcher held between its EXITED claim and its single outcome publication. Test-only copies keep each module independent without introducing a production API or a new fixture dependency.",
        "callersAndStateOwners": "RegistrationExitCallersTest uses the fixture for chat first launch and relaunch and activation self-test. RegistrationExitExtractionTest uses it for crash and heap-exhaustion classification and replacement. Each test owns its child JVM, temporary trigger file, control, ledger sink and latch. The process-information seam delegates to the original real Process and triggers exit on the identity-capture read. The ledger sink pauses only that owned child-exit append; no shared application or dev stack is used.",
        "indirectBehaviours": "Existing assertions remain. New checks assert start does not return before outcome publication, the self-test never exercises its healthy probe or passes, chat preserves code 37 and its diagnostic launch log on both attempts without readiness notification, and extraction retains code 37 and stderr, distinguishes crash from heap exhaustion, and serves the next request with a replacement. Child-exit record and recent-exit counts stay exactly one per launch. No test selection or production environment is changed.",
        "evidence": "Shared clone of e753cc6e2f39602edd51cb2eb48f7924234b9507 with the checks only: all six new checks fail assertions for returning before publication; self-test additionally reports SelfTestRun[exercised=true, failure=null] after the real exit 37. The other 14 focused cases pass. The repaired candidate passes all 20 focused cases without skips. Logs and fail-before XML are in build/child-reg-repair. Full checks: app-api 499 cases, 0 failures, 5 skips (baseline 497/0/5); app-inference 603/0/1 (baseline 601/0/1); worker-services 4381/46/24 (baseline 4379/46/24). Every one of the 46 worker-services failures matches the untouched candidate baseline by test identity, exception type and message. No added, removed or changed failure. All three modules pass pmdMain, pmdTest and spotlessCheck. The complete selected 20-case suite then executed 20 consecutive times under one owned busy CPU process: 400 executions, zero failures, zero skips. The load used 22037 user ticks and 4 system ticks and was stopped by its recorded PID and birth identity. Per-run XML is preserved under build/child-reg-repair/repeated-xml. An initial harness count expectation of 23 was corrected to the actual 20 before restarting the complete sequence; that first run passed its tests and stopped its load, but the harness itself failed its count assertion.",
        "unresolvedRisks": "Windows native execution is unavailable on this Linux host. Independent verification and a fresh acceptance record for the resulting digest remain required."
      }
    }
  ]
}
```
