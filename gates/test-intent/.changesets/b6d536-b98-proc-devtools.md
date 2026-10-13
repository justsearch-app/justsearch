---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b98-proc-devtools
---

# Process ownership and exit accounting, round 3

The orchestrator narrowed this lane to Node and developer scripts. Java work is carried
by b6d536/proc-java, observed at f40ba7da82543cb0755f101eb0d2797b2caff4d9.
All modules files now equal a164833f5 except modules/ui-web/scripts/dev-all.cjs.
The duplicate Java checks and their round-2 execution evidence are removed from this branch.

```json
{
  "entries": [
    {
      "class": "Obsolete",
      "sources": [
        {
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice",
          "delegationScope": "Process ownership and exit accounting, task t-20261005-b6d536",
          "label": "agent choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "choice": "Consolidate Java ownership, self-test descendants and GPU waits in b6d536/proc-java (observed commit f40ba7da82543cb0755f101eb0d2797b2caff4d9). Remove this lane duplicates and their checks; restore all modules files byte-for-byte to a164833f5 except dev-all.cjs. The safeguards are carried by proc-java, not abandoned; the orchestrator will integrate that lane."
        }
      ],
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlGpuBarrierTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlHarness.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExitedWrapperDescendantTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/WrapperProcessFixture.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SurvivingTerminator.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestDescendantReloadTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SessionWrapperFixture.java"
      ],
      "conflictSearch": "Searched docs/decisions for process ownership, self-test and GPU release; ADR-0050 and ADR-0051 preserve the safeguards and are consistent with consolidation in proc-java. No decision requires duplicate implementations or duplicate checks in this lane."
    }
  ]
}
```

Script checks are outside the product-test gate. Their before/after logs and generated
execution evidence are retained under F:/agent-sandbox/design-b6d536/codex/b98-proc-devtools/round3/.
They pin anonymous tree uncertainty, owner-only person-decided forgetting, refusal of
starts over living forgotten identities, batching, gone sweep stops and descendant bounds.
The final round-3 check has 18 cases: 17 fail by assertion on e214f8736 and all 18 pass
afterwards; the complete fresh-proof preservation case passes on both. The three staged
fixture lists also fail their helper-availability assertions before and pass afterwards.
The default-start and cleanup barriers are checked through the actual command line,
including force takeover, repeated forgetting and a pending identity query.
Execution records, source hashes, raw Java XML/PMD and interleaved latency samples are
generated in round3/execution-evidence.json under the run folder above. The delivery
stop medians (five interleaved runs each) are 1.544 s at 44a9e8aa2, 19.159 s at e214f8736,
and 3.331 s for this candidate; the allowed limit is 3.544 s.
Fail-before is e214f8736 in the builder's detached worktree. The latency reference is 44a9e8aa2.
No repository governance tooling changed. Independent acceptance remains pending.

## Acceptance records

Pending the orchestrator's independent verifier and the owner's behavior verdict.
