# Windows keystore child-JVM timeout evidence

Base: 1016257d2f200a6fd39bc1b4e88d20595e66223f.
All evidence here is small text. windows-excerpts.txt contains extracted original XML results,
failure payloads and progress log lines, with artifact paths and full XML SHA-256 digests.
child-jvm-survey.txt records the source search and neighbouring Windows observations.
Results are from the Windows-native contracts job, not Linux unit-test artifacts.

## Slow versus hung

- Durability: Windows passes in 27.022, 27.133 and 29.177 seconds. Run 39605828637 attempt 1
  fails three times at the 30-second limit; one failure interrupts parent-side password reset,
  not a child wait. Another execution reaches recovery step 3 before interruption in the final
  recovery-child wait. The longest observed consecutive password-repair interval is 9.107 seconds.
- Replica repair: PR #873 run 39632460020 attempt 1 fails once at 30 seconds
  (31.442 seconds including unwind) in parent-side regenerateRecovery, source line 140, with
  ClosedByInterruptException. Its first two completed password repair messages are at
  16:30:59.517 and 16:31:13.081: 13.564 seconds apart. The parent has therefore completed both
  password children and is preparing a later iteration when interrupted; there is no live child
  wait at the reported failure. Its retry passes in 22.561 seconds. Other Windows passes take
  18.558, 19.116, 19.685 (main) and 22.389 seconds.

Both failures have evidence of cumulative slowness. Historical reports lack isolated child start
times, so interrupted polling alone cannot prove that a particular child would have progressed.
The new per-child action, step, start-to-pause/incomplete elapsed time and failed-child output
make future stalls observable in the uploaded JUnit system-out reports.

## Measured budgets

Repair-log intervals include child startup and parent credential/verification/reset work.
They are conservative iteration-cost observations, not isolated child durations.

- Eight durability iterations x 9.107 seconds = about 73 seconds. Method timeout: 120 seconds,
  with about 47 seconds of setup/recovery/cleanup headroom.
- Four replica-repair iterations x 13.564 seconds = about 55 seconds. Method timeout: 90 seconds,
  with about 35 seconds of setup/recovery/cleanup headroom.

The 30-second per-child publication bound and 10-second termination bound remain in both tests.
The global 30-second default, all assertions, action/step combinations, real filesystem writes,
process kills, restart/unlock checks and credential-revocation checks remain.

## Sources

Each link identifies the run; exact attempt/artifact/XML paths are in windows-excerpts.txt.

- https://github.com/justsearch-app/justsearch/actions/runs/39605828637 (attempts 1 and 2)
- https://github.com/justsearch-app/justsearch/actions/runs/39632831411 (attempt 1)
- https://github.com/justsearch-app/justsearch/actions/runs/39597325251 (attempt 1)
- https://github.com/justsearch-app/justsearch/actions/runs/39615523562 (main, attempt 1)
- https://github.com/justsearch-app/justsearch/actions/runs/39632460020 (PR #873, attempt 1)

Failed-log and job-log retrieval were attempted. A rerun now passing may produce no failed-log
output; a still-running/cancelled run may refuse logs. Run 39605828637's completed attempt-1 job
log independently reports the three durability timeouts. Uploaded XML is the source of the exact
stacks, durations and progress messages retained here.

## Local checks and reproduction

local-checks.txt records the combined focused Gradle command, exit/result counts, method durations,
all twelve child pause timings and zero PMD violations. It executed 20/20 tests with no skips.
The scratch init script's verbatim text is in followup.init.gradle.txt; it is evidence data and is
not applied by the repository build. It removes the machine-specific tmpdir argument provider
only for the selected Test task, so the explicit tmpdir remains under $TMPDIR.

Standalone probe launchers use the captured Gradle runtime classpath and Java executable.
probes.py and TimeoutProbe.java produced the durability records; replica-probes.py and
ReplicaTimeoutProbe.java produce the sibling records. They compile separate overlay class
directories and delay only the publication fixture's child main. A baseline-only JVM tmpdir
argument prevents children defaulting to RAM-backed /tmp. No repository source is edited for
the injected delays.

To reproduce, use disk-backed $TMPDIR, recreate shared detached baseline clones named before
and replica-before at the base above, copy the appropriate producer and launcher files into
$TMPDIR, and capture evidence/test-classpath.txt and evidence/test-java.txt with the included
scratch init text during the focused Gradle call. Then run the producer from the checkout root.
The before and candidate code is compiled against the same captured dependencies; no production
change is under test.

The durability receipt and raw logs retain: baseline 30-second failure, delayed candidate pass in
46.302 seconds, a 30-second stalled-child assertion and an actual 120-second JUnit timeout.
The replica receipt and raw logs record the corresponding four-child checks with a 90-second
aggregate timeout. An additional normal standalone durability run passed 12/12.

The candidate still needs hosted Windows execution and independent acceptance. The historical
test-intent reconsider-rate warning is nonblocking (1 of 9 audited PRs, 11.1%).

Replica clean campaign results: seven-second delays fail on baseline at 31.045 s wall and pass
all four children on candidate in 37.893 s. A 60-second child stall fails the 30-second child
assertion in 31.179 s wall. With 23-second child delays, JUnit enforces the 90-second aggregate
limit in 90.408 s wall. Each expected outcome matched; the aborted duplicate campaigns are not
used as evidence. The 90-second failure reports recovery step 3 as not yet paused.

Raw probe logs are copied unchanged with a .txt suffix (and durability- prefix for the earlier
campaign), so repository log-ignore rules do not omit them. The producers still emit .log
scratch outputs; those outputs map directly to the .txt evidence files here.
