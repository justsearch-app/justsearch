# E4-E6 paired instruments: design before implementation

Status: design recorded 2026-10-01; implementation and Node verification pending.
Governing work: Lane F / 936, design sections 8 and 16; stage E sections 3-5.
Working tree: `codex/lane-f-e-instr`, inspected at `97f286aef`.
MAIN comparator: `lane-f-e-main`, pinned by the task to `ac1c93bf3`.

The owner requires this design in a final report before implementation. This
record is the design deliverable for that boundary. Continue the already
authorized implementation on resumption; no new implementation permission is
needed. No subagents, commits, pushes, Gradle, Engine, stack, or live evaluation
runs are authorized in this instrument lane. Root owns live execution.

## Verified constraints and ownership

- `stages/E.md` section 3 rows E4-E6 and `design.md:2295-2296,2310-2311`
  require clauses, not merely successful script exits. Section 16's acceptance
  allocation retains all conditions. Its signing exception is not a generic
  waiver for missing instruments or unsupported MAIN capabilities.
- `e-run.mjs:139-152,510-520,569` currently plans an uninstrumented soak,
  branch-only recovery script, and fake-Engine hangs. The fake cases remain
  useful supervisor conformance tests; retire their use as live E6 evidence,
  not the tests themselves.
- MAIN's production restart owner is
  `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java`,
  not the similarly named system-test `WorkerProcessManager`. Its death
  observation (`:744`), restart announcement (`:810`), PID announcement
  (`:425`), and successful port discovery (`:838`) supply timeline events.
  `doRestart` clears the MMF port/shutdown signal, respawns, rediscovers the
  port, and notifies the Head capability. Never equate port discovery with a
  successful index search.
- MAIN's dev runner `scripts/dev/dev-runner.cjs:2175-2205` records Head exit
  and terminates its own run; it does not respawn the Head. The paired dev arm
  therefore has no recovering Head supervisor. Installed/Tauri behavior is
  outside this dev-arm claim, and must be investigated separately if claimed.
- MAIN `SupervisionPolicy.java:37-40,57-68` fixes the Worker miss threshold
  at 3. `KnowledgeServerHealthMonitor.java:54,118-147` fixes the normal poll
  interval at 10 seconds. Neither is an operator override in this tree.
  `KnowledgeServerConfig.java:132-133` does expose Worker shutdown timeout.

## E4: collect every JVM, every owned process, and the actual live setting

Extend the existing E4 plan and samplers rather than add a launcher or service.
Bind everything to the owned start receipt, data directory, PID, process start
identity, and runtime manifest; resample identities across incarnations.

1. Launch G1 with the pinned heap and `-Xlog:gc*,safepoint` on every JVM:
   Engine on branch; Head and Worker on MAIN; extraction JVMs as children on
   both. Use separate role/PID/start log files and retain rotations. A single
   inherited `JAVA_TOOL_OPTIONS` logging flag can cover JVM children without
   changing MAIN; keep flags identical between arms and confirm them against
   live command lines. Use an absolute owned log destination with Windows
   drive-colon escaping. MAIN Worker additionally accepts custom JVM flags
   through `JUSTSEARCH_JVM_OPTS` (`WorkerSpawner.java:469-477`). Do not double
   inject logging/collectors through both channels. Do not claim comparable
   collectors unless the actual Head and Worker commands confirm G1.
2. Extract timestamps, GC IDs, collector event kind, post-GC used bytes, and
   pauses. Young-GC used bytes are not an exact live-object census. Record the
   ordinary post-GC trend separately. For the live-heap comparison, schedule
   the same diagnostic full collections on all JVMs on both arms using
   `jcmd <verified-pid> GC.run` at minute 5 and each subsequent five-minute
   boundary. Check for the corresponding completed full-GC records rather
   than trusting `jcmd` success. These pauses are part of this declared
   instrumented workload and the measured E4 worst safepoint pause.
