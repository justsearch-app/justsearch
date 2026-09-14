# Hosted parser containment fixture startup correction

2026-09-14, based on pushed5739cdb8b. This is a test-only correction to required C1
containment proof while lane F C2 remains active. No production timeout or containment
implementation changes.

CI34815355461 at0123adb1f failed WindowsParserContainmentTest's recycle parameter with
ExtractionTimeoutException before a PID file existed. The test deliberately exposes that
failure via pending.get(), so its live-native-child and onExit assertions did not execute.
The pool's response deadline includes cold JVM startup, process-boundary initialization,
protocol setup and native child spawn. The available hosted log cannot identify which of
those startup stages consumed the five seconds. This is not evidence that containment
failed, and it is not accepted as a hosted pass.

The neighboring parser pool fixtures and earlier C1 parser-fixture-startup proof use a
ten-second test allowance for cold Windows startup. Apply that same bounded allowance
here. The timeout arm still waits for the actual pool timeout with an already-observed
live descendant; its child sleeps120 seconds. Keep the45-second per-case watchdog,
PID handshake, actual liveness assertion, request-budget recycle, explicit close,
10-second native onExit bound and exact fixture PID cleanup. Add a six-second cold
startup child in a fourth slow-recycle arm so the former budget fails deterministically.

## Proof

- startup1554:5 cases/1 suite EXECUTED, zero failures/errors/skips; worker-services PMD
  and whole Spotless pass.
- budget-negative1555: same cases with former five-second budget; exactly slow-recycle
  fails with ExtractionTimeoutException before its PID handshake. The ordinary recycle,
  timeout, close and native-access refusal cases pass.
- kill-negative1556: restore the ten-second allowance and omit real Windows containment
  only from the test child bootstrap. All four lifecycle arms fail at native onExit,
  proving the extra startup allowance does not conceal surviving native children.
  Native-access refusal still passes. Finally cleanup owns each exact fixture PID.
- Restore the test byte-for-byte from tmp/parser-startup-positive1554.java (SHA recorded
  in tmp/parser-startup-positive1554-sha.txt), then run1557 with the exact Windows CI
  module selection and -PwindowsOnly=true, plus worker-services PMD and whole Spotless.
  Final1557 passes113 cases/9 suites across all six EXECUTED tasks, zero failures,
  errors or skips, with PMD and whole Spotless passing. The worker-services selection
  contains its one windows-tagged class:5 cases after adding slow-recycle. Local case
  counts describe one execution; CI enables the test-retry extension separately.

Hosted proof on the correction remains required after push. Raw logs, archived XML and
*-counts.json files are in F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/.
The hosted inputs are resolver-register-hosted1547.json and
resolver-register-hosted-failed1549.txt there. Retain until2026-10-14 or30 days after lane
acceptance, whichever is later. This correction does not complete C2 or authorize stage-F
merge before the remaining design/implementation and proof.

Independent review found no fixture defect and corrected one evidence metadata error:
kill-negative1556's later FAILED announcement must supersede the earlier task-start line.
The archived four failures were already correct; only status metadata changed. The active
capture helper now preserves a later FAILED status instead of retaining its first announcement.


## Hosted result at d892321c6 (2026-09-14)

[CI34818093845](https://github.com/justsearch-app/justsearch/actions/runs/34818093845)
completed with overall success and Windows-native tests success. Twelve jobs passed;
Integration tests (system-tests tier) failed:101 reported attempts including retries,
seven failures and42 skips. Failures include EngineSupervisedRecoveryE2ETest's lock-boot
and processing arms and OperationResumeE2ETest. The overall run conclusion is not an
integration pass. These recovery failures remain required lane work, being diagnosed from
hosted artifacts. CLA34818091906 passes. The intermediate5739cdb8b and bda750489 CI runs
were cancelled by later pushes, not verified passes. Local downloaded job metadata and
failed log:tmp/parser-hosted1561.json and tmp/parser-hosted-failed1561.txt.
