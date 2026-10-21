# Terminal continuity, enqueue-phase isolation, and production rail evidence

Builder session: `01a1562b-b2e9-7510-832f-04ea6ad6da28`. Checkpoint before the terminal repair:
`8eb70d443`. No acceptance record is authored here; existing records are preserved byte-for-byte.

## Consumers and repair (Q17)

The activity ledger was downstream of the lossy row-view handoff:
`IndexingJobsChangeStream → WorkerIngestService.subscribeIndexingJobs → EngineKnowledgeClient's
BoundedHandoff → RemoteIndexingJobsBridge → IndexingJobsBridgeWiring → ActionLedgerChangeRegistry`.
Overflow clears queued deltas, while a replacement snapshot excludes DONE and must not replay
activity. That combination lost terminal events. The new deterministic test held the first DONE
consumer, committed 399 more outcomes, forced typed overflow/resubscription, and observed **1 of
400** activity events on the checkpoint. The unchanged evidence runner reproduces that assertion.

Terminal acceptance now has an independent live subscription, established before the row view.
It has no row snapshot, history replay, or recoverable handoff. Its empty initial frame acknowledges
registration; cancellation and the existing subscription-generation fences own its lifetime.
Synchronous acceptance appends to the bounded activity store, and SSE delivery stays asynchronous.
The existing bridge lifecycle is reused with independent executor registration names. The view's
256-frame capacity, typed overflow recovery and backoff remain unchanged.

`IndexingJobsTerminalOverflowTest` checks all 400 expected path hashes, unique event IDs, scan
attribution, exactly 400 activity publications and 400 persisted outcome summaries after the forced
overflow/reconnect. Re-enqueue followed by a later failure yields exactly 401 in both projections.
It passes after the repair and fails before with `expected 400 but was 1`.

Consumer audit:

- Activity is the only live server-side terminal translator on the head's indexing-jobs feed.
  Resource views and the rail fold row state; status invalidation consumes state-presence metadata.
- Current ingestion outcome rollups read the worker's persisted ingestion ledger, written in the
  outcome transaction (`SqliteJobQueue.markDoneTransitions`, `insertLedgerEvent`,
  `ingestionOutcomeSummary`). They do not consume the head handoff. Their counts are checked beside
  the activity counts in the new test.
- `ActionEvent.ScanRollup` explicitly retains historical decoding/rendering only; Lane F retired
  its live producer. No scan aggregator still subscribes to this handoff. Recorded ingestion
  operation progress is worker-owned, independent of the row-view bridge.

## Q27: measure enqueue separately

The verifier's `network_probe.py` was adapted to pause only `indexing-loop` at `pollPending` entry,
before its queue lock, using a JDI breakpoint. This lets the submission phase end with every
submitted row PENDING. The SQLite writer, head drainer and HTTP/SSE threads remain runnable.
The stalled-observer control opens the real SSE socket, drains its initial snapshot, and then
stops reading with a small receive buffer. The probe resumes indexing only after recording enqueue
counts and overflow logs. Its old whole-run-zero-overflow assertion was removed to match the
explicit Q27 clarification; later overflows are reported, not treated as an enqueue failure.

| Installed run, 2,005 real files | Submission to all PENDING | Enqueue overflows | Later DONE-phase overflows |
| --- | ---: | ---: | ---: |
| No observer | 0.334 s | 0 | 0 |
| Socket reader stalled | 0.363 s | 0 | 3 |

Both runs' fresh head snapshots matched the terminal store window within the five-second Q26
bound. The later three overflows are the recorded residual, not a third attempt to fix DONE-burst
throughput. Their terminal-event loss is addressed by the independent Q17 subscription above.
Raw phase measurements, convergence observations, installed jar identities and cleanup receipts
are under `queue-scale-terminal-production/{enqueue-control,enqueue-stalled-final}/`.

## Q19: production Chrome / installed Engine

Exactly one current-schema F1 database was generated with the supplied `QueueChecks.seed` approach:
1,000,000 rows, initially 700,000 PENDING, 200,000 DONE, 1,000 FAILED, 16 PROCESSING and 98,984
RETRY_EXHAUSTED. It was reused throughout, never copied, backed up or VACUUMed. There were 12,000
real text files at its eligible queued paths. The installed Engine used the packaged **2 GiB** heap
and launcher flags (see `modules/shell/src-tauri/src/lib.rs`), with isolated HOME/DATA directories.
Vite's production build bundled the real TaskList and indexing-jobs bridge; an ordinary temporary
HTTP asset server served it. The same Chrome CSP/CORS bypass as the verifier was used. This is a
production component probe, not a native Tauri-shell trust-policy check.

The final Details-open run observed:

| Observation | Result |
| --- | ---: |
| Status samples, 500 ms waits | 600 over 305.8 seconds |
| Samples with expanded per-file rows | 599 |
| Maximum per-file rows rendered | 8 |
| Queued aggregate compared with each sample's status | All 600 equal |
| Maximum page-side status/reconcile response | 53.6 ms; no 3-second timeout |
| Maximum event-loop yield | 4.6 ms |
| Main-thread long tasks | 261; maximum 296 ms; total 26,395 ms |
| Successful real-file completions at quarter boundaries | 0, 576, 2,672, 5,104, 7,120 |
| Engine heap used at final observation | 115,853 KiB; no OOM |

