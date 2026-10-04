# C2-4 integrated completion-consumer boundary

2026-09-13. Full1202 passes at `4c6389be40549273b3695807500f1570a1a7e855`
in 25m 11s. The command is `./gradlew.bat build pmdAll
:modules:ui:compileIntegrationTestJava --continue -PincludeStress=true
-PtestParallelism=1 --max-workers=4 --console=plain`.

The run represents 10,362 tests in 1,674 suites: 10,195 cases across 31 executed
test tasks and 167 cases across seven cached test tasks. There are zero failures,
zero errors and 35 inherited skips, individually inventoried in the manifest.
Stress is explicitly enabled. Compilation, PMD and UI integration tests pass.
The earlier full1180 remains a failed run; this is a new successful boundary
including the journal factory correction and completion consumer.

The quiet interval in the Engine task was active sandbox/soak work, confirmed by
the retained thread dump; its task subsequently passed. JVM CDS/Unsafe warnings
and the PMD configuration-cache incompatibility remain visible in the raw log.

[Commands, task counts, skips and accessible artifacts](integrated1202-verification.json)
include the full log and a portable XML ZIP captured before subsequent builds.
Retain through lane acceptance plus 30 days and export before worktree release.

This is local Windows JVM proof. Installed schema-v5 and hosted proof remain owed.
At 14:12 UTC, CI run 34760983410 is pending and CLA run 34760982338 is queued at
the tested revision. No successful runner execution is inferred. Atomic SSE and
frontend keyed convergence remain the next C2-4 item; C2 and the lane remain open.
