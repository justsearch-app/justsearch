# Shutdown review checkpoint — 2026-09-08

Reviewed code head: `d23e94bdd`. Sole implementer: `fix_shutdown_review`;
independent read-only reviewer: `review_b7_b10`. The orchestrator independently
re-read the corrective diffs and recomputed the preserved XML totals.
This is a shutdown-fix checkpoint, not stage B completion.

## Implemented and reviewed

| Change | Commits | Production evidence at reviewed head |
|---|---|---|
| Reason reaches the real inference close path; quit/upgrade stop llama and restart/hang preserve it | `07dec949b`, `0df399ac9` | `HeadlessApp.java:1356`, `HeadAssembly.java:293`; real-manager close test in `InferenceLifecycleManagerShutdownTest` |
| Admission freeze precedes every ordinary close step | `0df399ac9` | `HeadlessApp.orderedShutdownSteps`; repeated upgrade freeze preserves the live preparation |
| Expired requests are refused; predecessor cleanup is strict and precedes API exposure | `c1df250ab`, `04cf2cb9f` | `ShutdownRequestWatcher.java:121`, `HeadlessApp.java:947-969`; watcher startup no longer clears current requests |
| Production absence is GRACEFUL, omission is UNKNOWN, thrown index close is FAILED | `fa46a7792`, `a4f574b87` | `HeadlessApp.java:1363-1368`, `EngineShutdownSequence.java:145-146` |
| Upgrade tests use the actual writer/acceptance/dispatcher factories and live preparation | `975608ff7` | `HeadlessApp.java:1219,1240,1254`; three further B6 findings remain below |
| Ordered shutdown closes its watcher without self-interruption | `0df399ac9`, `86cf4e9e1`, `d23e94bdd` | `HeadlessApp.java:1326-1333`; regression joins the actual callback thread and checks the later close step's interrupt flag |

The unused upgrade delegation on `HeadShutdownCoordinator` was removed. No public
watcher-liveness API remains merely to support the test.

## Verification and its limits

The implementer ran all four affected module test tasks successfully in 6m23s:

```powershell
.\gradlew.bat :modules:app-engine:test :modules:app-inference:test :modules:app-services:test :modules:ui:test
```

That command/duration is from the implementer's handoff; a separate raw capture of
the 6m23s run was not preserved. The module XML below preserves its inference and
app-services results.

After the corrective review edits, the same task set completed in 7m10s:

```powershell
.\gradlew.bat --console=plain :modules:app-engine:test :modules:app-inference:test :modules:app-services:test :modules:ui:test
```

The second run executed app-engine and UI; app-inference and app-services were
up-to-date from the preceding completed run. Its raw output is
`tmp/lane-f-takeover/shutdown-review-affected-modules-final.txt`. Before any focused
rerun, XML was copied to `tmp/lane-f-takeover/affected-modules-final-xml/`.
These `tmp` paths are local and gitignored; this committed table is the portable record.
The orchestrator independently parsed those XML files:

| Module | XML files | Tests | Failures | Errors | Skipped | Test timestamps (UTC) |
|---|---:|---:|---:|---:|---:|---|
| app-engine | 26 | 120 | 0 | 0 | 0 | 06:43:31–06:50:37 |
| app-inference | 28 | 299 | 0 | 0 | 0 | 06:27:06–06:27:23 |
| app-services | 396 | 2517 | 0 | 0 | 3 | 06:27:08–06:28:08 |
| ui | 153 | 1029 | 0 | 0 | 1 | 06:43:36–06:44:19 |
| Total | 603 | 3965 | 0 | 0 | 4 | 2026-09-08 |

The final test-only adjustment replaces the watcher-reference assertion with actual
thread termination. Its focused command passed in 16s; the worktree was clean after
the command and commit:

```powershell
.\gradlew.bat --console=plain :modules:app-engine:spotlessApply :modules:ui:spotlessApply :modules:ui:test --tests '*HeadlessAppShutdownWiringTest.watcherCallbackClosesProductionStepWithoutInterruptingLaterClose'
```

Raw output: `tmp/lane-f-takeover/watcher-production-close-thread-join-green.txt`.
`:modules:ui:spotlessCheck` also passed. No Gradle wrapper process remained when the
implementer released ownership.

Preserved falsification pairs, all under `tmp/lane-f-takeover/`:

| Deliberate defect restored | Failing run | Restored passing run |
|---|---|---|
| Production absent-index binding returns null | `finding4-null-index-falsification.txt` | `finding4-index-outcome-green.txt` |
| Watcher startup clears a current-boot request | `stale-boot-clear-falsification.txt` | `stale-boot-clear-green.txt` |
| Production watcher-close step is a no-op | `watcher-production-close-falsification.txt` | `watcher-production-close-green.txt`; later thread-join version above |

Earlier falsification claims are in commit bodies; their raw failing output was not
preserved and is not claimed as independently reproduced here. Strict boot cleanup
is covered through the production helper and test-owned call ordering, plus source
review of `main`; this is not live `HeadlessApp.main` boot-failure proof. The full
pre-implementation 9334-test run is separately recorded in
`takeover-verification.md`; it must not be presented as a post-change full suite.

## Still open

Three B6 findings remain: prepared direct files do not verify the controller's nonce;
request persistence happens after HTTP success and consumes the capability on failure;
and the writer is installed after API exposure. Current-boot preservation at watcher
startup is already fixed. The controller transaction, early writer installation and
tri-state verifier decisions are dated in `design.md` section 0.

Accepted-instance retention, first-claim-wins writer admission, original deadline and
exit-time observation, schema/version refusal, and named requested-unclean exit codes
remain a coordinated Java/Rust/Node batch. B7–B10 production-host findings R1–R9 remain
tracked in `b7-b10-independent-review.md`. B11–B17 are not checked complete by this
checkpoint. The live boot proof, live recovery/model checks, stage-wide gates and
final independent B review remain outstanding. PRs #708 and #717 still require the
owner's per-PR merge decision; this checkpoint authorizes no merge.
