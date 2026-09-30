# Integrated verification checkpoint (2026-09-08)

**The integrated stress suite is red.** This is not the stage-B checkpoint.
The reviewed transaction and host ownership changes are integrated; the shared
request-slot batch is stopped by [the scope review](scope-recut.md).

## Full inventory before any targeted rerun

At `3721395ff23504ecc093157f6eb0d42c43ab6b81`, ran:

```text
./gradlew.bat cleanTest test --no-build-cache --continue -PincludeStress=true -PtestParallelism=1 --console=plain
```

The run finished in 23m55s: **1,522 XML files, 9,364 tests, 2 failures, 0 errors,
25 skipped**, across 34 modules. Both failing module tasks and all other module
tasks executed; `--continue` allowed the remaining modules to finish. Gradle reports
229 tasks, 68 executed and 161 up-to-date. The test timestamps run from
08:12:26.954Z to 08:36:15.952Z.

[The committed output](integrated-stress.log) preserves the raw text; Git normalizes
its Windows line endings. The committed blob has SHA-256
`7757db0e3e6fdeeacfad634594c67b4465cb0d5c1e3ec5d5dc2a094aeb0eab96`.
The original local raw file and immutable snapshot copy retain SHA-256
`78215c17897dc2f129a12712de06ff57472c3c2c8d0d32db0a1a3e6864345006`;
the inventory's `logSha256` refers to that original file.
The [per-module inventory](integrated-stress-initial-summary.json) records counts
and timestamps. Every XML, its SHA-256 and a raw-log copy were preserved before
reruns under `tmp/lane-f-takeover/integrated-stress-initial-snapshot/`;
`files.json` is the individual-file manifest. This deliberately prevents filtered
reruns from being mistaken for a full inventory again.

The earlier 9,334-test green run predates the changes and did not include stress.
The count difference is accounted for: adapters-lucene +1, app-engine +6,
app-inference +1, app-services +1, ort-common +1 and ui +20. A larger count is
not evidence the new run passed.

## Drain-retry fixture repair

`LifecycleStressTest.fiftyHolderSwapCycles_noLeaks_concurrentReadsAndWrites`
failed because `writesRetried` was zero. Its classifier counted arbitrary
`IllegalStateException`, while the governed production drain signal is
`IndexRuntimeIOException(DRAINING)` (`IndexingCoordinator.java`:480-487;
`DrainAndCloseTest.java`:24-28). The old classifier was identical on main. The
intent was correct; the fixture did not measure it.

Independently reviewed commit `4349b28f5dfebb8543efd21d6f3d1c38a3133f3b` changes
only this test. It uses the existing telemetry callback after the writer acquires
the real read barrier and before the drain guard. The first swap deterministically
marks draining while writer 0 is held there, then releases it. Only typed DRAINING
counts as a retry; other writer failures reach the existing uncaught-error check.
All 50 swaps, successful reads/writes and resource-leak assertions remain.

Focused formatting, PMD and stress verification passed in 30s. Mutating the accepted
reason to BACKPRESSURE failed the retained retry assertion; restoring DRAINING
passed with all 16 tasks executed in 34s. No production code or product seam was
added. Raw logs under `tmp/lane-f-takeover/`:

| log | SHA-256 |
|---|---|
| `lifecycle-stress-fixture-green-1.txt` | `dae33fdc289ce30da8b15941035d1ec21c81fd0e646e53e044e0f816303c7ba8` |
| `lifecycle-stress-fixture-mutation-red.txt` | `4cbb3e6c5ae1ef70a5ad2d5dd14211ac17df5baffeadc665169ac722dd88d246` |
| `lifecycle-stress-fixture-restored-green.txt` | `2c68ca840096c590d7c344f03710c29257883287c03c5980e544640ae82a599f` |

## Unresolved writer-recovery blocker

`EngineFileLockContentionTest.engineBootsAndIndexesWhileAnIntruderHoldsLocks`
failed at its unchanged searchability assertion. Its companion mid-ingest test
passed in 4.809s. The failure is not merely a long scheduling delay:

1. At 10:17:45.183 local time, the commit fails with a Windows mandatory-lock
   `IOException` while Lucene writes a compound segment (original XML:7112-7146).
2. Subsequent writes immediately fail with `AlreadyClosedException: this
   IndexWriter is closed` (XML:7264-7277).
3. Repeated commits, including the final shutdown commit, still use the closed
   writer (XML:34616-34626). The searchability wait expires after 180 seconds.

[Verbatim failure excerpts](file-lock-writer-failure.txt) preserve original line
numbers. The full immutable XML is named in that file and has SHA-256
`3da56a91682057c0b70d599429daa9b11d2f9def9023eb1be558f2eff7cad35a`.