3. Warmup is the first five minutes of each window, frozen before either arm.
   Fit ordinary least-squares bytes/minute to the full-GC post-collection
   points from warmup end to window end, per JVM incarnation and role; also
   fit the aligned sum for the long-lived JVMs. Preserve point count, coverage,
   units, and the raw series. Require at least four completed collections
   spanning half the post-warmup interval. Missing coverage cannot pass.
   A positive fitted slope fails the literal no-growth condition unless an
   owner-approved tolerance was frozen before the pair; no inferred noise
   allowance. A restarted JVM cannot erase earlier heap growth or a crash.
4. Honor `values.json`: continuous windows of 55, 55, and 10 minutes,
   totaling 120 measured minutes, separated by explicit interruption
   boundaries. Evaluate the no-growth slope independently in both 55-minute
   windows. The ten-minute tail contributes duration, process memory and
   crash evidence, and is not a substitute for either long-window slope.
5. At minutes 15, 30, and 45 of each long window, POST
   `/api/settings/v2` with `ui.excludePatterns`, alternating two declared
   globs which match none of the frozen corpus. Preserve original patterns
   and restore them through the same API in cleanup. Branch carries the
   fresh settings witness and a fresh operation key; MAIN uses its existing
   partial-patch contract. Bootstrap the mutation token on each boot.
   Confirm GET settings, effective config, and the real consumer:
   POST `/api/indexing/excludes/apply?dryRun=true` must report the newly
   resolved patterns and no matched/deleted corpus units.
   The settings path is live on both arms: MAIN
   `SettingsController.java:112-146,203-214,278-279` saves and rebuilds the
   ConfigStore; branch `SettingsPatch.java:79-82` and
   `SettingsServiceImpl.java:227-262` use the accepted settings owner.
   Both `ExcludesServiceImpl.java:44-51` (MAIN) / `:47-55` (branch) read the
   current ConfigStore on each invocation. Handler routes are
   `IndexingController.java:360-371` (MAIN) / `:329-340` (branch).
   This proves real live settings application during the E4 workload; it
   does not prove encoder recomposition, which remains D1's separate row.
6. Continuously record supervisor state/lastExit/incarnation on branch.
   On MAIN tail Head logs, retaining offsets and rotations, and count Worker
   death/restart/PID changes plus Head death. A missing process sample or log
   interval is not proof of zero crashes. Include native exit evidence and
   process disappearance/reappearance independently of the log classifier.
7. Extend `head-rss-sampler.ps1` to scope all roles to the owned arm, including
   adopted children verified through identity/configuration. It currently
   matches every machine llama-server and all HeadlessApp instances; that
   global match is unsuitable for a paired arm. Emit explicit `head` and
   `worker` roles on MAIN, `engine` on branch, and child identity metadata.
   Sum unrounded private bytes at each timestamp and compare peak total
   private bytes against the split peak. Report working set separately.
   Incomplete identity/role coverage makes the sum unmeasurable, never zero.

The section 8 budget source is `design.md:1819-1830` plus
`evidence/C1/memory-budget.md:9-24`: heap 2 GiB, direct buffers 256 MiB,
metaspace collection threshold 128 MiB. `governance/retained-state.v1.json:15`
owns the 256 MiB direct allowance. Metaspace has no maximum and ORT host
allocation is unbounded. There is **no numeric total-process commit ceiling**
in those sources. The instrument will verify actual flags and report the
consumer lines and arm totals, but cannot turn 2 GiB + 256 MiB + 128 MiB into
an invented Engine ceiling. Root's 2026-10-01 clarification defines the
component-memory row as the paired summed commit comparison together with
the documented heap/direct/metaspace launch limits on each JVM. The instrument
uses that definition; it does not require or create an absolute total ceiling. File-backed page
cache is separate accounting, never added to private bytes as if disjoint
process working sets were a machine-wide physical-memory measurement.

## E5: compare the actual failure domains and retain capability differences

