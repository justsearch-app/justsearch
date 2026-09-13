# Closed-writer recovery investigation (2026-09-08)

Initial read-only investigation at `d8cb63f8f`, independently traced and re-read
by the orchestrator. The original findings below extend the preserved failure in
[integrated-verification.md](integrated-verification.md). The dated implementation
record at the end supersedes the missing-connection finding; it does not erase
the original stress red.

## Detection and replay

The failing commit is classified in `CommitOps.java`:100-113. `IndexingLoop.java`:
708-726 catches the failure and continues using the same runtime. The current
health probe can still see a readable searcher and an accessible queue; it does
not establish that the writer remains usable. Detection must inspect the existing
runtime's writer state. A generic `LOCKED` exception alone is insufficient grounds
to terminate the Engine: a recoverable lock error and an irrecoverably closed
writer are different conditions.

Successfully written documents do not become DONE before commit.
`JobBatchWriter.java`:132-163 writes and enqueues a transition in memory;
`IngestionOutcomeJournal.java`:86-93 and `IndexingLoop.java`:708-724 place DONE
after successful commit. A failed commit leaves those durable queue rows
PROCESSING. `KnowledgeServer.java`:567 invokes the startup reset implemented by
`SqliteJobQueue.java`:1260-1277.
Failed writes attempt a durable PENDING/backoff transition separately. If that
queue transaction fails, `IngestionOutcomeJournal.java`:217-238 retains PROCESSING
for startup recovery. Both states need a replay proof; this trace does not
substitute for running it.

## Recovery alternatives

`KnowledgeServer.swapRuntime()` is an existing component-reopen seam, but closes
the old runtime before opening its replacement (`KnowledgeServer.java`:1261-1283).
Failed open therefore leaves closed resources in the holder, despite the stronger
comment at :1252-1254. Service reconstruction starts the new loop before closing
the old service (:1229-1241). It also does not perform startup's immediate reset of
PROCESSING jobs. Reusing this path needs failed-open, admission, loop ownership
and replay guarantees; it is not a small call-site fix.

Whole-Engine fault escalation is the narrower direction to prove first. Existing
exit code 1 is a budgeted transient fault (`EngineExit.java`:43-58, :86-91), and
the default uncaught handler already exits with it (`HeadlessApp.java`:891-902).
Both supervisors already observe process exit and restart under their budget
(`lib.rs`:1091-1093; `supervisor.rs`:610-652, :807-832;
`dev-runner.cjs`:2719-2758, :2793-2815).
The missing connection is internal writer-failure detection to the Engine's fault
owner; `EngineRoot` currently supplies start and close, not a fatal callback.
This trades a full application outage for reusing established restart and startup
replay. Unsupervised launches would exit and remain stopped, as their shape implies.

## Proof still required

Prove one escalation for a genuinely unusable writer, with no escalation for an
ordinary recoverable lock or intentional close. Exercise the real process exit,
ordered shutdown hook, both supervisor bindings and crash-budget accounting.
After restart, demonstrate replay of accepted-but-uncommitted jobs and both
failed-write queue outcomes. A manually reopened test harness proves only reopening/replay, not automatic
production recovery. Preserve the file-contention assertion and its original red.

Do not import D1's live replacement merely to make this test pass. If whole-Engine
recovery cannot meet the lane's existing property, apply section 17.8 before
changing the stage cut. No new cross-process artifact or general fault framework
is approved by this investigation.

The stress test's historical scheduler explanation (`EngineFileLockContentionTest.java`:
57-70) is now qualified as a hypothesis about the earlier runs, with the proven
closed-writer incident distinguished explicitly. This comment-only correction
changes neither the tag nor the assertion and does not establish recovery.

## Implemented ordered fault path (2026-09-08)

