# Browser churn / enqueue burst checkpoint — incomplete assignment

Author: builder session `01a1562b-b2e9-7510-832f-04ea6ad6da28`. Base for new expectations:
`e4d7bbfb5`. Existing acceptance records are preserved; this is execution evidence, not acceptance.

The assignment is **blocked at the builder's two-attempt checkpoint**. Q27 still fails in the installed
Engine. The five-minute production browser check has not run. No F1 database was generated.

## Changes and deterministic checks

- Task-store auto-pruning kept 50 rows from the 216-row external mirror. Reconciliation recreated
  and pruned the missing failures on every pass, notifying the rail repeatedly. External mirrors
  now own removal of their bounded live rows. A projection publishes once; 100 identical
  projections publish nothing. Namespace lookup keeps unrelated tasks out of reconciliation.
  Locally owned terminal tasks retain their original oldest-insertion-first pruning order.
  Label-driven reprojections coalesce onto animation frames; feed deltas still apply synchronously.
- Newly inserted PENDING rows outside the window no longer occupy head handoff slots unless they
  carry state-presence metadata. Updates, including retries removing a visible failure, and every
  terminal delta remain forwarded. The capacity remains 256. The API contract map records this.
- No pre-existing test was modified, rewritten, skipped or deleted. Old→new assertions: none.

| Added durable test | New expectation | e4d7bbfb5 observation |
| --- | --- | --- |
| `indexingJobsResponsiveness.test.ts` | Retain 216 rows; one initial notification; no notifications across 100 stable reconciliations; remove the whole mirror once | Assertion failure: 50 rows instead of 216 |
| same file | Reconcile the job namespace beside 10,000 unrelated live tasks, including a prior mount's orphan | Assertion failure: namespace read returned unrelated agent tasks |
| `IndexingJobsEnqueueBurstTest.java` | A held real Engine subscription plus a real 2,005-file submission does not overflow on invisible PENDING inserts | Assertion failure: expected null error, got typed 256-slot StreamOverflowException |

`queue-scale-browser-drain.json` is written by the unchanged evidence producer, whose entire
lifecycle ran under `js-gradle-slot`, with `TMPDIR` and client `JAVA_OPTS` under the ignored task
folder. No before overlays or patches. The temporary before worktree was removed by the producer.

## Installed Engine observations and why work stopped

Adapted the verifier's `sse_probe.py` paths into ignored `tmp/builder/browser-drain/sse_probe.py`,
using the installed distribution, 2,005 real files, isolated JUSTSEARCH_HOME and JUSTSEARCH_DATA_DIR,
explicit JVM temporary directories, and the no-observer control. The control's `DONE` count includes
five bundled help files (2,010 terminal rows). All runs converged, but convergence does not satisfy Q27.

| Version/attempt | Handoff overflow count |
| --- | ---: |
| Initial installed code, no observer | 15 |
| Initial code with JFR diagnostic sampling | 13 |
| Filter invisible pending inserts | 4 |
| Same filter with temporary callback timings | 3 |
| Filter plus finite bulk queue drain | 7 |

The enqueue-time overflow disappeared with the filter. Remaining overflows originated on the
`indexing-loop` thread during concentrated DONE bursts. Temporary timings captured 15,608 accepted
frames versus 15,351 delivered, with queued DONE frames and an in-flight callback at about 192 μs;
another capture was about 86 μs. This establishes the seam, not the remaining bottleneck's cause.
The finite bulk drain did not meet Q27 and was removed completely; no handoff capacity change remains.
After those two different attempts, the builder contract requires returning rather than improvising.

Raw logs, the JFR diagnostic, adapted scripts, hashes and cleanup receipts remain in the ignored task
folder. Every Engine exited (143 after controlled termination); all started children stopped. No Vite
server or Chrome was started. Small probe databases/indexes/corpora were deleted. The machine-wide
`~/.config/justsearch` directory is absent. No million-row database, copy or backup was created.

## Checks

- Related task/rail Vitest: 5 files, 50 tests passed; `--maxWorkers=4`.
- `npm run lint`, `npm run typecheck`: passed in `modules/ui-web`.
- `js-gradle-slot ./gradlew spotlessApply :modules:app-engine:test --tests '*BoundedHandoffTest'
  --tests '*IndexingJobs*' :modules:ui:installDist --continue -PskipWebBuild=true`: BUILD SUCCESSFUL,
  18 seconds. The evidence producer additionally reran the final new class and proved base failures.
- The full production Q19 probe, stalled-observer Q27 probe and F1 generation remain unperformed.
  The no-observer Q27 probe fails, so neither scenario is claimed delivered.