Branch deliberately kills its verified Engine JVM under the production
supervisor. MAIN kills its verified Worker under the surviving Head. Never
kill the supervisor or a PID selected by an unscoped class-name match.

Reuse `real-writer-recovery.mjs`'s fixture ownership and
`processing-replay-scenario.mjs:12-100` identity-verified SIGKILL mechanics.
Add a separate measured mode/observer seam; preserve the existing regression's
post-death observation semantics. Its PROCESSING mode forces a 10-second
cooldown (`real-writer-recovery.mjs:283-287`) to read before successor startup.
That mode is not the 1-second production-cooldown latency sample. The E mode
must retain ordinary production cooldown and take continuous pre-kill snapshots
and event timestamps, without lengthening the outage for measurement.

Prepare the same 100-document recovery workload and require an accepted
durable unit in flight and at least one completed unit before injection.
On branch, retain operation key, record identity, attempts, checkpoint cursor,
completed units and the exact RUNNING/PROCESSING revision, then prove the same
operation advances from its checkpoint without repeating completed effects.
`SqliteOperationStore.java:823-825` owns checkpoint cursor and counters.
Existing single-file PROCESSING replay alone does not prove a nonzero
multi-unit checkpoint: add the observation/assertion to the appropriate
existing bulk/ingest scenario, not a second operation store.

MAIN has the jobs ledger and accepted ingest work but no Lane F operations
ledger. Select the genuinely PROCESSING job before killing its Worker; retain
queue identity, content revision and completed job evidence and verify recovery
without resubmission. That is a job-replay comparison, not a fabricated
operation checkpoint value. If the needed in-flight cut cannot be reliably
held using the pinned tree's existing fixture/debug controls, record the clause
as unavailable; do not replace it with an idle Worker kill.

Use one collector clock for injection issued/confirmed death, first successful
API probe, first successful text search, restart detection, and successor
identity. Preserve UTC timestamps plus monotonic elapsed durations and polling
resolution. Branch API restoration must answer from the successor instance;
index readiness requires a real text query for a pre-crash committed marker.
Recovered in-flight marker availability is a separate completion timestamp.
MAIN API can stay alive: report observed service continuity and the first
post-death successful API probe, not an invented restart timestamp or exact
zero. Bootstrap the token after each observed boot; do not use stale tokens.

Freeze 13,600 ms crash-to-API ceiling (1,000 ms first cooldown + 12,600 ms
warm-start budget) from `values.json`. Report crash-to-index separately;
section 16 references index-ready as context, not an independently specified
13,600 ms index ceiling. Do not reuse `crashToApiRestoredMs` as that ceiling.
Measure the split timings as well as branch compliance.

Before kill and after recovery/stop capture verified child identities,
including process start time and executable/configuration. Verify surviving
healthy llama-server adoption on branch and continuity on MAIN; verify stale
extraction children do not remain or acquire a second owner. Run named crash,
requested restart, quit, and upgrade-shutdown paths separately. An upgrade
shutdown/child reconciliation probe is not an installed upgrade/E7 pass.
Absent actual active llama-server/extraction children, child-policy coverage
is missing, not vacuously successful. Cleanup stops only this owned run.

MAIN cannot supply `supervisor.v1.json` or Lane F operation checkpoint identity.
Record the Head's production Worker restart narration alongside branch's
supervisor transitions; retain operation checkpoint proof as branch capability
acceptance and MAIN's job replay as its distinct baseline. Section 16 credits
capability work separately from merge benefit. Since it does not explicitly
waive these paired clauses, unsupported clauses retain an unmeasurable verdict
and a named proposed disposition for root/owner; they never silently pass.

## E6: external debug faults and the limit of the paired claim

Add a small Node JDWP client/fault helper under the existing conformance
scripts, exercised by e-run, with PID/boot identity verification before attach.
Use loopback-only JDWP at distinct owned ports; bind the listener through
launch flags without application or MAIN source changes. Preserve debug socket
ownership across the fault; debugger disposal would resume suspended threads.
Use jcmd for diagnostics/full collections, not an assumed jcmd suspend command.

