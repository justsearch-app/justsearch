# C2-4 integrated SSE boundary and request lifetime

2026-09-13. Full1249 passes at028be4ac8 plus the four fixture changes and concurrent Activity description change identified in the
[verification manifest](integrated1249-verification.json). Java production is unchanged.
Build, PMD, UI integration tests and stress-enabled tests pass in2m12s. The exact command
is retained in the manifest; the raw log corroborates the tasks and results.

The run represents10403 cases across1680 suites:1310 cases in four executed test tasks
(including2 skips),9093 cases in34 reused tasks (including33 skips). Overall there are
zero failures, zero errors and35 inherited skips. Reuse follows unchanged Gradle task
inputs; it is not claimed as fresh execution. Full1242 had already executed8046 represented
cases before the three stale UI assertions failed; that18m17s run remains FAILED.

Capabilities, operation history and runtime context controllers now assert the actual
pre-created request future stays incomplete until onClose and then completes; they also
assert that keepAlive is not called. Existing snapshot/update assertions remain intact.
EngineSseAdmissionLifetimeTest retains the legacy keepAlive case and adds SseConnection
under a real Javalin server. Both hold admission after callback return, keep Health reachable,
and release admission only after the actual managed client closes.

Focused1246 executes20 cases with zero failures/errors/skips and PMD/format checks green.
Negative1250 completes the future prematurely; allthree controller lifetime assertions
fail for the intended reason. The mutant was restored byte-for-byte before full1249.
First negative attempt1245 remains FAILED: three Mockito nested-stubbing errors and the
premature-future real-network case's timeout cannot stand in for the clean negative proof.
The root fixed the fixtures by obtaining Context before starting Mockito stubbing.

All XML was captured before subsequent builds. Commands, task execution/reuse, individual
failures/skips, raw logs and portable XML archives are accessible in the manifest and
retained through lane acceptance plus30days, with export required before worktree release.
JVM CDS/Unsafe and PMD configuration-cache warnings remain visible; no warning was suppressed.

Live1238 and rendered1241 proof remain in [the preceding campaign](sse-live-rendered.md).
The real-browser campaign is still open: controlled native-source reconnect rendered the
second row once. Reload selects the default System Health tab; the harness must select
Activity again before asserting the retained snapshot. This is the current routing contract.
Installed-schema5 and hosted proof remain required. This coherent local Java boundary does
not close C2-4, C2 or the lane.
