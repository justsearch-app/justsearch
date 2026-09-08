---
title: "Lane F stage B — lifecycle: implementation checklist"
stage: B
created: 2026-09-08
base: dafc4a484
status: "B1-B14 and B16 implemented and verified on the lane; B15 and remaining B17 proofs open; signed dead-Engine installer round deferred as decided"
updated: 2026-09-08
---

# Lane F stage B — lifecycle: implementation checklist

Written from the three inputs 17.6 requires: the stage's row in `design.md` 17.3,
`verified-facts.md`, and a fresh read of the code at this worktree's HEAD (`58d889e78` =
`worktree-lane-F-A`, the stage-A head plus an in-flight fix pass). Section numbers are
`design.md`'s unless prefixed with a letter.

**This checklist is a draft.** It was written while stage A's checkpoint review was still open,
so the code it cites may move before B starts. Every `file:line` below was opened at `58d889e78`;
17.6 requires them re-checked at stage start, and a fact that no longer holds corrected in
`verified-facts.md` *before* the checklist relies on it. §0 lists what this pass already owes.
**One caveat on provenance:** this worktree carries another agent's *uncommitted* fix pass, so a
file read from disk is the working tree, not `58d889e78`. One row is affected —
`MigrationStartResponse`/`MigrationRollbackResponse`'s field is `restart_scheduled` at HEAD and
`restart_required` in the working tree — and I10/B15 are written against the latter, which is
where that pass is going. `MigrationCutoverResponse` has no such field in **either**, so B15's
substance is unchanged. Re-check I10 once that pass lands.

Stage B in one line (17.3 row B): **the supervisor contract with the re-cut budget, a conformance
harness with a fake engine, both implementations, the child registry and its reconciliation, the
ordered shutdown owned by the root with the request file as its second trigger, and the
dead-Engine updater path.**

---

## 0. Corrections to inherited facts (apply to `verified-facts.md` / `stages/A.md` before relying on them)

Seven corrections fall out of this pass. Two are moved citations; five change a claim.

1. **`WORKER_RESTART_EXHAUSTED` still has no producer — `stages/A.md` §10 row 1 over-corrected.**
   A.md (corrected 2026-09-08) says `KnowledgeServerHealthMonitor` "still emits …
   `WORKER_RESTART_EXHAUSTED` (`:343`, `:500`). These are production call sites, not tests." Read
   at this HEAD, both are **consumer** references: `:342-345` is an equality read feeding
   `BootRecoveryDecision.Input`, and `:490-500` latches and logs without a `transition(...)`. The
   one exhaustion transition on that path, `:536-547`, passes
   `WORKER_SPAWN_RECOVERY_EXHAUSTED.code()` — a different code. (`WORKER_RECOVERING` at `:419`
   **is** a genuine producer.) So the design's original statement holds and B owes the code a
   producer (**B14**). The gate cannot catch this and says so:
   `scripts/ci/check-readiness-reason-codes.mjs:169-173` names this exact code as its own worked
   example of "a reference is not an emission".
2. **`-XX:+UseCompactObjectHeaders` is already in the Engine's flag set at both spawn sites.**
   17.7 places that decision at stage E ("gate run, with the collector"). At this HEAD the pin
   `scripts/dev/test-dev-runner-head-java-opts.mjs:43-49` lists it in `SHARED_FLAGS`, the
   dev-runner emits it (`scripts/dev/dev-runner.cjs:708-709`) and the Tauri spawn carries it
   (`modules/shell/src-tauri/src/lib.rs:778-828`). Stage A (A13) decided it. Either 17.7's row
   is stale or A pre-empted the owner; **raise at stage start, do not re-decide in a commit body.**
3. **`check-runtime-manifest-closure.mjs` line citations moved**, and its dev-runner comment is
   false-in-waiting. `verified-facts.md` records `SKIP_PATHS` at `:64-80` with `dev-runner.cjs`
   at `:73` and `lib.rs` at `:80`; at this HEAD the block is `:65-84`, `:74`, `:81`. The skip's
   own justification (`:71-73`) reads "The dev-runner writes nothing into `<dataDir>/runtime/` —
   it observes"; item **B8** makes it a writer, so narrowing is not a nicety — leaving it makes
   the check pass vacuously over a new writer.
4. **`WorkerInfo.grpcPort` is already earmarked for this stage.**
   `modules/app-api/src/main/java/io/justsearch/app/api/runtime/RuntimeManifest.java:157-163`
   says the field has had no producer since A11 and that "stage B's versioned schema bump
   (design 7.2, the child registry) is where it goes". The bump (**B11**) therefore removes a
   field as well as adding one — a *breaking* change, not an additive one.
5. **`HeadShutdownCoordinator` does not own the sequence; `HeadlessApp` does.** 7.3 says the
   ordered shutdown is "seeded from `HeadShutdownCoordinator`". The coordinator
   (`modules/ui/src/main/java/io/justsearch/ui/HeadShutdownCoordinator.java:19`) owns
   idempotency, the receipt and the exit; the eight ordered steps live in the `Supplier` passed
   to it, `HeadlessApp.performOrderedShutdown` (`modules/ui/.../HeadlessApp.java:1184-1243`).
   B re-homes both halves, not one.
6. **`commit-shutdown` already does not wait for a second process.**
   `UpgradeController.commitShutdown` (`modules/ui/.../api/UpgradeController.java:97-139`) takes
   one synchronous status snapshot (`:180-193`) and 409s if not ready; the polling is the
   caller's. What B changes is the *tail*: it writes the request file instead of invoking the
   coordinator inline. Smaller than 7.3's sentence reads.
7. **The `supervision-contract` register carries a phantom method.**
   `governance/supervision-contract.v1.json:78` cites
   `KnowledgeServerBootstrap.supervisionEngagedOnLastAttempt`, which does not exist in code
   (grep: only this JSON and `docs/tempdocs/825-*`). Sweep it with **B16**; it is inside the
   still-active part of a register B edits.

---

### 0.1 Corrections found while implementing (appended per item)

- **Scope stop (2026-09-08).** The newest design section 0 amendment suspends the
  unimplemented shared first-claim/accepted-marker protocol. Read
  `evidence/B/scope-recut.md` before the next B2/B3/B6/R3 batch. The preferred
  single-writer cut needs production proof before replacing the current transport.
- **B17's explicit stress run found a new blocking red (2026-09-08).** The
  integrated 9,364-test inventory has two failures, preserved in
  `evidence/B/integrated-verification.md`. The obsolete drain classifier is repaired
  in `4349b28f5`; the Engine boot-under-file-lock test exposes a writer closed by
  a segment-write failure with no effective runtime recovery. This is not B14's
  no-client boot case. Do not claim stage-B verification or change the failing
  assertion while the recovery investigation remains open.
- **Terminal-writer repair (2026-09-08).** The installed Engine now routes an
  unusable writer through the complete ordered shutdown and fatal exit 1. The
  dev-runner restarts it once and retained/accepted documents become searchable.
  See `evidence/B/writer-recovery-investigation.md` for source, negative tests,
  native-initialization evidence and verification limits. This closes the bounded
  detection/ordered-exit connection; B17 remains open for hostile-lock survival,
  both durable queue outcomes and the other supervisor's production proof.

- **B10's production binding was not proved by the conformance adapter.** The adapter owns a
  distinct fake actuator, so its real-child timing cannot prove the shell's child slot, close/spawn
  admission, manifest event, stdout generation, watcher lifetime, or UI event writer. The follow-up
  extracts those responsibilities into the Tauri-free `engine_host.rs` core used by `lib.rs` and
  tests that core with real child handles. `Cargo.toml` now states the conformance boundary instead
  of calling the adapter proof of the production actuator.
- **A reaped child must close manifest admission before incarnation reset.** Merely checking the
  discovery phase left a `Bound` phase able to accept the dead child's manifest after its handle was
  removed. Reap/take now transitions to `AwaitingSuccessor`, and observation also requires the live
  installed child's PID. The regression exercises the interval before reset as well as stale and
  successor manifests.
- **Watcher cancellation can originate on the watcher thread.** The manifest tick holds only a
  temporary upgrade of a weak host reference, so its release can run fallback cleanup on that same
  thread. Cancellation always wakes the condition variable; ordinary close joins, while a watcher
  cancelling itself skips self-join. This keeps explicit close deterministic without making Drop a
  circular termination dependency. The single watcher also retains the last tooltip projection so
  identical manifest polls do not repeat the native tray mutation.

- **Every B1-B6 citation re-checked at stage start; none was wrong, nine had drifted.** The
  branch moved between this draft (`58d889e78`) and the stage start (`dafc4a484`) — the
  checkpoint fixes plus two merges — so 22 load-bearing `file:line` citations were re-resolved
  against HEAD by content, not by line. All 22 resolve; 9 sit up to 6 lines from where the draft
  put them (`HeadlessApp` exits at `:901`/`:955`/`:1129`, `performOrderedShutdown` at `:1186`,
  the JVM hook at `:1115`, `dev-runner.cjs`'s HeapDump at `:715`, `lib.rs`'s at `:796`,
  `SHARED_FLAGS` at `:44`, `SqliteJobQueue.close` at `:2120`). No claim changed.
- **§0.1 of this draft is itself now stale, and in the direction it warned about.** It says
  `stages/A.md` §10 row 1 "over-corrected" by claiming `WORKER_RESTART_EXHAUSTED` has a producer.
  That was true of the A.md it was written against; stage A's checkpoint then corrected row 1
  from the call sites and reached the same conclusion this draft did. Independently re-verified
  here: `KnowledgeServerHealthMonitor.java:545` emits `WORKER_SPAWN_RECOVERY_EXHAUSTED` — a
  *different* code — and no site transitions to `WORKER_RESTART_EXHAUSTED`. It is now declared in
  `governance/readiness-reason-codes.v1.json`'s `awaitingProducer` with `owner: lane-F/B`, and
  the gate's new emission direction reds if a producer appears and the entry is not removed. B14
  still owes the producer; what changed is that the gate now agrees.
- **B1's premise about the exit codes was wrong on one of three, and it is the one that matters.**
  The item says the Head's three `System.exit` calls "are all plain boot failures, i.e.
  **non-transient**". Read at the sites: `:955` (another instance holds the data-directory lock)
  is genuinely non-transient, but `:901` is the default uncaught-exception handler — installed
  before anything boots, able to fire on any thread at any time, i.e. a crash — and `:1129` is the
  catch around the whole run. Both exit `1` and nothing in the integer separates them. Classifying
  that union non-transient would send every runtime crash straight to `exhausted` with no retry,
  the opposite of 7.1's intent. `EngineExit` classifies `1` **transient** and records the
  ambiguity at the constant; the non-transient class grows by giving a genuinely unretryable boot
  failure its own code.
- **The out-of-memory exit code is 3, measured.** 7.1 calls "the out-of-memory exit" transient
  without naming the code. Measured on the project's JDK (Temurin 25.0.2, 2026-09-08): with
  `-XX:+ExitOnOutOfMemoryError` the JVM exits **3**; the same program WITHOUT the flag exits
  **1**, because the OOM reaches the uncaught-exception handler. That is the argument for the
  flag — not diagnostics, but the difference between a supervisor that can see a memory death and
  one that reads it as a bad config.
