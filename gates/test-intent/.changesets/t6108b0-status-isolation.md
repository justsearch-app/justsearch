---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Status fresh-read coalescing without wall-clock aging

The repeated-fresh-read case runs in its original class, five unchanged subclasses, and the
StatusIsolationHttpTest copy. Replace the minute of waiting with the existing held-lane aging seam
between equal bursts of real HTTP reads. The new assertion checks the stale precondition, while
the existing producer-call and sampler-thread oracles remain unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Replace the 60 one-second gaps with two bursts of 30 fresh reads separated by the existing StatusLaneAging.ageHeldLanes(handler) seam. The seam advances only the test status clock past the maximum staleness allowance and completes a bounded sampler pass; it does not release the held worker producer. An explicit workerRpcStale assertion confirms the second burst starts after the held lane has become stale. Keep all 60 fresh reads, real Javalin HTTP requests, the blocking producer and entered/release latches, the producer-call equality assertion, the at-most-one status-observation thread assertion, the 90-second timeout, and all other test methods, classes and inheritance. No production code changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "the night's testing work, whose accepted design removes waiting without weakening checks",
          "choice": "ST1: All seven executions, including StatusIsolationHttpTest, keep 60 fresh HTTP reads without sleeping between reads and finish within seconds.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "the night's testing work, whose accepted design removes waiting without weakening checks",
          "choice": "ST2: Fresh reads still coalesce while the producer is hung; a mutation that starts a worker refresh for every read must fail before and after this adaptation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "the night's testing work, whose accepted design removes waiting without weakening checks",
          "choice": "ST3: Fresh reads still coalesce after the held lane becomes stale, aged through the existing StatusLaneAging seam between two bursts; a mutation bypassing coalescing for a stale held lane must fail after the adaptation and be measured before it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "the night's testing work, whose accepted design removes waiting without weakening checks",
          "choice": "ST4: Keep the at-most-one status-observation sampler-thread assertion, the same producer-call assertion, the same real HTTP path, every other case, and all classes and inheritance.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "the night's testing work, whose accepted design removes waiting without weakening checks",
          "choice": "ST5: The changed classes must pass 20 consecutive runs.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Execution evidence

ST1–ST4 builder checks completed. The builder ran ST5 round 1 (both changed classes, 25 tests)
and stopped while waiting for the shared Gradle lock; the independent verifier ran all 20 rounds.

The builder returned under the role contract's instruction to come back when work runs well
past the assignment. The original estimate was 25–45 minutes; repeated shared-lock waits
extended the task beyond an hour. The independent verifier then ran ST5: 20 of 20
consecutive runs passed, 25 tests each, no skips.
No check was skipped as a pass or removed. One coverage trade-off is accepted (orchestrator,
under the autonomy grant): the old test spanned a minute of real time, so it also caught a
defect timed by the real clock, such as a watchdog abandoning a held refresh after 20 s. The
new test ages only the handler's injected clock, which is what production staleness uses
(StatusLifecycleHandler clockMs); a watchdog timed by that clock is still caught. A future
real-clock timer in the status lanes needs its own test.

All commands, complete Gradle output, JUnit XML, per-case timings, and four mutation diffs are
prepared under `tmp/g-status/`; `tmp/g-status/RESULTS.md` maps them to the scenarios. The before
checkout was a detached worktree at origin/main (`1e17cb806c1c`) and was removed after preserving
its evidence. `tmp/g-status/stability-stop.json` records the identities of the only processes
stopped: this builder's stability driver and its waiting flock. All temporary production
mutations are restored. No acceptance record is written.

### ST1 / ST4: before and after

| Execution | Before (s) | After (s) |
| --- | ---: | ---: |
| StatusIsolationHttpTest | 68.190 | 9.313 |
| StatusPublicationCompletenessTest | 66.354 | 7.351 |
| VerifierStatus2Test | 66.339 | 7.315 |
| VerifierStatus3Test | 66.320 | 7.301 |
| VerifierStatus4Test | 66.314 | 7.295 |
| VerifierStatus5Test | 66.306 | 7.285 |
| VerifierStatusIsolationTest | 66.294 | 7.282 |

Total named-case time: 466.117 s → 53.142 s; saved 412.975 s (88.6%).

Each target passed before and after over real loopback HTTP. Each changed target retains 60 fresh reads, the exact producer-call equality assertion, and the exact at-most-one status-observation sampler-thread assertion. Other methods, classes, timeout, routes, inheritance, and production sources are unchanged. Two unrelated ArchUnit guardrails also execute and pass with the Gradle method filter; they are not counted as target executions.

Raw evidence: `tmp/g-status/before/summary.json`, `tmp/g-status/after/summary.json`; corresponding directories include complete Gradle output and JUnit XML.

## ST2 / ST3: mutation checks

Each temporary mutation bypasses the StatusObservation running-future guard only for the worker lane on the real `status-fresh-dispatch` thread. M1 does so on every fresh read; M2 does so only when the worker lane is stale. Neither applies to the aging seam sampler pass. All mutations are restored by the runner, including on failure. Each run stores `mutation.diff`, full `gradle.log`, JUnit XML, and a parsed summary.

| Probe | Targets | Assertion failures | Other failures |
| --- | ---: | ---: | ---: |
| `tmp/g-status/before-mutant-every-refresh/summary.json` | 2 | 2 | 0 |
| `tmp/g-status/before-mutant-stale-refresh/summary.json` | 2 | 2 | 0 |
| `tmp/g-status/after-mutant-every-refresh/summary.json` | 7 | 7 | 0 |
| `tmp/g-status/after-mutant-stale-refresh/summary.json` | 7 | 7 | 0 |

Before mutations run both distinct test implementations; after mutations run all seven inherited/copied executions. The changed case also asserts workerRpcStale after the existing aging seam and before the second burst, independently confirming the stale precondition.

## ST5: 20 consecutive stability runs

Each run selects both changed classes in full, as required by ST5. All seven inherited/copied executions are covered by the separate before/after and after-mutation runs. No test is cached: `test --rerun` executes each round. Every result, including additional guardrails, must pass.

| Run | Checks passed | Named executions passed |
| --- | ---: | ---: |
| `tmp/g-status/stability-01/summary.json` | 25 | 2 |
| Verifier, runs 1–20 on 8aa5200fe | 25 each, no skips | 2 each |

Completed: 20/20 (verifier).



The final requested `node scripts/governance/gates/test-intent/cli.mjs --base origin/main`
returned 1 only for the missing acceptance once the branch was rebased onto main.
The gate also reports its existing reconsider-rate warning (2 disagreements, 0 audited PRs).

## Acceptance records

Pending independent verification; no acceptance record written by the builder.