- Branch soft fault: suspend the live Engine API request/dispatch threads,
  identified from actual thread names/stacks, keeping
  `engine.shutdown-request-watcher` runnable. Prevent newly created matching
  request threads from making the wedge disappear. Prove a healthy pre-fault
  response, post-fault probe timeouts, and an out-of-band shutdown request
  consumed by this boot. Require graceful exit before the request deadline;
  a forced exit fails the soft clause. Locks held by selected threads can
  prevent close: this is an injection validity concern, not evidence of a
  graceful recovery. Keep the observed thread set/stack evidence and mark an
  invalid wedge unmeasurable; do not widen it to all threads.
- Branch hard fault: JDWP VirtualMachine.Suspend stops application threads
  including the request watcher. Keep the connection open until the host
  forces this JVM to die. Require request written, deadline elapsed, forced
  death, successor identity and restored API/index. This is an application
  whole-VM suspension; do not claim a real GC safepoint stall or suspension
  of every native VM service thread.
- MAIN Worker soft/hard: target Worker gRPC dispatch threads or suspend the
  entire Worker application VM, keeping Head and its health monitor running.
  Verify the MMF shutdown channel and observed graceful/forced outcome against
  MAIN's native deadlines. This compares supervised indexing failure domains,
  not a wedged Head HTTP API pool.
- MAIN Head API/whole-VM faults have no recovering dev-arm supervisor. The
  literal paired Head-API clause cannot pass by substituting Worker gRPC or
  manually restarting Head. Report the unsupported mechanism and absent
  autonomous recovery. Root can run bounded Head faults as descriptive
  evidence with explicit owned cleanup, but those are not an E6 pass.

No existing real-JVM API-pool or whole-JVM hang hook was found in the inspected
production trees. The branch fault barrier and chaos extraction parser serve
other cuts; the latter hangs an extraction child, not the Engine. A JDWP
thread filter is therefore provisional until live thread/stack evidence
demonstrates the intended fault; dry-run construction is not that proof.

Read hang policy from the supervision contract and values frozen after E4:
interval >= 10 s; interval * misses >= 3 * worst observed E4 safepoint pause.
Measure injection-to-detection, detection-to-request, request-to-death and
death-to-API/index, plus aggregate fault-to-restoration. Budget detection
separately, including poll phase and probe timeout. Graceful and forced
restoration ceilings are request deadline + the same warm-start budget;
include the production counted-restart cooldown in the documented budget
accounting rather than silently dropping it.

If E's selected interval/threshold differ from MAIN's fixed 10 s/3,
MAIN cannot exercise the exact E-set clause without modifying MAIN. Show the
actual MAIN policy and native-policy result, keep exact-policy parity
unmeasurable, and propose a bounded owner disposition. A harness override on
branch alone is not a paired pass. If the selected values equal MAIN's, prove
that equality before enabling the comparable Worker timing verdict.

JDWP semantics source: Oracle JDK 25 `VirtualMachine.suspend()` and JDK 24
`ThreadReference.suspend()` documentation:
https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jdi/com/sun/jdi/VirtualMachine.html
https://docs.oracle.com/en/java/javase/24/docs/api/jdk.jdi/com/sun/jdi/ThreadReference.html

## Implementation boundary, teardown and verification

Extend `scripts/jseval/lane-f/e-run.mjs`/`e-run.test.mjs`,
`head-rss-sampler.ps1`, and the existing real-recovery/scenario scripts. Keep
pure argument/plan/parser/verdict helpers separate from process execution.
Small instrument helpers can live in those existing directories; do not add
a cross-language product contract, database, daemon, or production fault hook.
The existing receipt owns lifecycle; artifacts are projections of its events.

