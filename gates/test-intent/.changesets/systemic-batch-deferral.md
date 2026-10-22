---
schema: test-intent.v1
task: t-20261020-c2e24a
author-role: builder
author-session: 01a15fc2-b9f6-7a13-9835-bda855d20c35
---

# Test-intent entries

U17 and U19 were adopted by the owner on 2026-10-20 with "i agree, proceed accordingly".
Their text below is verbatim. The classification, ambiguity trade-off, eight-second cap and logging
policy are delegated agent choices. No jobs.db schema change. Existing unreadable-source,
retry-until-available, exhaustion and re-enqueue logic is unchanged.

Checks use the production loop and real SQLite claims, with fault injection at service ports.
The evidence runner is a temporary copy of run-evidence.mjs adapted to use js-gradle-slot with
--continue, compile/PMD tasks and the required temp settings; before states are shared clones under
TMPDIR. Its digest is recorded. The round-two candidate invocation combines all seven module test tasks and both modules' compile/PMD tasks. The evidence records repaired and preservation checks from that same invocation; only repaired checks run in its before comparison. Unchanged U19 checks also have a separate fresh baseline control. The original U17 repairs compare with d05fb4bbac5c. The poison repair
compares with that same base plus systemic-batch-deferral-prior.patch: the exact prior candidate
production code, saved before this continuation. That patch intentionally contains the flawed
unbounded classification, so the new assertions must fail there for the reported counterexample.
No candidate repair code is overlaid into that before state. The READONLY regression compares against systemic-batch-deferral-before-readonly.patch, the intermediate bounded candidate saved before adding code8; its old rule incorrectly turns that proven store outage into counted retries. Its runner executes this before comparison first, then repeats the candidate module checks. The original scenario test file remains byte-identical throughout these extensions. The explicit-unavailable receiver check compares with systemic-batch-deferral-before-unavailable.patch, saved after READONLY was repaired and before the receiver-state guard. Both added cases run their before comparison first in shared clones. The patch is execution evidence,
not a source of intent. Existing preservation checks also run before and after.

