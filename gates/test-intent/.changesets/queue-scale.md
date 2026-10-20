---
schema: test-intent.v1
task: t-20261020-540095
author-role: builder
author-session: 01a1562b-b2e9-7510-832f-04ea6ad6da28
---

# Test-intent entries

Author: builder Codex session 01a1562b-b2e9-7510-832f-04ea6ad6da28. Scope is the final branch diff against merge-base c00a0cb24 with origin/main, not intermediate commit diffs. The user adopted scenarios Q1–Q27 on2026-10-20: “i agree, proceed accordingly”. Every scenario source below is **agent-drafted, owner-adopted**; no brief text is attributed as owner words. Choices are **agent choice** under the separately quoted delegation. Q20 uses its aggregate-queued amendment; Q25–Q27 are included. Q6 retains the agreed whole-call limit: pollPending(16) under10ms p95, including its durable commit. Selection-only timing is additional reporting, not a substitute threshold. Q24 packaged-system verification belongs to the independent verifier, not this test-intent delivery.

For modified files with both retired oracles and added checks, separate entries name the portions. All full-table and per-file-PENDING state/input changes are Obsolete, not hidden as adaptations. Old→new assertion inventories are attached to those entries; remaining original assertions stay unchanged. The two Adaptation entries identify exactly unchanged assertions and old/new transport encoding. Repaired checks require an assertion fail-before; new instrumentation and preservation checks are explicitly identified and do not pretend a missing old API/table was a defect.