Every E4/E5/E6 arm plan supports `--dry-run` without filesystem mutations,
starts, attaches, kills or API calls. Plans name actual targets, role-specific
logging, settings requests, required input bounds, policy incompatibilities,
and unsupported clauses. Allow explicit arm-tree overrides so the instrument
tree can target root's built branch rather than hardcoding this unbuilt tree.
Validate options by group, reject duplicate/unknown options, non-loopback
addresses, invalid numbers/windows and ambiguous process targets.

Replace obsolete blanket gaps only where a connected instrument supplies
evidence. Missing inputs, snapshots, role logs, checkpoints, live children or
injection confirmation remain missing. Known violations dominate missing
data. Never aggregate away a failed window, restart, or incompatible policy.
Represent unsupported clauses explicitly with source/reason and proposed
disposition; the paired group remains unmeasurable until required evidence
or an authorized disposition exists. E7 retains its existing signing rule.

Node tests must falsify: MAIN/branch plan construction; all-JVM logging;
owned process coverage and foreign PID exclusion; full/young GC parsing,
units/rotations/incarnation separation; positive/zero/negative slopes and
insufficient coverage; scheduled settings/witness handling; crash timestamps
and MAIN API continuity; nonzero checkpoint/no-duplicate requirements;
orphan/adoption policy failures; soft forced-exit failure; hard graceful-exit
failure; exact/native policy mismatch; timeout/incomplete evidence; failed
window aggregation; and dry-run's complete lack of execution side effects.

After implementation run `node --check` for each changed/new .mjs/.cjs,
focused `node --test` suites and the existing E1 sampler/analyzer regressions,
then `git diff --check`. No Gradle is needed for a scripts-only implementation.
If implementation exposes a necessary Java change, stop with the owner's
specified READY FOR BUILD report before running Gradle. Root owns all live
measurement; local Node proof must be reported separately from live proof.

Reach: the reusable principle is to bind measurements to the surviving
supervisor and actual boot/process identity, and to compare capability clauses
without inventing symmetry. It also applies to E7 and D1 readiness evidence,
but this work does not generalize their harnesses. It earns its keep when a
foreign process, wrong incarnation, or unsupported MAIN clause is rejected by
a falsifying test. Retire arm-specific adapters when both arms expose the
same authoritative recovery/process contract.


## Implementation and root handoff (2026-10-01)

Root accepted this design before implementation. The instruments extend the
existing E driver and real-writer recovery mechanics; no Java/product code,
production fault hook, daemon, new ledger, stack start, or Gradle run was added.
The scoped PowerShell sampler emits exact private bytes and creation identities
for every owned descendant. The collector projects full-GC per-JVM and aligned
arm slopes, launch limits, settings-consumer receipts, and crash observations.
The crash experiment preserves before/dead/after ledgers, kill/API/index times,
checkpoint samples and child-path evidence. Completed job timestamps must stay
unchanged: a reprocessed DONE job cannot pass by returning to DONE between polls.
JDWP uses verified owned process identities, loopback, request-thread suspension
(including new pool threads), VM suspension, and explicit cleanup. Missing live
threads or absent timeout evidence cannot become a successful wedge.

MAIN's adapter reads its real jobs schema; absent `unit_revision` is recorded
as NULL, never added to the database. MAIN operation/checkpoint/supervisor
clauses stay `unmeasurable-on-split`. Native Worker recovery and Head API
continuity are separately reported. Hang request times use producer UTC log
stamps where available, request deadlines on branch, or explicit observation
intervals. Graceful death/recovery and detection use conservative timestamp
bounds; the native split probe-time limit is not invented.

The requested `git merge --no-edit codex/lane-f-pr1` was attempted first and
rejected by the filesystem sandbox: ORIG_HEAD.lock is in the main checkout's
Git metadata, outside this writable root. The four newer driver/readiness
files were projected directly from that ref before implementing, preserving
optional tokens, readiness gates, repo-root selection and machine facts.
Root must reconcile the actual Git merge while preserving these edits; this
is not a claim that a merge commit exists. No commits or pushes were made.