Round three compares its two lifecycle checks directly with committed candidate 0b56cd1b7 in a shared clone, without repair overlays. Its temporary runner combines adapters-lucene, worker-services and indexer-worker tests and main/test compile/PMD in one candidate invocation; it does not rerun app-engine/ui/system-tests broadly. JAVA_TOOL_OPTIONS, JUSTSEARCH_HOME and JUSTSEARCH_DATA_DIR are unset; JVM temp paths stay under TMPDIR. Unchanged U17/U19 checks are selected from that same candidate run. The first full round-three run completed all three module suites (4,090 reported tests, one known launcher-NOTE environment failure, 48 skips); both new checks and all 34 round-two targeted executions passed without skips. PMD found that importing TimeUnit made two existing qualified names redundant. The import was removed and the new wait uses qualified names, with no behavior change. Final evidence reruns main/test compile and PMD for all three modules and the 35 indexer-worker targeted executions plus the worker-services READONLY check. Its producer validates the first evidence self-digest, identical oracle digests and exact before revision, then retains those already-executed fail-before assertions instead of rebuilding the identical before state. Original before-proof producer digest: sha256:3126f8e32960a5f93e815f181971fdbc36627e3d0ac3082c595dfb206c261dff. The current producer digest is in the new machine-produced evidence. Full-run output and its original evidence are retained under tmp/builder/round3/.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java"
      ],
      "class": "New",
      "behaviour": "Whole-batch writer/identity outages keep attempts zero during short outages, retry within 1\u20135 seconds in persisted row deadlines, recover all rows in one claim, and finish without spending attempts. Three ambiguous grace batches retain this bound. A running loop pauses between failed batches even while fresh backlog remains ready, including draining.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U17",
          "quote": "A failure of the writer or identity store for a whole batch defers its rows without charging per-file attempts, with a retry within seconds; after recovery they are claimed at once; one bad file does not starve healthy later files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 systemic writer/identity failure classification and bounded short retry, preserving U19; no schema change.",
          "choice": "Judge each service separately after the batch: a successful peer makes unknown failures per-file. IllegalArgumentException and nonfatal Error use the ladder immediately. Other unknown runtime failures (including IllegalStateException, NullPointerException, Lucene DISK_IO and CONFIGURATION) get at most  three consecutive unsuccessful grace batches for that service; the fourth and subsequent batches charge attempts and use the existing durable ladder/exhaustion until a service success. This uses constant-size in-memory service history, not per-row memory. A restart grants three new grace batches but preserves existing persisted attempts, first_failed_at and exhaustion. A generic outage and all-poison batch cannot be distinguished; favor bounded retries and retained RETRY_EXHAUSTED rows that existing re-enqueue revives, rather than infinite free retries. Proven infrastructure signals remain uncounted: writer DISK_FULL, LOCKED, CORRUPT_INDEX, SCHEMA_MISMATCH, BACKPRESSURE and DRAINING; identity SQLException primary availability codes BUSY/LOCKED/READONLY/IOERR/CORRUPT/FULL/CANTOPEN/NOTADB, including wrapped causes. SQL constraints/unknown codes are ambiguous. A successful peer makes failures per-file except the existing DRAINING sentinel. The running service pause grows 1, 2, 4, 8 seconds and caps at 8 seconds; any success of that service resets its pause and ambiguous grace. Existing SQL deferClaim still schedules all rows for one second, so no schema/API change is needed and recovery detection takes at most eight seconds plus batch processing. Continue visiting every document, and log at most one error summary per service per minute, retaining per-file failure details in the durable ledger. The explicit DocumentIdentityStore.UNAVAILABLE receiver is also proven service unavailability, like the existing draining sentinel; it defers without attempts and uses the capped service pause even beyond grace and even if binding becomes available for a later peer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#writerOutageDefersWholeBatchWithoutAttemptsAndRecoversPromptly",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#identityOutageDefersWholeBatchWithoutAttemptsAndRecoversPromptly",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#repeatedSystemicFaultsStayBoundedWithoutChargingAttempts",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#systemicOutagePausesBetweenBatchesEvenWithReadyBacklog"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java"
      ],
      "class": "New",
      "behaviour": "Per-file writer/identity failures retain their counted minute-scale ladder, including an invalid document with no healthy peers; healthy peers and later enqueued files still finish.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U17",
          "quote": "A failure of the writer or identity store for a whole batch defers its rows without charging per-file attempts, with a retry within seconds; after recovery they are claimed at once; one bad file does not starve healthy later files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 systemic writer/identity failure classification and bounded short retry, preserving U19; no schema change.",
          "choice": "Judge each service separately after the batch: a successful peer makes unknown failures per-file. IllegalArgumentException and nonfatal Error use the ladder immediately. Other unknown runtime failures (including IllegalStateException, NullPointerException, Lucene DISK_IO and CONFIGURATION) get at most  three consecutive unsuccessful grace batches for that service; the fourth and subsequent batches charge attempts and use the existing durable ladder/exhaustion until a service success. This uses constant-size in-memory service history, not per-row memory. A restart grants three new grace batches but preserves existing persisted attempts, first_failed_at and exhaustion. A generic outage and all-poison batch cannot be distinguished; favor bounded retries and retained RETRY_EXHAUSTED rows that existing re-enqueue revives, rather than infinite free retries. Proven infrastructure signals remain uncounted: writer DISK_FULL, LOCKED, CORRUPT_INDEX, SCHEMA_MISMATCH, BACKPRESSURE and DRAINING; identity SQLException primary availability codes BUSY/LOCKED/READONLY/IOERR/CORRUPT/FULL/CANTOPEN/NOTADB, including wrapped causes. SQL constraints/unknown codes are ambiguous. A successful peer makes failures per-file except the existing DRAINING sentinel. The running service pause grows 1, 2, 4, 8 seconds and caps at 8 seconds; any success of that service resets its pause and ambiguous grace. Existing SQL deferClaim still schedules all rows for one second, so no schema/API change is needed and recovery detection takes at most eight seconds plus batch processing. Continue visiting every document, and log at most one error summary per service per minute, retaining per-file failure details in the durable ledger. The explicit DocumentIdentityStore.UNAVAILABLE receiver is also proven service unavailability, like the existing draining sentinel; it defers without attempts and uses the capped service pause even beyond grace and even if binding becomes available for a later peer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#isolatedWriterFailureUsesLadderAndDoesNotStarveLaterFiles",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#isolatedIdentityFailureUsesLadderAndDoesNotStarveLaterFiles",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#documentArgumentFailureUsesLadderEvenWithoutSuccessfulPeers"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java"
      ],
      "class": "New",
      "behaviour": "Singleton and all-poison ambiguous writer/identity failures leave the three-batch grace window, spend an attempt, follow the minute-scale ladder and exhaust without deleting rows; later healthy work completes. Service success resets ambiguity. Proven writer/SQLite outages survive beyond the eight-second cap at zero attempts, recover the whole batch and newly queued healthy work, grow and cap their pause, reset after success, and emit one rate-limited batch summary instead of per-document/per-second errors.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U17",
          "quote": "A failure of the writer or identity store for a whole batch defers its rows without charging per-file attempts, with a retry within seconds; after recovery they are claimed at once; one bad file does not starve healthy later files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 systemic writer/identity failure classification and bounded short retry, preserving U19; no schema change.",
          "choice": "Judge each service separately after the batch: a successful peer makes unknown failures per-file. IllegalArgumentException and nonfatal Error use the ladder immediately. Other unknown runtime failures (including IllegalStateException, NullPointerException, Lucene DISK_IO and CONFIGURATION) get at most  three consecutive unsuccessful grace batches for that service; the fourth and subsequent batches charge attempts and use the existing durable ladder/exhaustion until a service success. This uses constant-size in-memory service history, not per-row memory. A restart grants three new grace batches but preserves existing persisted attempts, first_failed_at and exhaustion. A generic outage and all-poison batch cannot be distinguished; favor bounded retries and retained RETRY_EXHAUSTED rows that existing re-enqueue revives, rather than infinite free retries. Proven infrastructure signals remain uncounted: writer DISK_FULL, LOCKED, CORRUPT_INDEX, SCHEMA_MISMATCH, BACKPRESSURE and DRAINING; identity SQLException primary availability codes BUSY/LOCKED/READONLY/IOERR/CORRUPT/FULL/CANTOPEN/NOTADB, including wrapped causes. SQL constraints/unknown codes are ambiguous. A successful peer makes failures per-file except the existing DRAINING sentinel. The running service pause grows 1, 2, 4, 8 seconds and caps at 8 seconds; any success of that service resets its pause and ambiguous grace. Existing SQL deferClaim still schedules all rows for one second, so no schema/API change is needed and recovery detection takes at most eight seconds plus batch processing. Continue visiting every document, and log at most one error summary per service per minute, retaining per-file failure details in the durable ledger. The explicit DocumentIdentityStore.UNAVAILABLE receiver is also proven service unavailability, like the existing draining sentinel; it defers without attempts and uses the capped service pause even beyond grace and even if binding becomes available for a later peer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-poison.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#poisonEventuallyUsesLadder",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#successResetsAmbiguousGrace",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java#provenOutageBacksOffCapsLogsAndResets"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ReadOnlyStoreDeferralTest.java"
      ],
      "class": "New",
      "behaviour": "A wrapped SQLite READONLY identity-store failure remains an uncounted shared-service outage through eight consecutive unsuccessful batches, beyond the three-batch ambiguous grace.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U17",
          "quote": "A failure of the writer or identity store for a whole batch defers its rows without charging per-file attempts, with a retry within seconds; after recovery they are claimed at once; one bad file does not starve healthy later files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 systemic writer/identity failure classification and bounded short retry, preserving U19; no schema change.",
          "choice": "SQLite READONLY (primary result code8, including extended codes through the existing low-byte mask) describes the identity database being unwritable, independently of document content. Preserve its uncounted outage policy beyond the ambiguous grace window. This is separate from a genuinely unreadable source file, whose existing retry ladder and retry-until-available remain unchanged. The first bounded candidate omitted this availability code; pin that regression with eight consecutive batch decisions, before and after its one-line classification repair. No schema change. The explicit DocumentIdentityStore.UNAVAILABLE receiver is also proven service unavailability, like the existing draining sentinel; it defers without attempts and uses the capped service pause even beyond grace and even if binding becomes available for a later peer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-readonly.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ReadOnlyStoreDeferralTest.java#readOnlyStoreRemainsUncountedBeyondAmbiguousGrace"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnavailableIdentityBatchTest.java"
      ],
      "class": "New",
      "behaviour": "An explicit unavailable identity receiver defers a multi-file batch without attempts through eight batches and grows to the8s service cap, while publishing no documents.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U17",
          "quote": "A failure of the writer or identity store for a whole batch defers its rows without charging per-file attempts, with a retry within seconds; after recovery they are claimed at once; one bad file does not starve healthy later files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U19",
          "quote": "A genuinely locked or unreadable single file still uses the retry ladder and retry-until-available; background sync and watcher re-enqueue still revive retry-exhausted rows.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 systemic writer/identity failure classification and bounded short retry, preserving U19; no schema change.",
          "choice": "Capture the identity receiver once. The explicit DocumentIdentityStore.UNAVAILABLE singleton rejects every document independently of input, so record its failures as uncounted deferrals and reuse the existing unavailable-batch flag for growing/capped pauses. Other runtime exceptions retain the bounded ambiguous rule. This preserves fail-closed extraction and no schema change. The check uses real SQLite claims and two readable files through eight batches; the preceding candidate charges an attempt in batch4, the repaired candidate retains0 and reaches the8s cap.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-unavailable.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UnavailableIdentityBatchTest.java#unavailableIdentityReceiverNeverChargesTheBatch"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OutageLifecycleTest.java"
      ],
      "class": "New",
      "behaviour": "A cooperative stop during the capped eight-second outage pause finishes within the five-second join budget without interrupting the Lucene owner, losing queue rows or charging attempts. A real closed Lucene runtime remains a proven outage beyond three grace batches; the running loop completes deferred and newly queued healthy rows without attempts after a replacement runtime is opened.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U17",
          "quote": "A failure of the writer or identity store for a whole batch defers its rows without charging per-file attempts, with a retry within seconds; after recovery they are claimed at once; one bad file does not starve healthy later files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 systemic writer availability and bounded cooperative outage retry, preserving round-two classification and U19; no schema change.",
          "choice": "Use a timed monitor wait with a monotonic deadline and the existing running flag; every cooperative stop signals the monitor, including shutdown, upgrade quiescence and profiling reset. Spurious wakes retain the remaining delay, and stop-before-wait cannot lose a notification. Never interrupt the Lucene owner. All nine existing null-writer guards in WritePathOps/CommitOps throw IndexWriterUnavailableException, a dedicated IllegalStateException subtype preserving their messages and existing catches. No exact-class consumer of these writer errors was found; unrelated exact-class VDU and offline-operation checks concern their own errors. The batch classifier recognizes this lifecycle type as proven service unavailability; generic IllegalStateException and poison rules remain unchanged. Runtime restart still uses a replacement single-shot RunningRuntime, with a test-side forwarding port to its active instance; no production lifecycle wrapper is added.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-lifecycle.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OutageLifecycleTest.java#stopDuringCappedOutagePauseWakesWithoutInterruptingOrChargingRows",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OutageLifecycleTest.java#closedLuceneRuntimeDefersBeyondGraceAndRecoversThroughTheRunningLoop"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
