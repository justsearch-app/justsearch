---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: 01a18dc0-3a64-79b0-b2ef-9601591a8962
---

# WP7 job queue test intent

Scope is the 13 uncovered modified files in the orchestrator's saved gate output,
`tmp/ti/flagged.txt`, compared with merge-base
`9eb36cc7923b530624f7ced8ea0bc678f0011b31`. The three isolated new test files are
exempt and receive no entry here. All choices below quote scenario detail under
the owner's delegation; none claims owner adoption of agent wording.

No execution evidence or acceptance is claimed. Evidence must be generated at
`gates/test-intent/evidence/ingfix-wp7.json`. Every Defect pin entry requires
an assertion failure in each of its `newChecks`, using
`--before 9eb36cc7923b530624f7ced8ea0bc678f0011b31` with
`--before-patch gates/test-intent/compatibility/ingfix-wp7-before.patch`.
The compatibility patch adds only a no-op authority-notification signature.
D27 is fixed for the ordinary and bulk
recorded-permission paths with a completed producer only; the live-enumeration and
queue-side denial cases were reverted (b0dc203b87) and stay open. The two New entries are
caller-preservation checks and must pass before and after. Reproduction fields
describe how to reproduce, not gate acceptance.

The following supplementary preservation checks belong to the corresponding
file entry and must also be included as `--check` arguments in the same evidence
capture. They are separated from the defect-pin `newChecks` because they must
pass on the before-state, not fail. Their expectations are described in the
file entries and sourced to C22/C23 and C30/C31 respectively:

- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java#legacyPropertiesHistoryLoadsAndRetainsNewVersionsUntilRetirementEnds`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java#sameIntentResumesAfterRollbackWithActualMutableRetryPreserved`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#failedMaintenanceAdmissionStillFailsTheScan`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#refusedAdmissionCannotProduceCleanCompletionOrAdmittedProgress`

