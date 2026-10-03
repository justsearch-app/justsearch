# C2 verification correction: deterministic watchdog assertion

2026-09-13, fromfa2c2e560. Full UI1171 failed one existing test: the heartbeat timer
expired after40ms and queued a second reconnect timer, but the test's70ms wall-clock
wait could finish first under suite load. Unchanged isolated1174 and full1175 pass.
Independent read-only exploration traced EnvelopeStream's watchdog and bounded
backoff and found no causal link to the effect UUID change or demonstrated runtime
defect. Raising the wait would retain the race.

Only the failing test now uses Vitest fake timers. It asserts the original connection
is open at39ms, closed at40ms, and exactly one replacement appears within the20ms
backoff cap. finally stops the stream and restores real timers. The test's heartbeat
contract is preserved and its intermediate assertions strengthened. Other tests and
production behavior are unchanged.

Focused1177 passes24 tests. Negative1178 changes the production watchdog guard to
disable positive timeouts; the corrected test fails at the40ms closed assertion
(1 expected failure,23 filtered skips). Exact production bytes are restored before
full1179, which passes6511 tests in484 files. Commands: from modules/ui-web run
`npm run test:unit:run -- src/shell-v0/streaming/EnvelopeStream.test.ts`; the negative
adds `-t 'heartbeat-absence watchdog expires'`; full uses `npm run test:unit:run`.
Raw logs: lane worktree tmp/c2-4-watchdog1177.txt,
tmp/c2-4-watchdog-negative1178.txt, tmp/c2-4-watchdog-ui1179.txt; original production
bytes in tmp/c2-4-watchdog1178-original.ts. Retain until lane acceptance plus30 days,
export before releasing the worktree. Runs use fa2c2e560 plus this test-only diff.
The source is committed unchanged with this record; no post-commit rerun is claimed.

This closes the local timing correction, not the separate completion-consumer,
atomic-SSE, installed-v5 or hosted obligations. Coherent JVM build1180 is in progress.
