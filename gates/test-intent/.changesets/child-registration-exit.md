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
      "items": ["modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxPackChildEnvironmentTest.java"],
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
    }
  ]
}
```