The service cancellation and maintenance-scan calls use explicit no-exception
assertions so recorded-cancellation and maintenance no-op exceptions on the
before-state are reported as assertion failures for promised valid calls, rather
than escaping before the outcome assertions.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "A bulk permission grant permits ALLOW_FORCE and notifies claim-authority invalidation before orphan recovery, so a recovery failure cannot prevent permission reconsideration. Only a new method is added; existing expectations are unchanged. Retained bulk permissions recover from a transient denial.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C14 **D27 — Fairness must cover both recorded permission paths.** `RecordedIngestionCoordinator` has ordinary child permissions and bulk permissions; exercise authority changes through both, including a permission refresh after an earlier recovery failure."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java#bulkPermissionGrantInvalidatesClaimsBeforeAttemptingOrphanRecovery",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedBulkIngestionCoordinatorTest.java#retainedBulkPermissionRecoversAfterTransientDenialDespiteNewArrivals"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        },
        "reproduction": "Maintain a bulk coordinator over a building runtime while observing calls delegated to its real queue. Assert that recordedClaimAuthorityChanged precedes recoverStuckJobs and that the granted permission is ALLOW_FORCE. The before implementation performs recovery without the preceding notification."
      },
      "reproduction": "Maintain a bulk coordinator over a building runtime while observing calls delegated to its real queue. Assert that recordedClaimAuthorityChanged precedes recoverStuckJobs and that the granted permission is ALLOW_FORCE. The before implementation performs recovery without the preceding notification."
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "An ordinary child permission grant invalidates claim selection before orphan recovery. The queue invocation order is the chosen check of the grant notification guarantee; Retained permissions must recover from transient denial once enumeration is complete, reconsidering an older member on the next poll despite newer arrivals.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C14 **D27 — Fairness must cover both recorded permission paths.** `RecordedIngestionCoordinator` has ordinary child permissions and bulk permissions; exercise authority changes through both, including a permission refresh after an earlier recovery failure."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#childPermissionGrantInvalidatesClaimsBeforeAttemptingOrphanRecovery",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#retainedChildPermissionRecoversAfterTransientDenialDespiteNewArrivals"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        },
        "reproduction": "Execute an accepted child request, maintain its coordinator with a delegating queue observer, and compare the filtered invocation sequence with [recordedClaimAuthorityChanged, recoverStuckJobs]. The before implementation omits the first invocation."
      },
      "reproduction": "Execute an accepted child request, maintain its coordinator with a delegating queue observer, and compare the filtered invocation sequence with [recordedClaimAuthorityChanged, recoverStuckJobs]. The before implementation omits the first invocation."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ClaimCursorInvalidationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "After denying and passing an older recorded member, grant authority without updating its queue row and require that member on the next poll despite a newer admission. Reflection permits the same test to execute where the notification API does not yet exist.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/ClaimCursorInvalidationTest.java#authorityOnlyGrantRevisitsOlderMemberDespiteContinuousNewArrivals"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        },
        "reproduction": "Seed an older denied recorded job and two newer ordinary jobs with deterministic timestamps, claim new-0, grant authority, send the notification if available, and enqueue new-2. Compare the next claimed path with the oldest recorded path; the before cursor continues to newer work."
      },
      "reproduction": "Seed an older denied recorded job and two newer ordinary jobs with deterministic timestamps, claim new-0, grant authority, send the notification if available, and enqueue new-2. Compare the next claimed path with the oldest recorded path; the before cursor continues to newer work."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueRetryLadderTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Replace the old expectation of an IO_FAILED observer event after a retryable attempt with no event. Then make the failure terminal with retry policy NONE and require exactly one IO_FAILED event. This intentionally changes the existing oracle under S-D123, while preserving its terminal outcome-class observation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D123 (Outcome metrics omit success and count nonterminal attempts) Observe success, skip and nonterminal failure and require declared terminal counts to match."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueRetryLadderTest.java#outcomeObserverSeesTheOutcomeClass"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D123 (Outcome metrics omit success and count nonterminal attempts) Observe success, skip and nonterminal failure and require declared terminal counts to match."
        },
        "reproduction": "Attach the outcome observer, enqueue and claim a file, and mark it failed with the retryable IO outcome. Assert the observer is empty before issuing a NONE-policy failure; then assert exactly [IO_FAILED]. The before implementation counts the nonterminal attempt."
      },
      "reproduction": "Attach the outcome observer, enqueue and claim a file, and mark it failed with the retryable IO outcome. Assert the observer is empty before issuing a NONE-policy failure; then assert exactly [IO_FAILED]. The before implementation counts the nonterminal attempt."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobReadinessAndClaimTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "An authority-only grant behind 600 denied members must become claimable within three polls of 257 candidates under continuous arrivals. All 600 still-denied rows remain PENDING; the pre-grant poll sequence also preserves progress to ordinary work.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C15 **D27 — Reconsideration must retain bounded polling and per-row authorization.** Keep a large denied prefix indefinitely, interleave ordinary ready work, and require forward progress within the candidate budget; revoke permission during polling and ensure later rows remain fenced."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobReadinessAndClaimTest.java#authorityGrantReconsidersBehindDeniedPrefixWithinThreeBudgetedPolls"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D27 (Authority-only changes do not revisit skipped jobs fairly) Grant an older denied job authority while sustaining newer jobs and require bounded-time claiming."
        },
        "reproduction": "Seed 600 denied members, an older grantable member, and two ordinary jobs at ordered timestamps. Pass the prefix and claim new-0, grant authority and notify if supported, then admit newer work before each of three polls. Assert the granted member is claimed within the bound and denied members remain pending; the before cursor misses the bound."
      },
      "reproduction": "Seed 600 denied members, an older grantable member, and two ordinary jobs at ordered timestamps. Pass the prefix and claim new-0, grant authority and notify if supported, then admit newer work before each of three polls. Assert the granted member is claimed within the bound and denied members remain pending; the before cursor misses the bound."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RecordedWalkAuthorityTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "The existing denied orphan stays fenced until permission is granted. After explicitly aging that restart orphan, an unrelated heartbeat must leave it eligible for aged recovery: aged recovery now returns one, subsequent unconditional recovery returns zero, and the recovered job is claimable. This replaces the former expectation that the heartbeat renewed an unowned orphan, as S-D26 requires.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D26 (Heartbeats renew abandoned claims) Alternate unrelated heartbeats and aged recovery after failed startup recovery; require abandoned claims to return to PENDING."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/RecordedWalkAuthorityTest.java#laterAllowedAuthorityAndUnconditionalRecoveryReviveHeartbeatedProcessing"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D26 (Heartbeats renew abandoned claims) Alternate unrelated heartbeats and aged recovery after failed startup recovery; require abandoned claims to return to PENDING."
        },
        "reproduction": "Seed a PROCESSING recorded row, reopen with denied authority, verify recovery returns zero, grant permission, and set its timestamp to 1. Heartbeat with no issued local claim and assert recoverStuckJobs(60000) returns one. The before heartbeat renews the orphan and that assertion fails."
      },
      "reproduction": "Seed a PROCESSING recorded row, reopen with denied authority, verify recovery returns zero, grant permission, and set its timestamp to 1. Heartbeat with no issued local claim and assert recoverStuckJobs(60000) returns one. The before heartbeat renews the orphan and that assertion fails."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "After 16 retained corrections, measure checkpoint FileWrite bytes for 4 and 32 more corrections, both batched and across repeated calls. Require nonzero observed writes and at most 10x bytes for 8x corrections, all 52 correction records reloadable, and no corrections after retirement or in a later retirement. The same file also adds legacy-properties retention/cleanup preservation and extends the durable-intent rollback test with close/reopen and exact retained-intent equality; their separate passing-before checks are listed below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D32 (Release recovery rewrites growing history for every correction) Increase correction count and require approximately linear checkpoint bytes written."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C21 **D32 — Checkpoint write volume must scale with corrections across real call patterns [broken].** Measure cumulative bytes for increasing correction counts both within one correction batch and across repeated calls; include a pre-existing history so an empty-history benchmark cannot hide repeated rewrites."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C22 **D32 — Durable intent must still precede SQL installation.** Interrupt after retaining a correction but before SQL commit, restart, and resume; require reuse of the retained replacement identity and preservation of the actual predecessor’s retry state, as exercised by `Release030CorrectionHistoryTest`."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C23 **D32 — Storage changes must preserve old checkpoints and ownership validation.** Load existing properties histories, replay multiple corrections of one source, reject mismatched owner/generation/metadata evidence, and finish retirement; require all predecessors until completion and no stale correction evidence contaminating a later retirement."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java#checkpointWriteBytesScaleLinearlyWithinAndAcrossCorrectionCalls"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D32 (Release recovery rewrites growing history for every correction) Increase correction count and require approximately linear checkpoint bytes written."
        },
        "reproduction": "Prepare 52 completed files, mutate their contents, retain 16 corrections, and measure the next 4 versus 32 with JFR FileWrite events matching the checkpoint path. Assert large <= small * 10 for both correction call patterns. Growing full-history rewrites in the before implementation exceed the selected linear bound; this is a reproduction recipe, not an executed measurement."
      },
      "reproduction": "Prepare 52 completed files, mutate their contents, retain 16 corrections, and measure the next 4 versus 32 with JFR FileWrite events matching the checkpoint path. Assert large <= small * 10 for both correction call patterns. Growing full-history rewrites in the before implementation exceed the selected linear bound; this is a reproduction recipe, not an executed measurement."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SqlitePathResolutionStoreTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "For roots containing underscore, percent, or backslash, a trailing-slash prune removes exactly two literal descendants and preserves a same-prefix sibling, a SQL-wildcard-like sibling, and a case-distinct POSIX path. A second prune without the slash removes zero.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D31 (Path-resolution pruning interprets folder names as SQL patterns) Prune `proj_a` and require stored `projXa` resolutions to survive."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C34 **D31 — Root pruning must use literal path boundaries [broken].** Prune roots containing `_`, `%`, and any chosen escape character; include same-prefix siblings, trailing-slash variants, nested descendants, and case-distinct POSIX paths; require only literal descendants to disappear and the deletion count to match."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/SqlitePathResolutionStoreTest.java#pruningUsesLiteralCaseSensitivePathBoundaries"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D31 (Path-resolution pruning interprets folder names as SQL patterns) Prune `proj_a` and require stored `projXa` resolutions to survive."
        },
        "reproduction": "Store child, nested, same-prefix sibling, projXa sibling, and case-distinct resolutions for each special-character root. Prune the literal root plus slash and assert only two descendants disappear. The before LIKE predicate interprets wildcard characters and folds ASCII case, violating the literal boundary."
      },
      "reproduction": "Store child, nested, same-prefix sibling, projXa sibling, and case-distinct resolutions for each special-character root. Prune the literal root plus slash and assert only two descendants disappear. The before LIKE predicate interprets wildcard characters and folds ASCII case, violating the literal boundary."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DocumentIdentityBootImportGuardTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: when the identity store throws an unavailable exception during boot import, the caller propagates an IllegalStateException and never calls recordImport. No production boot-import caller change occurs in this branch, so this is a newly explicit guard of existing behavior, not a claimed fail-before repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C3 **D30 — Identity callers must not advance after uncertain writes.** `DocumentIdentityBootImport` records completion after importing; `WorkerIngestService.updateDocumentPaths` moves Lucene rows after rekeying; inject store failures through these entry points and require neither a false import-completion record nor continued rename publication."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DocumentIdentityBootImportGuardTest.java#failedStoreImportNeverRecordsCompletion"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceDocumentIdentityTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: with an indexed old path and a store that throws during rekey, rename reports WorkerServiceException, the old Lucene row remains present and the new path has no row. The store-first rename caller is unchanged in this branch; this guard does not claim to reproduce the repaired store transaction fault itself.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C3 **D30 — Identity callers must not advance after uncertain writes.** `DocumentIdentityBootImport` records completion after importing; `WorkerIngestService.updateDocumentPaths` moves Lucene rows after rekeying; inject store failures through these entry points and require neither a false import-completion record nor continued rename publication."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceDocumentIdentityTest.java#uncertainIdentityRekeyStopsRenamePublication"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "A populated queue with unreadable count state must yield unhealthy ERROR status and unavailable failed-job listing; unreadable ingestion ledger state must make outcome summary unavailable. Service cancellation of ordinary pending, backing-off, and unsealed recorded members, repeated twice, must succeed, leave no queued/claimable work, and record SKIPPED_POLICY/CANCELLED/NONE. This test does not assert the additional reopen, sealed-receipt, coverage, or subscriber portions of C26.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D106 (Queue read failure looks like an empty queue) Fail SELECTs against populated state and require explicit unavailability."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D58 (Recorded-job cancellation uses an invalid success outcome) Cancel an unsealed recorded PENDING job and require a valid cancellation outcome."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C24 **D58 — Ordinary and recorded cancellation need a valid terminal cancellation outcome [broken].** Cancel PENDING and backing-off jobs through `cancelIndexingJob`, including an unsealed recorded member; require committed cancellation, NONE retry policy, compatible SKIPPED coverage, and no success classification."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C26 **D58 — Repeated cancellation must preserve immutable recorded receipts.** Cancel twice, reopen, and inspect queue, ledger, coverage counters, and subscriber state; also cancel a retained row after its walk is sealed and require the sealed receipt to remain unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceTest.java#unreadableQueueCannotProduceHealthyStatusOrEmptyOutcomeSummaries",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceTest.java#cancelThroughServiceRecordsSkippedOutcomeAndStopsPendingWork"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D106 (Queue read failure looks like an empty queue) Fail SELECTs against populated state and require explicit unavailability."
        },
        "reproduction": "For read failure, rename job_counts and later ingestion_ledger on the test database and assert explicit error observations. For cancellation, resolve a path hash, admit each pending/retry/recorded variant, call cancelIndexingJob twice, and inspect depth, claims, and recent ledger outcome. On the before implementation, count failure can appear healthy/empty and recorded cancellation uses an invalid success outcome. Each named method still needs actual assertion-failure evidence; an uncaught cancellation exception would not satisfy that requirement."
      },
      "reproduction": "For read failure, rename job_counts and later ingestion_ledger on the test database and assert explicit error observations. For cancellation, resolve a path hash, admit each pending/retry/recorded variant, call cancelIndexingJob twice, and inspect depth, claims, and recent ledger outcome. On the before implementation, count failure can appear healthy/empty and recorded cancellation uses an invalid success outcome. Each named method still needs actual assertion-failure evidence; an uncaught cancellation exception would not satisfy that requirement."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackoffPolicyTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "For zero, negative, small, production-sized, and Long.MAX_VALUE bases against zero, finite, and Long.MAX_VALUE caps, require the nonnegative exact exponential sequence followed by stable saturation. Counts include negative clamps, failures 54–65, 1000, and Integer.MAX_VALUE. Expected values use independent BigInteger arithmetic, with explicit saturation after count 63.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D29 (Exponential backoff overflows before saturation) Check failure counts 54–65 for nonnegative, monotonic capped delays."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C35 **D29 — Saturation must hold beyond both multiplication overflow and Java shift wrapping [broken].** Check production base/cap at failures 54–65, larger counts through `Integer.MAX_VALUE`, already-capped bases, zero base, and existing negative-input clamps; require the documented exponential sequence followed by stable saturation."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackoffPolicyTest.java#saturatesAcrossMultiplicationOverflowShiftWrappingAndExtremeCounts"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "S-D29 (Exponential backoff overflows before saturation) Check failure counts 54–65 for nonnegative, monotonic capped delays."
        },
        "reproduction": "Evaluate the base/cap/count matrix in the named test and compare each delay with the independent capped BigInteger oracle, also asserting monotonicity and bounds. The before multiplication and shift behavior diverges at overflowing or wrapped counts."
      },
      "reproduction": "Evaluate the base/cap/count matrix in the named test and compare each delay with the independent capped BigInteger oracle, also asserting monotonicity and bounds. The before multiplication and shift behavior diverges at overflowing or wrapped counts."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java"
      ],
      "class": "Defect pin",
      "behaviour": "Initial system maintenance scans over two files complete with admitted counts 0, 1, or 2 according to actual admissions, with no terminal error. A new preservation check still propagates an actual admission exception. The existing refusal/progress test retains all expected outcomes but now explicitly supplies user/rpc provenance and scan id explicit, selecting ordinary admission rather than the maintenance path whose partial/no-op counts C30 deliberately permits. This input change is scenario-authorized, not a whole-oracle Adaptation; preservation check ids are listed below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C30 **D124 — Honest no-op counts must not make successful scans fail.** `WorkerScanOps.flushBatch` currently requires `accepted == batch.size()`; run all-no-op and mixed maintenance scans through that caller and require successful completion with accurate admitted counts, while real admission failures still surface."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C31 **D124 — Maintenance handling must preserve deliberate readmission behavior.** Check ordinary explicit scans, FORCE_REINDEX, watcher/readmission paths, RETRY_EXHAUSTED revival, recorded memberships, and candidate-generation admission; require their existing reset, ownership, and journal guarantees to remain intact."
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp7.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java#maintenanceScanAcceptsNoopsAndReportsOnlyActualAdmissions"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "label": "agent choice",
          "choice": "C30 **D124 — Honest no-op counts must not make successful scans fail.** `WorkerScanOps.flushBatch` currently requires `accepted == batch.size()`; run all-no-op and mixed maintenance scans through that caller and require successful completion with accurate admitted counts, while real admission failures still surface."
        },
        "reproduction": "Scan two files with a mocked maintenance enqueue result of 0, 1, or 2. Assert complete, empty terminal reason, and the matching admitted count. The before flushBatch rejects 0 and 1 because accepted differs from batch size; the full-admission variant remains a preservation case."
      },
      "reproduction": "Scan two files with a mocked maintenance enqueue result of 0, 1, or 2. Assert complete, empty terminal reason, and the matching admitted count. The before flushBatch rejects 0 and 1 because accepted differs from batch size; the full-admission variant remains a preservation case."
    }
  ]
}
```

## Acceptance records
