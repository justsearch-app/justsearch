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

Round four copies the independent verifier D3 unwritable-index regression, embedding only its required fixture because its companion helper was not supplied with that test. Its assertion body is unchanged apart from the fixture class name. Both new checks fail by the intended assertions on committed 72e16d9ea. The first candidate build was cancelled after it started before source edits finished; only its valid before comparison is retained. A frozen-source run validates the retained before evidence and runs all three touched module suites and main/test compile/PMD together. Its 4,133 reported tests have one known launcher-NOTE environment failure and 48 skips; all 38 targeted executions pass without skips. PMD finds one redundant qualifier inherited from the copied verifier import. Narrowing that import preserves the assertion body. Windows-only annotations mark the POSIX permission API unavailable there, while leaving the pure policy test enabled. A third check exercises the original Directory exception across eight policy batches independently of the subsequent closed-writer signal. The queued two-check follow-up was replaced before it started to include this stronger provenance oracle. The final producer rebuilds a fresh before clone with that exact final test source, then combines all three modules' main/test compile/PMD with the 39 focused U17/U19 executions. No production code changes after the full module run. Existing acceptance records below are preserved byte-for-byte.

Round five repairs verifier D4 I7 without modifying #892 status logic. Its equivalent regression observes the actual running loop's capped pause through the public status service and durable SQLite rows, including mixed services, then clears the fault and checks recovery. The temporary evidence producer compares the exact new test on committed 1c949d43c in a shared clone, without repair overlays, then runs worker-core, worker-services and indexer-worker main/test compile, main/test PMD and full module tests together. Existing acceptance records remain byte-for-byte unchanged. The earlier fixed one-second scheduling choice is superseded by the new entry below. The first full run exposed a standalone draining-write compatibility regression (fixed by retaining immediate outcomes outside an active batch) and the pacing measurement including a second full sleep. Its new status assertions passed, but the evidence selector did not recognize JUnit's default parameter labels; explicit method labels fix selection without changing those assertions. The final producer rebuilds exact-test shared before clones for I7, the original U17 repairs and the poison repairs (with the existing recorded prior-candidate patch), refreshing both affected SystemicBatchDeferralTest evidence files. It then reruns all three touched modules' full tests, compile and PMD together with one worker/fork to reduce native model setup contention. No old evidence is relabelled as a new run.

