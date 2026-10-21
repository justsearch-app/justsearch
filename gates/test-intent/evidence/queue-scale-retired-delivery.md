# Retired indexing-jobs subscription repair

Task t-20261020-540095; builder session 01a1562b-b2e9-7510-832f-04ea6ad6da28.
Working tree based on e1c4d720b. Before revision for the new assertions: 11a8ff327.
All existing acceptance/rejection records are preserved. No acceptance is authored by the builder.

The head fences frames, errors, completion and late-returned handles by their producing subscription.
Application and publication are serialized; retirement holds only a separate lifecycle monitor and
never waits for listener delivery. The frame's subscription is checked at state application and
before listener publication. Out-of-order frames are rejected before state-presence changes or
terminal forwarding. Since worker wire sequences restart per subscription, a replacement snapshot
rebases its local sequence into the head cursor; following deltas retain their relative order.
The worker wire protocol is unchanged. The API contract map records the monotonic head cursor.

Other consumers inspected:

- SQLite change-feed: its COW iterator can retain a removed subscriber. A per-subscription active
  flag now drops those captured callbacks and closing the feed skips the remaining batch.
  Cancellation still uses tryLock for cache cleanup and never waits for the queue writer.
- BoundedHandoff: rechecks closure at deliverFrame, immediately before invoking the consumer.
  The regression additionally pauses *after* that check to prove the head's identity fence is
  independently necessary.
- The other handoff subscriber is scan-root progress. Its operation owns one finite flow; it drains
  before completing and reports delivery failure if draining times out. It does not replace a
  subscription into the head mirror. The consumer-boundary close check protects this flow too.
- WorkerIngestService's subscription owns its emit lock, cancellation state and close flag;
  callbacks already check cancellation inside the emission monitor. No worker-services change.

## Durable tests

| File | New coverage |
|---|---|
| IndexingJobsRetiredDeliveryTest | Real small SQLite queue, service and handoff: pause old PROCESSING after the handoff check, overflow, reconnect, advance/finish current claims, resume old callback; head/store rows, cursor and presence signal agree. |
| IndexingJobsSubscriptionFenceTest | Higher-sequence retired callback, late error/completion, active out-of-order snapshot/delta/presence and terminal replay, local cursor restart, synchronous failure's late handle, and closed producer callbacks. |
| IndexingJobsSubscriberRetirementTest | Cancellation and feed closure while a captured COW batch is being delivered. |
| BoundedHandoffTest (one added method) | Consumer-entry close check after the outer drain check. |

All nine cases are durable. No pre-existing assertion, input, precondition or tag is rewritten.
The added handoff test initially missed an assertion import. The new late-handle test initially
asserted before asynchronous close had finished; it now waits for the close callback, with its
exact close-count assertions unchanged. The final affected-class batch and installDist passed.
The first broader three-module batch recorded the import failure and the new fixture race;
its app-services run completed 3984 tests, one failed, 11 skipped. Those are not claimed as a pass.

## Installed Engine reproduction

The supplied engine_probe.py was copied into task temp; only its BASE constant was redirected
there. probe-source.json records its original path/hash. JdiPause.java was compiled byte-identical
with JDK 25 and an explicit task java.io.tmpdir. The installed app-engine/app-services/indexer-worker
jars were hash-compared with the current module jars before starting the probe.

Command: `python3 engine_probe.py --root <checkout> --label fenced-candidate --mode handoff-processing --heap 1g --count 3000`.

The probe passed after a real typed handoff overflow, with 3005 jobs DONE. It paused a real
PROCESSING delivery at BoundedHandoff.deliverFrame, let indexing drain, then resumed that old call.
Its first post-release sample (the supplied probe deliberately waits 5.2s) matched the empty store
window at 5.244673s since the last observed database state change, with snapshotSeq94940. This
sample is an observation after the fault, not a measurement of the earliest convergence time.

Raw captures are in queue-scale-retired-engine/: driver.log, debugger.log, identity.json,
convergence.json, snapshot-frames.ndjson, overflow-lines.txt, throughput.json and final status.
Engine events are retained too. The probe's cleanup.json records Engine exit143, debugger stopped,
and machineConfigAbsent=true. JUSTSEARCH_HOME and JUSTSEARCH_DATA_DIR both pointed into task temp.
The generated corpus, index and jobs.db were removed after cleanup. No million-row database was
created for this repair.

All Gradle work is under js-gradle-slot with its default wait. No JAVA_TOOL_OPTIONS is set.
The unchanged evidence runner's entire lifecycle is under the slot, as authorized for this gate;
its own before worktree is removed by the runner. Ordinary Gradle tasks use js-gradle-slot ./gradlew.

## Fail-before receipt

queue-scale-retired-subscription.json was generated by the unchanged evidence runner, with
11a8ff327 as --before and no production overlays or compatibility patches. All four selected check
IDs passed on the candidate (nine cases). The base failed all nine cases by assertion: app-engine
2/2, app-services5/5, indexer-worker2/2. The real-flow witness reported the exact defect:
expected CachedSnapshot[seq=33,items=[]], observed CachedSnapshot[seq=2,items=[PROCESSING]].
The runner removed its temporary before worktree. No million-row measurement was rerun.

## Affected-class preservation and installed build

The final batched invocation passed (BUILD SUCCESSFUL,33s):

```sh
js-gradle-slot ./gradlew spotlessApply \
  :modules:app-engine:test --tests '*BoundedHandoffTest' --tests '*IndexingJobs*' --tests '*ScanRoot*' \
  :modules:app-services:test --tests '*IndexingJobs*' --tests '*StatusStateProducersTest' \
  :modules:indexer-worker:test --tests '*IndexingJobs*' --tests '*QueueProjectionCommitTest' --tests '*SqliteJobQueueConformanceTest' \
  :modules:ui:installDist --continue -PskipWebBuild=true
```

TMPDIR and JAVA_OPTS's java.io.tmpdir both targeted the task temp folder; JAVA_TOOL_OPTIONS was
unset. The final gate covers41flagged items; its sole remaining problem is acceptance-stale.
The nonblocking reconsider warning remains4disagreements/4audited PRs over28days on origin/main.