Execution protocol and compatibility scaffolding: gates/test-intent/evidence/queue-scale-execution.md. Evidence files are produced only by the unchanged run-evidence.mjs, with its whole lifecycle under js-gradle-slot; the builder authors no acceptance record and preserves the independent records below.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RemoteIndexingJobsBridgeCapacityTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/IndexingJobsBridgeResubscribeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueProjectionCommitTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SqliteJobQueueConformanceTest.java",
        "modules/ui-web/src/shell-v0/strategies/subscriptionStrategy.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingJobsSubstrateIntegrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q1",
          "quote": "Heap after boot and SSE subscribe on F1 and F2: live job-mirror objects (view rows, job rows, map entries, row-identity cache) total under 5 MB on both worker and head; boot and reconnect, including concurrent snapshot construction, complete with the Engine at a 1 GiB heap without OOM (transient peak, not only retained heap).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q9",
          "quote": "Exact counts: after 100k random operations (enqueue, re-enqueue/replacement, claim, success, defer, fail, retry, recovery, clear-failed, delete, rollback) and a reopen, the kept per-state counts and byte totals (including NULL sizes and RETRY_EXHAUSTED) equal direct aggregates, and each row's retry metadata, failure details, ownership and scan attribution are unchanged by the new bookkeeping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q14",
          "quote": "A bounded view says it is bounded: the rail and Resource view show accurate queue totals from status, and never present the window as the complete queue, including when PROCESSING or FAILED rows fall outside it. The API contract map records the windowed snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q17",
          "quote": "The activity ledger and scan rollups keep today's terminal-event semantics (including later outcomes after retry or re-enqueue), also for rows never in the view, with no replay on reconnect.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q21",
          "quote": "/api/status, fresh status, /api/knowledge/status and the MCP status tool agree during indexing and restart; stale/unavailable reporting and corpus document counts keep their meaning (not derived from the window).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q22",
          "quote": "Large cleanup or root deletion on F1 stays within memory and still sends correct delete notifications.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Create the job_counts table, count-maintenance triggers and three claim/readiness indexes in the unreleased jobs.db v23 migration, and retain all existing reset/carry field semantics.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Skip materialize when there are no subscribers.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Re-enqueue admissions use UPSERT and emit a PENDING Update instead of an Insert, retaining the full row reset/carry semantics.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0028 path hashing/resolution retained; no decision record requires a complete jobs-table snapshot. The prior per-file PENDING rail contract is superseded specifically by owner-adopted amended Q20, not by the implementation.",
      "oldToNew": [
        {
          "test": "RemoteIndexingJobsBridgeCapacityTest.capacityRecovery",
          "old": "snapshot states PENDING then DONE; latestSnapshot first state DONE",
          "new": "PROCESSING then FAILED; latestSnapshot first state FAILED. Same seq2/call-count2/automatic recovery assertions retained."
        },
        {
          "test": "IndexingJobsBridgeResubscribeTest.snapshot helper",
          "old": "PENDING row in snapshots",
          "new": "PROCESSING row. Retry delays, attempts, ownership, cancellation and nonempty identity assertions retained; PENDING visibility precondition retired."
        },
        {
          "test": "RemoteIndexingJobsBridgeTest.snapshotFrameDelivered",
          "old": "hash-a PENDING and hash-b PROCESSING are visible",
          "new": "hash-a FAILED and hash-b PROCESSING visible; same payload, cursor and scan assertions"
        },
        {
          "test": "RemoteIndexingJobsBridgeTest.latestSnapshotReflectsDeltasNotFrozenSubscribeTime",
          "old": "PENDING→DONE; latest snapshot has one DONE row",
          "new": "PROCESSING→DONE; cached window empty, live terminal Update still DONE with same pathHash, seq2 retained"
        },
        {
          "test": "RemoteIndexingJobsBridgeTest.deleteDeltaRemovesFromLatestSnapshot",
          "old": "PENDING snapshot row deleted→empty",
          "new": "PROCESSING snapshot row deleted→empty; same identity/deletion assertions"
        },
        {
          "test": "RemoteIndexingJobsBridgeTest.latestSnapshotPairIsCoherent",
          "old": "seq5 PENDING→seq6 DONE; pair=(6,[DONE])",
          "new": "seq5 PROCESSING→seq6 FAILED; pair=(6,[FAILED]); size1 and convenience-accessor equality retained"
        },
        {
          "test": "IndexingJobsChangeStreamTest.replacementEnqueuesRetainOnlyLiveRowIdsAndCleanupReleasesThem",
          "old": "boot cache populated from2PENDING rows;1000 replacement deltas all Insert; clearAll emits2Delete",
          "new": "boot cache empty; hydrate2FAILED window rows;1000 re-admissions emit PENDING Update (UPSERT), reverse/forward cache stay2; clear emits2knownDelete plus empty Resync; unsubscribed/closed caches empty"
        },
        {
          "test": "IndexingJobsChangeStreamTest.scanIdRoundTripsFromEnqueueToDelta",
          "old": "terminal DONE scan-42 row appears in reconnect snapshot",
          "new": "terminal delta retains scan-42; DONE absent from window; a newly claimed PROCESSING scan-42 row preserves snapshot attribution"
        },
        {
          "test": "IndexingJobsChangeStreamTest.snapshotCapturesPriorState",
          "old": "2prior PENDING enqueues in snapshot; seq>=2",
          "new": "claim both first;2PROCESSING rows in snapshot; snapshotSeq==currentSeq and no live deltas. Old snapshotSeq>=2 is explicitly retired: with no subscribers, skipped materialization emits no feed sequence for the unseen enqueues. Monotonic/coherent emitted cursors and no replay remain required."
        },
        {
          "test": "QueueProjectionCommitTest.rollbackDropsProvisionalDeltasButPreservesEarlierCommittedChunks",
          "old": "snapshot499DONE+2PENDING",
          "new": "snapshot empty; strict status499DONE+2PENDING; same499terminal Update observations and injected rollback"
        },
        {
          "test": "QueueProjectionCommitTest.reentrantMutationDeliversOriginalBatchToEverySubscriberFirst",
          "old": "insert,insert,delete,delete",
          "new": "insert,insert,resync(empty); same subscriber order and no overtaking"
        },
        {
          "test": "QueueProjectionCommitTest.reentrantSnapshotExcludesAlreadyCommittedQueuedDeltas",
          "old": "snapshot2PENDING, then2Delete",
          "new": "snapshot empty at seq2, then1empty Resync; no prior-commit replay"
        },
        {
          "test": "QueueProjectionCommitTest.concurrentSnapshotWaitsForConnectionOwner",
          "old": "snapshot size1PENDING after release",
          "new": "snapshot empty at seq1 and strict pendingCount1; same blocked-reader timeout, writer result and rollback boundaries"
        },
        {
          "test": "SqliteJobQueueConformanceTest.countAndStreamAgreeAcrossTransitions",
          "old": "queueDepth==snapshot PENDING+PROCESSING count",
          "new": "queueDepth==strict pending+processing totals; snapshot omits PENDING/DONE and shows every PROCESSING row across same lifecycle"
        },
        {
          "test": "subscriptionStrategy.test.ts TABULAR indexing resource fixtures",
          "old": "snapshot PENDING+PROCESSING; upsert PENDING→PROCESSING; delete DONE; replacement rows lack state",
          "new": "snapshot FAILED+PROCESSING; upsert FAILED→PROCESSING; delete known visible PROCESSING; replacement uses eligible states. Key/cursor/replacement assertions retained; initial visibility assertions strengthened."
        },
        {
          "test": "IndexingJobsSubstrateIntegrationTest.fullChainDeliversFrames",
          "old": "hash-a PENDING in two-row snapshot",
          "new": "hash-a FAILED in two-row snapshot; hash-b PROCESSING, hashed identity/schema/SSE assertions retained"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q14",
          "quote": "A bounded view says it is bounded: the rail and Resource view show accurate queue totals from status, and never present the window as the complete queue, including when PROCESSING or FAILED rows fall outside it. The API contract map records the windowed snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q19",
          "quote": "Browser at 700k pending: the rail shows accurate totals, renders at most a bounded number of per-file tasks, sends at most window + concurrency path-label requests, and stays responsive; label caches stay bounded over a long run.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q20",
          "quote": "Rail behavior (amended 2026-10-20, agreed by the user: waiting files show as one aggregate queued entry with the status count, see Q19; a retried task is counted there until claimed): PENDING shows queued, fresh PROCESSING runs, stale PROCESSING demotes, DONE disappears, failures stay distinguishable, retry and cancel revive or stop the task; through both standalone and multiplexed SSE.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Cap bridge label resolution at 16 simultaneous requests, the shared label LRU at 512 entries, shared in-flight requests at 216, and rendered per-file task rows at eight.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Keep per-file indexing Tasks read-only; the Resource view retains cancel/retry operations.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0028 path hashing/resolution retained; no decision record requires a complete jobs-table snapshot. The prior per-file PENDING rail contract is superseded specifically by owner-adopted amended Q20, not by the implementation.",
      "oldToNew": [
        {
          "test": "PENDING read-only task",
          "old": "h1 queued, not cancellable, label contains docs",
          "new": "h1 absent and no per-file tasks; read-only and docs label assertions moved to existing fresh PROCESSING test; real queued count checked through both transports in indexingJobsQueueRail.test.ts"
        },
        {
          "test": "departure h3",
          "old": "PENDING→queued→empty",
          "new": "PROCESSING→running→empty"
        },
        {
          "test": "running→FAILED h5",
          "old": "PENDING fixture then one failed task",
          "new": "PROCESSING fixture explicitly running then same one failed task"
        },
        {
          "test": "reset/reappear h6",
          "old": "PENDING→empty→PENDING queued again",
          "new": "PROCESSING→empty→PROCESSING running again"
        },
        {
          "test": "FAILED→PENDING retry h9",
          "old": "same per-file task becomes queued",
          "new": "per-file task disappears; aggregate increments until claim (new both-stream tests)"
        },
        {
          "test": "unresolved hx label",
          "old": "PENDING receives short-hash fallback",
          "new": "PROCESSING receives same short-hash fallback; pending paths no longer resolved"
        },
        {
          "test": "standalone snapshot a1b2 and reappear f6",
          "old": "PENDING→queued for both",
          "new": "fresh PROCESSING→running for both; DONE/delete/reset lifecycle still checked"
        },
        {
          "test": "pooled z1",
          "old": "PENDING→queued",
          "new": "fresh PROCESSING→running; shared EventSource/second observer assertions unchanged"
        },
        {
          "test": "multiplexed mux1",
          "old": "PENDING→queued",
          "new": "fresh PROCESSING→running; mux dispatch/cleanup assertions unchanged"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueMigrationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q12",
          "quote": "Upgrade: a v0.3.0 jobs.db (user_version 13) and a current jobs.db, each with 1M rows, migrate; counts match; claim and status answers and every row's saved fields equal the pre-migration ones; migration time recorded; a crash mid-migration leaves the version unchanged and the next boot completes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q13",
          "quote": "An older binary opening the migrated jobs.db refuses it with the existing message.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Create the job_counts table, count-maintenance triggers and three claim/readiness indexes in the unreleased jobs.db v23 migration, and retain all existing reset/carry field semantics.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. ADR-0028 path hashing/resolution retained; no decision record requires a complete jobs-table snapshot. The prior per-file PENDING rail contract is superseded specifically by owner-adopted amended Q20, not by the implementation.",
      "oldToNew": [
        {
          "test": "JobQueueMigrationTest.migratesV11ToCurrentPreservingQueueAndIdentityRowsAndRefusesTheNextVersion",
          "old": "assertEquals(22,SqliteSchema.TARGET_VERSION)",
          "new": "assertEquals(23,SqliteSchema.TARGET_VERSION); user_version equality, document_identity_import schema/columns and saved-state checks unchanged"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Same logical all-queue states: first {PENDING}, count-only additions/removal remain {PENDING}, then {PENDING,PROCESSING}. Encode state presence separately because PENDING rows are outside the view. Both assertEquals(before,statusCounter()) and assertEquals(before+1,statusCounter()) remain byte-for-byte unchanged; fault/timeout/default tags unchanged. Also statusCounterTracksJobsAndNotReadOnlyReattachment: same PENDING snapshot, identical reattachment, PROCESSING transition; three assertEquals(before+1/before+1/before+2) unchanged, metadata encoding added.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Compute the full all-queue state-presence set on snapshots, re-read only departed states on commits, and carry changed presence on existing frames as a coalescing barrier.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-current.json",
      "beforeEvidence": "gates/test-intent/evidence/queue-scale-original-base.json"
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueProjectionCommitTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Only subscriberClaimSurvivesEnqueueAndCompletionBookkeeping: admission observer accepts Insert OR PENDING Update after INSERT→UPSERT. Same enqueue, exact issued claim, stale-completion/ownership and ledger expectations; every assertion in this method unchanged. Other changed methods are separately Obsolete above.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q9",
          "quote": "Exact counts: after 100k random operations (enqueue, re-enqueue/replacement, claim, success, defer, fail, retry, recovery, clear-failed, delete, rollback) and a reopen, the kept per-state counts and byte totals (including NULL sizes and RETRY_EXHAUSTED) equal direct aggregates, and each row's retry metadata, failure details, ownership and scan attribution are unchanged by the new bookkeeping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q11",
          "quote": "Claim ownership: replacing a path while its old claim is active, a stale completion, unfinished-claim return and restart give no duplicate or lost work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Create the job_counts table, count-maintenance triggers and three claim/readiness indexes in the unreleased jobs.db v23 migration, and retain all existing reset/carry field semantics.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Re-enqueue admissions use UPSERT and emit a PENDING Update instead of an Insert, retaining the full row reset/carry semantics.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-current.json",
      "beforeEvidence": "gates/test-intent/evidence/queue-scale-original-base.json"
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsCoalescingTest.java"
      ],
      "class": "New",
      "behaviour": "New statePresenceFramesAreBarriersAndSurviveLaterHeartbeats: presence-bearing insert/delete stay as barriers while intermediate same-key heartbeats coalesce; seq1,3,4 and explicit empty presence survive. All pre-existing assertions unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Compute the full all-queue state-presence set on snapshots, re-read only departed states on commits, and carry changed presence on existing frames as a coalescing barrier.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsCoalescingTest.java#statePresenceFramesAreBarriersAndSurviveLaterHeartbeats"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsWindowFlowTest.java"
      ],
      "class": "New",
      "behaviour": "Real service/head convergence during reentrant snapshot writes and >256 deltas; bounded stalled handoff never gates claims, reconnect recovers; real worker carries presence on every snapshot and state-changing commit. Stalled-subscriber part already passed base; window/presence expectations repaired.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q16",
          "quote": "A stalled SSE subscriber does not stop claims or indexing; buffers stay bounded and the subscriber recovers on reconnect.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Compute the full all-queue state-presence set on snapshots, re-read only departed states on commits, and carry changed presence on existing frames as a coalescing barrier.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsWindowFlowTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java"
      ],
      "class": "New",
      "behaviour": "New outside-window appearance/last removal changes status, count/heartbeat/identical reconnect do not; sustained600failure churn stays at200plusprocessing and reload/reconnect match without PENDING/DONE phantoms. Old full-snapshot oracles separately Obsolete.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q1",
          "quote": "Heap after boot and SSE subscribe on F1 and F2: live job-mirror objects (view rows, job rows, map entries, row-identity cache) total under 5 MB on both worker and head; boot and reconnect, including concurrent snapshot construction, complete with the Engine at a 1 GiB heap without OOM (transient peak, not only retained heap).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q2",
          "quote": "Per-change cost: 10k state changes applied to the mirror with F1 loaded take under 1 s total; sustained churn beyond the window size keeps retained objects bounded (no gradual regrowth).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Compute the full all-queue state-presence set on snapshots, re-read only departed states on commits, and carry changed presence on existing frames as a coalescing barrier.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java#statePresenceOutsideWindowInvalidatesButHeartbeatCountsAndReconnectDoNot",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java#sustainedChurnAndReconnectConvergeToBoundedWindowWithoutPhantoms"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamTest.java"
      ],
      "class": "New",
      "behaviour": "New unknown-delete coalescing/rollback, all-processing+newest200failure snapshot, reentrant snapshot/commit convergence, cancellation independent of writer lock, and bounded identity churn. Cancellation get deadline remains1s and now uses assertDoesNotThrow. That wait already succeeds before; the fail-before is the final empty row-identity-cache assertion after cancellation. This is a bounded-cache repair, not a newly claimed cancellation-deadlock repair. Existing rewritten methods separately Obsolete.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q1",
          "quote": "Heap after boot and SSE subscribe on F1 and F2: live job-mirror objects (view rows, job rows, map entries, row-identity cache) total under 5 MB on both worker and head; boot and reconnect, including concurrent snapshot construction, complete with the Engine at a 1 GiB heap without OOM (transient peak, not only retained heap).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q2",
          "quote": "Per-change cost: 10k state changes applied to the mirror with F1 loaded take under 1 s total; sustained churn beyond the window size keeps retained objects bounded (no gradual regrowth).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q16",
          "quote": "A stalled SSE subscriber does not stop claims or indexing; buffers stay bounded and the subscriber recovers on reconnect.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q22",
          "quote": "Large cleanup or root deletion on F1 stays within memory and still sends correct delete notifications.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Skip materialize when there are no subscribers, and clear the bounded row-identity cache when its last subscriber closes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamTest.java#unknownDeletesCoalesceAndRollbackDoesNotResync",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamTest.java#snapshotKeepsAllProcessingAndOnlyNewestTwoHundredFailures",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamTest.java#reentrantSubscribeAndWritesConvergeWithoutReplayingEarlierCommit",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamTest.java#cancellationNeverWaitsForTheQueueWriterLock",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamTest.java#distinctFailureChurnDoesNotRegrowTheIdentityCache"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsStatePresenceTest.java"
      ],
      "class": "New",
      "behaviour": "Snapshot includes all-queue presence; commits publish exactly changed presence, no heartbeat/count-only/reconnect invalidation, replacement/ignored insert/rollback remain correct. Indexed SEARCH existence per state; recheck only departed states, connection-local tracking adds no persistent trigger.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Compute the full all-queue state-presence set on snapshots, re-read only departed states on commits, and carry changed presence on existing frames as a coalescing barrier.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsStatePresenceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/IndexingJobsRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Successful snapshots reset delay/log attempts; typed overflow first retry immediate (including negative monotonic clock), repeated loops paced. The SSE-isolation checks now have a separate entry with refreshed execution evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q25",
          "quote": "After a successful snapshot, the head's resubscribe backoff starts again from its first step: an overflow following a good snapshot is retried within the first delay (250 ms), not after a doubled delay; the logged attempt count and the delay agree. Fails today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q26",
          "quote": "An overflow of the head's indexing-jobs handoff (a typed cause, not exception-text matching) resubscribes immediately and bounded (no tight loop under sustained overload); real indexing of a 3k-file corpus ends with the head matching jobs.db within 5 s of the last commit. Fails today (up to 30 s stale).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q27",
          "quote": "SSE serialization and sending do not run on the thread that drains the handoff: a slow or stalled SSE observer does not make the handoff overflow during a 2000-row enqueue burst.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Reset ordinary reconnect backoff on a successful snapshot; allow the first typed-overflow recovery immediately and pace repeated overflow recoveries by 250 ms.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-e890.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/IndexingJobsRecoveryTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ClaimCursorInvalidationTest.java"
      ],
      "class": "New",
      "behaviour": "Eight cases: future cursor/current enqueue, re-enqueue lowering time, direct backwards timestamp and equal-time earlier path, retry becoming ready, stale-owner completion/batch/return expose replacement; oldest eligible key always claimed, active ownership still fences.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q7",
          "quote": "Claim order: eligible rows come out oldest first by (last_updated, path).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q8",
          "quote": "A retry_after passing with no queue write makes the row claimable and counts it as ready, and backfill then yields to ingestion; while every pending row is still in backoff, backfill does not yield for them.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q11",
          "quote": "Claim ownership: replacing a path while its old claim is active, a stale completion, unfinished-claim return and restart give no duplicate or lost work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "The cursor fix uses connection-local notifications and retains no job rows.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Use a skip budget of limit + 256 candidates and a (last_updated, path) keyset cursor that wraps at exhaustion.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-e890.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ClaimCursorInvalidationTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsReplaceTest.java"
      ],
      "class": "New",
      "behaviour": "On a queue-owned connection, raw SQL REPLACE immediately keeps state counts/known bytes/unknown sizes exact through every state, NULL/known sizes and rollback.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q9",
          "quote": "Exact counts: after 100k random operations (enqueue, re-enqueue/replacement, claim, success, defer, fail, retry, recovery, clear-failed, delete, rollback) and a reopen, the kept per-state counts and byte totals (including NULL sizes and RETRY_EXHAUSTED) equal direct aggregates, and each row's retry metadata, failure details, ownership and scan attribution are unchanged by the new bookkeeping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "The count fix enables recursive triggers on queue-owned connections.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-e890.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsReplaceTest.java#plainSqlReplaceKeepsCountsAndBytesExactWithoutReopen"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsReplaceTest.java"
      ],
      "class": "New",
      "behaviour": "Unmanaged writer with recursion explicitly off creates drift; existing open reconciliation repairs counts without modifying rows, logs once, clean second open does not warn again. Preservation: already passed e890; not a new repair claim.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q9",
          "quote": "Exact counts: after 100k random operations (enqueue, re-enqueue/replacement, claim, success, defer, fail, retry, recovery, clear-failed, delete, rollback) and a reopen, the kept per-state counts and byte totals (including NULL sizes and RETRY_EXHAUSTED) equal direct aggregates, and each row's retry metadata, failure details, ownership and scan attribution are unchanged by the new bookkeeping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q12",
          "quote": "Upgrade: a v0.3.0 jobs.db (user_version 13) and a current jobs.db, each with 1M rows, migrate; counts match; claim and status answers and every row's saved fields equal the pre-migration ones; migration time recorded; a crash mid-migration leaves the version unchanged and the next boot completes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "The count fix enables recursive triggers on queue-owned connections.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-e890.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsReplaceTest.java#reopenRepairsUnmanagedWriterDriftOnceWithoutChangingRows"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java"
      ],
      "class": "New",
      "behaviour": "New counter instrumentation and migration mechanism preserve exact direct aggregates across random2k/100k operations, all former REPLACE admission paths, row reset/carry metadata/ownership, NULL sizes, terminal states, rollback/reopen and drift repair. V23 injected failure rolls back table/triggers/3indexes/version and next open completes. Existing contract preservation using newly introduced job_counts instrumentation, not claiming the uninstrumented base had a job_counts table.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q9",
          "quote": "Exact counts: after 100k random operations (enqueue, re-enqueue/replacement, claim, success, defer, fail, retry, recovery, clear-failed, delete, rollback) and a reopen, the kept per-state counts and byte totals (including NULL sizes and RETRY_EXHAUSTED) equal direct aggregates, and each row's retry metadata, failure details, ownership and scan attribution are unchanged by the new bookkeeping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q10",
          "quote": "Strict reads fail closed: an injected database read failure in jobStateCountsStrict never certifies an empty, healthy or drained queue.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q11",
          "quote": "Claim ownership: replacing a path while its old claim is active, a stale completion, unfinished-claim return and restart give no duplicate or lost work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q12",
          "quote": "Upgrade: a v0.3.0 jobs.db (user_version 13) and a current jobs.db, each with 1M rows, migrate; counts match; claim and status answers and every row's saved fields equal the pre-migration ones; migration time recorded; a crash mid-migration leaves the version unchanged and the next boot completes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q13",
          "quote": "An older binary opening the migrated jobs.db refuses it with the existing message.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Create the job_counts table, count-maintenance triggers and three claim/readiness indexes in the unreleased jobs.db v23 migration, and retain all existing reset/carry field semantics.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "The count fix enables recursive triggers on queue-owned connections.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Keep million-row fixture, heap, latency and 100k-operation measurements behind the evidence tag, using an instrumentation agent, paired selection/whole-poll timings, and an explicit ten-minute evidence deadline.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-harness-current.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobCountsMigrationTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueryPlansTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobReadinessAndClaimTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopClaimBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "Status and both claim sources use indexed SEARCH, no jobs scan/ordered-source temp sort; ready/deadline passage, backoff-only backfill, bounded denied-prefix progress/wrap, walk authority per candidate, progress invalidation, source merge and UTF8 binary path ties; budget-stopped empty poll repeats before idle/backfill.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q4",
          "quote": "Status reads (jobStateCounts, jobStateCountsStrict, queueDepth, pendingBytes, failureSummary) under 5 ms p95 warm on F1 and F2; no query plan scans the whole jobs table.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q5",
          "quote": "hasPendingIngestWork under 1 ms with only retry-waiting rows and with ready rows, and correct in both.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q6",
          "quote": "Claim: pollPending(16) under 10 ms p95 on F1 with the denied and retry-waiting head, and with 100k rows sharing one last_updated timestamp; repeated polls drain every eligible row with no starvation; denied rows are never claimed (authority not bypassed).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q7",
          "quote": "Claim order: eligible rows come out oldest first by (last_updated, path).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q8",
          "quote": "A retry_after passing with no queue write makes the row claimable and counts it as ready, and backfill then yields to ingestion; while every pending row is still in backoff, backfill does not yield for them.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q10",
          "quote": "Strict reads fail closed: an injected database read failure in jobStateCountsStrict never certifies an empty, healthy or drained queue.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q11",
          "quote": "Claim ownership: replacing a path while its old claim is active, a stale completion, unfinished-claim return and restart give no duplicate or lost work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Use a skip budget of limit + 256 candidates and a (last_updated, path) keyset cursor that wraps at exhaustion.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueryPlansTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobReadinessAndClaimTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopClaimBudgetTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexingJobsSnapshotOrderTest.java"
      ],
      "class": "New",
      "behaviour": "Two new tests: resync serializes as a snapshot with causal sequence and no terminal replay; full snapshot/changed-commit presence metadata distinguishes explicit empty from unchanged. Existing snapshot-before-deltas tests untouched.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q17",
          "quote": "The activity ledger and scan rollups keep today's terminal-event semantics (including later outcomes after retry or re-enqueue), also for rows never in the view, with no replay on reconnect.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Compute the full all-queue state-presence set on snapshots, re-read only departed states on commits, and carry changed presence on existing frames as a coalescing barrier.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexingJobsSnapshotOrderTest.java#resyncIsASnapshotAndRetainsCausalOrderWithoutReplayingTerminalDeltas",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexingJobsSnapshotOrderTest.java#presenceMetadataUsesExistingFramesAndDistinguishesEmptyFromUnchanged"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiringTest.java"
      ],
      "class": "New",
      "behaviour": "New liveTerminalOutcomesOutsideWindowKeepScanAttributionWithoutSnapshotReplay: four later terminal outcomes after retry/re-enqueue retain scan-42 even outside view; reconnect snapshot does not replay. Existing terminal classification assertions untouched. Owner-supplied channel fixture only supports async delivery. Preservation, already passed old synchronous path. Fixture adaptation: identical frames fan out to the registered row-view and terminal-outcome streams; all original assertions and fault injection are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q17",
          "quote": "The activity ledger and scan rollups keep today's terminal-event semantics (including later outcomes after retry or re-enqueue), also for rows never in the view, with no replay on reconnect.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q23",
          "quote": "Windows drive and UNC paths keep their identities through replacement, deletion, reload and label resolution; feed payloads stay hashed.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-terminal-fixtures.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiringTest.java#liveTerminalOutcomesOutsideWindowKeepScanAttributionWithoutSnapshotReplay"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiringTest.java"
      ],
      "class": "New",
      "behaviour": "New terminalLedgerObserversCannotBlockJobDeliveryAndSnapshotsNeverReplayThem: blocked ledger SSE observer cannot stall terminal acceptance, both scan-tagged outcomes stored once, snapshot no replay. Future.get1s is wrapped in assertDoesNotThrow and strengthened to250ms below the old synchronous1s delivery bound to avoid a race and classify timeout as assertion; release/scan/no-replay assertions unchanged. Fixture adaptation: identical frames fan out to the registered row-view and terminal-outcome streams; all original assertions and fault injection are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q17",
          "quote": "The activity ledger and scan rollups keep today's terminal-event semantics (including later outcomes after retry or re-enqueue), also for rows never in the view, with no replay on reconnect.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q27",
          "quote": "SSE serialization and sending do not run on the thread that drains the handoff: a slow or stalled SSE observer does not make the handoff overflow during a 2000-row enqueue burst.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Use a 250 ms assertion deadline for the terminal-ledger delivery check, below the pre-existing one-second synchronous-delivery bound, and wrap the Future wait with assertDoesNotThrow.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-terminal-fixtures.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiringTest.java#terminalLedgerObserversCannotBlockJobDeliveryAndSnapshotsNeverReplayThem"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ResourceView.render.test.ts",
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.test.ts",
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts"
      ],
      "class": "New",
      "behaviour": "Appended Resource window notice/exact outside-window totals/unavailable status; bounded long-run label LRU retains Windows/UNC identities; standalone/multiplexed aggregate700k queued and retry-until-claim, fresh/stale processing, DONE/delete/cancel/resync, distinct failures; max8file rows, bounded outstanding requests, ignore departed late replies, prune long-run labels. Existing Resource/resolver assertions unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q14",
          "quote": "A bounded view says it is bounded: the rail and Resource view show accurate queue totals from status, and never present the window as the complete queue, including when PROCESSING or FAILED rows fall outside it. The API contract map records the windowed snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q19",
          "quote": "Browser at 700k pending: the rail shows accurate totals, renders at most a bounded number of per-file tasks, sends at most window + concurrency path-label requests, and stays responsive; label caches stay bounded over a long run.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q20",
          "quote": "Rail behavior (amended 2026-10-20, agreed by the user: waiting files show as one aggregate queued entry with the status count, see Q19; a retried task is counted there until claimed): PENDING shows queued, fresh PROCESSING runs, stale PROCESSING demotes, DONE disappears, failures stay distinguishable, retry and cancel revive or stop the task; through both standalone and multiplexed SSE.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q21",
          "quote": "/api/status, fresh status, /api/knowledge/status and the MCP status tool agree during indexing and restart; stale/unavailable reporting and corpus document counts keep their meaning (not derived from the window).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q23",
          "quote": "Windows drive and UNC paths keep their identities through replacement, deletion, reload and label resolution; feed payloads stay hashed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Cap bridge label resolution at 16 simultaneous requests, the shared label LRU at 512 entries, shared in-flight requests at 216, and rendered per-file task rows at eight.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-e890.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ResourceView.render.test.ts#names the bounded window and reads totals outside it from status",
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.test.ts#bounds the label cache over a long run, retaining recent Windows and UNC identities",
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingQueueCounts.test.ts"
      ],
      "class": "New",
      "behaviour": "New pure selector tests preserve worker-reported exact counts independent of row window and reject hard-zero UNAVAILABLE/NOT_STARTED/DEGRADED/missing-worker and missing aggregates. New helper API did not exist on base; repaired observable Resource/rail behavior has separate fail-before evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q14",
          "quote": "A bounded view says it is bounded: the rail and Resource view show accurate queue totals from status, and never present the window as the complete queue, including when PROCESSING or FAILED rows fall outside it. The API contract map records the windowed snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q19",
          "quote": "Browser at 700k pending: the rail shows accurate totals, renders at most a bounded number of per-file tasks, sends at most window + concurrency path-label requests, and stays responsive; label caches stay bounded over a long run.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q21",
          "quote": "/api/status, fresh status, /api/knowledge/status and the MCP status tool agree during indexing and restart; stale/unavailable reporting and corpus document counts keep their meaning (not derived from the window).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-current.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingQueueCounts.test.ts"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/strategies/indexingJobsWindow.test.ts"
      ],
      "class": "New",
      "behaviour": "Live sequence equals reload with retry-to-PENDING, DONE, delete, refresh ordering, eviction; preserve every processing row, newest200failures, immutable previous reducer states, replace-on-resync and generic resources unaffected.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q14",
          "quote": "A bounded view says it is bounded: the rail and Resource view show accurate queue totals from status, and never present the window as the complete queue, including when PROCESSING or FAILED rows fall outside it. The API contract map records the windowed snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-repairs-base.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/strategies/indexingJobsWindow.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java",
        "modules/indexer-worker/build.gradle.kts"
      ],
      "class": "New",
      "behaviour": "New opt-in million-row measurement selection/heap-agent task. Q1 boot/subscribed/concurrent peak at1GiB and<5MB retained; Q2 10kupdates/commits<1s and bounded churn; Q3 snapshot lock<50ms; Q4 warm status<5ms/no jobs scans; Q5 readiness<1ms; Q6 entire pollPending(16), including commit, <10ms p95 in each of F1, ties, F2,700k due,700k retry-waiting and interleaved cases; selection-only timing is additionally reported; drain640k unique ordered eligible rows with no denied claims and bounded polls. Added one current-v23 reopen/drift measurement. Stress tests stay opt-in.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q1",
          "quote": "Heap after boot and SSE subscribe on F1 and F2: live job-mirror objects (view rows, job rows, map entries, row-identity cache) total under 5 MB on both worker and head; boot and reconnect, including concurrent snapshot construction, complete with the Engine at a 1 GiB heap without OOM (transient peak, not only retained heap).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q2",
          "quote": "Per-change cost: 10k state changes applied to the mirror with F1 loaded take under 1 s total; sustained churn beyond the window size keeps retained objects bounded (no gradual regrowth).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q3",
          "quote": "Resubscribe/reconnect: the queue lock is held under 50 ms during snapshot-and-subscribe on F1.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q4",
          "quote": "Status reads (jobStateCounts, jobStateCountsStrict, queueDepth, pendingBytes, failureSummary) under 5 ms p95 warm on F1 and F2; no query plan scans the whole jobs table.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q5",
          "quote": "hasPendingIngestWork under 1 ms with only retry-waiting rows and with ready rows, and correct in both.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q6",
          "quote": "Claim: pollPending(16) under 10 ms p95 on F1 with the denied and retry-waiting head, and with 100k rows sharing one last_updated timestamp; repeated polls drain every eligible row with no starvation; denied rows are never claimed (authority not bypassed).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q12",
          "quote": "Upgrade: a v0.3.0 jobs.db (user_version 13) and a current jobs.db, each with 1M rows, migrate; counts match; claim and status answers and every row's saved fields equal the pre-migration ones; migration time recorded; a crash mid-migration leaves the version unchanged and the next boot completes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Keep million-row fixture, heap, latency and 100k-operation measurements behind the evidence tag, using an instrumentation agent, paired selection/whole-poll timings, and an explicit ten-minute evidence deadline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Use a skip budget of limit + 256 candidates and a (last_updated, path) keyset cursor that wraps at exhaustion.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q7",
          "quote": "Claim order: eligible rows come out oldest first by (last_updated, path).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Generate each million-row fixture on demand, retain only the active database, and delete it after the check.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-harness-before.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q1MirrorHeapBootSubscribeAndConcurrentReconnect",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q2TenThousandHeadChangesAndSustainedChurn",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q3SnapshotAndSubscribeQueueLock",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q4WarmStatusReadsAndPlans",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q5PendingIngestProbeReadyAndRetryWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q5PendingIngestProbeHistoryReadyAndRetryWaiting",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q6ClaimDeniedRetryHeadAndTimestampTies",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q6RetryDueWaitingAndInterleaved"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java"
      ],
      "class": "New",
      "behaviour": "New million-row v13/v22 upgrade and current-v23 reconciliation instrumentation: every saved-field digest/count/byte/status/ordered16claims equal pre-open, crash leaves version and rows unchanged, next open completes; current drift repair<60s. These preserve saved state using the new v23 bookkeeping, not a claim that old binaries already supported v23.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q9",
          "quote": "Exact counts: after 100k random operations (enqueue, re-enqueue/replacement, claim, success, defer, fail, retry, recovery, clear-failed, delete, rollback) and a reopen, the kept per-state counts and byte totals (including NULL sizes and RETRY_EXHAUSTED) equal direct aggregates, and each row's retry metadata, failure details, ownership and scan attribution are unchanged by the new bookkeeping.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q12",
          "quote": "Upgrade: a v0.3.0 jobs.db (user_version 13) and a current jobs.db, each with 1M rows, migrate; counts match; claim and status answers and every row's saved fields equal the pre-migration ones; migration time recorded; a crash mid-migration leaves the version unchanged and the next boot completes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q13",
          "quote": "An older binary opening the migrated jobs.db refuses it with the existing message.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Create the job_counts table, count-maintenance triggers and three claim/readiness indexes in the unreleased jobs.db v23 migration, and retain all existing reset/carry field semantics.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Keep million-row fixture, heap, latency and 100k-operation measurements behind the evidence tag, using an instrumentation agent, paired selection/whole-poll timings, and an explicit ten-minute evidence deadline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Generate each million-row fixture on demand, retain only the active database, and delete it after the check.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Use a 60-second budget for million-row migration/open reconciliation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-harness-current.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q12MigrationTimeSavedFieldsCountsAndRollback",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleEvidenceTest.java#q12CurrentMillionRowOpenDetectsAndRepairsDrift"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamLocaleTest.java"
      ],
      "class": "New",
      "behaviour": "Locale ar-EG formatting cannot break SQL identifiers/LIMIT literals, a FAILED row remains queryable and its feed key remains a64-character hash; isolated class loader removes test-order dependence. Preservation against base (the localization risk was introduced and fixed within this branch).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q1",
          "quote": "Heap after boot and SSE subscribe on F1 and F2: live job-mirror objects (view rows, job rows, map entries, row-identity cache) total under 5 MB on both worker and head; boot and reconnect, including concurrent snapshot construction, complete with the Engine at a 1 GiB heap without OOM (transient peak, not only retained heap).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q23",
          "quote": "Windows drive and UNC paths keep their identities through replacement, deletion, reload and label resolution; feed payloads stay hashed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "the window holds all PROCESSING rows plus the 200 most recent FAILED/RETRY_EXHAUSTED rows",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Use an isolated class loader under ar-EG FORMAT locale to check locale-independent SQL and hashed feed keys.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-current.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsChangeStreamLocaleTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueProjectionCommitTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Bind the original-base execution receipt for the exact unchanged assertions in the preceding Adaptation scopes. Original c00a StatusStateProducersTest, RemoteIndexingJobsBridgeTest.statusCounterTracksJobsAndNotReadOnlyReattachment and QueueProjectionCommitTest.subscriberClaimSurvivesEnqueueAndCompletionBookkeeping pass without overlays. Candidate counterparts also execute in queue-scale-current.json. This is the before-side receipt of those adaptations, not an additional oracle change.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q11",
          "quote": "Claim ownership: replacing a path while its old claim is active, a stale completion, unfinished-claim return and restart give no duplicate or lost work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q18",
          "quote": "Status invalidation: a state appearing only outside the window and the removal of its last row change the status signal as today; heartbeat-only updates, count-only changes and identical reconnect snapshots do not.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Compute the full all-queue state-presence set on snapshots, re-read only departed states on commits, and carry changed presence on existing frames as a coalescing barrier.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Create the job_counts table, count-maintenance triggers and three claim/readiness indexes in the unreleased jobs.db v23 migration, and retain all existing reset/carry field semantics.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-original-base.json"
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleFixtures.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleHeapAgent.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueScaleMigrationCrash.java",
        "modules/indexer-worker/src/test/resources/queue-scale-v13.sql"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q1",
          "quote": "Heap after boot and SSE subscribe on F1 and F2: live job-mirror objects (view rows, job rows, map entries, row-identity cache) total under 5 MB on both worker and head; boot and reconnect, including concurrent snapshot construction, complete with the Engine at a 1 GiB heap without OOM (transient peak, not only retained heap).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q12",
          "quote": "Upgrade: a v0.3.0 jobs.db (user_version 13) and a current jobs.db, each with 1M rows, migrate; counts match; claim and status answers and every row's saved fields equal the pre-migration ones; migration time recorded; a crash mid-migration leaves the version unchanged and the next boot completes it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Keep million-row fixture, heap, latency and 100k-operation measurements behind the evidence tag, using an instrumentation agent, paired selection/whole-poll timings, and an explicit ten-minute evidence deadline.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Supporting generator/agent/crash-child/v0.3.0 schema fixture for opt-in gate; no alternate product oracle or default-suite selection.",
        "callersAndStateOwners": "Only QueueScaleEvidenceTest and its launched child use these fixtures. SQLite owns jobs/counts; agent measures reachable sizes without materializing jobs; harness owns generated build-directory SQLite files and child identities.",
        "indirectBehaviours": "FixtureF1/F2 cardinalities/backoff/denied head and v13/v22 schema are asserted by real migration/status/claim checks. Child exits74 inside migration; parent asserts unchanged version/fields and successful next boot. Byte-identical originals remain available to review.",
        "evidence": "gates/test-intent/evidence/queue-scale-harness-current.json runs Q1/Q12; failed measurements, wrong row counts/fields, wrong exit/version or skipped checks fail. Earlier real tagged runs are retained in task temp; runner evidence carries the final source digests.",
        "unresolvedRisks": "No packaged Engine/Q24 claim; native byte accounting and SQL fixture generation are test instrumentation only."
      },
      "evidence": "gates/test-intent/evidence/queue-scale-harness-current.json"
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsRetiredDeliveryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/IndexingJobsSubscriptionFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsSubscriberRetirementTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/BoundedHandoffTest.java"
      ],
      "class": "New",
      "behaviour": "Retired-subscription repair: hold a real PROCESSING handoff after its close check, overflow/resubscribe, advance and finish current claims, then release the old callback; store window, head cursor and state-presence signal stay unchanged. Also fence higher-sequence retired callbacks, old error/completion callbacks, out-of-order active snapshots/deltas/presence and terminal replay, worker-local cursor resets, a late subscribe return after synchronous failure, completed callbacks, worker COW subscriber cancellation/feed closure, and delivery at the closed handoff consumer boundary. Only a new method/import are added to BoundedHandoffTest; all pre-existing inputs, assertions and tags are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q15",
          "quote": "Live, reloaded and reconnected clients converge: concurrent writes during snapshot, more than 256 initial deltas, a reconnect and a browser reload all end with the same rows and no phantom jobs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q26",
          "quote": "An overflow of the head's indexing-jobs handoff (a typed cause, not exception-text matching) resubscribes immediately and bounded (no tight loop under sustained overload); real indexing of a 3k-file corpus ends with the head matching jobs.db within 5 s of the last commit. Fails today (up to 30 s stale).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "fence every delivery to the subscription that produced it, so a callback from a retired or closed subscription is dropped even if it passed the close check before; and the head never applies a frame whose sequence is older than its current snapshot (the cursor never moves backward).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Rebase each replacement subscription's local wire sequence at its first snapshot so the head cursor remains monotonic without changing the worker wire protocol.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Skip retired subscribers in captured copy-on-write batches and recheck closure immediately before a handoff invokes its consumer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-retired-subscription.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsRetiredDeliveryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/IndexingJobsSubscriptionFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/IndexingJobsSubscriberRetirementTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/BoundedHandoffTest.java#closeAfterOuterDrainCheckStillFencesTheConsumerCall"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsResponsiveness.test.ts"
      ],
      "class": "New",
      "behaviour": "Retain all 216 externally mirrored processing/failure rows, publish reconciliation once, and suppress all listener churn across 100 identical projections. Reconcile only the indexing task namespace even with 10,000 unrelated live tasks; preserve remount cleanup. The unchanged deterministic oracle is supplemented by the five-minute installed production Engine/Chrome probe in queue-scale-terminal-continuity.md. No pre-existing test is rewritten.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q19",
          "quote": "Browser at 700k pending: the rail shows accurate totals, renders at most a bounded number of per-file tasks, sends at most window + concurrency path-label requests, and stays responsive; label caches stay bounded over a long run.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Keep externally mirrored terminal rows until their bounded source removes them, batch task notifications per reconciliation, and index task ids by namespace so indexing never enumerates unrelated tasks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-browser-drain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsResponsiveness.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsEnqueueBurstTest.java"
      ],
      "class": "New",
      "behaviour": "Hold a real Engine subscription delivery and submit 2,005 actual files through the real ingest client. Invisible newly inserted PENDING rows must not fill the handoff. Installed no-observer and stalled-socket-reader probes separately measure submission through all rows PENDING: zero enqueue overflows in both. Later DONE-phase overflows fall under Q26; terminal continuity across them is checked independently under Q17. All original assertions and the 256-slot capacity remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q27",
          "quote": "SSE serialization and sending do not run on the thread that drains the handoff: a slow or stalled SSE observer does not make the handoff overflow during a 2000-row enqueue burst.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Omit newly inserted PENDING rows before the head handoff unless they carry state_presence; retain all updates, terminal outcomes and state-presence barriers.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "a larger buffer alone is not a fix",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "zero overflows during the enqueue burst, with no observer and with a stalled observer",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-browser-drain.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsEnqueueBurstTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsTerminalOverflowTest.java"
      ],
      "class": "New",
      "behaviour": "Hold one actual DONE delivery, commit 399 further outcomes, force the 256-frame view handoff to overflow and resubscribe, then assert all 400 expected hashes/scan attributions reach activity exactly once and the persisted ingestion outcome rollups count 400. A later failure after re-enqueue reaches both once (401); reconnect snapshots never replay events. Uses the real EngineKnowledgeClient, worker service and SQLite feed. Historical ScanRollup has no live producer; current rollups read the persisted worker ledger.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q17",
          "quote": "The activity ledger and scan rollups keep today's terminal-event semantics (including later outcomes after retry or re-enqueue), also for rows never in the view, with no replay on reconnect.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-540095 request",
          "delegationScope": "implementation choices inside the agreed queue-scale scenarios Q1-Q27",
          "choice": "Accept committed terminal outcomes independently of the recoverable row-view handoff, with an empty registration barrier and no snapshot replay; keep SSE observer delivery asynchronous.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-terminal-overflow.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsTerminalOverflowTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsSseIsolationTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve the existing stalled-observer 2000-row handoff isolation and typed FAIL_FAST assertions. The mock additionally supplies an empty terminal-source registration barrier so the independent lifecycle can start; the original five assertions, burst pacing, blocked observer, sequence and thread checks are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q27",
          "quote": "SSE serialization and sending do not run on the thread that drains the handoff: a slow or stalled SSE observer does not make the handoff overflow during a 2000-row enqueue burst.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q17",
          "quote": "The activity ledger and scan rollups keep today's terminal-event semantics (including later outcomes after retry or re-enqueue), also for rows never in the view, with no replay on reconnect.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/queue-scale-terminal-fixtures.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsSseIsolationTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiringTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingJobsSseIsolationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/TestKnowledgeClient.java"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "signature"
      ],
      "whatMoved": "Fixture transport only: the two Wiring tests broadcast the same input frames to whichever of the window/outcome streams registered; the SSE isolation mock supplies an empty terminal registration; TestKnowledgeClient implements the new abstract outcome port with the same explicit unsupported-stream fixture policy. All 24 Wiring assertion calls and all 5 SSE-isolation assertion calls match checkpoint 8eb70d443 ignoring whitespace; inputs, expected counts, scan IDs, timing bounds, fault injection and test selection remain unchanged. Old-to-new inventory and normalized assertion proof are in queue-scale-terminal-continuity.md.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-540095",
          "scenario": "Q17",
          "quote": "The activity ledger and scan rollups keep today's terminal-event semantics (including later outcomes after retry or re-enqueue), also for rows never in the view, with no replay on reconnect.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a158c2-d82e-7711-ac4d-057b42b2a387",
  "verdict": "reject",
  "digest": "sha256:470304e07197d7c6f630f9068e83bc4d10b1c271afb1b814eb2bfc9838929a68",
  "note": "Entry 22 allows a 20 ms whole poll under a Q6 amendment absent from the supplied complete agreement, which requires under 10 ms p95."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a158f5-1b26-7c80-81bf-58ecfbd5b7d8",
  "verdict": "accept",
  "digest": "sha256:e38068e824267d4dcc0629767dfeed8d1a78ae20d8df7af564b1c2864bc3e5c8",
  "note": "Test-intent classifications, sources and receipts are valid; candidate delivery separately fails Q15/Q26 in the independent installed-Engine probe."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15932-243c-7282-8d8a-c468f484ff60",
  "verdict": "accept",
  "digest": "sha256:55de4183e60c1c92e9e837fcd8a676c29f03b69c7dc705cc4e5f07c4b23e18df",
  "note": "All 28 entries preserve or explicitly supersede baseline intent under Q1–Q27 and the quoted delegation; two repair claims independently fail before and pass after, while installed-system limitations remain separately reported."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15999-0c3d-79a3-b61e-29399505e62a",
  "verdict": "accept",
  "digest": "sha256:ffe6dbfd39a1b82f27b1328c0f75cc16dc4e432375eee52a294f7b61406b31d3",
  "note": "All 33 entries preserve or explicitly supersede baseline intent under Q1–Q27 and the quoted delegation, with terminal-overflow and rail-responsiveness claims independently failing before and passing after."
}
```
