# C1 fanout parent-exit regression correction — 2026-09-09

Full271 exposed a false timing assumption in EngineFanoutOwnershipTest: after the child
executor terminated, the test immediately attached new work. The parent call still had its own
admission reference while finishing cleanup. Admission correctly refused; child exit alone
does not prove parent exit. The failing case is callerInterruptReturnsPromptlyButRetainsAdmissionAndPacingUntilChildExit.
Raw failure: `tmp/c1-runtime-final-integrated-271.txt` and the preserved reports at
`tmp/c1-runtime-final-integrated-results-271/manifest.json`.

The correction strengthens the same lifetime invariant: hold parent cleanup explicitly after
child exit, assert admission and pacing are still held, release parent cleanup, then await both
actual owner counts reaching zero before admitting the next work. The test still requires prompt
caller cancellation, exact refusal propagation, no replay of later child bodies, and retention
while the child is alive. No production admission behavior or timeout is weakened.

Build273 passes. Its command included -x test, so the explicit test selectors in that command
did not execute tests; it is build evidence only. Separate focused execution274 passes both
fanout cases, all four EngineRoot lifetime/reload tests and both generation-capacity tests.
Logs: `tmp/c1-runtime-build-and-ownership-273.txt`, `tmp/c1-runtime-ownership-restored-274.txt`.
Full271 remains the last full-run result until a subsequent full run completes; focused green
cannot replace it. Local raw evidence is retained through lane F acceptance.