Candidate above `6f38df7e5`, with independent read-only review. `RuntimeSession`
observes the existing writer after mutation/commit locks are released. Tragic or
closed writers notify once; ordinary IO with a usable writer does not. Runtime
retirement wins against a close-induced notification, including a late NRT
handler after close has cleared the snapshot. The initial and resumed CRTRT
handlers route only known `AlreadyClosedException` failures; unrelated failures
retain their previous handler. Listener RuntimeExceptions are diagnostic;
Errors propagate rather than hiding a failed fault-thread start.
Before the listener is attached, a known terminal writer is handled without
claiming its notification; listener installation rechecks it. The initial CRTRT
already runs during RuntimeSession construction, so binding only at subsequent
KnowledgeServer publication did not by itself close this interval.

`KnowledgeServer.publishIngestLifecycle` binds each writable runtime before it
becomes visible, including before boot's switch-buffer replay. `EngineRoot`
accepts the current server identity once and dispatches a dedicated non-daemon
thread. Headless binds that action to its complete ordered shutdown sequence
through a future created before asynchronous boot. A fault before binding waits;
an independent main-thread boot failure retains the existing startup fallback.
The sequence uses RESTART resource policy, retaining the Brain for adoption, and
selects fatal exit 1. An admitted fatal request upgrades a concurrent cooperative
exit before final selection; it cannot revise an exit already selected or write
a clean upgrade receipt for the fatal outcome.

The smaller alternative, calling `System.exit` immediately, failed a real run:
`writer-recovery-live-probe-3.txt`, run
`82ad0355-2ce6-40af-bf53-a3a7bb82ed1c`. The NRT uncaught handler entered
`System.exit` while the Engine JVM hook joined that same NRT thread, producing a
circular wait and a host hang kill. On the successor's normal stop, another NRT
failure entered JVM shutdown while `lifecycle-orderly-shutdown` was closing ORT
sessions; `hs_err_pid32900.log` records an access violation in
`OrtSession.closeSession`. The installed ORT 1.24.3 bytecode registers its own
`OrtEnvCloser` hook. This was the same shutdown-ordering failure class, not an
unrelated model issue.

The Engine therefore completes its own ordered close before entering JVM exit.
KnowledgeServer also awaits the existing deferred-model initializer's actual
completion. Abandoning it after five seconds allowed native initialization to
continue during native teardown. A stuck initializer can keep an unsupervised
close pending; the external supervisor's existing hang/force path owns death
after the API stops. No new watchdog, runtime swap or cross-process artifact was
added. A terminal writer does not earn a clean Lucene shutdown marker merely
because its subsequent `close()` returns normally.

## Verification of the repair (2026-09-08)

The full default suite passed with
`./gradlew.bat test --no-build-cache --no-parallel -PtestParallelism=1 --console=plain`:
**9,379 cases, 0 failures, 0 errors, 25 skipped**, in 17m43s. All 1,521 XML files
across 34 modules were copied into `writer-root-full-snapshot` before any later
filtered test run; `summary.json` contains module totals and `files.json` contains
every XML hash. Gradle reported 194 tasks, 37 executed and 157 up-to-date. This
inventory excludes stress and the separate installed-process integration tier.
This full run preceded the final listener-not-yet-bound NRT branch correction.
That correction is covered by the failing-before/passing-after regression, the
entire affected adapters-lucene module and a fresh installed-process run. Do not
describe the 17m43s result as a second full suite after that last correction.

`./gradlew.bat spotlessCheck pmdAll build -x test --no-parallel --console=plain`
passed in 57s (325 tasks). The full unit result above, not this `-x test` build,
is the unit verification. The final integration wrapper's cleanup corrections
receive a separate compile, PMD and installed-process rerun below. ESLint,
markdownlint, docs validation, generated-doc/skill checks, canonical links and
stress-policy validation also pass.

All raw paths in this section are relative to `tmp/lane-f-takeover/`. These runs
used the dirty candidate above `6f38df7e5`; the distribution's git label identifies
that base, not a later commit. The distribution content stamp appears in the
fixture log. Focused runs do not constitute a full-suite result.

