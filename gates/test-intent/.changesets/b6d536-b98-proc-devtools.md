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
        "scripts/dev/test-dev-runner-engine-job.mjs",
        "scripts/dev/test-dev-runner-job-stops.mjs",
        "scripts/dev/test-dev-runner-data-folder.mjs",
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
      "whatMoved": "Identity-only stops, as on the integration side since 58a51a608. Checks that asserted -Tree, a tree outcome or a descendant sweep now check the identity-only stop: the recorded process is ended through the handle on which its birth is rechecked, no tree option or -Tree reaches the terminator, a parent link in the process table licenses nothing (tree-handles drives the real handle with a linked child and shows it survives; a -Tree request is refused), and a frontend stop counts only a confirmed termination. Legacy stopUnresolvedTrees records are imported as tracked identities: a living one keeps the stop incomplete and is reported, never killed; an anonymous one is retired once its recorded roots are gone (no enumeration can re-prove it). The batch tree census and inspection host checks became checks of the batched identity lookup (one PowerShell per phase, fresh identities per phase, census armed before the lookup); the inspection-exception check became a lookup-exception check; a duplicate anonymous case in round3 was dropped. Fixture cleanup checks follow the integration side's spawn-recorded p.kill() and stopRecordedProcess paths. jseval keeps the integration side's held-Popen backend stop and its tests (subprocess.run is never called); the exited-backend case also asserts no termination; owned_process stops only the captured identity and test_owned_stops checks that and the absence of a descendant sweep; health_failure is checked for the manifest and exit-ledger identity path; the installer cleanup check runs Stop-FixtureProcess with a replaced, an unregistered and an owned process. The live sweep check asserts kill_backend ends the recorded command and, since the follow-up's job-object stop, the java stand-in behind it before the fixture's own exit signal. The stop-recovery self-exit fixture now exits once the runner has recorded its quit intent, because the identity-only frontend stop no longer waits for a tree enumeration. The stop-recovery check of a detached leaf of an exited wrapper, a named gap at the merge, is a normal check since the follow-up's job-object stop. CLI fixture lists drop the removed inspect-process-trees.ps1. CI759 round 3: real Windows creation-identity, job and native-stop cases now state their Windows prerequisite; synthetic refusal, ordering and lookup-decision assertions remain portable and every original assertion still executes on Windows. Round3 and round4 unknown-identity cleanup fixtures explicitly retain the child PID where no native birth is available. The recovery CLI-forget refusal stays portable: its owned sleeper exits through a private quit-file channel only after the unchanged alive-through-forget assertions, and native birth capture runs only on Windows. The data-folder scratch repository now supplies equivalent .bat and executable POSIX Gradle stubs with the same marker and exit status, preserving all portable marking, refusal and cleanup assertions.",
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
    },
    {
      "class": "Adaptation",
      "items": [
        "scripts/dev/test-dev-runner-process-races.mjs",
        "scripts/dev/test-dev-runner-stop-recovery.mjs",
        "scripts/jseval/tests/test_owned_stops.py",
        "scripts/supervisor-conformance/fixture-cleanup.test.mjs"
      ],
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Job-object stops for dev tools (scripts/dev/lib/job-run.cjs, job-host.ps1, job-run.cs, stop-job-run.ps1). process-races 'dev-all refuses a replacement at the termination boundary' and stop-recovery 'dev-all birth capture finishes in the spawn turn before stop' evaluate dev-all's declarations with the job starter injected instead of engine-actuator: the stop carries the host's recorded birth and dev-all never hands a bare PID to taskkill /T; inputs and expected outcomes are kept. test_owned_stops' two sweep checks drive kill_backend with a fake run stop taking the run record (-RecordPath, -Code) instead of a PID-addressed terminator; already-gone is still success and a foreign port owner is still never stopped. CI759: the two sweep checks and installer cleanup check now explicitly require Windows; all three still execute there, while the portable ownership checks remain enabled on every OS. The fixture preload check uses the existing Windows identity-boundary prerequisite for the same reason. No input or outcome assertion changes. CI759 round 3: the synthetic dev-all job-starter fixture explicitly injects platform win32, because the mocked Windows job boundary must not inherit the host OS; no native call or actual spawn is introduced and its captured-birth assertion is unchanged.",
      "sources": [
        {
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice",
          "delegationScope": "Process ownership and exit accounting, task t-20261005-b6d536",
          "label": "agent choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "choice": "Follow-up to the merge of b6d536/batch-1-proc, decided by the orchestrator under the owner's delegation: close the named gaps while keeping the identity-only rule (never find processes through parent PIDs, names or PID alone). A dev tool starts the real process directly where it can (node or java by absolute path, shell:false, no cmd shim) and records PID plus creation time at start; what a third-party tool starts is contained by a Windows job the starter creates (KILL_ON_JOB_CLOSE, no breakaway), so the kernel adds each descendant at its creation, and the run's stop ends the job host it recorded by identity, whose job handle closing ends every member. The run record names the root and every member by PID plus creation time; the stop verifies each has ended. A recorded PID that now shows another birth is not the run and is never touched."
        }
      ]
    },
    {
      "class": "Adaptation",
      "items": [
        "scripts/dev/test-dev-runner-process-races.mjs",
        "scripts/dev/test-dev-runner-stop-recovery.mjs",
        "scripts/jseval/tests/test_owned_stops.py"
      ],
      "moved": [
        "signature"
      ],
      "whatMoved": "Fail-before for four rewritten checks is not available at d52ee5e7a, because the interface they drive does not exist there: process-races 'dev-all refuses a replacement at the termination boundary' and stop-recovery 'dev-all birth capture finishes in the spawn turn before stop' evaluate dev-all's startOwnedChild, childRuns and terminateChild with the job starter (job-run.cjs) injected, and d52ee5e7a's dev-all has no startOwnedChild, childRuns or job starter (it spawns directly and its terminateChild stops the child's identity through engine-actuator); test_owned_stops 'test_sweep_accepts_a_recorded_process_already_gone' and 'test_sweep_leaves_a_foreign_port_owner_alone' drive kill_backend with a fake run stop taking -RecordPath and -Code, and d52ee5e7a's sweep reads no run stop (JUSTSEARCH_SWEEP_STOPPER) and calls a PID-addressed terminator (JUSTSEARCH_SWEEP_TERMINATOR -Pid). Copied to d52ee5e7a they fail on the missing names, not on the behavior they check. Their warrant is the unchanged inputs and expected outcomes of the checks they replace (a stale PID never reaches another killer; the recorded birth reaches the stop in the spawn turn; already-gone is success; a foreign port owner is never stopped), and each passes at a000e15c7 and at this change.",
      "sources": [
        {
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "kind": "delegated-choice",
          "delegationScope": "Process ownership and exit accounting, task t-20261005-b6d536",
          "label": "agent choice",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (archived at F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "choice": "Follow-up to the merge of b6d536/batch-1-proc, decided by the orchestrator under the owner's delegation: close the named gaps while keeping the identity-only rule (never find processes through parent PIDs, names or PID alone). A dev tool starts the real process directly where it can (node or java by absolute path, shell:false, no cmd shim) and records PID plus creation time at start; what a third-party tool starts is contained by a Windows job the starter creates (KILL_ON_JOB_CLOSE, no breakaway), so the kernel adds each descendant at its creation, and the run's stop ends the job host it recorded by identity, whose job handle closing ends every member. The run record names the root and every member by PID plus creation time; the stop verifies each has ended. A recorded PID that now shows another birth is not the run and is never touched."
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
dev-runner stops end only the identity they started.

Follow-up (fix(b6d536): end every process a dev tool starts, without parent links): the named gaps of the merge are closed
without parent links. scripts/dev/lib/job-run.cjs starts a run under job-host.ps1, which creates the root suspended inside a new
job (KILL_ON_JOB_CLOSE only, no breakaway), assigns it, writes the run record (host, root and every member by PID plus creation
time, refreshed every 50 ms) and resumes it; when the root exits the host terminates the job, so an exited wrapper's detached leaf
ends with it. stop-job-run.ps1 ends the host only when its handle shows the recorded birth (its job handle then closes and Windows
ends every member) and verifies each recorded identity has ended; a PID that now shows another birth is reported gone and never
touched. Per case: (a) the sweep's ui.bat runs through cmd.exe as its interpreter inside the job, so the java behind it is a member;
(b) serve-worktree-fe starts node vite.js directly (no npx or cmd shim) inside a job; (c) dev-all starts node vite.js directly and
gradlew.bat inside jobs; (d) a detached leaf of an exited wrapper is inside the starter's job, so it ends with the run. A leaf that
was never recorded and was started outside any job cannot be proven ours: dev-runner reports it by identity as an unowned survivor
(unresolvedProcesses) and leaves it alive.

New script checks: test-dev-runner-job-stops.mjs runs the real serve-worktree-fe.cjs and dev-all.cjs in a scratch repository with
CPU-only Vite and Gradle fixtures that record their own identities, snapshots the job records while the run lives, and asserts no
recorded identity survives the stop and a same-name node.exe outside the run is alive; a third check stops a record naming a live
decoy's PID under a recycled-looking birth and a genuine same-name run, and the decoy is never touched. The stop-recovery --gaps
check is now a normal check (a detached leaf of an exited wrapper does not survive its run's stop). The live sweep check now asserts
kill_backend ends the java stand-in behind the recorded command before the fixture's own exit signal. Each fails by assertion on
d52ee5e7a (detached worktree, new check files copied in) and passes after.

Follow-up (fix(b6d536): ordered Engine quit and job containment for dev-all and dev-runner): on Ctrl+C or a stop, dev-all asks
the Engine to quit through POST /api/lifecycle/shutdown, waits for its exit (QUIT_DEADLINE_MS plus 2 s) and only then stops its
job; its children start in new process groups, so the console's Ctrl+C reaches dev-all alone. With no --data-dir, dev-all gives
the Engine its own dev data folder as JUSTSEARCH_DATA_DIR and JUSTSEARCH_HOME (an inherited JUSTSEARCH_DATA_DIR is not an
explicit choice). dev-runner starts the Engine inside a starter-owned job (job-run.cjs); the stop keeps its order (ordered quit,
recorded identities, then the job for what is left), and each process the job's termination ended is listed by identity in the
stop result and stop-report.json (engineJob.endedByJob); the Engine's exit record names the containment DEV_RUNNER_JOB. A clean
Ctrl+C removes dev-all's and serve-worktree-fe's run records. New script checks: test-dev-runner-engine-job.mjs (the real CLI
with the fake Engine and a detached grandchild; WindowsEngineJob's nested job reproduced call for call; the fallback stop's order)
and four cases in test-dev-runner-job-stops.mjs (dev-all ordered quit on its own stop and on a real console Ctrl+C, dev-all
isolation, serve-worktree-fe Ctrl+C record removal). Each of the seven new checks fails by assertion on bc5978095 (detached
worktree, new check files copied in) and passes after; the nested-job check's containment and exit-code parts already pass on
bc5978095, as a confirmation should, and it fails there only on the stop's listing of what the job ended.

Follow-up (fix(b6d536): dev tools clean only their own data folders and stop helpers in order): a soft or hard clean, and
dev-all's clean at every start, runs only on a data folder a dev tool created and marked (scripts/dev/lib/dev-data-folder.cjs,
marker .justsearch-dev-data.json naming the tool and the creation time). A missing folder is created and marked, an empty one
marked; the default modules/ui-web/.dev-data without a marker is adopted only when every top-level entry is a known dev-data
name (the Engine's own names plus the recoverability register's); any other folder is refused before anything starts, with a
message naming it and how to proceed, and nothing in it changes (dev-runner: DATA_DIR_NOT_OURS, checked before admission and
again at the clean; `--clean none` deletes nothing and is not checked). The stop keeps the decided order: ordered Engine quit,
the registered-helper step, the recorded identities, and only then the job. The Engine's job no longer ends with the Engine:
its host holds the job after the root exits (job-run.cs holdAfterRoot, state root-exited) and the job is closed by the stop's
last step, or when the run ends without a stop; in the runner's fallback the registered-helper step runs before the runner is
ended (with a fresh identity batch only when the ordered quit was asked for, so a stop still takes one lookup per phase).
The forgotten-run fixtures of test-dev-runner-stop-round3.mjs ('real default CLI start refuses forgotten live processes') and
test-dev-runner-stop-round4.mjs ('forgotten cleanup ...') now mark their data folder, as the start that created it would:
the refusal and the clean they check are unchanged. test-dev-runner-engine-job.mjs's first check now expects the Engine's exit record to show its job still holding the
detached process (jobEmpty false), because the job is no longer closed at the Engine's exit. New script checks:
test-dev-runner-data-folder.mjs (the real dev-all in a scratch repository with a stub Gradle, and the real dev-runner CLI,
each refusing a non-empty unmarked --data-dir and leaving it byte-identical; marking, adoption and the marker kept through
dev-all's clean) and a registered-helper case in test-dev-runner-engine-job.mjs (stopped by the registered-helper step and
recorded there, not as DEV_RUNNER_JOB). The two refusal checks and the registered-helper check fail by assertion on 280d21c19
(detached worktree, new check files copied in: the folder was cleaned; the helper was already dead when its step came) and
pass after; the marking and adoption checks fail there because the module does not exist.

Earlier CI759 non-Gradle repair: only the three PowerShell/Windows-handle cases in test_owned_stops.py and the fixture-identity preload case have an OS prerequisite. Windows before and after runs execute all cases without skipping. Python and Node script checks are outside the product-test evidence runner; run-evidence has no Python or Node runner. The original assertions are retained. No acceptance is recorded by this builder.

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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4a-20261015",
  "verdict": "accept",
  "digest": "sha256:953bdcb457fb9617f120d1865c22617c779d3a8d61a32b9864606b1499177e4d",
  "note": "Reviewed the changed identity-only and job-run interfaces and the explicit candidate-only reasons against current and prior code. Reran all eight listed dev-tool scripts, four analytics suites and the 40 listed jseval cases; all passed."
}
```