The final run reindexed existing paths with changed content, so the corpus document count correctly
stayed 9,168 while successful real-file outcomes advanced. Activity is measured from those actual
file outcomes, not invented document-count growth or the bounded mirror.

Probe corrections and real exits are retained in the ignored task folder:

1. Chrome initially rejected the long temporary UNIX socket path. A short symlink to the same
   owned temporary folder fixed this; it was removed after cleanup.
2. A seed-only root had no deliberate-add volume witness, so real files were deferred. The real
   root-witness API recorded the fixture's deliberate add, and eligibility was restored for the
   same 12,000 paths in the same database. This did not weaken production admission checks.
3. An initial responsive 305-second run never expanded Details: the verifier tried to click it
   only on sample zero, before SSE rows arrived. The final probe clicks once when it appears;
   the rendered-row assertions remain intact. The same database was reused with the 12,000 real
   paths reset for another indexing pass; no second F1 was generated.
4. The final **browser probe exited 0** with all its response/count/rendering assertions passing.
   Its Python wrapper then exited 1 on an extra assertion I had added requiring a completed commit
   every five seconds. That proxy was invalid for the configured batched commit cadence. The raw
   capture is unchanged. `validate_production.py` exited 0 after checking the 600 browser samples,
   accurate totals, row bound, five-minute duration, outstanding real work, and increasing real
   successful outcomes across all four quarters. The helper now uses those observation-period
   checkpoints; no additional five-minute run was spent on this validation correction.

`queue-scale-terminal-production/` contains the browser result and screenshot, production bundle
hashes (no Lit development warning/NODE_ENV literal), throughput, heap, installed jar identities,
phase results, fixture preparation and deletion receipts. Adapted scripts and complete logs remain
in ignored `tmp/builder/terminal-continuity/`.

## Pre-existing tests: fixture-only old → new inventory

| File / test | Old fixture or assertion | New fixture or assertion | Class |
| --- | --- | --- | --- |
| `IndexingJobsBridgeWiringTest.liveTerminalOutcomesOutsideWindowKeepScanAttributionWithoutSnapshotReplay` | One mock window callback receives the same retry/terminal sequence; 4 events, scan-42, no snapshot replay | The identical frames fan out to registered view/outcome callbacks; all assertions unchanged | Adaptation: split |
| Same file, `terminalLedgerObserversCannotBlockJobDeliveryAndSnapshotsNeverReplayThem` | One callback, blocked ledger observer, 250 ms delivery assertion; 2 scan-tagged events; no replay | Identical faults, deadlines and assertions; same frames supplied to both stream registrations | Adaptation: split |
| `IndexingJobsSseIsolationTest.stalledSseObserverCannotOverflowTheHeadHandoffDuringTwoThousandRowBurst` | Mock row stream; held SSE observer, same paced 2,000-row burst and cursor/thread assertions | Empty terminal registration added to the mock; the row-stream fault and every assertion unchanged | Adaptation: split |
| `TestKnowledgeClient` | Explicitly unsupported job-stream test fixture | New abstract outcome-port signature also explicitly unsupported; all existing method bodies unchanged | Adaptation: signature |

All **24** Wiring assertion calls and all **5** SSE-isolation assertion calls match checkpoint
8eb70d443 after whitespace normalization (`unchanged-assertions.json`). No expected value, tag,
condition or pre-existing input sequence was weakened or deleted. No Obsolete reclassification.

## Commands and execution evidence

- Narrow batched Gradle invocation under `js-gradle-slot`, with `--continue`, passed in 21 seconds:
  `./gradlew spotlessApply :modules:app-engine:test --tests '*IndexingJobs*'
  :modules:app-services:test --tests '*IndexingJobsBridgeWiringTest' --tests '*IndexingJobsSubscriptionFenceTest'
  :modules:worker-services:test --tests '*IndexingJobs*' :modules:ui:installDist -PskipWebBuild=true`.
- After strengthening the new identity/publication/rollup oracle, only that affected class was
  rerun with Spotless: BUILD SUCCESSFUL in 4 seconds.
- The entire unchanged evidence-runner lifecycle ran under `js-gradle-slot`:
  `queue-scale-terminal-overflow.json`: new Q17 test passes candidate, assertion fails on 8eb70d443.
- `queue-scale-terminal-fixtures.json` refreshes the modified fixtures' evidence against e8903c54b:
  candidate checks pass; SSE isolation/typed cause and the blocked-ledger check fail assertions;
  the four-outcome/no-replay preservation check passes before. The producer exits 1 because that
  intentional preservation check passes before, while its receipt records the expected distinction.
- That fixture run reuses the already accepted constructor-injection patch and typed-cause overlay.
  The new `queue-scale-terminal-api-compat.patch` supplies only the new mock method signature;
  its body throws and is never called by the old product. It adds no delivery repair to the base.
- Web source/tests are unchanged from checkpoint 8eb70d443; their existing deterministic receipts
  remain valid. The production build and Chrome checks above exercise the final web code.

The one F1 database (725,712,896 bytes at deletion), its WAL/SHM/indexes/corpus and all small probe
fixtures were removed after capture. Every started Engine, debugger, asset server, Node driver and
Chrome exited. No Vite server was started. `~/.config/justsearch` is absent. The evidence producer
removed its before worktrees. No commit was made; only independent changeset acceptance remains.
