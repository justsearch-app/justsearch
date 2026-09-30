# C1 native GPU waiter cancellation

## Defect and ownership decision (2026-09-09)

Installed standard-model pacing run170 failed after two native ORT calls stalled and15
foreground callers accumulated behind NativeSessionHandle's uninterruptible GPU semaphore.
The Engine deadline already interrupts those threads and retains admission until work exits.
The same semaphore now honours that interrupt. No new cancellation registry or lifecycle state
is introduced. Interrupted acquisition restores the interrupt flag and throws CancellationException;
an acquired but unissued permit is released exactly once. Issued leases retain their original
release owner until native work actually exits. This does not claim that interruption terminates
OrtSession.run, or that active sessions may be closed before their callers quiesce.

The raw stall evidence and co-resident hardware limitation are recorded in
[integrated-candidate.md](integrated-candidate.md). Fresh pacing remains required.

## Focused proof

Run182 failed compilation because ort-common did not yet declare Mockito. Root added the
existing catalog test dependency; full resolveAndLockAll --write-locks183 passed and changed
only ort-common's lockfile. Run184 then passed:

```text
./gradlew.bat :modules:ort-common:test :modules:app-engine:test -PskipErrorProneTests=false --tests '*NativeSessionHandleGpuWaiterCancellationTest' --tests '*EngineSandboxFailureWorkflowTest' --console=plain
```

The three native waiter tests use a mocked OrtSession at the native boundary, with the real
NativeSessionHandle and semaphore. They cover a blocked interrupted waiter, a pre-interrupted
caller, and deterministic interruption after acquisition through the existing telemetry callback.
Assertions prove prompt cancellation, cause/interrupt preservation, absence of extra permits or
premature session close, and successful acquisition after the real holder releases. They do not
substitute for real model execution. Root replaced the test's scheduler yield with bounded sleep
after strict compilation warned about Thread.yield; that final test-only change needs the next run.

Evidence: worktree-local tmp/c1-native-waiter-182.txt, tmp/c1-native-locks-183.txt,
tmp/c1-waiter-sandbox-184.txt, and tmp/c1-waiter-sandbox-184/ (preserved four-test XMLs).
Required stress, integrated, fresh standard pacing and hosted checks are pending.


Run186 passed restored confinement, workflow and waiter proofs with strict test-source checking
(103 tasks,6 executed,4 from cache,93 up-to-date;37 represented test cases). The scheduler-yield
warning is gone. Run187 then replaced only interruptible acquisition with an uninterruptible wait
(keeping an explicit pre-interrupt throw so compilation remained valid). The blocked waiter test
FAILED at its prompt-completion assertion:3 tests,1 failure,0 errors. The raw XML names
interruptedWaiterExitsWithoutReleasingActiveGpuLease and "interrupt must release the blocked
waiter". The original source bytes were restored in finally. Evidence:
tmp/c1-native-waiter-mutation-187.txt and tmp/c1-native-waiter-mutation-187/.
Restored native plus all Stress-named tests188 stopped at three modules with no matching test
names (app-config, core-contracts, app-util); this is a selector failure, not native stress proof.
Integrated190 runs build/test/installDist with stress enabled and strict test-source checking,
without filtering any module to an empty set. It is in progress. A pass of the existing CPU stress
suite does not establish native shutdown quiescence; D1 now has that explicit required obligation.