Local proof: syntax checks for all nine changed/new MJS/CJS files, PowerShell
parser validation, focused Node tests (including the existing E1 instrument
suite), and three Python readiness unit tests. Fixtures exercise HTTP, SQLite
and a loopback JDWP protocol peer; none launches an application JVM. Final
counts and commands are recorded after the final verification below.

Root run prerequisites: use the owner's already frozen E0 evidence via
`--repo-root F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify`; keep
MAIN pinned at ac1c93bf3 and use root's clean built branch arm. The driver
allows only evidence/E output dirt in an arm source tree; product/instrument
source changes still fail the built-revision check. Each arm records machine,
corpus, instrument and values hashes. Run every command serially under root's
shared-stack lease. Preserve raw tmp/lane-f-e and evidence/E through stage F.

```powershell
$eDriverPath = 'F:/justsearch-public/.claude/worktrees/lane-f-e-instr/scripts/jseval/lane-f/e-run.mjs'
$eEvidenceRoot = 'F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify'
node $eDriverPath e4-memory-soak --arm main --window 1 --repo-root $eEvidenceRoot
node $eDriverPath e4-memory-soak --arm main --window 2 --repo-root $eEvidenceRoot
node $eDriverPath e4-memory-soak --arm main --window 3 --repo-root $eEvidenceRoot
node $eDriverPath e4-memory-soak --arm branch --window 1 --repo-root $eEvidenceRoot
node $eDriverPath e4-memory-soak --arm branch --window 2 --repo-root $eEvidenceRoot
node $eDriverPath e4-memory-soak --arm branch --window 3 --repo-root $eEvidenceRoot
node $eDriverPath e4-hang-values --repo-root $eEvidenceRoot
node $eDriverPath e5-crash --arm main --repo-root $eEvidenceRoot
node $eDriverPath e5-crash --arm branch --repo-root $eEvidenceRoot
node $eDriverPath e6-hang --arm main --repo-root $eEvidenceRoot
node $eDriverPath e6-hang --arm branch --repo-root $eEvidenceRoot
node $eDriverPath table --repo-root $eEvidenceRoot
```

Append `--dry-run` to each invocation for a no-side-effect plan. E6's default
invocation runs soft and hard in separate fresh owned runs, retaining both
results; `--fault soft|hard` selects a diagnostic subset, which cannot alone
satisfy the complete E6 row. Main's exact-policy clause stays unmeasurable if
E4 chooses something other than MAIN's fixed ten seconds / three misses.
E5's upgrade child path is the real prepare/commit-shutdown API, not E7's
signed installer experiment. Missing active generative/extraction children
leave the child-policy clause unmeasurable.


Final local verification (2026-10-01): **58/58 Node tests**, **3/3 Python
readiness tests**, **9/9 Node syntax checks**, PowerShell parse and
`git diff --check` passed on this uncommitted working tree. Commands:

```text
node --test scripts/jseval/lane-f/e456-instruments.test.mjs scripts/jseval/lane-f/e-run.test.mjs scripts/jseval/lane-f/test/e1-instruments.test.mjs
node --check <each of the nine changed/new MJS/CJS files listed in the source digest>
PYTHONPATH=scripts/jseval python scripts/jseval/lane-f/capability-ready.test.py
git diff --check
```

[Complete Node suite output](../../../../../tmp/lane-f-e-instruments/verification-node.txt)
and [tested script digests](../../../../../tmp/lane-f-e-instruments/verification-owned-files.json)
are retained locally through root's review. The Python tests ran with
PYTHONDONTWRITEBYTECODE=1. Live E4/E5/E6 measurements, PowerShell process
sampling, installed-engine runs and Gradle remain **unperformed** here under
the owner's restrictions. Successful local fixtures are not JVM recovery
or stage-E acceptance evidence. The actual requested Git merge remains
blocked as described above.