The earlier `writer-recovery-system-e2e-green-2.txt` is a failed run despite its
filename: its first health probe caught Worker STARTING (503) immediately after
the supervisor became running. The fixture now waits for health 200 before
submitting work; it does not accept STARTING as healthy. Original output is in
`writer-junit-3ef6023d-1cd1-4a8e-bf19-103b48c0827b/fixture-output.txt`.

| proof | evidence / outcome |
|---|---|
| Final build, formatting and installed process after the correction | `writer-root-post-correction-build-process.txt`: successful, 1m5s; one integration test; run `d37fe7b7-8394-4f8e-99a3-4060a0ed4f58`, fatal exit 1, one charged restart, both exact document hits; cleanup exited in 646ms with closed ports and no errors |
| Final unbound initial-NRT regression | `writer-root-unbound-nrt-red.txt`: previous branch fails; corrected branch passes in the entire affected module |
| Post-correction affected module, formatting and PMD | `writer-root-lucene-final.txt`: successful, 1m26s; 98 XML files, 709 cases, no failures/errors/skips; XML preserved in `writer-root-lucene-snapshot` |
| Final focused Java tests after early binding restoration | `writer-root-focused-1.txt`: successful, 32s; adapters-lucene, indexer-worker, app-engine and ui selected tests |
| Final installed distribution, controlled inference-disabled arm | `writer-root-process-1.txt`: successful, 22s, one integration test; run `89e8206e-bc5a-4a25-b48a-00b4d77f64d2` |
| Native model initialization/warm-up overlap | `writer-recovery-live-ai-enabled-1.txt`: run `c1e46cb7-d282-4968-abaa-4608c0739fed`; passed before the final early-runtime binding correction |
| Commit/mutation detection negative pairs | Removing the report makes `writer-recovery-mutation-no-commit-report-red.txt` / `writer-recovery-mutation-no-coordinator-report-red.txt` fail |
| Resumed NRT routing negative pair | `writer-recovery-mutation-no-resumed-crtrt-route-red.txt` fails; restored route included in final focused run |
| Early runtime binding negative pair | `writer-recovery-mutation-no-startup-runtime-binding-red.txt` fails; restored binding included in final focused run |
| Deferred initializer completion negative pair | Restoring the old five-second timeout fails `writer-recovery-mutation-five-second-init-abandonment-red.txt`; completion held beyond five seconds passes in final focused run |

The installed test creates a committed first document (queue DONE), then creates
the next compound-segment filename with create-new semantics and submits a second
document. It requires the collision in the first incarnation's diagnostic log,
fatal exit 1 with no requested reason, exactly one charged restart under the same
supervisor run, a new Engine instance, health 200, and exact path/content hits for
both documents within a shared 180-second recovery budget. The second job was
observed PENDING before death. This does not establish both PENDING and PROCESSING
retained at restart. Cleanup uses the fixture's own state root and run identity.

In the native-enabled run, both GPU embedding and reranker sessions initialized;
reranker warm-up performed an actual model call. The first incarnation exited 1
and restarted normally, without the earlier hang classification. The successor
completed ordered quit; its stop report records HTTP 202, graceful exit in
1,068ms, closed ports and no errors. No crash report or hs_err file was produced
under this run. The search probes themselves used sparse retrieval and skipped
cross-encoder reranking: this is native initialization/teardown overlap evidence,
not a full AI workflow or search-quality result.

The Java wrapper invokes the production distribution through dev-runner;
`:modules:system-tests:integrationTest` depends on `:modules:ui:installDist`.
The Windows CI integration job provisions Node and uploads the owned fixture
directory. That hosted job remains advisory. Neither this fixture nor the
conformance driver executes the packaged Tauri application's real startup path.