Round six copies verifier D5's64-row capped-backlog case with only the required fixture adaptation, an actual-pause observation guard and stronger future-retry/recovery checks. A separate mixed-outcome SQL integration check pins the selective, forward-only extension. Both exact-source checks compare against committed2a936a7e1 in a reusable shared checkout; its compatibility patch adds only the default no-op JobQueue method needed by the mixed test, with no implementation or loop repair. One candidate Gradle invocation runs worker-core, worker-services and indexer-worker full tests and main/test compile/PMD with one worker/fork. Fixed tmp/builder/jvm/before and after roots and one immutable tiny corpus are reused. No existing test or acceptance record changes.

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
        },
        {
          "kind": "owner-words",
          "quote": "Make the persisted deferral deadline agree with the loop's actual pause.",
          "location": "task t-20261020-c2e24a user turn Round 5"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Align the U17 batch retry deadline and loop pause without changing #892 status or U19.",
          "choice": "The one-second minimum backoff is a batch window that includes persisting outcomes, not a second full sleep after that persistence. Preserve the >=1000ms no-hot-loop assertion measured between batch polls, and add a second assertion that the next poll cannot precede the persisted first-batch deadline. The old post-return measurement was997\u2013999ms because the2\u20133ms ledger persistence correctly consumes that same window. Fresh fail-before comparisons execute the changed oracle on d05fb4bbac5c; all other expectations stay unchanged.",
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
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierReadOnlyWriterTest.java"
      ],
      "class": "New",
      "behaviour": "An actual unwritable index directory throws AccessDeniedException while the real running loop writes extracted healthy content. Its row stays PENDING with zero attempts and DEFER_WITHOUT_ATTEMPT beyond the three ambiguous grace batches. A proven shared-service outage remains uncounted even after an earlier peer succeeds. The original real index-directory IOException also remains an uncounted outage across eight policy batches, independently of the writer closing after its first failure.",
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
          "delegationScope": "Classify index-directory/filesystem failures as proven writer outages while preserving U19, poison retries, pacing, logging and existing acceptance records; no schema change.",
          "choice": "The Lucene write/commit catches now retain an explicit index-storage flag on IndexRuntimeIOException. Within a bounded eight-cause walk, an absolute FileSystemException path inside the index directory proves storage origin; an explicit outside/invalid/relative path remains unproven. For actual FSDirectory instances the canonical directory path comes from the directory itself, including symlink roots. Plain checked IO is proven by org.apache.lucene.store stack frames; typed CorruptIndexException and LockObtainFailedException also prove storage origin. The decision never inspects exception message text. Existing diagnostic reason classification runs only after storage origin is proven; otherwise checked operation IO stays untagged DISK_IO. Legacy manually specified shared-service reasons remain supported. This separates malformed-chunk/parent reconstruction IO in WritePathOps read-modify-write APIs (wrapped as DISK_IO) from index storage faults; FieldMapper.toDocument materializes values and does not read source files. Source readability/locking remains handled before writing by the existing unreadable-source authority and retry-until-available ladder. Untagged DISK_IO, generic runtime poison and CONFIGURATION retain their ambiguous grace/ladder. All nine existing absent-writer guards also check IndexWriter.isOpen, because a real filesystem failure can abort the writer while its snapshot remains non-null. Proven outages override peer success; peer success still makes unknown failures per-file. Eight-second capped/resetting backoff and minute summaries are unchanged. The copied verifier method retains every assertion and its real POSIX fault; its otherwise unavailable fixture is embedded with equivalent queue/store/content/commit wiring. The two real POSIX permission checks are disabled on Windows, where that fixture API is unsupported; the pure peer-policy test remains enabled. Linux before/after verification executes all three checks. An additional actual-Directory check reuses the captured original AccessDenied IOException for eight policy batches, so the closed-writer guard cannot mask missing index-storage provenance.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-index-filesystem.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierReadOnlyWriterTest.java#realUnwritableIndexDoesNotChargeHealthySourceFiles",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierReadOnlyWriterTest.java#provenSharedOutageRemainsFreeAfterSuccessfulPeer",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierReadOnlyWriterTest.java#actualDirectoryFailureRemainsFreeBeyondGrace"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OutageWaitingStatusTest.java"
      ],
      "class": "New",
      "behaviour": "During capped writer, identity, draining and mixed-service outage pauses, both durable rows stay uncounted and ineligible, public status is WAITING with the exact shared next retry time, and clearing the fault resumes both within seconds and completes them without lost rows. Equivalent of verifier D4 I7, extended across service paths.",
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
          "kind": "owner-words",
          "quote": "Make the persisted deferral deadline agree with the loop's actual pause.",
          "location": "task t-20261020-c2e24a user turn Round 5"
        },
        {
          "kind": "owner-words",
          "quote": "Status then shows WAITING with the correct next retry time, and the rows become ready exactly when the loop resumes.",
          "location": "task t-20261020-c2e24a user turn Round 5"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 systemic deferral deadline and bounded service pause, preserving U19 and #892 status logic; no schema change.",
          "choice": "At batch completion, classify both services, select their maximum current 1/2/4/8-second backoff, and persist that single absolute retry_after for every systemic deferral, including draining and the unavailable identity receiver. Complete identity outcomes after the write phase so mixed outages agree. Sleep only until that deadline, subtracting outcome/claim-return time; keep cooperative stop signaling. The existing queue claim ownership and supersession checks remain; add a deadline overload with the existing overload retaining its one-second default. Restart reads the persisted deadline and resets only existing in-memory backoff history. This supersedes earlier entries' fixed one-second row scheduling; poison classification, ladder, rate limiting and service-success reset remain unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-waiting.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OutageWaitingStatusTest.java#outageBacklogWaitsUntilTheLoopResumesAndRecoversWithinSeconds"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierCappedBacklogStatusTest.java"
      ],
      "class": "New",
      "behaviour": "Verifier D5 I7: all64 real SQLite outage rows across16-row batches remain uncounted, ineligible and WAITING during the later eight-second pause, with a future exact shared next-retry time; recovery processes every row promptly at that deadline. Mixed persistence guards preserve per-file ladder and retry-until-available deadlines, other deferrals, fresh work, nonpending rows and later deadlines; extension never moves backwards or alters another column.",
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
          "kind": "owner-words",
          "quote": "while the loop is paused for a service outage, no row that was deferred because of that outage counts as ready before the loop's resume deadline.",
          "location": "task t-20261020-c2e24a user turn Round 6"
        },
        {
          "kind": "owner-words",
          "quote": "Never move a deadline backwards, and never touch rows whose retry belongs to the per-file ladder or to retry-until-available.",
          "location": "task t-20261020-c2e24a user turn Round 6"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "U17 outage-pause backlog deadlines, preserving U19, per-file schedules and #892 status logic; no schema change.",
          "choice": "Immediately before a service-outage pause, execute one forward-only UPDATE of retry_after for PENDING rows whose last_retry_policy is DEFER_WITHOUT_ATTEMPT, last_outcome_class is WRITE_FAILED or WRITE_UNAVAILABLE_DRAINING, and retry_after is less than the loop's resume deadline. Those persisted outcomes identify writer/identity outages and their bounded ambiguous grace; source-stale and cloud policy deferrals use other classes. Other columns and ledger history do not change. Fresh enqueues clear the outcome tuple and remain eligible. Counted RETRY_WITH_BACKOFF and RETRY_UNTIL_AVAILABLE rows are excluded even if their outcome class is WRITE_FAILED. Existing claim-cursor invalidation observes retry_after updates. Retain the 1/2/4/8-second cap, subtract update time from the cooperative wait, preserve later deadlines and restart behavior. The mixed integration test's before comparison adds only the new default no-op interface method so it compiles; no SQLite implementation or loop call is overlaid. Copy the verifier oracle with an actual-pause observation guard to avoid sampling between batch completion and entering the pause; add exact64/batch16, future-next-retry and recovery assertions. Use one fixed tiny immutable73-file corpus, fixed before/after JVM temp roots and one reusable before checkout; generate no large fixtures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-capped-backlog.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierCappedBacklogStatusTest.java#previouslyDeferredBacklogIsNotReadyDuringLaterCappedPause",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VerifierCappedBacklogStatusTest.java#extendingServiceDeferralsPreservesMixedFileRetriesAndNeverMovesDeadlinesBackwards"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SharedServiceAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Every audited shared ingestion availability state defers healthy files without attempts beyond grace, with bounded shared deadlines, WAITING status and prompt recovery through a running loop; document poison, source access ladders and revive remain unchanged.",
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
          "kind": "owner-words",
          "quote": "Make every one of them throw a typed \"service unavailable\" signal at the service boundary.",
          "location": "task t-20261020-c2e24a user turn Round 7"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Audit shared ingestion services and repair availability signals, preserving document poison/source retry behavior, prior pacing/status/recovery and schema.",
          "choice": "Retain Lucene IndexWriterUnavailableException and provenance-tagged IndexRuntimeIOException. Gate every coordinator mutation under the existing write barrier and session close-state monitor before accessing collaborators; distinguish actual writer/Directory closure from an arbitrary analyzer AlreadyClosedException. Component-open IO has no document input and is shared index IO. Use an IllegalStateException-compatible IngestionServiceUnavailableException for identity/path store and timebox availability. Store closed flags and JDBC isClosed checks run under their existing connection locks on every API. Classify known SQLite infrastructure primary result codes at fixed-SQL service boundaries, including schema/permission/connection failures; constraints, mismatched/oversized values, range and unknown codes remain ambiguous. Move path recording into the batched dependency failure boundary. Extraction submission/closed sandbox and child startup/reader rejection are service failures; keep SandboxExtractionException compatibility with a dedicated unavailable subtype, original causes and cleanup. Child crash/OOM/protocol/parser/file failures retain existing per-file semantics. Give extraction availability its own bounded service history, so successful identity resolution cannot reset an extraction outage. Recheck the timebox closed flag before classifying failed futures and the parser-pool flag before classifying EOF/IO from an in-flight request; serialize child startup/publication with closing so a slot waiter cannot create a child after close enumerates owned children. Preserve DRAINING precedence after a runtime has drained, rather than changing that existing typed signal to absent-writer. Existing service success resets its own history; restart preserves durable attempts/deadlines and resets only in-memory counters. Recovery in the matrix routes to reopened real services, matching owner replacement during cutover; no automatic recreation or deletion of production stores. Before compares exact final test source to1baf378a8; fixture uses real stores/runtimes, native SQLite/FS faults and controlled lifecycle state. Existing typed states are preservation cases; untyped states fail assertions. No schema/status changes; preserve acceptance bytes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/systemic-batch-deferral-service-audit.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SharedServiceAvailabilityTest.java"
      ],
      "repaired": true
    }
  ]
}
```


## Round seven shared-service availability audit

Scope traced from `JobBatchExtractor.extractJob`, `JobBatchWriter.write`,
`IndexingLoop.processBatchInner`, the running/deferred runtime owners, and both SQLite stores.
Production identity implementations are the SQLite authority and UNAVAILABLE sentinel. No
connection is published before constructor success; a failed open cannot yield a half-started store.
Runtime phases publish a RunningRuntime only after writer construction succeeds. Both SQLite
close operations serialize with operations under their own lock: a concurrent resolve waits for
close, then observes the closed flag, so closing and a retired receiver converge on the closed
case below. Runtime closing uses its existing session flag/monitor, with write-barrier protection
before mutation. Cutover retains old receivers until owner rebinding; the matrix replaces them
with newly opened real instances while the loop remains running. No service is silently recreated
in production. The D6 case opens and closes the production identity store on the same jobs.db as
the queue, leaves that receiver routed to the running loop, and visits four batches.

The test column names `SharedServiceAvailabilityTest` parameter values. `IWU` means
IndexWriterUnavailableException; `IRI` means IndexRuntimeIOException with proven index-storage
provenance/reason; `ISU` means IngestionServiceUnavailableException; `SSU` means
SandboxServiceUnavailableException, a SandboxExtractionException subtype preserving its cause.
SQL classifications are by result code, never message. Before values are source-audited here;
per-parameter execution results and first observed exceptions are retained with the machine evidence.

| Shared state | What it threw before (1baf378a8) | Boundary now | Matrix case |
| --- | --- | --- | --- |
| Writer/runtime closed or retired after switch | IWU in WritePathOps | IWU before collaborator access | WRITER_CLOSED |
| Runtime closing with issued generation lease, snapshot still live | Mutation could succeed; no close-state gate | IWU under session monitor | WRITER_CLOSING |
| Writer not started/read-only/deferred phase | IWU if leaked coordinator reaches WritePathOps; write accessor absent from phase type | IWU before document validation | WRITER_DEFERRED |
| Writer handle rolled back/lost/tragically closed | IWU if isOpen observed; AlreadyClosedException if closure races write | IWU, including race at write boundary | WRITER_CONNECTION_LOST |
| Directory closed while writer receiver remains wired | Raw Lucene AlreadyClosedException; ambiguous if writer remains open | IWU for index Directory provenance | WRITER_DIRECTORY_CLOSED |
| Retiring/draining generation during switch | IRI(DRAINING); RMW guard used untyped IllegalStateException | IRI(DRAINING) for mutation lanes | WRITER_DRAINING |
| Shared writer backpressure/queue admission | IRI(BACKPRESSURE) | Same typed rejection | WRITER_BACKPRESSURE |
| Index directory read-only/access denied | IRI with storage provenance (AccessDeniedException) | Same typed outage | WRITER_READ_ONLY |
| Index directory/files missing during mutation | IRI with index-owned filesystem path | Same typed outage | WRITER_DIRECTORY_LOST |
| Runtime open fails on index filesystem (no document has been supplied) | IRI(DISK_IO), not storage-tagged; could spend grace then attempts | Storage-tagged IRI from component-open boundary | WRITER_OPEN_FAILED |
| Replacement writer cannot acquire existing index lock | IRI(LOCKED) | Same typed outage | WRITER_OPEN_LOCKED |
| Identity authority not wired/not started | Untyped IllegalStateException from sentinel (a routed receiver loses sentinel identity) | ISU from sentinel itself | IDENTITY_UNWIRED |
| Identity store closed/closing/old receiver after replacement (D6) | NullPointerException from null connection | ISU checked under store lock | IDENTITY_CLOSED |
| Identity JDBC connection lost/closed outside store | Wrapped SQLException, often code0 and ambiguous | ISU from JDBC isClosed under lock | IDENTITY_CONNECTION_LOST |
| Identity connection read-only or filesystem read-only | IllegalStateException wrapping SQLITE_READONLY | ISU with original SQLException | IDENTITY_READ_ONLY |
| Identity table/schema lost | IllegalStateException wrapping SQLITE_ERROR | ISU: fixed SQL has no document-dependent syntax/schema | IDENTITY_SCHEMA_LOST |
| Open identity DB file removed/lost | IllegalStateException wrapping native READONLY_DBMOVED/IOERR | ISU with original native result | IDENTITY_FILE_LOST |
| Identity open/connection establishment fails (missing parent) | IllegalStateException wrapping SQLITE_CANTOPEN | ISU before construction succeeds | IDENTITY_OPEN_FAILED |
| Identity locked/busy connection | IllegalStateException wrapping SQLITE_BUSY | ISU; bounded service retry | IDENTITY_BUSY |
| Identity file is not a SQLite database/corrupt | IllegalStateException wrapping SQLITE_NOTADB/CORRUPT | ISU; no destructive automatic recovery | IDENTITY_CORRUPT |
| Path-resolution service closed/closing/old receiver | NullPointerException before identity resolution | ISU under store lock, batched by extractor | PATH_CLOSED |
| Path-resolution JDBC connection lost | SQLException logged and swallowed | ISU checked under store lock | PATH_CONNECTION_LOST |
| Path-resolution read-only | SQLException logged and swallowed | ISU with native result | PATH_READ_ONLY |
| Path-resolution fixed schema missing | SQLException logged and swallowed | ISU with native result | PATH_SCHEMA_LOST |
| Path-resolution open fails | Untyped IllegalStateException wrapping CANTOPEN | ISU at constructor boundary | PATH_OPEN_FAILED |
| Timeboxed extraction closed/closing | Untyped JDK RejectedExecutionException or existing typed Engine refusal | ISU using submission/close monitor | EXTRACTOR_CLOSED |
| Extraction executor/registration unavailable or saturated | JDK rejection or EngineExecutorRejectedException | ISU for untyped rejection; retain existing Engine typed refusal | EXTRACTION_EXECUTOR_CLOSED |
| Parser pool closed/closing | SandboxExtractionException; counted as per-file failure | ISU under pool close-state monitor, propagated through timebox | EXTRACTION_SANDBOX_CLOSED |
| Timebox closes during an admitted extraction (interrupted/cancelled future) | Source IOException / ExtractionException after executor shutdown, charged as a file failure | ISU checked under submission/close monitor before classifying the failed future | EXTRACTOR_CLOSING |
| Parser pool closes during an admitted request | Pipe EOF/IO reported as a per-file SandboxExtractionException | SSU after checking close flag under pool monitor; no new child admitted after closing | EXTRACTION_SANDBOX_CLOSING |
| Parser child cannot start (missing executable/control/manifest unavailable) | SandboxExtractionException with original IOException | SSU with same cause; no file bytes parsed yet | EXTRACTION_START_FAILED |
| Shared sandbox reader rejects request | SandboxExtractionException after child cleanup | SSU after the same cleanup; retain child ownership | EXTRACTION_READER_CLOSED |

Additional equivalences and exclusions checked from code:

- Other index-directory IO (disk full, generic IO, corrupt/missing segments, locks) follows the same
  `IndexRuntimeIOException.fromIndexOperation` storage classifier. Existing running-loop DISK_FULL
  checks and `VerifierReadOnlyWriterTest` exercise this lane. No disk-filling fixture is generated.
  CommitOps already checks writer absence and wraps index IO; a racing closed Directory now yields
  IWU. Its metadata-validation failures remain document/configuration errors. Snapshot reader/count
  failures in extraction are deliberately caught and extraction proceeds to the guarded writer;
  they cannot charge a healthy file on their own.
- Raw writer IO can originate in a document token stream/analyzer. An explicit filesystem path
  outside the index remains document IO even under Lucene calls; pathless IO needs Lucene store
  frames, not merely IndexWriter frames. Malformed chunk re-derivation can throw raw IOException
  in RMW. `DISK_IO`/CONFIGURATION without storage provenance, arbitrary NPE/IllegalStateException,
  constraints, mismatched/oversized values and nonfatal poison errors retain the existing ambiguity
  budget/ladder. Existing single/all-poison and U19 checks remain unchanged.
- SQLite services use fixed SQL with normalized path hashes, UUIDs and timestamp fields. Known
  primary availability codes are1–17,21–24 and26–28 (see exact switch); constraints19,
  value types20, oversized18, range25 and unknown0 remain per-file/ambiguous. `isClosed` catches
  real lost JDBC connections even when the driver would otherwise report code0. Native read-only,
  busy, missing file/schema and malformed DB are exercised, without fabricating SQL exceptions.
- Optional embedding/SPLADE and model metadata are not required lexical publication services:
  unavailable bindings choose lexical/pending-vector output; batch embedding and token-count
  exceptions are caught by their existing fallbacks. No new attempt policy is introduced there.
- Sandbox response protocol failure, timeout, child crash/OOM and decoder errors can be caused by
  one file. They remain per-document outcomes. A parser process dying on a poison file is not
  evidence that every parser is unavailable. Startup happens before any file request; reader
  rejection is an explicit shared executor admission failure, so those two are proven outages.
- Closing is checked again when an admitted extraction fails, not only before submission. The
  matrix closes a real timebox while its task is blocked and a real persistent pool after a child
  consumes the first request byte. Failed futures/pipe EOF caused by these closes remain uncounted
  outages. Child startup/publication is serialized with the pool close flag. A missing individual
  identity row still yields the existing per-hash convergence/integrity error; no closed connection
  can reach that path after the locked open check.
- jobs.db is the durable queue/control plane, not an alternate document authority. Its existing
  OutcomeWriteException and outer loop recovery retain claims; an extension failure retains the
  orchestrator's bounded pause and minute-rate warning. This round does not alter that handler,
  ledger commit ordering, background sync/watcher revive, file locks or source read policy.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16184-919e-7620-a7a1-1301e332461f",
  "verdict": "reject",
  "digest": "sha256:4ef650684d31d2595a50cf173ceb958f0dc54f4393e901a87273fe2f3b18c521",
  "note": "The real unwritable-index outage consumes a healthy file attempt and schedules a minute-scale retry after three free batches, violating U17."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a161ff-4236-7ad0-bcec-b9b7150991ba",
  "verdict": "reject",
  "digest": "sha256:5a94bf88ece7cd8d7bedb72a151ec59f207bde1c42f128b66379764f0a6311ab",
  "note": "U17/U19 and entries 1–7 pass independent checks, but I7 fails because indexState reports INDEXING during the capped outage pause with zero rows processing."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16264-47fb-7912-9eba-53a30f2ddd51",
  "verdict": "reject",
  "digest": "sha256:d38f885f634e520ea12e3c42b478fcb75f1dcd48f23db507c6958f0c035bb69c",
  "note": "I7 fails: during a capped real SQLite outage, 48 of 64 deferred rows are ready while zero process and status reports INDEXING."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a162b5-e897-7ed3-a4aa-a16e63f4a1cc",
  "verdict": "reject",
  "digest": "sha256:97b45cdcdd7c91157e53cb97df372b30efba7f623b547e4a4a65fec88f27c2ca",
  "note": "U17 fails: a real closed SqliteDocumentIdentityStore charges both healthy rows and schedules minute-scale retries after three grace batches."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1635f-98be-7772-a266-db83c062e197",
  "verdict": "reject",
  "digest": "sha256:708f2167481acfa9583c87bf8435b5cb1f83c9591d83df36d8fad19caedfa1e2",
  "note": "I7 reports INDEXING during a real outage pause, I9 retries and logs every second with persistent queue READONLY, and I10 misses a closed extractor registry that terminally fails healthy files."
}
```