- **B4's "re-home both halves" is not available; one half moves.** The item asks for
  `HeadlessApp.performOrderedShutdown` **and** `HeadShutdownCoordinator` to move into
  `app-engine`. The eight steps close `LocalApiServer`, `HeadAssembly` and six other types living
  in `modules/ui` and `app-services`, and the edge runs `ui -> app-engine`; importing them into
  the root inverts it and breaches ArchUnit rule 6b. What moved is the **sequence** — order,
  reason, error accounting, idempotency, receipt, single exit — and what stayed is the **binding**
  of each step to the object it closes. `HeadShutdownCoordinator` survives as a 45-line adapter
  because `UpgradeShutdownAction` is a `ui.api` interface the root cannot implement.
- **B5's WAL acceptance could not be met as written, and three falsifications say why.** The item
  asks for a test on the `-wal` file's size or the pragma result, "not on the call" — to prevent a
  vacuous `verify()`. Each draft passed with the new call deleted: (1) `-wal` after a lone close —
  SQLite checkpoints and deletes it itself when the last connection closes cleanly; (2) database
  growth with a second connection held open, on the theory SQLite defers — identical numbers with
  and without (`4096 -> 139264`, wal absent), because the connection was never used and
  sqlite-jdbc opens lazily; (3) `-wal` shrinking to zero — failed, because
  `wal_checkpoint(FULL)` copies frames and REUSES the log file rather than truncating it
  (`wal 3753352 -> 3753352, db 4096 -> 139264`). On this driver an ordinary close already drains
  the log either way. The explicit call still lands, because 7.3 step 7's guarantee should be
  stated by our code rather than inherited from the driver's happy path — but that is a smaller
  claim than "undrained frames were piling up", and the rename (`checkpointForUpgrade` named its
  *caller*, and had exactly one) is the change with teeth.
- **B2's field set gained `preparationId` at B6.** The receipt is preparation- and nonce-bound and
  both were passed in memory before; the file became the gap they had to cross. Extended before
  any second writer exists, which is the only cheap moment.
- **B3 lands before B4 in the commit order.** B.md numbers the watcher before the sequence it
  runs. Implemented sequence-first so each commit is green on its own terms; the watcher was inert
  for one commit and went live at B6, which is stated in B3's commit body rather than left as a
  gap a reader has to notice.
- **B7's drift check could not go in `SupervisionContractTest`, and the reason is an edge, not a
  preference.** §9 asks for an `enginePolicyMatchesCode()` there. That class is in `app-services`
  and the dependency runs `app-engine -> app-services`
  (`modules/app-engine/build.gradle.kts:26`, `implementation(project(":modules:app-services"))`),
  so importing the mirror would invert the edge for a test. The row now NAMES its check in a
  `driftCheck` field and `everyLiveProcessHasADriftCheck` requires a live row to be checked in that
  class **or** to name a check that resolves — so "cannot be skipped by omission" survives while the
  edge does not move. Two consequences worth stating: the live-process set is still pinned
  (`{brain, engine}`), and guard resolution had to learn about repo-relative PATHS, because every
  test that exercises an engine row is Node or Rust and an FQCN-only resolver would have left the
  whole row on sentinels — unguarded, with a note saying so.
- **`EngineSupervisionPolicy` had to be a pure constant holder, and that is honest rather than a
  dodge.** A record with defaults — `BrainSupervisionPolicy`'s shape — has no production caller,
  and `WholeProgramDeadCodeTest` would have failed the build for it (correctly: §10 forbids growing
  that baseline). `isConstantHolder` exempts a namespace of `static final` constants because javac
  inlines them, which is exactly what this class is. The register's `authorityNote` says outright
  that nothing in Java calls it.
- **§2's cooldown ceiling is inert at §2's budget.** With `maxRestartAttempts` 3 the linear ramp
  reaches 3000 ms on the last attempt, so `maxCooldownMs: 5000` never binds. It is declared anyway —
  it bounds the ramp if the attempt budget is ever raised — but the register and the mirror both say
  so, and the one conformance case that reaches it raises the budget explicitly. A ceiling that
  looks load-bearing and is not is the kind of number a later reader tunes for nothing.
- **The dev-runner's FIRST incarnation is not supervised, and cannot be.** `start` is synchronous
  for its caller, so a first incarnation that never publishes a port already fails the command with
  an error a human reads; the supervisor has no caller to fail. `startDeadlineMs` therefore governs
  RESTART incarnations only. The same distinction does not exist on the Tauri side, where the shell
  spawns the Engine at setup and nobody is waiting — so there the deadline covers the first boot too.
- **B8's port-wait loop stopped short-circuiting on an explicit `--api-port`.** It used to, which
  meant such a start never read the manifest at all and recorded `portSource: unresolved` with a
  null `instanceId`. A supervisor needs the instanceId on every incarnation to tell one boot from
  the next, and the manifest is deleted before each spawn, so waiting for it is now the readiness
  signal in both cases. Timeouts and the failure message are unchanged.
- **`lastExit.class` records the class the BUDGET used, not the integer's.** An Engine asked to stop
  for a hang runs its ordered shutdown and exits 0 — the code of a clean quit. Recording that as
  `REQUESTED` would tell a reader the death was free when it was charged. Both implementations carry
  `codeClass`/`codeReason` beside it so neither reading has to be inferred from the other.
- **Q5's mirror is a sibling JSONL, not `RuntimeManifestPublisher`'s per-instance mirror.** That
  mirror is Java-written and keyed by `instanceId`, and the case that needs the terminal record
  hardest is the one where no instance ever published a manifest — so there is no key. The
  supervisor writes `runtime/instances/supervisor-history.v1.jsonl`: same directory (already
  sanctioned), same append-only shape, keyed by time instead of by an identity the failure mode may
  have prevented from existing. Written by the dev-runner; the shell's terminal record is the live
  file, which is what the updater (B13) reads.
- **`dev-runner.cjs` refuses a `--data-dir` outside the repo root**, and the refusal is reported as
  JSON on STDOUT. The conformance harness's work directories therefore live under the repo's
  gitignored `tmp/`, not `os.tmpdir()`. Worth recording because of how it presented: every
  dev-runner case failed with "supervisor never reached running" and an EMPTY stderr, which is a
  message that actively misleads. Both adapters now quote stdout too.
- **B9's runner discovers NINE files, not eight.** B8 added `test-dev-runner-supervisor.mjs`. The
  runner asserts a FLOOR rather than a count, so the next file is not a two-file change and the
  assertion still fails when discovery breaks — which is the only failure a runner would otherwise
  report as a green.
- **B9's CI step went to `windows-native-tests`, not `public-claims`.** Same workflow, same
  `pull_request` + `push` triggers, so the coverage B9 asked for is unchanged. The reason is the
  runner's subject: `dev-runner.cjs` is Windows-first by its own header, and cuda12 resolution,
  `taskkill` and the handle-release cooldown either do not exist or do not mean the same thing on
  ubuntu — and a Linux run could not be verified from this branch. The dev-runner conformance
  ADAPTER joins it there for the same reason. `public-claims` gets the harness `--self-test`, which
  is pure Node and was otherwise running nowhere.
- **`check-runtime-manifest-closure`'s Rust scan had never scanned anything.** Its glob was
  `modules/shell/src-tauri/src/**/*.rs`, and the file's own glob translator turns `**` into `.*`
  followed by a required `/` — so it matched `src/bin/foo.rs` and never `src/lib.rs`, and until B10
  every Rust file in the crate lived directly under `src/`. `lib.rs`'s SKIP_PATHS entry was
  therefore hiding a file the scan could not have seen. Found by doing what B10 asks in the order it
  asks: remove the entry, plant an unsanctioned artifact name, and observe. The check stayed green.
  Glob and entry both fixed; the same falsification now reds. §6's row asked for the two writers to
  be checked rather than exempt, and for the Rust one that required fixing the scan first.
- **A Tauri incarnation that died WHILE STARTING was read as a start-deadline hang.** `await_ready`
  failing does not mean the child is up and silent; it can mean the child is gone. The loop then
  wrote a `hang` request — and `hang` is the one requested reason that is counted — so a
  NON_TRANSIENT death was rewritten as a counted transient one and spent the whole budget. Only the
  actuator half could surface this; the decision table cannot, because the two events look identical
  to `decide`. This is Q2's argument for option (a) paying for itself on the first run.
- **The stage's own harness assertions needed falsifying twice.** Two of them passed for the wrong
  reason: the dev-runner adapter matched `/forcing\./` against the supervisor's own "…then a forced
  kill" narration, so every graceful hang recovery reported itself as forced; and a concurrent
  liveness probe (1 s timeout, 200 ms interval) could arm several request deadlines of which only
  the last was tracked, so an untracked one could `taskkill` an incarnation that had done nothing
  wrong. The second was a real supervisor defect found through a test that was itself wrong.
- **No conformance case covers "`exhausted` kills the registered children".** §7 lists it, and it
  cannot be written at B10: the child registry is B11 and reconciliation is B12, so there is nothing
  to kill and nothing to assert was left alone. Named here rather than added as a case both adapters
  would have to skip — "a case skipped for one adapter fails the harness" makes an unimplementable
  case a permanent red, not a placeholder. The engine row's `external-adoption` fault mode carries
  the same deferral with a dated sentinel.
- **The same "died while starting" race exists in the dev-runner, and the critical-analysis pass is
  what found it there.** After the Rust fix above, re-reading the JS path showed the mirror image:
  `startNextIncarnation`'s await is still pending when a fast-dying incarnation's own exit handler
  decides a restart and advances the counter, so the stale rejection would have requested a shutdown
  of an incarnation that had just started and done nothing wrong. Guarded by an incarnation witness.
  Neither adapter caught it — the case that comes closest (`start-deadline-stops-a-partial-boot`)
  passes either way, because the wrong request lands on an incarnation the case does not inspect.
  Recorded because it is the second instance of one class: **an asynchronous start racing a
  synchronous death handler**, in two languages, from one design.
- **The loop is shared between the two Rust actuators; the ACTUATORS are not, and the conformance
  run only exercises one of them.** `run_supervision` is the same code in production and under the
  harness — which is the point of the trait — but `ShellActuator` binds it to the real child and
  `FakeEngineActuator` binds it to the fake engine, and no test drives the first. The
  critical-analysis pass found two divergences that only the unexercised half had: `force_kill`
  TOOK and reaped the child handle, so the next `poll_exit` would have returned `None` forever and
  the loop would have sat in `stopping` waiting for an exit it had already consumed; and the
  shutdown check ran only at the top of a turn, leaving a 50 ms window in which a death observed
  just as the shell began quitting would have restarted the Engine into a closing desktop. Both
  fixed. The general finding is the one to carry into B11-B13: a shared loop makes the DECISIONS
  common and the bindings divergent, so the actuator that CI cannot reach needs reading, not
  assuming.
