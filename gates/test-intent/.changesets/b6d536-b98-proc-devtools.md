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
      "whatMoved": "Identity-only stops, as on the integration side since 58a51a608: the merge keeps the integration side's version of this test. The live Windows backend stop is observed on the original Popen handle (terminate, then wait), with no taskkill, no PID-addressed terminator and no -Tree. The record is still present during stop and retires afterwards; every failed-boot case retains its original error and retires its record. This script test is outside the product-test gate.",
      "sources": [
        {
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice",
          "delegationScope": "Process ownership and exit accounting, task t-20261005-b6d536",
          "label": "agent choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "choice": "Keep recorded identities and every stop safety rule while repairing exited-backend cleanup, clean-checkout fixtures, real sweep result capture, forgotten cleanup/stop reporting, and stop latency. A shared read-only inspection host performs fresh scans; independent frontend and Engine teardown can overlap."
        },
        {
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice",
          "delegationScope": "Process ownership and exit accounting, task t-20261005-b6d536",
          "label": "agent choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "choice": "Merge of b6d536/batch-1-proc into b6d536/integration, decided by the orchestrator under the owner's delegation: identity-only stops, as on the integration side since 58a51a608. Scripts stop only the identity they started (the owner's standing process rule: never stop a process by PID alone, by name, or by following a parent PID). Children are covered by their creator's ordered stop and by the Engine's manifest and exit ledger, never by a tree sweep; the tree mode of terminate-process.ps1, the batch tree census and stop_named_descendants are removed, and every other guarantee of this lane is kept."
        }
      ]
    },
    {
      "class": "Adaptation",
      "items": [
        "scripts/dev/test-dev-runner-stop-ownership.mjs",
        "scripts/dev/test-dev-runner-process-races.mjs",
        "scripts/dev/test-dev-runner-frontend-tree.mjs",
        "scripts/dev/test-dev-runner-tree-handles.mjs",
        "scripts/dev/test-dev-runner-stop-recovery.mjs",
        "scripts/dev/test-dev-runner-stop-round3.mjs",
        "scripts/dev/test-dev-runner-stop-round4.mjs",
        "scripts/dev/test-dev-runner-fixture-dependencies.mjs",
        "scripts/jseval/tests/test_owned_stops.py",
        "scripts/jseval/tests/test_sweep_owned_processes.py",
        "scripts/jseval/tests/test_backend.py",
        "scripts/agent-analytics/861-w4-reaper-kill.test.mjs",
        "scripts/agent-analytics/861-w5-remove-worktree-teardown.test.mjs",
        "scripts/agent-analytics/936-remove-worktree-cli.test.mjs",
        "scripts/agent-analytics/952-worktree-lifecycle-cli.test.mjs"
      ],
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Identity-only stops, as on the integration side since 58a51a608. Checks that asserted -Tree, a tree outcome or a descendant sweep now check the identity-only stop: the recorded process is ended through the handle on which its birth is rechecked, no tree option or -Tree reaches the terminator, a parent link in the process table licenses nothing (tree-handles drives the real handle with a linked child and shows it survives; a -Tree request is refused), and a frontend stop counts only a confirmed termination. Legacy stopUnresolvedTrees records are imported as tracked identities: a living one keeps the stop incomplete and is reported, never killed; an anonymous one is retired once its recorded roots are gone (no enumeration can re-prove it). The batch tree census and inspection host checks became checks of the batched identity lookup (one PowerShell per phase, fresh identities per phase, census armed before the lookup); the inspection-exception check became a lookup-exception check; a duplicate anonymous case in round3 was dropped. Fixture cleanup checks follow the integration side's spawn-recorded p.kill() and stopRecordedProcess paths. jseval keeps the integration side's held-Popen backend stop and its tests (subprocess.run is never called); the exited-backend case also asserts no termination; owned_process stops only the captured identity and test_owned_stops checks that and the absence of a descendant sweep; health_failure is checked for the manifest and exit-ledger identity path; the installer cleanup check runs Stop-FixtureProcess with a replaced, an unregistered and an owned process. The live sweep check asserts kill_backend ends the recorded command, and the fixture leaf ends on its creator's go signal. The stop-recovery self-exit fixture now exits once the runner has recorded its quit intent, because the identity-only frontend stop no longer waits for a tree enumeration. The stop-recovery check of a detached leaf of an exited wrapper needs a tree stop; it is kept out of the suite as a named gap (run with --gaps; it fails by design). CLI fixture lists drop the removed inspect-process-trees.ps1.",
      "sources": [
        {
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice",
          "delegationScope": "Process ownership and exit accounting, task t-20261005-b6d536",
          "label": "agent choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "choice": "Merge of b6d536/batch-1-proc into b6d536/integration, decided by the orchestrator under the owner's delegation: identity-only stops, as on the integration side since 58a51a608. Scripts stop only the identity they started (the owner's standing process rule: never stop a process by PID alone, by name, or by following a parent PID). Children are covered by their creator's ordered stop and by the Engine's manifest and exit ledger, never by a tree sweep; the tree mode of terminate-process.ps1, the batch tree census and stop_named_descendants are removed, and every other guarantee of this lane is kept."
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

Merge into b6d536/integration (identity-only stops, as on the integration side since 58a51a608):
the tree mode of terminate-process.ps1, inspect-process-trees.ps1, the tree exports of
recorded-termination.cjs and engine-actuator.cjs, the dev-runner tree inspection phases and
stop_named_descendants are removed; installer, CI, sweep, dev-all, serve-worktree-fe, jseval and
dev-runner stops end only the identity they started. Named gaps, not covered by a creator's ordered
stop or by the Engine's manifest and exit ledger: the sweep's java child of its recorded cmd shim,
serve-worktree-fe's vite grandchild of its cmd shim, dev-all's npm/vite children, and a detached
leaf of an exited wrapper (stop-recovery --gaps).

## Acceptance records

Pending the orchestrator's independent verifier and the owner's behavior verdict.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-proc3-20261014",
  "verdict": "accept",
  "digest": "sha256:34b6f941bfdb537d4bcde1d77ab920755216656c8efc57a6df0a85ae9fcf9175",
  "note": "VC-proc3 (independent Claude verifier) over four rounds on b6d536/batch-1-proc, accepting 002073b2b vs 44a9e8aa2: identity check and termination share one process handle; a record without a start time never proves ownership and stops as STOP_INCOMPLETE with the process left alive; forced takeover of an incomplete run is refused with data kept; forget is owner-only and starts stay blocked; helpers spawned during a stop are all ended; jseval suite has no failure the base lacks; every scripts/dev test-dev-runner check passes with plain node on a clean checkout; sweep kill_backend handles an already-exited backend by real run; stop median at most 3.0 s over interleaved runs with a fake Engine, real vite frontend and 8 helpers; forgotten-run cleanup and plain stop refuse with reasons. Named follow-up: Engine-registered helpers end serially (about 0.37 s each). Pre-existing: a refused start exits 0. Digest bound with --base 44a9e8aa2, the lane's base."
}
```
