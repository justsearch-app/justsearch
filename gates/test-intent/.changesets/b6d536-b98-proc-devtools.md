---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b98-proc-devtools
---

# Process ownership and exit accounting, round 2

Round 1 is committed at 5242836f1. This revision repairs the rejected candidate under the
same delegation. The builder is not its acceptor. The expectation source is delegated-choice,
quoting only the owner's archived delegation; none of the orchestrator's repair instructions
is represented as owner words.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlGpuBarrierTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExitedWrapperDescendantTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/WrapperProcessFixture.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestDescendantReloadTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SessionWrapperFixture.java"
      ],
      "class": "New",
      "behaviour": "A successful self-test cannot leave its serving descendant alive. Retain descendants through wrapper exit and across activation scope release, refusing main-server admission until they exit. Start the GPU release producer before publishing either exit future; refuse a GPU start when the bounded release wait reports pending, and allow retry. Preserve the existing completed-poll timeout and unmeasured contracts. The fixture records its leaf birth at spawn and exits both processes through private signal files; it never terminates by PID. The admission checks run after the real self-test wrapper exit and serving-descendant survival.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "task t-20261005-b6d536, process ownership and exit accounting",
          "choice": "A successful self-test cannot leave its serving descendant alive. Retain descendants through wrapper exit and across activation scope release, refusing main-server admission until they exit. Start the GPU release producer before publishing either exit future; refuse a GPU start when the bounded release wait reports pending, and allow retry. Preserve the existing completed-poll timeout and unmeasured contracts.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b98-proc-devtools-r2.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlGpuBarrierTest.java#synchronousExitContinuationStartsReplacementAfterGpuRelease",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlGpuBarrierTest.java#pendingGpuReleaseRefusesLaunchAndAllowsRetry",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExitedWrapperDescendantTest.java#exitedSelfTestWrapperWithLiveDescendantCannotPass",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExitedWrapperDescendantTest.java#selfTestDescendantSurvivalBlocksMainReload",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestDescendantReloadTest.java#closingRealSelfTestSessionCannotReloadBesideItsDescendant"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlHarness.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SurvivingTerminator.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Cleanup retains the original native handles opened at spawn and uses those same handles, carrying their creation-time identity. No oracle, fault injection, expected result, tag, or test selection changes. Unsafe independent fixtures remain excluded by the private verification init script."
    }
  ]
}
```

Script checks under `scripts/` remain outside the product-test gate. Their independent
before/after evidence is in `F:/agent-sandbox/design-b6d536/codex/b98-proc-devtools/round2/`:

- Stop recovery rechecks recorded roots and descendants on later attempts, resolving gone or
  reused identities. A frontend that self-exits at 400 ms during a delayed fake-Engine quit
  permits first stop, second stop, other-session takeover, and next start through the real CLI.
- A separate stop snapshot survives runner record writes and preserves new partial subtrees
  found during retries. Legacy missing-birth PID reuse bounds remain tied to the PID and
  incarnation, rather than moving when a record is rewritten.
- `stop --forget-unresolved` reports what remains, leaves every process running, and clears
  tracking without any runner, HTTP or forced stop. Its retirement marker prevents later
  writes from restoring authority, and start ignores a retired pointer. Cleanup requires a
  verified STOPPED result and cannot delete data through a forgotten record.
- Unknown ownership reports PID, process name, recorded/observed start time, reason and the
  retry/forget command. Takeover failure never recommends the already-used takeover flag.
- dev-all records birth synchronously at spawn. The native reader can read an exited root
  while the spawn caller still pins its PID; the real exited-wrapper fixture then stops its
  surviving leaf through the identity-bound tree helper.
- Both broken fixed staging lists include recorded-termination and the PowerShell terminator.
  The third existing list preserves its dependency closure. No other fixed list imports a
  changed launcher without its needed source.
- Sweep, jseval backend, health-failure and the locally runnable installer verifier carry
  spawn identities to the shared terminator and refuse unowned stops. The five boundary
  checks fail by assertion before and pass after. Existing backend tests now express the
  selected identity terminator and seed their fake owned records; readiness oracles remain.

Round-2 Java evidence uses a private path/admission/cleanup adaptation of the repository's
D4 producer (`round2/run-evidence-safe.mjs`). Its parser, result classification, content
hashes and self-digest are unchanged. It executes the checks itself, uses this builder's
existing detached before worktree, private output paths and a fresh Gradle slot/space check
before every invocation. Dependency junctions are unlinked for the Java-only evidence run.
The repository's governance tooling is unchanged. The report records commands, results,
filtered unsafe fixtures, all architecture cases, PMD comparison and cleanup.

## Acceptance records

Pending the orchestrator's independent verifier and the owner's behavior verdict.