- **NAMED RED, not stage B's: `OnnxEmbeddingEncoderLongDocForensicTest.longDocEmbedWithSpansMatchesBaseEmbed`.**
  The full `./gradlew.bat cleanTest test --no-build-cache` at the end of B10 reported one failing
  task. Two cases in it timed out; one (`OnnxEmbeddingEncoderBoundedTokenizeTest`, a 10-minute
  budget) passed on an isolated re-run and was load starvation, the way `agent-lessons.md` predicts.
  The other is DETERMINISTIC: run alone on an otherwise idle machine it takes **68.4 s against its
  own `@Timeout(30, SECONDS)`** — a 2.3x overshoot, not a race. It is not this stage's: `git diff
  origin/main..HEAD -- .../indexerworker/embed/onnx/` is empty, and the one file the branch touches
  in `embed/` (`EmbeddingFingerprint.java`) changed at stage A's checkpoint. Deliberately NOT
  "fixed" by raising the number: 68 s for an 8192-token embed is the signature of a CPU fallback,
  so the budget may be right and the machine wrong, and widening a timeout to make a red go away is
  the move `fix-root-causes-not-symptoms` names. Recorded here and reported rather than acted on,
  because acting on it means deciding whether the encoder is meant to be on the GPU in a unit test —
  which is tempdoc 710's question, not stage B's. **Everything else is green: 1433 result files,
  9001 tests, 1 failure, 0 errors, 19 skipped.**
- **Open item for B11/B12: each Tauri restart starts another `watch_manifest` thread.**
  `spawn_headless_backend` starts one per spawn and the loop exits only on a spawn error, so a
  supervised restart leaves the previous watcher polling. The duplicates are idempotent (both call
  `observe_manifest`, which is provenance-checked) but they accumulate, and two watchers can both
  emit `backend-restart` for one instance change. Pre-existing shape, made reachable by B10.
- **The review fix batch implements shutdown admission but cannot yet cancel interactive turns.**
  The ordered sequence now freezes the existing `OperationLeaseService` first for every reason;
  an upgrade reuses the preparation that already owns the barrier. Design 7.3 step 2 remains
  deferred to C1 because the interactive context/admission front does not exist in stage B. The
  shutdown binding's javadoc therefore describes concrete steps rather than claiming that all
  eight conceptual steps are already implemented.
- **The B3 review fixes make request lifetime and watcher ownership explicit.** Expired requests
  are refused and cleared before dispatch, a prior incarnation's request is strictly cleared after
  taking the instance lock and before readiness publication, and the watcher is itself an ordered
  shutdown step. Closing it from its own
  callback uses orderly executor shutdown so the remaining close steps are not interrupted.
  Accepted-request retention and supervisor observation remain the separately decided follow-up
  protocol in `design.md` §0.
- **The B4 review fixes classify the index half from observable close behavior.** The production
  binding reports `GRACEFUL` when no index half was composed; an omitted index step remains
  `UNKNOWN`. An exception from the index-half step reports `FAILED` and makes the sequence unclean.
- **The B5 review fixes connect the reason table to the real inference lifecycle.** The production
  ordered step sets the close directive before `HeadAssembly.close()`, the assembly delegates to
  its held `InferenceLifecycleManager`, and the manager's real `close()` reaches `LlamaServerOps`
  only for `QUIT` and `UPGRADE`. `RESTART` and `HANG` preserve the generative backend.
- **The B6 review fixes exercise production request seams rather than copied test lambdas.** The
  package-visible writer, live-lease acceptance predicate and dispatcher are the same factories
  used by `HeadlessApp`. A prepared upgrade dispatches only when its `preparationId` equals the
  currently frozen `OperationLeaseService` snapshot and carries the controller-owned nonce.
  The writer is installed before API exposure. Commit reserves a short PERSISTING phase, persists
  synchronously before writing the response, and reaches ACKNOWLEDGED only after a successful
  flush; persistence and response failures restore OPEN so retry or cancellation remains possible.
  A controller-owned PREPARING reservation also spans freeze, cancellation request, nonce
  publication, Worker prepare and response publication. Competing prepare/cancel/commit calls get
  a retryable conflict, the reservation releases in `finally`, and repeated prepare preserves the
  existing capability.
  The watcher defers the exact request during PERSISTING, including after its force deadline, and
  accepts it after acknowledgement while the preparation remains live. Plain accepted requests
  retain generic expiry protection, and missing/null verification fails closed. Production-path
  tests cover direct wrong/missing nonces, persistence and flush failure, a request written before
  watcher startup, and response-before-dispatch ordering.
  First-claim request publication, accepted-instance marking/retention, schema and named exit
  changes, and Java/Rust/Node supervisor observation remain the separate protocol batch in
  `design.md` section 0; this transaction fix does not solve the shared single-slot race.
  The now-unused upgrade method and `UpgradeShutdownAction` implementation were removed from
  `HeadShutdownCoordinator`.
- **The B7/B10 follow-up must prove the shell's production ownership paths.** The conformance
  binary exercises the shared supervisor loop through a distinct actuator. The host-core,
  binding, child-close race, launch-failure and watcher-lifetime requirements in `design.md` §0
  add production-path evidence; they do not replace the existing conformance harness.
- **B11/B12 require a durable ownership handoff, not only new manifest fields.** The first v2
  ownership seed carries unreconciled predecessor children before child-capable bootstrap. The
  shutdown completion callback retains surviving ownership through restart/hang and failed close.
  The public projection moves to v2 without private child data; managed configuration comparison
  and the existing unmanaged external-server exception follow the dated decisions in `design.md` §0.
- **B13's no-port branch still needs exclusive process ownership.** No bound API port does not
  prove the Engine is absent. A releasable replacement hold, persistent mutually exclusive stop
  evidence, one supervised resume after failure, and a packaged recovery UI available before API
  binding are part of B13's acceptance. The older section 8 claims are amended by `design.md` §0.

---

## 1. What stage A left to B (the inheritance ledger)

Each row is a fact about the branch today, and the item that answers it. Nothing here is
optional: 17.3's "branch state after" for B is not reached while any row is open.

| # | inherited state, at this HEAD | item |
|---|---|---|
| I1 | **Nothing observes a running Engine's death.** Tauri drains stdout and only notices an exit that happens *before* a port is bound (`lib.rs:900-918`); `watch_manifest` (`lib.rs:989-1035`) polls the manifest and emits `backend-restart` (`:1012`) but never respawns. The dev-runner's child `exit` handler (`dev-runner.cjs:2182-2210`) writes a stop report and then `process.exit(code)`s itself — no retry, no backoff, no budget anywhere in `cmdStart` (`:1485-2221`). | B8, B10 |
| I2 | **The three restart paths answer, they do not restart.** `WorkerServiceImpl.restart()` (`modules/app-services/.../worker/WorkerServiceImpl.java:57`) unconditionally throws `RestartRequiredException` (`RestartRequiredException.java:31`, `CODE = "restart_required"`), surfaced as HTTP 409 (`InferenceHandlers.java:635,682`; `RestartWorkerHandler.java:61,74`). B's supervisor must serve them through the *requested restart* path so config-apply, AI install and pack import work again. | B8, B10, B15 |
| I3 | **`WORKER_RESTART_EXHAUSTED` has no producer** (§0.1), so `BootRecoveryDecision`'s `RESTART_EXHAUSTED` give-up (`BootRecoveryDecision.java:178-180`) is unreachable in practice and `KnowledgeServerHealthMonitor.java:490-500` is dead. | B14 |
| I4 | **`supervisionActive()` is hard-coded false** (`KnowledgeServerBootstrap.java:460-462`, doc `:450-459`: "Always false since lane F stage A item A11"), so `BootRecoveryDecision`'s `SUPERVISION_ENGAGED` veto (`:194-196`, `Veto` at `:69-102`) never fires and its narration arm is dead **by switch totality** (`KnowledgeServerHealthMonitor.java:530-534`, comment: "Unreachable"). | B14 |
| I5 | **`check-readiness-reason-codes`'s producer direction cannot tell the difference.** Its predicate (`scripts/ci/check-readiness-reason-codes.mjs:185-194`) matches `LifecycleReasonCode.NAME` or the quoted code string anywhere under `modules/*/src/main`, not inside a `transition(...)`. The register's own note says the effective exemption list is empty and "if a future code appears to need one, that is a design conversation, not a one-line entry" (`governance/readiness-reason-codes.v1.json`, `producerDirectionNote`). B must not satisfy it with a reference. | B14 |
| I6 | **`isRunning()` / `shutdownLatch` are orphaned.** `KnowledgeServer.java:191` declares the latch; nothing awaits it (A13 deleted the entry point that parked on it — doc `:182-190`, `:2285-2287`); `isRunning()` (`:2145-2147`) has zero production callers, only tests. | B16 |
| I7 | **`worker-config-snapshot.json` residue.** Allowlisted at `check-runtime-manifest-closure.mjs:48`; path still computed at `dev-runner.cjs:1970`; registered at `governance/store-corruption-policies.v1.json:45,48`; doc reference at `HeadlessApp.java:676`. | B16 |
| I8 | **The launch-flag pin is an exact set.** `test-dev-runner-head-java-opts.mjs:69-74` asserts `deepEqual` over `SHARED_FLAGS` (`:43-49`) for the dev-runner and text-greps `lib.rs` for the same flags exactly once each (`:118-136`). Any flag B adds must land at both spawn sites and in the pin in one commit. | B1 |
| I9 | **`stress` and `load-sensitive` are outside the default suite.** `stress` excluded repo-wide (`build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt:104-111`), opt-in with `-PincludeStress=true`, and CI runs only a *policy guard* over it (`.github/workflows/ci.yml:206-207` → `scripts/ci/verify-stress-suite-policy.mjs`). `load-sensitive` is excluded from `app-services` (`modules/app-services/build.gradle.kts:153,168`) with its own tasks (`:180`, `:196`) and the comment "Deliberately NOT wired into `check` or any CI lane" (`:171-174`). | B17 |
| I10 | **`restart_required` does not cover cutover.** `MigrationStartResponse.restart_required` (`modules/ipc-common/src/main/proto/indexing.proto:1182`) and `MigrationRollbackResponse.restart_required` (`:1263`) exist but are echoes of the caller's `restart_worker` flag (`MigrationControlOps.java:39,62` and `:161,184`); `MigrationCutoverResponse` (`:1190-1194`) has **no such field**. Since the cutover is what promotes a generation, B's supervisor is the only thing that can answer "who reopens the index after a promotion" until D1's live swap. | B15 |
| I11 | **The death-observability test runs nowhere.** `scripts/dev/test-dev-runner-death-observability.mjs` is referenced by no runner or workflow (repo-wide grep; the only mention is a comment at `dev-runner.cjs:666`); `package.json:17`'s `test:dev-runner` runs 2 of the 8 sibling `test-dev-runner-*.mjs` files, and is invoked only from `.github/workflows/onramp-smoke.yml:144`, whose trigger is `workflow_dispatch` only (`:8-14`, "not a per-PR gate"). It asserts nothing about the lease or the run id. | B9 |

---

## 2. The supervisor budget, re-cut (17.7 row, 7.1 lock paragraph)

The 627 seeds are read from the register, not from the tempdoc's prose, because the register is
what a test asserted against: `governance/supervision-contract.v1.json:23-29` (Worker `policy`
block, now `status: "retired"` at `:87`, `retiredAt` at `:88`).

| element | 627 seed (register `:24-28`) | Engine, stage B | why it changes (7.1) |
|---|---|---|---|
| max restarts | `maxRestartAttempts: 3` | **3**, unchanged | the count bounds a loop; nothing about one process makes a different count right |
| cooldown floor | `baseCooldownMs: 1000` | **the process handle closing** — a fact waited for, not a number | *cooldown is the outage*: with one process the cooldown is dead-API time, and Windows keeps file handles until the handle closes, which the death path must wait for anyway |
| cooldown ceiling | `maxCooldownMs: 30000` | **handle release + a small fixed increment, ceiling 5 s** (implementer's first cut) | a long cooldown only lengthens dead-API time; the restart *count*, not the cooldown, is what stops a crash loop |
| backoff shape | exponential 1/2/4 s, cap 30 s | **linear, +1 s per attempt over the handle-release floor** | same reason; exponential existed to protect a live API that no longer exists behind the child |
| stability window | `stabilityWindowMs: 300000`, counted from spawn | **300 000 ms, counted from `ready`** (the essential components: `api`, `index`) | the Engine's boot carries the encoder load (~40 s at A13's live check); a window from spawn is mostly consumed by boot |
| hang poll interval | `10 000 ms` (`KnowledgeServerHealthMonitor.DEFAULT_POLL_INTERVAL_MS`, `:62`) | **placeholder; set at E** | health and indexing allocation now share a heap: interval × count must exceed the worst safepoint pause the soak observes, or a long pause reads as a hang |
| hang miss count | `hangUnhealthyThreshold: 3` | **placeholder; set at E** | as above |
| exit classes | none — every death counted alike | **requested / transient / non-transient** | a boot failure whose exit code says the same input fails again must not be retried; three retries of a port conflict is forty seconds of flapping before the same answer |
| — *requested* | 627 U3's caveat: the manual `restart()` path bypassed `restartCount` entirely, so two accountings could double-spend | `restart`, `upgrade`; **not counted**, one budget and one classifier for both paths | 627's own unresolved caveat, closed by construction |
| — *transient* | n/a | native crash, `ExitOnOutOfMemoryError`'s exit, a hang; **counted, retried under cooldown** | |
| — *non-transient* | n/a | invalid configuration, port in use, a store at a schema the release cannot open; **`exhausted` at once, no retry** | boot failures the Head never budgeted for, because the Head never restarted anything |
| graceful-stop budget | signal-bus `writeShutdown` → `waitFor` → `destroyForcibly` (627:463) | **the request file's own `deadline` field, then forced kill** | the MMF bus went at A10; the file is the only out-of-band channel left |
| terminal state | `WORKER_RESTART_EXHAUSTED`; 627:118 records "no distinct user-surfaced terminal state" | **`ENGINE_RESTART_EXHAUSTED`** with the last exit reason, in `supervisor.v1.json`; on this path **only**, the children in the manifest registry are killed | 627's own finding; the file is visible precisely when the engine is not |
| start deadline | `awaitPort` timeout (627:122) | `starting` until `api` readiness or a start deadline; **hang detection suspended in `starting` and `stopping`** | per-component start deadlines are D1's; B needs one number for the `starting` → `running` edge |

**Not in this table, deliberately:** per-component start deadlines, the local-recovery attempt
budget and the escalate-immediately reason codes are 17.7's D1 row. B builds the
*requested-restart path* those will call (7.6) and nothing else.

---

## 3. Checklist items

Ordering rule: **the branch is green after every item.** Stage B is additive — it composes a
supervisor where none exists rather than replacing one — so unlike A there is no legitimate
non-compiling window. §10 names the only reds allowed, and none of them is a compile red.

Ordering shape: Engine-side mechanism first (B1-B6), because both supervisors depend on the
Engine exiting with a classifiable code and honouring a request file; then the contract and
harness (B7); then the two implementations (B8-B10); then children (B11-B12); then the updater
(B13); then the inheritance sweep (B14-B17).

### B1 — exit classes and `ExitOnOutOfMemoryError`

A single exit-code table on the Engine side (`app-engine`), mapping every deliberate
`System.exit` to a class the supervisor can read. Today the Head path has three
(`HeadlessApp.java:899`/`:1127` = 1, `:953` = 2, per `stages/A.md` §10 row 2) and all of them
are plain boot failures, i.e. **non-transient**. Add `-XX:+ExitOnOutOfMemoryError` at both spawn
sites (`dev-runner.cjs:686-731`, `lib.rs:778-828`); `-XX:+HeapDumpOnOutOfMemoryError` is already
at both (`dev-runner.cjs:721-724`, `lib.rs:792-797`) and stays.

**Acceptance:** `test-dev-runner-head-java-opts.mjs` updated in the same commit — both the
`SHARED_FLAGS` `deepEqual` (`:43-49`, `:69-74`) and the `lib.rs` text assertions (`:118-136`) —
and green; a unit test asserts every exit code the table names is classified, and that an
unknown code classifies **transient** (fail-open to retry, not to `exhausted`); `node
scripts/dev/test-dev-runner-head-java-opts.mjs` green.

### B2 — the shutdown request file

`<dataDir>/runtime/shutdown-request.v1.json`: `reason` (`quit` | `restart` | `upgrade` | `hang`),
`deadlineEpochMs`, optional `nonce`, `issuedBy`. Written by the supervisor and by
`commit-shutdown` (B6); read by the Engine. One writer helper shared by the Rust and JS halves
by *shape*, not by code (they cannot share a library).

**Acceptance:** a schema test pins the field set; `check-runtime-manifest-closure` is green with
the new artifact added to `ALLOWED_RUNTIME_ARTIFACTS` (`scripts/ci/check-runtime-manifest-closure.mjs:42-51`)
and a `tempdoc-ref` justification comment; a `store-recoverability.v1.json` entry exists
(EPHEMERAL, `RESET`, owner `SHELL`) and `check-store-recoverability` is green.

### B3 — the request-file watcher

A dedicated single-thread executor in the composition root, started after `api` readiness,
polling the request file. **Never the API pool** (7.3: a hung engine answers no HTTP). On a
valid request it runs B4's sequence with the request's reason; an invalid or nonce-mismatched
file is logged and ignored, never acted on.

**Acceptance:** a test drives each of the four reasons through a fake sequence and asserts the
reason reaches step 6 (B5); a test asserts an unparseable file does not shut anything down; a
test asserts the watcher thread is not on the API executor (assert by name against the executor
the API front owns).

### B4 — the ordered shutdown, owned by the root

Re-home both halves (§0.5): `HeadlessApp.performOrderedShutdown` (`HeadlessApp.java:1184-1243`,
eight steps) and `HeadShutdownCoordinator` (`HeadShutdownCoordinator.java:19`, memoised at
`:43-54`, exit gated at `:61-67`/`:69-92`, receipt at `:20`/`:76-87`) into `app-engine` as one
`EngineShutdownSequence` taking a **reason**. Both existing triggers keep working:
`POST /api/lifecycle/shutdown` (`LifecycleApiModule.java:36`, handler `:49-66`, 202-then-shutdown)
and the JVM hook (`HeadlessApp.java:1112-1120`). The eight steps map onto 7.3's eight; the two
that are not yet buildable are named rather than faked:

- 7.3 step 1 (close mutating admission) has no admission front until C1 — B implements it as the
  upgrade barrier's existing admission freeze (`UpgradeController.java:101-106` reads
  `snapshot.admissionFrozen()`), generalised to every reason.
- 7.3 step 3 (checkpoint durable operations) has no operations table until C2 — **this is 17.8's
  fifth bullet firing**; see §11. B implements the buildable half: stop taking new work and
  checkpoint what the job queue already has (B5).

**Acceptance:** idempotency preserved (a test calls the sequence twice and asserts one run);
both triggers reach it; the receipt is still written nonce-bound on the upgrade path
(`HeadShutdownCoordinator.java:76-87`); `:modules:ui:test` and `:modules:app-engine:test` green.

### B5 — llama-server by reason, and the WAL checkpoint on the ordinary close

Two independent facts, one commit because both are step-6/step-7 of the same sequence.

- `InferenceLifecycleManager.close()` (`modules/app-inference/.../InferenceLifecycleManager.java:1339-1348`)
  calls `serverOps.stopLlamaServer()` unconditionally at `:1342`, inside `synchronized (runner.lock())`,
  with no state check. Add the reason: `quit` and `upgrade` stop it; `restart` and `hang` leave it
  for adoption (7.2).
- `SqliteJobQueue.checkpointForUpgrade()` (`modules/indexer-worker/.../queue/SqliteJobQueue.java:2201-2219`,
  `PRAGMA wal_checkpoint(FULL)` at `:2206`) has exactly one production caller,
  `WorkerUpgradeQuiescence.java:51`; `close()` (`:2119-2138`) does not checkpoint. Move the
  checkpoint into the ordinary close path (rename off `ForUpgrade`), keeping the upgrade caller.

**Acceptance:** a test per reason asserting `stopLlamaServer` called / not called — including the
adverse precondition (`green-masked-destructive`: assert `restart` leaves it running, not only
that `quit` stops it); a test asserting the WAL is checkpointed after an ordinary `close()`
(assert on the `-wal` file's size or the pragma result, not on the call); `:modules:app-inference:test`
and `:modules:indexer-worker:test` green.

### B6 — `commit-shutdown` as the front half

`UpgradeController.commitShutdown` (`UpgradeController.java:97-139`) keeps its nonce validation
(`ownsNonce`, `:239-242`), its one-shot claim (`claimCommit`, `:244-248`) and its blocker
reporting (`blocking`, `:222-227` — every active lease drains). Its tail changes: instead of
invoking the coordinator on a daemon thread (`:135-138`), it writes B2's request file with
`reason: "upgrade"` and the nonce. The receipt contract and `head-shutdown-receipt.v1.json`
(`HeadShutdownCoordinator.java:20`) are untouched.

**Acceptance:** the updater state-machine tests (`modules/shell/src-tauri/src/updater.rs:1796`
`mod tests`, and the Java-side upgrade tests) green unchanged; a test asserts a nonce mismatch
writes **no** file; a test asserts the receipt still carries the nonce.

### B7 — the supervisor contract, the fake engine, the conformance harness

Design in §7. Ships with no implementation under test: the harness's own self-test drives the
fake engine and asserts it exits, hangs and answers under each class, so the commit is green and
the contract is fixed before either implementation is written (17.4: "the harness fixes the
contract").

**Acceptance:** `node scripts/supervisor-conformance/run.mjs --self-test` green; the contract's
numeric parameters are read from `governance/supervision-contract.v1.json`, not duplicated
(see §9 and Q3).

### B8 — the dev-runner supervisor

Replace `dev-runner.cjs:2182-2210`'s "child died → write stop report → `process.exit`" with the
state machine: `starting` / `running` / `stopping` / `restarting` / `exhausted`. Classify the
exit (B1), cooldown over handle release, count against the budget, restart, reset the count when
`ready` has held for the stability window. Write `<dataDir>/runtime/supervisor.v1.json` on every
state change. **A supervised restart keeps the run id, the lease and the log files** — 7.6's
dev-runner sentence — so `runId` (`:1543`), `active.json` (`:61`, written `:2000-2026`, renewed
every 10 s at `:2164-2178`) and the four log streams survive the child, and the stop report
becomes a per-incarnation record rather than the runner's last act.

**Acceptance:** the harness (B7) green against the dev-runner adapter for all three exit classes,
the hang path and both requested reasons; `supervisor.v1.json` added to
`ALLOWED_RUNTIME_ARTIFACTS` and **`scripts/dev/dev-runner.cjs` removed from `SKIP_PATHS`**
(`check-runtime-manifest-closure.mjs:65-84`, dev-runner at `:74`) in this commit, with the check
green *because* the writer is checked, not because it is exempt; a test asserts a restart does
not read as `TAKEOVER_ABANDONED` to another session.

### B9 — the death-observability test wired into a runner, with lease and run-id assertions

Two halves, both required (I11):

- **A runner.** Add `scripts/dev/run-dev-runner-tests.mjs` that auto-discovers
  `scripts/dev/test-dev-runner-*.mjs` — the established precedent is
  `scripts/agent-analytics/run-all-tests.mjs`, wired at `.github/workflows/ci.yml:136-137` with a
  comment recording the exact failure mode ("previously ran in CI NOWHERE — manual-only, i.e.
  only when someone remembered"). Point `package.json:17`'s `test:dev-runner` at it and add a
  step to the `public-claims` job (`ci.yml:45`), which is on `pull_request` and `push`
  (`ci.yml:9-12`). `onramp-smoke.yml` is dispatch-only and does not count as CI.
- **The assertions.** `test-dev-runner-death-observability.mjs` gains: the run id in the stop
  report equals the run id in `run.json`; a supervised restart preserves both the run id and the
  lease's `sequence`/`expiresAt` continuity; `engine.log` from incarnation N is preserved before
  incarnation N+1 can truncate it (730 §B1 is the reason the file exists).

**Acceptance:** the runner discovers all eight sibling files and all eight pass; the CI step is
present and green on a PR run; a deliberately broken assertion reds the lane (verify the bite,
then revert).

### B10 — the Tauri supervisor, `supervisor.v1.json` and the `supervisor-state` event

`lib.rs` gains a supervision loop over the existing child handle (`BackendState.child`, `:52`;
`child_pid()` `:202-208`; `wait_for_child_exit` `:178-200`; `kill_child` `:130-174`, which
already tries `POST /api/lifecycle/shutdown` over a raw socket at `:224-259` before
`taskkill`/`kill`). The loop follows the file's existing split: an OS thread for the wait, as
the drain and watch threads do (`:875`, `:924`, `:949`). `restart_headless_backend`
(`:959-974`) becomes the *requested restart* leg rather than the updater's private helper (its
only caller today is `updater.rs:590`). **`justsearch://backend-restart` is untouched** — same
emit site (`:1012`), same instance-id trigger (`:1006-1010`), same `u16` payload, same FE
consumer (`modules/ui-web/src/api/backendRestart.ts:17`). The new
`justsearch://supervisor-state` event carries the state file's contents.

**Acceptance:** the harness (B7) green against the Tauri adapter under `cargo test --lib
--locked` (§7); `modules/shell/src-tauri/src/lib.rs` removed from `SKIP_PATHS`
(`check-runtime-manifest-closure.mjs:81`) with the check green; a test pins `backend-restart`'s
name and payload so a future edit cannot fold the two events together; the FE consumer for
`supervisor-state` lands with `modules/ui-web` typecheck, unit tests and
`node scripts/ci/run-ui-web-gates.mjs` green.

### B11 — the child registry in the runtime manifest (versioned schema bump)

`RuntimeManifest` (`modules/app-api/.../runtime/RuntimeManifest.java:40-106`,
`CURRENT_SCHEMA_VERSION = 1` at `:108`) gains a `children` list: per child, PID, start instant,
executable identity, port-or-pipe, and **config identity** (model path plus an argument hash —
7.2 requires it, and `LlamaServerOps.buildLaunchCommand` (`:343-390`) already assembles every
input it needs). Recorded at spawn, removed at exit. `WorkerInfo.grpcPort` is removed in the same
bump (§0.4, `RuntimeManifest.java:157-163`), which makes this a **breaking** change, not an
additive one.

**Acceptance:** `CURRENT_SCHEMA_VERSION` bumped to 2 and
`RuntimeManifestSchemaCompatibilityTest` extended — the tolerant reader still parses a v1 body
(`:52-80`) and the strict reader still refuses unknown fields (`:321-352`), plus a new case
proving a v2 reader handles a v1 file that has no `children`;
`governance/store-recoverability.v1.json`'s `runtime-manifest-history` entry updated
(`currentVersion: 2`, `readableLegacyVersions: [1]`, `:843-844`) and `check-store-recoverability`
green; `RuntimeManifestPublisherTest` (the declared `futureVersionRefusalTest`, `:846`) green.

**2026-09-08 status:** implemented. The v2 private manifest owns managed-child and shutdown
handoff records, while the public v2 projection omits those fields and the mutation token. The
composition root creates one mutable registry and the publisher remains the only persistent
writer. A synchronous pre-bind seed carries predecessor ownership forward before worker or
inference child-capable bootstrap can start; registration persists before healthy publication
and a failed registration reaps the new process. Runtime contract `0.3.0`, the root/resource
schema copies, generated clients, and recoverability metadata moved together. Exact commands
and limitations are in [the B11-B12 evidence](../evidence/B/b11-b12-managed-child-registry.md).

### B12 — reconciliation on start: adopt or kill by identity

At Engine start, walk the registry. Adopt a child whose **PID plus start instant** match
(`AppInstanceLock`'s existing pattern is the precedent: metadata written at
`AppInstanceLock.java:234-247`, staleness judged at `:152-194` including the PID-reuse check
against `ProcessHandle.startInstant()` with a 1000 ms tolerance), whose health answers, **and
whose recorded config identity matches the applied config version the Engine boots from**.
Anything else listed is killed by PID with identity evidence — never by port. Replaces
`LlamaServerOps.adoptExistingServerIfPresent()` (`:813-847`), which today checks only
`GET /health` 200 plus a `/props` shape (`ServerPropsOps.looksLikeLlamaServerProps`, `:405-412`)
and explicitly drops the handle (`adoptExternalServer`, `:851-874`, `process = null` at `:854`).
Extraction children keep their own parent watch and need no reconciliation
(`PersistentExtractionSandbox.java:404-405` passes `--parent-pid`;
`ExtractionSandboxChild.java:133-159` halts when the parent goes).

**Acceptance:** four tests — identity match + config match → adopted with a handle; identity
match + config mismatch → stopped and respawned from A; identity mismatch → killed; PID reused
by an unrelated process → **not** killed (the `AppInstanceLock` start-instant rule is what makes
this safe, and it is the adverse precondition that must be tested); a test asserts the death path
does **not** kill children (7.2: adoption could never fire otherwise).

**2026-09-08 correction and status:** implemented under the newer section 0 amendments, which
supersede the earlier “identity mismatch → killed” sentence above. Reconciliation acts only when
PID, start instant, and executable identity match. A mismatched or unknown live identity is never
terminated; a confirmed dead or mismatched record may be removed. Managed llama adoption also
requires bounded health/props and the applied declared-config hash. Extraction children are
registered and never adopted. Failed termination retains ownership. Restart/hang supervision
preserves registered children, while terminal cleanup is identity checked. Shutdown deletes the
manifest only after confirmed child death and clean GRACEFUL quit/upgrade completion; other
outcomes retain the handoff.

### B13 — the dead-Engine updater path

Design in §8.

**Acceptance:** `cargo test --lib --locked` green including a new phase-transition case; the
no-receipt witness is a distinct serde name, and a test asserts it is never `HEAD_STOPPED`; the
sandbox exercise is recorded per §8 and §10 row 3.

**2026-09-08 completion:** the branch host-level exercise is implemented, independently
reviewed and green. The real post-staging coordinator stops/reaps an unbound owned Engine,
reconciles a real registered child and retains distinct stop evidence through reconciliation.
Failed normal prepare/commit never uses the dead path; uncertain termination retains the hold.
See [the B13 record](../evidence/B/b13-updater-handoff.md). The signed installer/store-recovery
round is deferred to the first post-merge installer round under design section 0's prior decision,
with its procedure registered as `upgrade-dead-engine-recovery`; that packaged proof is not claimed.

### B14 — `ENGINE_RESTART_EXHAUSTED` gets a real producer

The supervisor writes the terminal state into `supervisor.v1.json`; the Engine, on its next
boot, reads it and **transitions** the capability with the code (a `transition(...)`, not an
equality read — I5). `KnowledgeServerBootstrap.supervisionActive()` (`:460-462`) starts
answering from the same file, which revives `BootRecoveryDecision`'s `SUPERVISION_ENGAGED` veto
(`:194-196`) and `KnowledgeServerHealthMonitor.java:530-534`'s narration arm, and makes
`RESTART_EXHAUSTED` (`:490-500`) reachable. Whether the code is renamed
`WORKER_RESTART_EXHAUSTED` → `ENGINE_RESTART_EXHAUSTED` at B or left to D1's component re-cut is
**Q4**.

**Acceptance:** a test asserts the emission, not the reference — the readiness envelope carries
the code after a simulated exhausted supervisor, and the equality read at
`KnowledgeServerHealthMonitor.java:342-345` now sees a value something wrote;
`node scripts/ci/check-readiness-reason-codes.mjs` green; the `readinessNotice.ts` rows for
`worker.recovering` (`:245-249`) and `worker.restart_exhausted` (`:253-257`) re-worded now that a
supervisor exists again, and the `worker.lost` row (`:273-278`) — whose comment block explicitly
says A11 made its "restart the application" wording load-bearing — restored to a self-healing
wording; `run-ui-web-gates.mjs` green.

**2026-09-08 completion under amended design section 0:** the terminal producer is the native
host's memory snapshot/event projected into recovery UI before API discovery. It is not a stale
supervisor-file verdict imported into a new Engine. Obsolete whole-Worker recovery vetoes and
phantom reasons are retired; local recovery remains enabled. R7 distinguishes HTTP liveness
(including 503) from a continuous API/index-ready stability window. Independent source review,
frontend tests, both real supervisor adapters (11/11 each), readiness gates and the combined
9,405-test Java suite passed. See `b14-local-recovery.md`, `recovery-ui.md` and
`r7-host-liveness.md` under `evidence/B/`; the integrated results are in the B13 record.

### B15 — `restart_required` covers cutover, rollback and start; and who reopens the index

Two obligations 17.3 row B hands B through the restart-required settings of 7.4:

- `MigrationCutoverResponse` (`indexing.proto:1190-1194`) gains `restart_required`, and
  `MigrationControlOps.requestCutover` (`:74-99`) sets it — not as an echo of the request flag
  (which is what start and rollback do today, `:39,62` / `:161,184`) but from the fact that the
  promotion needs the index reopened. Wire change → `--gate wire`.
- **Who reopens the index after a promotion, until D1's live swap:** the Engine answers
  `restart_required`, and the *supervisor's requested-restart path* performs it. This is the
  concrete consumer that makes I2's three refusals useful again, and it is the answer 7.6's
  bounded escalation will later reuse.

**Acceptance:** `node scripts/governance/run.mjs --gate wire` green; a test asserts a cutover
returns `restart_required` and that a requested restart follows and comes back with the promoted
generation active; the `restart_required` HTTP 409 path (`InferenceHandlers.java:635,682`) still
answers for settings that genuinely need it.

### B16 — the residue sweep (`retire-with-a-sweep`)

Every fingerprint the stage's own subject leaves behind, in one commit:

- `KnowledgeServer.isRunning()` / `shutdownLatch` (`:191`, `:2145-2147`, `:2285-2287`): delete,
  or give the supervisor a real consumer. Deleting is the default — nothing in `src/main` calls
  it (I6).
- `worker-config-snapshot.json`: `check-runtime-manifest-closure.mjs:48`, `dev-runner.cjs:1970`,
  `governance/store-corruption-policies.v1.json:45,48`, `HeadlessApp.java:676` (I7).
- `governance/supervision-contract.v1.json:78`'s phantom
  `KnowledgeServerBootstrap.supervisionEngagedOnLastAttempt` (§0.7).
- `SupervisionContractTest` lives at
  `modules/app-services/src/test/java/io/justsearch/app/services/worker/SupervisionContractTest.java`
  — a Worker-flavoured package for a register that no longer has a live Worker row. Re-home with
  the register edit of §9.

**Acceptance:** `WholeProgramDeadCodeTest` green (its baseline shrinks, never grows);
`git grep worker-config-snapshot` returns only labelled history; the full suite green.

**2026-09-08 completion:** removed the unconsumed `isRunning` method, retained the latch with
its actual EngineRoot await consumer, moved the supervision tests and removed the phantom
register method. The full combined suite passed, including WholeProgramDeadCodeTest; no dead-code
baseline grew. [The residue record](../evidence/B/b16-residue.md) records the fingerprint sweep.

### B17 — the supervision assertions are in a suite that runs

- [x] **B17-R1: terminal writer escalation (moved from D1, 2026-09-08).** The narrow
  repair and bounded proof are recorded in design section 0 and
  `evidence/B/writer-recovery-investigation.md`. Its completion does not close B17:
  hostile-lock fixture relocation, both durable replay outcomes, the second real
  supervisor binding and the remaining stage checks stay in this item.

Any test B writes that needs a real kill, a real hang or a real spawn must not silently land
behind `stress` or `load-sensitive` (I9). It must be a unit-level test in the default suite; an
`@Tag("stress")` test registered in `scripts/ci/stress-suite-policy.v1.json` and run explicitly
with `./gradlew.bat test -PincludeStress=true`; or an installed-process `integrationTest` invoked
explicitly. Terminal-writer recovery uses the last tier:
`./gradlew.bat :modules:system-tests:integrationTest --tests
io.justsearch.systemtests.supervision.TerminalWriterSupervisedRecoveryE2ETest
-PskipWebBuild=true --console=plain`.

**Acceptance:** `node scripts/ci/verify-stress-suite-policy.mjs` green; §10's "what a green suite
covers" paragraph updated for stage B with the exact command behind every claim.

---

## 4. Commit plan (one commit per item, 17.6)

`B1 … B17` in the order above, each `feat(936):` / `refactor(936):` / `chore(936):` with the item
letter in the subject, matching stage A's convention (`git log`: `feat(936): A18 — …`). Two
commits carry a mapping table in the body rather than a line-by-line review: **B11** (the schema
bump touches the record, the publisher, two governance registers and the compatibility test) and
**B16** (the sweep). No commit is expected to be large; if one is, that is a signal to re-read
§11 before continuing.

The branch is asserted green after **every** item. A commit that cannot be green on its own is a
sign the item was cut wrong — split it, do not open a red window (§10).

---

## 5. Section 6 contract changes this stage makes, and the gate for each

| change | gate | item |
|---|---|---|
| `supervisor.v1.json` beside the manifest | closure check's `ALLOWED_RUNTIME_ARTIFACTS` **plus** `dev-runner.cjs` (`:74`) and `lib.rs` (`:81`) removed from `SKIP_PATHS` so the writers are checked rather than exempt; `check-store-recoverability` | B8, B10 |
| `justsearch://supervisor-state`, a new Tauri event | shell event tests (`lib.rs:1580` `mod tests`); the ui-web gate set for the consumer | B10 |
| `justsearch://backend-restart` **untouched** | a pin test on the emit site (`lib.rs:1012`), the payload and the FE subscriber (`backendRestart.ts:17`) — asserted, not changed | B10 |
| runtime manifest gains a child registry; `WorkerInfo.grpcPort` removed | `RuntimeManifestSchemaCompatibilityTest` (versioned schema); `store-recoverability.v1.json`'s `currentVersion`/`readableLegacyVersions` + `check-store-recoverability`. The closure check does **not** read fields, so it is not the gate here | B11 |
| `/api/lifecycle/shutdown` kept as the cooperative trigger; `commit-shutdown` becomes its nonced front half | updater state-machine tests (`updater.rs:1796`) + the Java upgrade-barrier tests | B4, B6 |
| a shutdown request file as the second trigger | its own schema test; the closure check's allowlist | B2 |
| `MigrationCutoverResponse.restart_required` | `--gate wire` | B15 |
| a readiness reason code gains a producer (rename deferred or made — Q4) | `check-readiness-reason-codes` **plus** an emission test, because the gate's own predicate cannot tell a reference from an emission (`check-readiness-reason-codes.mjs:169-173`) | B14 |
| `core.restart-worker` still answers `restart_required` | `operation-surface` — **unchanged at B**; D1 retires the operation | — |
| readiness codes re-cut **by component** | `check-readiness-reason-codes` + a component-key test | **not stage B** — D1 |
| admission rejections / `cursor expired` / the operation outcome contract | contract diff | **not stage B** — C1, C2, D2 |

---

## 6. Section 16 rows stage B must leave exercisable

Stage B produces no measurement (E does). Four rows are B's to *make* exercisable, and it must
not make any other row unmeasurable.

- **recovery, process** — B's headline row. After B: crash-to-API-restored is measurable (first
  cooldown plus warm start); `restarting` is visible in `supervisor.v1.json`; no orphaned child
  after a restart; a healthy llama-server adopted rather than reloaded on the crash and `restart`
  paths, and stopped on `quit` and `upgrade`. The durable-operation half of the row ("resumes
  from its last checkpoint") is **C2's** and stays unmeasurable until then — say so in the
  evidence record rather than reporting the row as passed.
- **hang, graceful** — a wedge that leaves the watcher thread runnable is recovered through the
  request channel. B3's watcher on its own executor is the mechanism; the *parameters* are E's
  placeholders, so B must show the path fires, not that it fires within a tuned budget.
- **hang, forced** — a whole-JVM wedge ignores the file and is recovered by the forced kill.
  Separate from the row above so the kill branch is exercised; B must leave both reachable, and
  the fake engine (§7) has a mode for each.
- **dead-Engine upgrade** — a sandbox round applies a release over an Engine that cannot boot,
  with the registered children reconciled first. B builds the branch (§8); the round itself is
  constrained — see §10 row 3 and Q1.
- **stuck component, the escalation half** — 7.6's escalation calls the supervisor's
  requested-restart path and counts against the same budget, so B must leave that path *callable
  from inside the Engine* (B15's consumer is the first caller) and must not build the requested
  restart as an external-only trigger. Deadlines, local recovery and the attempt budget are D1's.
- **memory** — B adds `ExitOnOutOfMemoryError` (B1), which turns an OOM into a supervised restart
  instead of a zombie answering health. The soak that checks it is E's.
- **recovery, workflow** — B restores "API is restored" and "children never leak" (7.6's
  guarantee list). The client-re-bootstrap half needs C2's outcome query; do not claim it.

---

## 7. The conformance harness

**Where it lives.** `scripts/supervisor-conformance/`, a Node harness. Node, not Rust or Java,
because it is the only runtime both implementations can be driven from without embedding one in
the other, and because the dev-runner half is already Node.

**Shape.** Three parts.

1. **`fake-engine.mjs`** — a Node script the supervisor spawns instead of the real Engine. Modes:
   `clean-exit <code>`, `crash`, `oom` (exits with `ExitOnOutOfMemoryError`'s code),
   `boot-fail <non-transient-code>` (port-in-use, bad config, store-schema), `hang-soft` (stops
   answering `/api/health`, still reads the request file), `hang-hard` (stops answering *and*
   ignores the file — the forced-kill branch), `honour`, `slow-start <ms>` (exceeds the start
   deadline). It publishes a real `manifest.json` so `watch_manifest` and the dev-runner's
   readiness probe work unmodified.
2. **`contract.mjs`** — the cases, one per row of §2 and per state edge of 7.1: each of the three
   exit classes; `requested` not counted; non-transient exhausting at once; the cooldown waiting
   for handle release; the stability window measured from `ready`, not spawn; hang-soft recovered
   through the request file; hang-hard recovered by forced kill; `exhausted` killing registered
   children and no other path doing so.
3. **`adapters/`** — one per implementation. `dev-runner.mjs` drives `dev-runner.cjs` with
   `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` pointed at a temp dir (the override already exists,
   `dev-runner.cjs:57-59`) and the fake engine substituted for the start script. `tauri.mjs`
   drives a small Rust binary (below).

**How the Tauri half is tested without a Tauri window.** The CI lane is
`.github/workflows/ci.yml:1150-1176`: `cargo test --lib --locked` in `modules/shell/src-tauri`,
`windows-latest`, no features, no display — and there is **no** `modules/shell/src-tauri/tests/`
directory, so `--lib` means `#[cfg(test)] mod tests` only (`lib.rs:1580`, `updater.rs:1796`).
That constrains the design, and the constraint is the design: the supervisor is extracted into
`src/supervisor.rs` as a **pure decision function** plus a thin actuator trait —
`decide(observation, policy) -> Action` — for which 627's `SupervisionDecision` is the precedent
and the register's `decisionSeam` field (`governance/supervision-contract.v1.json:22`) the
declared slot. The **decision half** is table-driven `#[cfg(test)]` tests in `supervisor.rs`,
running in the existing lane with no workflow change, over the same case list `contract.mjs`
reads from the register so the two cannot drift. The **actuator half** needs a real child, so it
ships as a `[[bin]]` target, `supervisor-conformance`, which `adapters/tauri.mjs` runs — and
which `cargo test --lib` does **not** cover. See **Q2**: the recommendation is one extra step in
the existing lane, because a contract with one untested implementation is not "one contract".

**"One contract" is true only while both pass it.** The harness's exit code is the checkpoint
proof for that clause; a case skipped for one adapter fails the harness rather than being
reported as N/A.

---

## 8. The dead-Engine updater path

**The branch to add.** In `run_install_now` (`modules/shell/src-tauri/src/updater.rs:369-630`),
before the first Head RPC. Today three preconditions each require a living backend and each is a
hard error:

- `post_head` requires both a port and a session token (`:1132-1137`), so `prepare_head`
  (`:965-967`), `cancel_head` (`:969-984`) and `commit_head` (`:986-1001`) all fail.
- `run_install_now` requires the child-process witness `backend.child_pid()` (`:488-505`).
- `wait_for_backend_ready` re-asserts the same triad after the restart (`:1056-1072`).

The new branch fires when **the supervisor reports `exhausted`, or no Engine has bound a port**.
It reads `supervisor.v1.json` (B8/B10) rather than inferring from `has_spawn_error()`
(`lib.rs:123-128`), because "no port yet" and "gave up" are different states and only the file
distinguishes them. In that branch:

1. Skip `prepare` and `commit` entirely — there is nothing to freeze.
2. **Reconcile the manifest's child registry** (B11) so nothing holds the llama-server binary or
   VRAM while the installer overwrites it. This is the Rust half of B12's rule: kill by PID with
   identity evidence, never by port. It is the reason 7.3 puts reconciliation *before* the launch.
3. Record a **distinct no-receipt witness** — a new `UpgradePhase` variant (the enum is at
   `:90-99`; its serde names are pinned by `durable_phase_names_are_stable`, `:1802-1817`) and a
   new legal transition in `transition`'s table (`:1563-1586`). It must never be `HeadStopped`:
   forging `HEAD_STOPPED` would make a later reconciliation believe a receipt existed. Proposed
   name `EngineUnrecoverable` → `"ENGINE_UNRECOVERABLE"`, with `(EngineUnrecoverable,
   InstallLaunching)` added and nothing else.
4. `launch_installer` (`:586`, Windows impl `:1343-1395`) unchanged, over the same staged,
   `MZ`-verified artifact (`:437-443`).
5. On restart, reconciliation must handle the new phase: `:708` currently gates on
   `RepairRequired | Cancelled`; the new phase joins the set that reconciles rather than repairs.

The Settings action that reaches this is already shell-owned and reachable with the backend
down, so no FE work is needed beyond `supervisor-state` (B10).

**How a sandbox round exercises it — and the constraint.** There is no `docs/how-to/*sandbox*`;
the round is documented in `docs/how-to/cut-a-release.md:50-133` and `scripts/sandbox/*.md`.
Entry: `python scripts/sandbox/sandbox-launch.py --installer <candidate.exe> --upgrade-from
<previous.exe> …` (args at `sandbox-launch.py:1830-1929`), then
`scripts/sandbox/start-in-app-update-test.ps1 -Autorun`, which needs the *source* build compiled
with `JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE=1` and the Tauri updater's
`dangerousInsecureTransportProtocol=true`. Two supported modes exist, `fresh-install` and
`upgrade-from-release`; **there is no broken-install mode** — no `--repair`, no corrupt-install
flag anywhere in `sandbox-launch.py`'s argparse. So the round needs a new step: after installing
the previous release, damage its Engine so it cannot boot non-transiently (the cheapest honest
damage is a config the resolver rejects, which exercises the *non-transient* class rather than a
corrupted jar, which exercises the classifier's fail-open), let the supervisor reach `exhausted`,
then drive the update.

**And the constraint that may prevent it on the branch:** `build-installer.yml:57` declares
`environment: release-signing`, whose deployment-branch protection refuses every ref but `main`
(`stages/A.md` §10 row 5 records the same wall blocking A13's packaging verification), and a
local build is blocked by Smart App Control (`CLAUDE.md`, Common Pitfalls). The candidate
installer the round needs cannot be produced from the branch by the mechanism the release uses.
**Q1** puts the options to the owner; do not quietly downgrade the checkpoint proof.

---

## 9. Governance changes

| register / check | what stage B does |
|---|---|
| `governance/supervision-contract.v1.json` | **Add an `engine` process row.** The Worker row stays `status: "retired"` (`:87-90`; `SupervisionContractTest.workerEntryIsRetired()` pins it and `retiredNote` says B *replaces* rather than un-retires it). The new row declares `authority`, `decisionSeam`, a `policy` block carrying §2's numbers, and a `faultModes[]` entry for each of `clean-exit, hang, oom, zombie, partial-boot, external-adoption` (`:9-16`) with guards resolving to real tests or one of the two sentinels (`:5-8`). `SupervisionContractTest.everyLiveProcessHasADriftCheck` currently pins the live set to `{brain}` — widen it and add an `enginePolicyMatchesCode()` drift check. See **Q3** for what the policy record can be, given the implementations are Rust and JS. |
| `check-runtime-manifest-closure` | `supervisor.v1.json` and `shutdown-request.v1.json` added to `ALLOWED_RUNTIME_ARTIFACTS` (`:42-51`) with justification comments; `scripts/dev/dev-runner.cjs` (`:74`) and `modules/shell/src-tauri/src/lib.rs` (`:81`) **removed from `SKIP_PATHS`** (`:65-84`), which is the whole point of §6's row — the check passing while both writers are exempt is a vacuous green. |
| `governance/store-recoverability.v1.json` | `runtime-manifest-history`: `currentVersion` 1 → 2, `readableLegacyVersions: [1]` (`:843-844`). Two new entries for the supervisor state file and the shutdown request file (`root: DATA_DIR`, `recoverability: EPHEMERAL`, `upgradeHandling: RESET`, `owner: SHELL`; two `SHELL`-owned entries already exist). Gate: `check-store-recoverability`. |
| `governance/readiness-reason-codes.v1.json` | Only if Q4 renames the code. **No new allow-list**: the register's `producerDirectionNote` says the effective exemption is empty and a new entry "is a design conversation, not a one-line entry". B does not need one because B adds the producer. |
| `governance/sandbox-coverage.v1.json` | A `mustWatch` entry for the dead-Engine upgrade, in the shape of the existing `upgrade-index-survives` / `upgrade-user-data-survives` rows (`:70-71`, `modes: ["upgrade-from-release"]`), stating the procedure and that it is not CI-gateable. Gate: `scripts/sandbox/gen_coverage_brief.py` fails closed on an uncovered derived surface, and `test_sandbox_coverage_mode_filter.py` covers the mode filter. |
| `governance/adr-probes.v1.json` | See the ADR decision below. |
| `governance/store-corruption-policies.v1.json` | `worker-config-snapshot` entry (`:45,48`) swept with B16. |
| `regen-all --check` | Run after B11 (manifest shape) and B15 (proto) — the OpenAPI/route snapshots and the wire artifacts are in the regen set. |
| `run-ui-web-gates.mjs` | Required by B10 and B14 (`modules/ui-web/src` is touched: `backendRestart.ts`'s sibling for `supervisor-state`, `readinessNotice.ts`'s rows). |

**ADR probe: does ADR-0049 need a lifecycle amendment, or does the supervisor contract warrant a
new ADR? — Neither. Amend nothing, add nothing.**

ADR-0049 already carries the supervisor as a *stated consequence and a named future*, not as an
open question: "Supervision — crash detection, restart budget, cooldown — has no producer at the
end of stage A and is stage B's work" (`docs/decisions/0049-…md:102-103`), and its first
reassessment trigger says "The answer is stage B's supervisor, not a re-split — unless the
supervisor proves insufficient, which would reopen this ADR" (`:147-149`). Stage B *discharges*
that consequence; discharging a consequence an ADR already predicted is not a decision, and
amending the ADR to say "the thing it said would happen happened" adds prose without adding a
constraint. The three probes ADR-0049 declares
(`adr-0049-lucene-owners-pinned`, `adr-0049-engine-port-boundary`, `adr-0049-no-grpc-in-module-builds`)
are untouched by B and stay green.

A **new** ADR is also not warranted, for a sharper reason: the supervisor contract already has a
governance home with a gate — `governance/supervision-contract.v1.json` plus
`SupervisionContractTest`, built for exactly this by 627. An ADR whose load-bearing premise is
already a register row with a drift test would be a second authority over one fact, which is the
representation-drift class `CLAUDE.md` asks to check for before authoring a new representation.
Adding the `engine` row is the projection; a new ADR would be the fork.

**Two conditions would change this answer, and B must watch for both.** (a) If the owner accepts
a *reduced* recovery promise for any launch shape — say the bare `app-launcher` run stays
unsupervised in a way that changes what the product promises — that is a decision, not a
mechanism, and it belongs in an ADR or in 3.1's supported-shapes row. (b) If B's implementation
cannot satisfy 7.1's "never" clause (the supervisor is not a JVM and not on the Engine's
classpath), the boundary itself has moved and ADR-0049's decision rule is in play. Neither is
expected. Record the judgement in the stage-B evidence directory either way, so F's sweep can
see that the question was asked.

---

## 10. What is allowed to be red after stage B, and nothing else

**Integrated checkpoint, 2026-09-08:** `./gradlew.bat test --no-build-cache --rerun-tasks`
passed 9,405 tests (25 skips), zero failures/errors, across 34 modules; the 1,525 exact `test`
XML files and hash inventory were preserved before any filtered rerun. This excludes stress and
installed-process integration tiers. `cargo test --lib --locked` passed 76; build the conformance
binary with `cargo build --bin supervisor-conformance --locked` before running
`node scripts/supervisor-conformance/run.mjs --adapter tauri`. Both that adapter and
`--adapter dev-runner` passed 11/11. Commands, hashes and the signed-installer deferral are in
[the integrated B13 record](../evidence/B/b13-updater-handoff.md). B15 and B17's hostile-lock,
PROCESSING replay and remaining production shutdown proofs remain acceptance obligations.


17.3's "branch state after" for B is: *the three restart paths work through requested restart; a
crash comes back under the budget; children are adopted or killed.* That leaves very little
room, and B is additive, so the list is short.

**Allowed red / deliberately unfinished until a later stage:**

1. **The hang parameters are placeholders, so the hang rows are exercisable but not tuned.**
   7.1 and 17.7 both fix this at E ("hang interval and count set with the collector"). A test may
   assert the hang path *fires*; no test may assert it fires within a production-tuned budget, and
   the harness's hang cases use an explicit test-only interval. **This is not licence for a red
   test** — a placeholder that reds is a defect.
2. **Shutdown step 3 checkpoints the job queue, not an operations table.** C2 owns the operations
   table (7.5). B's step 3 is "stop taking new work and checkpoint what exists". The 16
   `recovery, process` row's durable-operation clause is therefore **unmeasurable, not failing**;
   the evidence record must say unmeasurable, not passed. See §11.
3. **Shutdown step 2 cannot cancel interactive turns yet.** C1 owns the interactive context and
   admission front that can identify and cancel those turns with a reason code. B freezes all new
   mutation admission first for every shutdown reason; the cancellation half remains unmeasurable
   until that C1 contract exists.
4. **The dead-Engine sandbox round may be unrunnable on the branch** — `build-installer.yml:57`'s
   `environment: release-signing` refuses every ref but `main`, and a local build is blocked by
   Smart App Control. This is the same wall that left A13's packaging steps "changed, locally
   reasoned, never executed" (`stages/A.md` §10 row 5). It is an **unrunnable check, not a red**,
   and it is a checkpoint proof, so it needs the owner's word (Q1), not an agent's judgement.
5. **`core.restart-worker` still answers `restart_required`** rather than restarting anything
   (`RestartWorkerHandler.java:61,74`). D1 retires the operation; B only makes the *requested
   restart* that answers it real (B15).
6. **Readiness is still Worker-shaped.** `LifecycleReasonCode`'s `WORKER_*` vocabulary
   (`:33,40,44,50`) and `readinessNotice.ts`'s rows survive B with corrected wording; the
   component re-cut is D1's. If Q4 says rename, one code moves and the rest wait.

**Not allowed red (a defect of the stage, not a deliberate loss):**

- the full unit suite, `spotlessCheck`, `pmdAll`, `./gradlew.bat build -x test` — after **every**
  item, not only at B17;
- `WholeProgramDeadCodeTest` (B16 shrinks its baseline; a growth is a defect);
- every gate in §9's table, including `check-runtime-manifest-closure` *with the two writers no
  longer skipped*;
- `cargo test --lib --locked` in `modules/shell/src-tauri` (`ci.yml:1174-1176`);
- `modules/ui-web` typecheck, unit tests and `run-ui-web-gates.mjs` (B10, B14 touch it);
- search, ingestion, the dev stack, `jseval run --start-backend` — B changes the dev-runner's
  spawn and exit paths, so this is the row most likely to break silently;
- the conformance harness, on **both** adapters (§7).
- the installed-process terminal-writer regression named in B17. A default `test` or full-unit
  claim does not include `integrationTest`; CI runs that tier in the advisory Windows
  `integration-tests` job and uploads its owned diagnostic directory.

---

## 11. Stop rule (17.8)

Five triggers. Two fire.

**1. "Development resumes on `main` while the branch is open." — FIRES. Owner's call.**
The handoff's rule 3 ("All other development is halted while the branch is open") no longer
describes reality. `git rev-list --count <merge-base>..origin/main` = **11** commits, 81 files,
covering tempdocs 941, 948, 949, 859, 935 and a WinGet retirement. 17.8's remedy is explicit:
"Either the resumed work waits, or the sequencing reverts to the incremental shape from the next
checkpoint, which reopens 15's flag decision. The owner's call, made then."

**The orchestrator's read, offered but not substituted for the owner's:** the conflict surface is
nil. `git diff --name-only <merge-base>..origin/main` filtered for every path stage B touches
(`src-tauri`, `dev-runner`, `updater`, shutdown, manifest, llama-server, supervision, readiness)
returns only WinGet package manifests and `scripts/release/winget-manifests.*` — nothing in the
lane's blast radius. So the coherence cost 17.1 priced has not actually been incurred yet.
Recommendation: proceed with a `git merge origin/main` at stage start (never a rebase —
`agent-lessons.md`), and re-run this count at every stage boundary, since the cost is cumulative
and the next batch may not be so lucky.

**2. "A stage needs a mechanism the table places later." — FIRES. Remedy applied, order unchanged.**
17.8 names this exact case: "for instance the shutdown's step 3 needing the operations table
before C2". It does. The prescribed remedy is "the item moves earlier with a note in section 0;
the checkpoint order itself does not change" — and here even the item does not need to move,
because the buildable half (stop taking new work; checkpoint the job queue, B5) is enough for the
sequence to be ordered and correct, and the unbuildable half is a *promise about resumption* that
16's row cannot measure until C2 anyway. Recorded in §0 and §10 row 2. **No re-cut.**

**Triggers 3, 4 and 5 do not fire.** No release is being cut (though trigger 1's WinGet
retirement and 0.3.0 activity sit adjacent to release work; if one *is* cut, 17.1's "no hotfix
path" cost is paid, not avoided); trigger 4 is stage-A-scoped and A's own §11 answered it
(verdict: proceed); no gate row has been run yet, since E runs them.

**Verdict: proceed, with two items on the owner's desk before B starts** — trigger 1's halt
question, and Q1's checkpoint-proof question (§8, §10 row 3). Neither is a stop; both are
decisions 17.6 reserves to the owner ("a change to a decision, a gate row or the stage table
waits for the owner's word").

---

## 12. Open questions the implementer must resolve on first contact

Do not stop for these — the orchestrator decides at brief time. Each has candidates and a
recommendation.

| # | question and evidence | candidates | recommendation |
|---|---|---|---|
| **Q1** (largest; owner's) | **How is B's checkpoint proof "the dead-Engine path exercised in a sandbox round" produced on the branch?** The round needs a candidate installer; `build-installer.yml:57` declares `environment: release-signing`, refused for every ref but `main` (`stages/A.md` §10 row 5 records the same wall), and a local build is blocked by Smart App Control (`CLAUDE.md` pitfalls). `sandbox-launch.py` also has no broken-install mode (§8). | (a) the owner relaxes the environment's deployment-branch policy for the lane branch; (b) the branch proof becomes a **host-level** exercise — the fake-engine harness drives `run_install_now`'s new branch with a staged no-op `.exe` as the installer, asserting reconciliation, the witness phase and the launch call, and the sandbox round is deferred to the first post-merge installer; (c) skip the proof | **(b) as the branch proof, with (a) requested and the deferral recorded as a named, dated gap.** (b) exercises everything the branch owns — the classifier, the reconciliation, the phase table — and stops exactly at the thing only a real installer can show. (c) is the predictable evasion: a checkpoint proof quietly downgraded to "reasoned about". 17.6 makes a change to the proof the owner's word, so this is put, not decided. |
| **Q2** | **Does the Tauri actuator half run in CI, or only the decision half?** `ci.yml:1174-1176` is `cargo test --lib --locked`; there is no `tests/` dir, so a `[[bin]]` conformance driver is invisible to that lane (§7). | (a) add one step to `shell-rust-tests` building and running the conformance bin against the fake engine; (b) `--lib` only, actuator run locally and recorded in the evidence dir; (c) move the whole harness into `#[cfg(test)]` with an injected process spawner and no real child | **(a).** "One contract, two implementations" is false if one implementation's actuator is never run; and 745's lesson, quoted in `ci.yml:132-135`, is that a layer nothing invokes is dead regardless of quality. (c) is attractive and wrong: a fake spawner cannot show the handle-release cooldown, which is the one Windows-specific thing §2 re-cut. |
| **Q3** | **Where does the Engine supervisor's policy record live, so `SupervisionContractTest`'s drift check can assert it?** That test asserts a register `policy` block equals a *live Java record's* defaults (`brainPolicyMatchesCode()`), but B's implementations are Rust and JS. | (a) `governance/supervision-contract.v1.json` is the single authority; Rust and JS read it (JS directly, Rust via `include_str!` + serde at build or test time) and a small Java `EngineSupervisionPolicy` mirrors it purely so the drift check has a subject; (b) a Java record is the authority and the two implementations are pinned to it by the harness only; (c) drop assertion (1) for the engine row | **(a).** The register is already the declared authority and the harness already needs to read the numbers (§7); (b) makes a Java record the source of truth for a component that is deliberately *not* a JVM (7.1's "never"); (c) silently deletes the only drift protection the row has. Predictable evasion to name in review: copying the five numbers into three files and calling them "the same contract". |
| **Q4** | **Does `WORKER_RESTART_EXHAUSTED` get renamed to `ENGINE_RESTART_EXHAUSTED` at B, or wait for D1's component re-cut?** 7.1 names the terminal code `ENGINE_RESTART_EXHAUSTED`; the enum member is `WORKER_RESTART_EXHAUSTED` (`LifecycleReasonCode.java:33`) with an FE row (`readinessNotice.ts:253-257`) and a terminal-code list (`:525-534`). | (a) rename at B, since B is the first commit that gives the code a producer and a rename with no live producer is free; (b) keep the name, re-cut at D1 with the rest of the vocabulary; (c) add the new code beside the old | **(a).** The rename is cheapest exactly when B lands the producer, and a code named for a process that no longer exists is the false-authority residue `retire-with-a-sweep` is about. (c) is the worst option — two codes for one state is the fork. Costs one `readinessNotice.ts` row and pulls `run-ui-web-gates.mjs` into B, which B10 already pulls in. |
| **Q5** | **Does the supervisor state file live under `runtime/` at all, given the Engine is down when it matters?** `<dataDir>/runtime/` is currently classified `EPHEMERAL` / `RESET` on upgrade (`store-recoverability.v1.json:840-841`), and an `exhausted` state read by the *updater* must survive the moment the Engine dies. | (a) `runtime/supervisor.v1.json`, as 7.1 says, accepting `EPHEMERAL`; (b) a sibling outside `runtime/` with its own recoverability class; (c) `runtime/`, but with the terminal state also mirrored into the manifest history (`RuntimeManifestPublisher.java:735-736` already keeps an append-only mirror) | **(a) with (c)'s mirror.** 7.1 fixes the location and the closure check's narrowing (§6) is written against it; `EPHEMERAL` is honest for live state. The mirror is what makes "the supervisor exhausted three boots ago" answerable, which the dead-Engine path (§8) needs and live state cannot give. |
| **Q6** | **Does the extraction child pool go in the child registry, or stay out?** 7.2 says the registry holds "llama-server … and the extraction pool", but the pool already self-terminates on parent death (`ExtractionSandboxChild.java:133-159`, halt at `:142-144`, 500 ms poll at `:42`), so reconciliation would rarely find one. | (a) register both kinds, reconcile both; (b) register llama-server only, and document the pool's parent-watch as the reason; (c) register both but never adopt an extraction child — kill on sight | **(c).** Registering it costs nothing and makes the terminal path's "kill everything the manifest lists" complete; adopting one is meaningless (a child is bound to one parent's pipes). (b) leaves a real class of orphan — a child whose watchdog thread died — invisible. Assert the 500 ms window in the reconciliation test so the race is exercised, not assumed. |
| **Q7** | **Where does `supervisionActive()` read from, and does the supervisor become a hard dependency of boot?** B14 makes it answer from `supervisor.v1.json`, but an unsupervised shape (bare `app-launcher`, 3.1) has no such file. | (a) absent file ⇒ `false`, i.e. unsupervised, which is exactly 3.1's "explicitly unsupervised mode"; (b) absent file ⇒ fail boot; (c) a launch flag declares the shape | **(a).** It matches 3.1's scoping sentence ("Every general statement in 7.6 is scoped to the supervised shapes") and keeps the CLI and `app-launcher` runs working. Test the adverse precondition: a boot with no supervisor file must reach `ready` and must **not** report a supervision verdict. |

---

*End of the stage-B checklist. Re-verify §0 and every citation at stage start (17.6); a fact that
moved is corrected in `verified-facts.md` before this file is relied on.*