The original mandatory-lock stress test and its assertions remain unchanged.
Its in-process harness cannot survive a deliberate whole-process restart. A
separate, explicit migration must preserve intruder-before-boot ordering, actual
file locks, corpus/acceptance/searchability assertions and the 180-second bound.
The responsive-shutdown deadline and single-writer transport re-cut remain in
[scope-recut.md](scope-recut.md); this repair does not close them or stage B.

### Preserved artifact hashes

| raw artifact | SHA-256 |
|---|---|
| `writer-root-post-correction-build-process.txt` | `109247ce18e25b26184938ea80162b03fbaa515f5724712d8c321798a73b3215` |
| `writer-root-build-static-1.txt` | `69158172ccc94856820f006104d4c0dcca56840846b3b85d479c9a05cf2619aa` |
| `writer-root-unbound-nrt-red.txt` | `f56356646f1fd1ca07f173e049c48f2f39fc2ecca72a4a7edba4647dd304ee0b` |
| `writer-root-lucene-final.txt` | `157b05639c58d87dad67b07d624024101d21883f0b9cdf9d3b311217acee7f87` |
| `writer-root-full-test-1.txt` | `48ac3f4236339832818c0d503eaa6efb741a246371c3ddc6be4d48a197ab0c1d` |
| `writer-root-full-snapshot/summary.json` | `51b0883784545a05226fd345ec9a096b9620e0666a0f31a1b9b44a61040afc2a` |
| `writer-root-full-snapshot/files.json` | `08f720fd2cfb4d3d22f9fca930c4b9d647c58ebd166cc8fc4e39fdfc2d0bc362` |
| `writer-root-dist-hashes.json` | `7ee5e1244ecafb71b590c81731a871281b4a76ce4b0f8550743338b4bee483f7` |
| `writer-root-focused-1.txt` | `79b900839fb3f8192daf64ac03f5042cd79f766d4d6dbb1e8b9d989db801bd37` |
| `writer-root-process-1.txt` | `d1e73a81d7c56b6dfd2fa835f1803286e0c0584ee8d895006687ab8f33274a5c` |
| `writer-recovery-live-ai-enabled-1.txt` | `43ba6c795d4d2b35ae16aef3608020df82395ba896fadae3803791d8a2794054` |
| `writer-recovery-live-probe-3.txt` | `36e2ecb7ad606c10a24536aa1beb0e435b0edd76389081a0fbac876d62b87af6` |
| `writer-recovery-mutation-no-commit-report-red.txt` | `3a420745b15111578b73dc792dbcbb02c5e5cac22cec320c354b0fee89412d23` |
| `writer-recovery-mutation-no-coordinator-report-red.txt` | `07a6bac61c82cbee7a20a85ffa948c67d9fca9425e4096c684d870935d1c8854` |
| `writer-recovery-mutation-no-resumed-crtrt-route-red.txt` | `6e06be78e3863bd3622c4ef7c49b9dace97b2c4713d8eb62917816dc5530a43e` |
| `writer-recovery-mutation-no-startup-runtime-binding-red.txt` | `4e57b06d01dcf965a072c2db4bfeefd39425c3b2471a0728165a15b999ff6b7d` |
| `writer-recovery-mutation-five-second-init-abandonment-red.txt` | `3c2ea5f6d2fef34cadd029dbe66701bed1382f8a05cca3481eb61428bbd85f03` |
| `writer-shutdown-causal/engine-run3.ndjson` | `8e9072aafb7bd31bcdcfc70eb9846fabceb1e34ecdfbadcc9404c833a36e4732` |
| `writer-shutdown-causal/hs_err_pid32900.log` | `5b5ba685606109f6102a9eaf737e2c06cea3413b6fdc8a911a225c31ffe9954b` |
| `writer-shutdown-causal/OrtEnvironment-javap.txt` | `242d0974db051289412baea0e59b764fb5a278dc3aba39090b4ea6615accc233` |