The test uses `EngineTestHarness` → `EngineRoot` → `KnowledgeServer`, without the
`KnowledgeServerHealthMonitor` constructed by `HeadlessApp.java`:597 and started
at :617. Adding that monitor alone would not supply recovery:
`KnowledgeServerHealthMonitor.java`:237-246 selects boot retry only when there is
no client. With a client, the health path checks queue access and reader count
(`WorkerHealthService.java`:200-225), not writer usability. A readable stale
searcher can therefore conceal a closed writer. Write failures attempt a durable
retry transition (`JobBatchWriter.java`:191-207; `SqliteJobQueue.java`:997-1025).
If that transaction fails, the row remains PROCESSING for startup recovery
(`IngestionOutcomeJournal.java`:217-238). Neither case repairs the closed writer.

This is owned by the index-runtime/KnowledgeServer recovery investigation, not
silently covered by B14's boot retry. It blocks the stage-B verification claim.
The next bounded experiment must capture writer/health/queue state at the first
failure, then distinguish detection, recovery and replay using a controlled
restart. A harness restart would prove only replay after reopening, not automatic
production recovery. Do not widen the timeout, weaken the assertion or use a lucky
rerun as the fix. If the repair needs D1's later activation machinery, apply 17.8
before pulling it into B; no live-swap mechanism is approved by this record.

## Other checks and limits

The final repository build with tests excluded passed at `4349b28f5` in 28s,
325 tasks (6 executed, 319 up-to-date). The invocation named the affected module's
test and also passed `-x test`, so it is reported strictly as a build, not a test
run. Raw `tmp/lane-f-takeover/fixture-final-build.txt`, SHA-256
`d4e2e586c2ae24fe5980ccc842c9f49f5d22755e72e670af16bba38705a3991f`.

The full affected module was then run separately with
`./gradlew.bat :modules:adapters-lucene:test --no-build-cache -PincludeStress=true -PtestParallelism=1 --console=plain`.
It passed in 1m42s: **98 XML files, 699 tests, no failures, errors or skips**,
with timestamps 08:45:55.748Z–08:47:34.135Z. Gradle executed the module test task
(20 tasks, 1 executed and 19 up-to-date). Raw
`tmp/lane-f-takeover/fixture-full-lucene-module.txt`, SHA-256
`b06990eb23ca5243583a17d06bad0378370dadd47b46e78e933e2d553829bdd6`.
This resolves the fixture failure; it is not a fresh full-repository green run
and cannot erase the independent Engine file-lock failure.

The Rust host's 59-test result and production-binding proof limits remain in
`b7-b10-independent-review.md` and the committed `host-ownership-cargo.log`.
Live boot, real-model recovery, packaged UI, installer and the remaining B items
are not established by this checkpoint. No merge was performed.

Documentation validation and markdownlint passed for the edited documents;
`git diff --check` passed. `gitleaks dir` scanned the complete B evidence directory
without findings. Session closeout ran the repository's registered-process sweep:
no owned process was reaped, no stale staging file was found, and the ownerless
OTLP sink (PID 14468) was reported and retained by policy. No development stack
was started for this checkpoint. PRs #708 and #717 were rechecked OPEN/CLEAN and
remain the owner's separate merge decisions.

## B17-R1 repair verification (2026-09-08)

The later candidate above `6f38df7e5` connects terminal writer detection to the
complete ordered Engine shutdown and existing supervised fatal restart. Its full
default suite passed: **9,379 cases, no failures/errors, 25 skipped**, with all
1,521 XML files from 34 modules preserved before filtered reruns. Build,
Spotless and PMD passed. The real installed-process collision proof restores both
a committed document and an accepted document after one charged dev-runner
restart. A separate native-enabled run confirms model initialization/warm-up and
ordinary ordered stop without the earlier native crash/hang failure.

The 9,379-case run predates the final listener-not-yet-bound NRT correction.
After that correction, all 709 adapters-lucene cases, PMD, the build and a rebuilt
installed-process regression passed; no second full-suite run is claimed.

Read [the repair record](writer-recovery-investigation.md) for exact commands,
hashes, negative tests and source attribution. This is B17-R1, explicitly moved
earlier from D1 under 17.8. It does not clear the original mandatory-lock stress
red, prove both durable queue outcomes, execute the packaged Tauri application,
or complete the shutdown ownership re-cut. Those remain named B17 work. The
earlier PR status in this historical section is superseded by
[the publication record](../publication.md): #708 and #717 are merged.
