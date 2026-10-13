---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b98-proc-devtools
---

# Process ownership and exit accounting, round 4

Java ownership belongs to b6d536/proc-java. Modules Java remains byte-for-byte at a164833f5; this round makes no modules changes. The Obsolete entry names only the four fixtures/checks actually removed in round 3. The three restored existing files are omitted.

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
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ExitedWrapperDescendantTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/WrapperProcessFixture.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestDescendantReloadTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SessionWrapperFixture.java"
      ],
      "conflictSearch": "Searched docs/decisions for process ownership, self-test and GPU release; ADR-0050 and ADR-0051 preserve the safeguards and are consistent with consolidation in proc-java. No decision requires duplicate implementations or duplicate checks in this lane."
    },
    {
      "class": "Adaptation",
      "items": [
        "scripts/jseval/tests/test_run_register.py"
      ],
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "The live Windows backend stop observes the identity-bound terminator instead of taskkill, with the captured spawn birth and -Tree. The record is still present during stop and retires afterwards; every failed-boot case retains its original error and retires its record. This script test is outside the product-test gate; the orchestrator explicitly requires an Adaptation record for the old taskkill expectation.",
      "sources": [
        {
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice",
          "delegationScope": "Process ownership and exit accounting, task t-20261005-b6d536",
          "label": "agent choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "choice": "Keep recorded identities and every stop safety rule while repairing exited-backend cleanup, clean-checkout fixtures, real sweep result capture, forgotten cleanup/stop reporting, and stop latency. A shared read-only inspection host performs fresh scans; independent frontend and Engine teardown can overlap."
        }
      ]
    }
  ]
}
```

Script checks are outside the product-test gate. Round-4 generated execution records,
logs, raw pytest XML, source hashes and interleaved real-Vite latency samples are at
F:/agent-sandbox/design-b6d536/codex/b98-proc-devtools/round4/.
Fail-before is e3877e532 in the builder's own detached worktree; latency and the
whole-change pytest preservation reference are 44a9e8aa2. The eight new round4 Node
boundary checks fail by assertion before and pass afterwards. The new exited
backend and real already-gone sweep checks also fail by assertion before and pass
afterwards; the live sweep stop passes both revisions. The full plain Node run
uses NODE_OPTIONS unset in a fresh detached candidate checkout. Full guarded Node,
jseval, conformance and the 936/952/861 CLI runs are also recorded.

The new checks exercise cleanup with living/unverifiable/gone forgotten identities,
nonzero forgotten stop, observer-before-action ordering, private-free round3 and
recovery execution, and fresh native identities from one inspection host. Timing
uses nine interleaved stops each with a fake Engine, real Vite and eight helpers.
The real sweep check invokes production start_backend and kill_backend with a
CPU-only command fixture. Remaining extracted/injected script checks are labeled
as such in report.md, rather than claimed as complete script executions.

Repository fixture instrumentation captures identities at spawn and guards cleanup;
round3/recovery use the repository-produced helper. The frontend extractor accepts
both function signatures: all 14 original cases pass before and after; the added
asynchronous-stop check fails by assertion before and passes afterwards. The old
exited-backend termination expectation now asserts no OS stop request, under the
explicit exited-process decision. The round3 forgotten cleanup check uses a real
living child instead of a dead synthetic PID to preserve its data-retention case;
the new gone case proves the newly allowed cleanup. These script changes and new
checks are out of product-test gate scope, and their warrants and execution logs
are recorded here for independent review, without claiming a D4 product-test run.

No repository governance tooling changed. Round-3 evidence remains historical;
round-4 execution-evidence.json links the final candidate runs. Independent
acceptance remains pending.

## Acceptance records

Pending the orchestrator's independent verifier and the owner's behavior verdict.
