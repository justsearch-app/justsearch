---
schema: test-intent.v1
task: t-20261020-c2e24a
author-role: builder
author-session: 01a16e27-471b-7b13-962b-48b7ad3a8943
---

# Test-intent entries

Owner adoption is task `t-20261020-c2e24a`, agreed 2026-10-20 with the exact words “i agree, proceed accordingly”. The author is thread `01a16e27-471b-7b13-962b-48b7ad3a8943`. Orchestrator design and corrupt/budget rulings remain delegated choices, never owner words. Every original U6/U13 and must-not-break obligation and all six amendments remain in force. Original D4 collection remains explicitly frozen-code evidence; integrated production dependencies differ and are not silently treated as a fresh recovery run. Separate R1 executions retain their actual commands and complete input manifests.

U6 and U13 were delegated verbatim in the builder assignment on 2026-10-24. The
amended PR4 design governs the implementation. No acceptance record is authored here. Verification continues under the orchestrator's rulings: corrupt-commit backup-first assertions pass on base; both base and candidate resume over-budget independent debt in MIGRATING, so the restart expectation follows base. Raw builder outputs are recorded outside the repository and source-bound execution envelopes are tracked; acceptance remains reserved for independent verifiers. The budget matrix exercises finite limits, exact limits, -1, forceSwitching and restart; it is preservation coverage, not a claimed new fail-before repair.

```json
{
  "entries": [
    {
      "class": "Adaptation",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerProducerOwnershipTest.java"
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The reaper fixture signals that close began from the registered scheduler shutdown/shutdownNow methods. The former interrupt latch was a synchronization signal for forced shutdown. Registration, two-minute cadence, close-waits-for-release, queue-stays-open, queue-close and initializer assertions keep the same conditions and deadlines. The initializer cancellation signal is unchanged. The new shutdown check independently requires maintenance not to be interrupted. Original and adapted ownership checks are compared on base.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Interrupted release-0.3.0 retirement and recovery through restart; no commit, schema change or product option.",
          "choice": "Repair the observed shutdown race by draining maintenance cooperatively, matching the existing indexing-loop shutdown policy. Observe scheduler shutdown directly in the ownership fixture while preserving all ownership and ordering requirements; prove original/adapted checks on base.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Adaptation of instrumentation: the diff replaces the interrupt latch used to detect close entry with the registered scheduler shutdown notification. Registration/cadence, the running callback, release latch, close-waits and queue-stays-open predicates are unchanged. Original and adapted ownership checks pass on 8292; the added real-writer shutdown check separately pins safe non-interruption. No owner words are attributed to this implementation choice."
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RecordedMigrationRestartRecoveryTest.java"
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The Switching fixture strict-count mock follows its configured jobStateCounts values, including the in-flight root walk (5,000 pending and one processing), instead of contradicting those values with a constant empty queue. No assertion, fault injection, deadline or scenario changes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U13",
          "quote": "Waiting jobs do not authorize an incomplete cutover where a migration does run: backoff-only work is not treated as drained, and the failed-job budget and explicit overrides apply as configured.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        }
      ]
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExactFileRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ReplayRetentionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ChallengerRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java"
      ],
      "behaviour": "Keep the existing native-migration preservation scenarios on the native path by making A's analyzer fingerprint genuinely incompatible after authentic fixture unpack/verification. Preserve all original assertions: exact-file recovery, independent and indexed exhausted debt, deadline failure/restart, completed Green admission identity and retention, unchanged fast path, preparation before claims and completed native cutover. Compatible migration retirement is covered separately by U6 checks. These replace the former compatible-fixture native precondition, which the adopted U6 retirement intentionally changes; this is not claimed as a mechanical adaptation. The native assertions remain preservation checks, to pass on both base and candidate. The identity helper has an explicit native-analyzer mode which asserts exactly the declared analyzer mismatch, while retaining the default compatibility assertion and requiring all other identities to match. R40 instrumentation counts the current eight-argument recovery dispatch; F5 still requires one initial full walk and zero repeated walks after restart. Its assertion and durable-cooldown requirement are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U13",
          "quote": "Waiting jobs do not authorize an incomplete cutover where a migration does run: backoff-only work is not treated as drained, and the failed-job budget and explicit overrides apply as configured.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Retire obsolete interrupted release-0.3.0 migrations on base 8292c62e3, preserving #893 recovery; no commit, schema change or option.",
          "choice": "Cancel only with a positive compatibility certificate for A. Reconcile every A/B difference against sources and retain uncertain obligations and B. Keep new mutations ordered by accepted_order in the existing fenced journal during replay. Preserve preparation/root debt until retirement and both pointer copies are normalized. Use authentic fixtures and real admission for receipt-bearing variants.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExactFileRecoveryTest.java#nativeInterruptedMigrationCompletesWithoutStateEdits",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ExactFileRecoveryTest.java#nativeCutoverKeepsUnavailableExactOldContentUntilItReturns",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ReplayRetentionTest.java#completedLegacyEffectIsSettledThroughNativeReplay",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ReplayRetentionTest.java#routineRetentionKeepsFailedCutoverReceiptUntilRecoveryCompletes",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ReplayRetentionTest.java#committedNativeFileRecoveredAsUnchangedKeepsItsAdmission",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java#neverIndexedExhaustedDebtDoesNotHoldNativeCutover",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java#actualDeadlineFailureRestartsWithIndependentExhaustedDebt",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030AdmissionDebtTest.java#indexedExhaustedExactContentIsNotDiscardedAtCutover",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ChallengerRecoveryTest.java#interruptedUpgradePreparesBeforeClaimsAndCompletesNativeCutover",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ChallengerRecoveryTest.java#includedUnreadableFileKeepsDebtWithoutRepeatedWalksAcrossRestart"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "New, not mechanical Adaptation or Obsolete: the actual diff changes authentic native-fixture inputs to a genuine analyzer incompatibility. U6 supersedes the former assumption that a compatible interrupted release must migrate. The same native recovery/retention obligations and assertions are exercised under a true native precondition, before and after; compatible retirement has separate checks. No guarded obligation is removed.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeProbeTest.java"
      ],
      "behaviour": "Preserve A generation identity, original analyzer/model identities and all legacy queue rows across interrupted-fixture boot. The former exact commit-fingerprint equality assumed read-only A: U6 opens A writable and compatible optional-field additions legitimately restamp its fingerprint. Preserve the compatibility identities explicitly instead. No exception or row-retention assertion changes. This is a preservation check on both trees.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Retire obsolete interrupted release-0.3.0 migrations on base 8292c62e3, preserving #893 recovery; no commit, schema change or option.",
          "choice": "Cancel only with a positive compatibility certificate for A. Reconcile every A/B difference against sources and retain uncertain obligations and B. Keep new mutations ordered by accepted_order in the existing fenced journal during replay. Preserve preparation/root debt until retirement and both pointer copies are normalized. Use authentic fixtures and real admission for receipt-bearing variants.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeProbeTest.java#interruptedMigrationFixturesKeepTheirServingIndexAndJobs"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "New preservation oracle, not mechanical Adaptation or Obsolete: the diff removes whole-fingerprint equality and explicitly retains A generation, analyzer and all three model identities plus every legacy queue row. U6 and amendment 2 permit additive optional-field restamping, so the full serialized fingerprint was a stronger input assumption than the adopted compatibility proposition. The retained proposition is established before and after, rather than treating code output as a source.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/LiveMigrationCutoverTest.java"
      ],
      "behaviour": "Preserve native failure-budget, embedding, promotion and gap-settlement assertions. Declare the mocked queue empty through the strict counts interface used by U13; Mockito otherwise returns null. No assertion changes.",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The empty mocked queue now explicitly answers the strict drain-count method; every test assertion and fault injection remains unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U13",
          "quote": "Waiting jobs do not authorize an incomplete cutover where a migration does run: backoff-only work is not treated as drained, and the failed-job budget and explicit overrides apply as configured.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        }
      ]
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030MigrationRetirementTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCrashTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementBudgetTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementOrderingTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPartialWalkTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCompletedBuildTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RecoveryShutdownTest.java"
      ],
      "behaviour": "U6: authentic MIGRATING and SWITCHING data boot through KnowledgeServer, replay current updates and confirmed deletions onto the original serving generation, preserve old search, retire B and migration leftovers, and survive restart. The DONE explicit-file case preserves content outside roots. Unavailable-root checks keep old search, B and durable debt across restart while unrelated mutations proceed, then retire after confirmation. Negative certificate checks preserve the native path on analyzer/vector differences, unresolved models and corrupt commits. Process crashes cover checkpoint/pointer publication, queue/sidecar preparation, difference reconciliation, committed replay before acknowledgement, acknowledgement before replay-complete publication, physical deletion, retirement before pointer-backup normalization, and final cleanup. Restoring the pointer backup cannot revive the obsolete build; scheduled root recovery resumes. Paused replay races acknowledged updates, deletes and prefix deletes before a real restart; unrelated admission remains available. A genuinely unreadable subtree reports delete_detection_unverified over the live sync interface and keeps its SYNC_ROOT and B until a full walk. Packaged HTTP evidence loads production classes from the complete bundled headless jars and runtime and reuses one fixed switching.zip Engine folder across first boot and restart, searching old, replayed and deleted paths. Both phase variants also build completed changes through real B admissions, commit and verify their effects, then remove only those exact receipts to model released unjournalled completion. A B-only file, B-only update and B-only deletion must reconcile onto A; real historical writer closure proves B's sibling clean-shutdown marker exists, and its obsolete rebuild-pending marker is also retired; denying the completed update retains old A content, sidecar debt and B through restart. Upgrade-root walks are journaled once before new mutations are accepted and their completion is durable before acknowledgement, preventing subsequent scheduled walks from resurrecting newer deletes. The root-journal transaction/sidecar publication gap is an additional process-kill cut; the deletion cut creates a real empty B commit so a payload removal exists to observe. A newer unscoped admission cannot borrow an A/B snapshot path as exact-file authority: old A bytes and B remain across restart, unrelated scoped mutations proceed, and only a deliberate exact-file admission indexes the new bytes and settles debt; its neighbor is never ingested. A separate ordinary-sync check on an IDLE release fixture directly proves incomplete-walk uncertainty reporting before/after and across restart; the interrupted native base cannot execute that ordinary request before retirement exists. Each successful exact SYNC_ROOT walk checkpoints its durable admissions before waiting for the writer, so retries do not repeatedly reset DONE rows. Real process kills cut root admission before/after that checkpoint and prove an unqueued root-only file survives. A public directory sync during retirement is journaled and fenced before a newer file update/delete/prefix-delete, and the race check asserts its scoped receipt exists. A deterministic real-boot shutdown control runs real serving-writer commits on the actual maintenance executor, observes whether close interrupts that work, waits for close, then restarts and verifies old/update/delete search and the original serving generation. The before-state unsafe signal is recorded before completing the real commit safely, and fails the final no-interruption assertion; no failure is hidden. This repairs the observed partial-walk restart race where forced maintenance interruption invalidates Lucene's shared native lock channel. R40 draft repair reconciles completed exact bounded B work using its retained difference and complete Q attribution; it does not relax the claim-owner validator. Confirmed nonregular removal is permission only: reobserve, delete A, commit, and require a fresh target proof before settlement. Even an empty SQL replay commits those A changes. The first five-class run failed both SWITCHING completed-build variants. Its retained state showed the B-deleted path had no current Q row but was superseded by a force-false ROOT declaration. The second repair excludes independent B differences from that declaration while preserving current-row and claim validation. All four completed-build variants pass the second five-class run; broader R40 preservation and canonical evidence are still pending.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U13",
          "quote": "Waiting jobs do not authorize an incomplete cutover where a migration does run: backoff-only work is not treated as drained, and the failed-job budget and explicit overrides apply as configured.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Retire obsolete interrupted release-0.3.0 migrations on base 8292c62e3, preserving #893 recovery; no commit, schema change or option.",
          "choice": "Cancel only with a positive compatibility certificate for A. Reconcile every A/B difference against sources and retain uncertain obligations and B. Keep new mutations ordered by accepted_order in the existing fenced journal during replay. Preserve preparation/root debt until retirement and both pointer copies are normalized. Use authentic fixtures and real admission for receipt-bearing variants.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Resume PR4 and settle corrupt-commit and budget preservation loops before completing the original checks.",
          "choice": "Corrupt A follows base's backup-first boot while B, migration state and receipts match base; assert the same outcome on both trees. Run budget checks unchanged on base first; follow base if it also resumes MIGRATING, otherwise repair any candidate regression. These rulings reset the repeated-failure loops. Executed base budget checks show the same MIGRATING restart phase as the candidate; retain the first-boot FAILED budget assertion and use MIGRATING for that restart, with A retained. The earlier timing-only U13 probe passed base and is not fail-before evidence; observe promotion entry deterministically using the real booted queue and writer.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCrashTest.java#killedRetirementResumesAndBackupCannotReviveBuild",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementOrderingTest.java#newerAcknowledgementWinsPausedReplayAndRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPartialWalkTest.java#partialWalkRetainsReceiptAndBuildUntilACompleteWalk",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCompletedBuildTest.java#completedBuildEffectsAreReconciledAndUncertaintyRetainsBuild",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#modernUnownedAdmissionCannotBorrowRetiredBuildAuthority",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPartialWalkTest.java#ordinaryPartialWalkReportsUnverifiedDeletionAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RecoveryShutdownTest.java#closeDrainsMaintenanceWithoutInterruptingTheServingWriter",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#reappearedLegacyDeleteProvesCurrentBytesBeforeAcknowledgement",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCrashTest.java#defaultSchedulerRecoversAcrossStartupAndRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCrashTest.java#cancellationDrainsOwnedCallbackAndSubmittedFailurePropagates"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: Split: the U6 authentic MIGRATING/SWITCHING boot, U13 backoff-only promotion, and DONE explicit-file outside roots exercise existing main behavior; keep repaired only for those checks. Crash cuts, ordering, uncertainty, completed-build reconciliation and shutdown are new retirement-path checks. Historical runs and their original revision bindings remain unchanged.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementHttpTest.java"
      ],
      "behaviour": "U6 packaged HTTP evidence (split from the entry above): production classes load from the complete bundled headless jars and runtime, and one fixed switching.zip Engine folder is reused across first boot and restart, searching old, replayed and deleted paths. Its evidence-tagged class is opt-in and needs the packaged headless resources, so the canonical runner's default test task cannot select it; its evidence remains the earlier packaged run, and the independent verifier reruns it on the final candidate.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U13",
          "quote": "Waiting jobs do not authorize an incomplete cutover where a migration does run: backoff-only work is not treated as drained, and the failed-job budget and explicit overrides apply as configured.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Retire obsolete interrupted release-0.3.0 migrations on base 8292c62e3, preserving #893 recovery; no commit, schema change or option.",
          "choice": "Cancel only with a positive compatibility certificate for A. Reconcile every A/B difference against sources and retain uncertain obligations and B. Keep new mutations ordered by accepted_order in the existing fenced journal during replay. Preserve preparation/root debt until retirement and both pointer copies are normalized. Use authentic fixtures and real admission for receipt-bearing variants.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Resume PR4 and settle corrupt-commit and budget preservation loops before completing the original checks.",
          "choice": "Corrupt A follows base's backup-first boot while B, migration state and receipts match base; assert the same outcome on both trees. Run budget checks unchanged on base first; follow base if it also resumes MIGRATING, otherwise repair any candidate regression. These rulings reset the repeated-failure loops. Executed base budget checks show the same MIGRATING restart phase as the candidate; retain the first-boot FAILED budget assertion and use MIGRATING for that restart, with A retained. The earlier timing-only U13 probe passed base and is not fail-before evidence; observe promotion entry deterministically using the real booted queue and writer.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementHttpTest.java#packagedSwitchingUpgradeAndRestartKeepReplayedSearch"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-recovery-current-bindings.json",
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: Split: the U6 authentic MIGRATING/SWITCHING boot, U13 backoff-only promotion, and DONE explicit-file outside roots exercise existing main behavior; keep repaired only for those checks. Crash cuts, ordering, uncertainty, completed-build reconciliation and shutdown are new retirement-path checks. Historical runs and their original revision bindings remain unchanged.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "Frozen source on execution HEAD 8292 plus its original uncommitted manifest, later materialized unchanged as d9663d16. Check/helper bytes still match integrated HEAD 1bb20567; changed production dependencies are enumerated separately. This is not a fresh integrated recovery execution."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementBudgetTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "behaviour": "Preservation on base and candidate: configured native failure budgets, exact/unlimited limits and forceSwitching; negative A certificates preserve native migration through restart; corrupt A follows the same backup-first boot, retained B/phase/receipts and empty replacement serving index as base.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U13",
          "quote": "Waiting jobs do not authorize an incomplete cutover where a migration does run: backoff-only work is not treated as drained, and the failed-job budget and explicit overrides apply as configured.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Retire obsolete interrupted release-0.3.0 migrations on base 8292c62e3, preserving #893 recovery; no commit, schema change or option.",
          "choice": "Cancel only with a positive compatibility certificate for A. Reconcile every A/B difference against sources and retain uncertain obligations and B. Keep new mutations ordered by accepted_order in the existing fenced journal during replay. Preserve preparation/root debt until retirement and both pointer copies are normalized. Use authentic fixtures and real admission for receipt-bearing variants.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Resume PR4 and settle corrupt-commit and budget preservation loops before completing the original checks.",
          "choice": "Corrupt A follows base's backup-first boot while B, migration state and receipts match base; assert the same outcome on both trees. Run budget checks unchanged on base first; follow base if it also resumes MIGRATING, otherwise repair any candidate regression. These rulings reset the repeated-failure loops. Executed base budget checks show the same MIGRATING restart phase as the candidate; retain the first-boot FAILED budget assertion and use MIGRATING for that restart, with A retained. The earlier timing-only U13 probe passed base and is not fail-before evidence; observe promotion entry deterministically using the real booted queue and writer.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#uncertifiedServingGenerationKeepsOriginalMigration",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementBudgetTest.java#nativeFailureBudgetAndForceSwitchingKeepTheirMeaning"
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "New preservation coverage: all 12 invocations pass before and on frozen code. Negative certificates and budget protections are not labelled repairs. Corrupt A keeps native backup-first behavior; restarted over-budget cases follow actual base MIGRATING while first-boot FAILED, exact/unlimited limits, overrides and completion protections remain asserted. Corrupt-commit attribution now uses a separate fresh method-only before/current entry; historical duplicate display names are retained without asserting unique binding.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommittedRetirementCertificateTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CommittedCertificateTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementRootProgressTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkCrashTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java"
      ],
      "behaviour": "U6/amendments1–4 and U7/U11 progress: ambiguous committed model inputs cannot retire a native migration; acknowledged later walks/updates/deletes retain accepted ordering through actual writer retry, process kill and restart; repeated faults retain debt/B; supported same-acknowledgement DB replacement cannot invalidate ordered deletion/source proof; disjoint roots progress while another receipt remains uncertain.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Repair independently reproduced R1 certificate and ordered-scope failures while retaining all adopted PR4 scenarios and six amendments.",
          "choice": "Require positive committed model identity; input-only unknowns retain native migration while complete digests permit determinate absence. Commit and verify exact deletions before recording their receipt versions, recognize later confirmed SYNC_ROOT scope in accepted order, preserve unresolved overlapping obligations and allow disjoint root progress. Correct only the replay fixture fault to one actual injection at the target later completed walk; retain repeated faults as pending/retained obligations without demanding completion before configured eligibility. Keep original faults/results, 100-second completion/search/restart oracles, native paths and all policies unchanged. Preserve a later exact receipt-owned DONE unit, planned source, collection and ingestion boundary during older upgrade recovery discovery; retain ordinary native certification and all uncertainty guards. Correct the named-collection fixture through real public registration and prove its queue membership. A legitimately later public sync is a distinct ownership transition; its remaining stale-receipt completion failure is reported for root rather than weakening native certification.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CommittedCertificateTest.java#unknownStoredModelCannotCertifyRetirement",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommittedRetirementCertificateTest.java#unknownStoredInputsAllowNativeOpeningButCannotCertifyRetirement",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java#acknowledgedPrefixThenForcedSyncSurvivesPendingWriterRetry",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java#overlappingScopesKeepTheirAcceptedOrderAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java#repeatedFaultsRetainDebtAndDeletionProofAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkCrashTest.java#completedWalkSurvivesAnActualKillAndRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementRootProgressTest.java#uncertainEarlierAlphaDoesNotStarveUnqueuedBetaOrAcknowledgedMutation"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: committed retirement certificates and retirement walk ordering/progress are introduced by this PR. Historical runs and their original revision bindings remain unchanged.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementOrderingTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommittedRetirementCertificateTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CommittedCertificateTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java"
      ],
      "behaviour": "Preserve complete matching committed digest without diagnostic inputs, fully resolved inputs-only identity, determinate NOT_CONFIGURED and its genuine transition to PRESENT, genuine model change/native opening and current uncertainty; ordinary ordered retry/restart and a real newer operation reusing its journal key retain their semantics.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Repair independently reproduced R1 certificate and ordered-scope failures while retaining all adopted PR4 scenarios and six amendments.",
          "choice": "Require positive committed model identity; input-only unknowns retain native migration while complete digests permit determinate absence. Commit and verify exact deletions before recording their receipt versions, recognize later confirmed SYNC_ROOT scope in accepted order, preserve unresolved overlapping obligations and allow disjoint root progress. Correct only the replay fixture fault to one actual injection at the target later completed walk; retain repeated faults as pending/retained obligations without demanding completion before configured eligibility. Keep original faults/results, 100-second completion/search/restart oracles, native paths and all policies unchanged. Preserve a later exact receipt-owned DONE unit, planned source, collection and ingestion boundary during older upgrade recovery discovery; retain ordinary native certification and all uncertainty guards. Correct the named-collection fixture through real public registration and prove its queue membership. A legitimately later public sync is a distinct ownership transition; its remaining stale-receipt completion failure is reported for root rather than weakening native certification.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CommittedCertificateTest.java#completeCommittedEvidenceRetiresAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030CommittedCertificateTest.java#changedCommittedModelKeepsNativeMigration",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommittedRetirementCertificateTest.java#completeDigestCertifiesKnownAbsentModelsWithOrWithoutDiagnosticInputs",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommittedRetirementCertificateTest.java#resolvedInputsWithoutDigestCertifyCompatibility",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommittedRetirementCertificateTest.java#currentUncertaintyCannotBorrowACompleteStoredIdentity",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommittedRetirementCertificateTest.java#completeNotConfiguredIdentityBecomingPresentIsAGenuineChange",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java#ordinaryOrderedPrefixThenSyncSurvivesRetryAndRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java#newerDeleteUsingTheSameKeyCannotBorrowTheOldReceiptProof"
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "New preservation coverage:13 exact-source invocations must pass before and after. No blanket null rejection, native comparator change, model assets/inference, retry shortening or timeout change. Receipt revisions are fresh opaque UUID versions; accepted_order supplies ordering. The same-key check uses real admissions and restarts, never fabricated identities.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementOrderingTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "behaviour": "U6/U11: unavailable-root obligations and B remain while unrelated mutations proceed; a returned root resolves its debt through real boots/restarts.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Repair independently reproduced R1 certificate and ordered-scope failures while retaining all adopted PR4 scenarios and six amendments.",
          "choice": "Require positive committed model identity; input-only unknowns retain native migration while complete digests permit determinate absence. Commit and verify exact deletions before recording their receipt versions, recognize later confirmed SYNC_ROOT scope in accepted order, preserve unresolved overlapping obligations and allow disjoint root progress. Correct only the replay fixture fault to one actual injection at the target later completed walk; retain repeated faults as pending/retained obligations without demanding completion before configured eligibility. Keep original faults/results, 100-second completion/search/restart oracles, native paths and all policies unchanged. Preserve a later exact receipt-owned DONE unit, planned source, collection and ingestion boundary during older upgrade recovery discovery; retain ordinary native certification and all uncertainty guards. Correct the named-collection fixture through real public registration and prove its queue membership. A legitimately later public sync is a distinct ownership transition; its remaining stale-receipt completion failure is reported for root rather than weakening native certification.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#unavailableRootKeepsBuildAndOldSearchWhileOtherMutationsProceed"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: retaining retirement debt/B for an unavailable root is a new retirement path. Historical runs and their original revision bindings remain unchanged.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "behaviour": "Amendment2 preservation: corrupt A follows base backup-first boot with native migration state/B/receipts retained; uncertainty cannot certify retirement.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Repair independently reproduced R1 certificate and ordered-scope failures while retaining all adopted PR4 scenarios and six amendments.",
          "choice": "Require positive committed model identity; input-only unknowns retain native migration while complete digests permit determinate absence. Commit and verify exact deletions before recording their receipt versions, recognize later confirmed SYNC_ROOT scope in accepted order, preserve unresolved overlapping obligations and allow disjoint root progress. Correct only the replay fixture fault to one actual injection at the target later completed walk; retain repeated faults as pending/retained obligations without demanding completion before configured eligibility. Keep original faults/results, 100-second completion/search/restart oracles, native paths and all policies unchanged. Preserve a later exact receipt-owned DONE unit, planned source, collection and ingestion boundary during older upgrade recovery discovery; retain ordinary native certification and all uncertainty guards. Correct the named-collection fixture through real public registration and prove its queue membership. A legitimately later public sync is a distinct ownership transition; its remaining stale-receipt completion failure is reported for root rather than weakening native certification.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#corruptCommitKeepsNativeRecoveryInsteadOfRetiring"
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "classificationRationale": "The existing check source/assertions are unchanged. Unique method-only8292/current preservation runs are required and remain pending; duplicate historical display names are never assigned by status.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementAckRecoveryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementContinuationTest.java"
      ],
      "behaviour": "U6/U14b: real child kills before/after exact SQL ACK through both captured phases and two verified boots; real failed physical cleanup followed by higher-order public admissions, same-key ROOT, exact C1-to-C2 unit/retry preservation, newer projection DELETE and eventual normalized physical retirement.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Complete the settled PR4 retirement ACK/reopen/original-ROOT ownership repair; no publishing, schema, option or policy changes.",
          "choice": "Retain exact verified removal packets in the existing checkpoint before SQL ACK, bind current queue proofs to open session and accepted versions, freeze legacy scope metadata before losing Q, retain actual ROOT owners, preserve configured retry policy and use existing targeted writer forcing for positively changed members. Keep native watcher reconciliation and generic certificates strict.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: retirement SQL ACK, cleanup continuation and ownership mechanics do not exist on main. Historical runs and their original revision bindings remain unchanged.",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementAckRecoveryTest.java#acknowledgedHeadResumes",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementAckRecoveryTest.java#committedBeforeAckResumes",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementContinuationTest.java#laterAdmissionsAfterEmptySqlAckKeepTheirOwnersAcrossRestart"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json"
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementRootImageTest.java"
      ],
      "behaviour": "U6/U11 ordered-scope preservation: actual available Alpha registration before uncertainty, later ordinary ROOT image and named live collection metadata, genuinely unrelated acknowledged content, real retention pins/release, confirmed-source convergence and restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Complete the settled PR4 retirement ACK/reopen/original-ROOT ownership repair; no publishing, schema, option or policy changes.",
          "choice": "Retain exact verified removal packets in the existing checkpoint before SQL ACK, bind current queue proofs to open session and accepted versions, freeze legacy scope metadata before losing Q, retain actual ROOT owners, preserve configured retry policy and use existing targeted writer forcing for positively changed members. Keep native watcher reconciliation and generic certificates strict.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "New running-system/queue/Lucene regression coverage. Existing assertions, faults, deadlines and policy checks are retained; no historical duplicate XML is assigned by status. R12 raw commands/source manifests distinguish initial/intermediate/final source and setup failures; final D4 envelopes and independent acceptance remain pending.",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementRootImageTest.java#acknowledgedImageSurvivesUncertaintyThenConverges"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json"
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementRootImageTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPrefixRestoreTest.java"
      ],
      "behaviour": "U6/U14b: changed same-stamp ROOT bytes require actual targeted writer work while ordinary unchanged siblings keep the normal skip/no-planned-hash/no-exact-receipt path. Authentic SAMEACK VACUUM before legacy prefix conversion is restored offline with A/B/checkpoint intact; normal two boots must reuse the frozen full typed receipt UUID/payload/order/count, retain uncertain Alpha/A/B and never mint another conversion admission.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Complete the settled PR4 retirement ACK/reopen/original-ROOT ownership repair; no publishing, schema, option or policy changes.",
          "choice": "Retain exact verified removal packets in the existing checkpoint before SQL ACK, bind current queue proofs to open session and accepted versions, freeze legacy scope metadata before losing Q, retain actual ROOT owners, preserve configured retry policy and use existing targeted writer forcing for positively changed members. Keep native watcher reconciliation and generic certificates strict.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: retirement root-image and frozen prefix-successor mechanics do not exist on main. Historical runs and their original revision bindings remain unchanged.",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementRootImageTest.java#sameStampedPublicSyncTargetsOnlyChangedMember",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPrefixRestoreTest.java#restoredRawPrefixReusesTheExactFrozenSuccessor"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json"
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPartialWalkTest.java"
      ],
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The private checkpoint reader now uses the existing Release030RecoveryState.differencesComplete() API instead of comparing retire|differences to the literal true. The settled session/admission-set binding changed this unshipped marker representation; all source permissions, partial-response assertion, counts, receipt/B/search, two restarts and 90/100-second oracles remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Complete the settled PR4 retirement ACK/reopen/original-ROOT ownership repair; no publishing, schema, option or policy changes.",
          "choice": "Retain exact verified removal packets in the existing checkpoint before SQL ACK, bind current queue proofs to open session and accepted versions, freeze legacy scope metadata before losing Q, retain actual ROOT owners, preserve configured retry policy and use existing targeted writer forcing for positively changed members. Keep native watcher reconciliation and generic certificates strict.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "A representation reader adaptation, not an assertion or scenario relaxation. The adapted same real method/class passes on f081; candidate narrow refresh is recorded separately. Genuine transitive and primary retry failures remain open and are not reclassified by this adaptation."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementLegacyMetadataTest.java"
      ],
      "behaviour": "The genuine released v13 DONE/SUCCESS_FULL mcp-ingest row outside every root preserves its named collection and exact-file boundary through raw UPSERT recovery and restart. A missing original Q row with no frozen original namespace retains B/journal and admits no guessed job across changed root collection configurations. Only raw receipt fixture input is seeded; DONE is the captured release writer result.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request; bounded R14 builder assignment",
          "delegationScope": "Finish positively obsolete live metadata reconciliation, diagnose the four ordering failures with real Q/A collection evidence, and minimally preserve original legacy metadata; no broader D4 or cost trial.",
          "choice": "Keep an existing raw legacy row collection (null means default), fill only its missing boundary with its exact file, freeze this original metadata during initial inventory, and refuse missing Q without original frozen namespace. Correct named-delete fixture preconditions only through real public registration and writer completion; preserve substantive ordering, content, restart and timeout assertions.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementLegacyMetadataTest.java#namedDoneRawReceiptKeepsCollectionAndExactBoundary",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementLegacyMetadataTest.java#missingOriginalQueueNamespaceStaysHeldAcrossChangedConfig"
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: raw-receipt namespace attribution and recovery are new retirement mechanics; the existing DONE outside-root repair is separately pinned by entry 6. Historical runs and their original revision bindings remain unchanged.",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real.",
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json"
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementTransitiveOrderTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java"
      ],
      "behaviour": "Named collection deletion starts only after public watch/sync and the real writer establish Q and A membership in that named collection. Boot bindings alone did not register the live watcher or move released default-collection A images. Existing final per-path, ordering, source uncertainty, retention, restart, B and pointer checks keep their original requirements and deadlines. A diagnostic line records the primary callback pending predicate before its unchanged assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request; bounded R14 builder assignment",
          "delegationScope": "Finish positively obsolete live metadata reconciliation, diagnose the four ordering failures with real Q/A collection evidence, and minimally preserve original legacy metadata; no broader D4 or cost trial.",
          "choice": "Keep an existing raw legacy row collection (null means default), fill only its missing boundary with its exact file, freeze this original metadata during initial inventory, and refuse missing Q without original frozen namespace. Correct named-delete fixture preconditions only through real public registration and writer completion; preserve substantive ordering, content, restart and timeout assertions.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementTransitiveOrderTest.java#transitiveDeferredCollectionCannotEraseLaterBetaSync",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java#overlappingScopesKeepTheirAcceptedOrderAcrossRestart"
      ],
      "classificationRationale": "New fixture preconditions via real public interfaces, not a product oracle waiver. The original wrong-namespace raw observations remain preserved. Current corrected results and any primary callback contradiction are recorded in the R14 handback.",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real.",
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json"
    },
    {
      "class": "New",
      "closureKey": "sync-policy",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSyncPolicyTest.java"
      ],
      "behaviour": "Retirement routing preserves ordinary MAINTENANCE retry waits and exact admission ownership; explicit DEFAULT resets retry metadata. The legacy hash entry does not throw on an interrupted caller, returns the same hash and retains the interrupt flag; finally always clears the flag.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "Retirement routing preserves ordinary MAINTENANCE retry waits and exact admission ownership; explicit DEFAULT resets retry metadata. The legacy hash entry does not throw on an interrupted caller, returns the same hash and retains the interrupt flag; finally always clears the flag.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "Preservation of released native policy: the identical final corrected whole check executes all three cells on genuine clean b84 and final production, and all three pass on both. MAINTENANCE and DEFAULT retain their existing contract; the legacy hash entry retains no-throw/hash/interrupt/finally assertions. Earlier intermediate R8 overlay failures remain their exact source history; an uncaught InterruptedIOException is not intended assertion fail-before.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSyncPolicyTest.java#liveSyncKeepsItsOrdinaryRetryPolicy",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSyncPolicyTest.java#legacyHashEntryStillReadsOnAnInterruptedThread"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "force-false-repaired",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementForceFalseTest.java"
      ],
      "behaviour": "An older ordinary ROOT member retains its entire queue row under a later force-false C ROOT and explicit C delete. Changed bytes are corrected, installed, claimed and searchable in the original namespace through retirement and restart. R40 draft repair lets an inapplicable native conditional correction fall through to independently validated retained FILE or ROOT authority; a force-false root without an actual member does not become that file's creator. The five-class after run passes these cases; broader preservation remains pending.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "An older ordinary ROOT member retains its entire queue row under a later force-false C ROOT and explicit C delete. Changed bytes are corrected, installed, claimed and searchable in the original namespace through retirement and restart.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: retirement correction and inherited namespace ownership are new mechanisms. Historical runs and their original revision bindings remain unchanged.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementForceFalseTest.java#indexedOrdinaryMemberKeepsOlderNamespaceAcrossForceFalseAndDelete",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementForceFalseTest.java#readableChangedOlderMemberCompletesInOriginalNamespaceAndRestarts"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "force-false-logical-default",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementForceFalseTest.java"
      ],
      "behaviour": "A known ROOT2 older ordinary owner with absent native collection is logical Default beneath a later force-false C ROOT. It retains its full row, corrects readable changed bytes under its original owner, installs/claims current content, retires and restarts in logical Default. R40 draft repair lets an inapplicable native conditional correction fall through to independently validated retained FILE or ROOT authority; a force-false root without an actual member does not become that file's creator. The five-class after run passes these cases; broader preservation remains pending.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "A known ROOT2 older ordinary owner with absent native collection is logical Default beneath a later force-false C ROOT. It retains its full row, corrects readable changed bytes under its original owner, installs/claims current content, retires and restarts in logical Default.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "This is a new positive combination/control under the released null inheritance contract. It is distinct from an unknown raw queue namespace, which stays held. No fabricated RootOrigin or new production seam supplies authority.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementForceFalseTest.java#logicalDefaultOlderRootSurvivesLaterForceFalseCollectionAndChangedSource"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "approved-native-gap",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/LegacyApprovedGapReplayTest.java"
      ],
      "behaviour": "Exact approval of a malformed legacy ROOT permits an unrelated valid explicit DELETE, preserves exact prepared versions and permits only their matching ACK.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "Exact approval of a malformed legacy ROOT permits an unrelated valid explicit DELETE, preserves exact prepared versions and permits only their matching ACK.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New preservation: main already excludes exact approved gap versions from replayDeleteOrder; the additional approved-ROOT filtering protects the retirement-only root-intent decoder introduced in this PR. Historical runs and their original revision bindings remain unchanged.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/LegacyApprovedGapReplayTest.java#exactApprovedMalformedRootPreservesUnrelatedDelete"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "approved-native-gap-controls",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/LegacyApprovedGapReplayTest.java"
      ],
      "behaviour": "An unapproved malformed ROOT stays held; a same-key changed revision cannot borrow old approval; original approved-prefix conversion cannot turn a skipped delete into an applied delete.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "An unapproved malformed ROOT stays held; a same-key changed revision cannot borrow old approval; original approved-prefix conversion cannot turn a skipped delete into an applied delete.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "All three complete controls pass on the identical promoted file before, on known-good and after. This promotion reuses the existing SQLite/Lucene fixture and replay context helper.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/LegacyApprovedGapReplayTest.java#upgradingAnApprovedGapCannotTurnItsSkippedDeleteIntoAnAppliedDelete",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/LegacyApprovedGapReplayTest.java#unapprovedMalformedRootIsHeld",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/LegacyApprovedGapReplayTest.java#changedRevisionCannotReuseApproval"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "closure-public-sync",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPublicSyncTest.java"
      ],
      "behaviour": "The stable public service driver requires all four watcher/watcherless and unchanged/changed cells: exact admission ownership, content, retirement and restart. Actual final whole source passed on bbdd. The b84 before source has different qualifiers; its real assertion failures are history, not an exact final-source before binding.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "The real stable public service exercises all four watcher/watcherless and unchanged/changed cells, including exact admission, content, retirement and restart. Final whole check plus actual helper bodies execute on genuine b84 and bbdd; earlier R5 source is history.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: public sync ordering during retirement is a new retirement path. Historical runs and their original revision bindings remain unchanged.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPublicSyncTest.java#laterPublicSyncMaySupersedeAnEarlierExactAdmission"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "closure-member-identity",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootMemberIdentityTest.java"
      ],
      "behaviour": "Four native-INHERIT and same-unit metadata tamper cases retain original whole-row and disjoint claim oracles. Stable existing queue API has genuine f69 intended assertion failures, not invalid originator fixtures.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "Four native-INHERIT and same-unit metadata tamper cases retain original whole-row and disjoint claim oracles. Stable existing queue API has genuine f69 intended assertion failures, not invalid originator fixtures.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: retained retirement root-member identity and metadata ownership are new mechanisms. Historical runs and their original revision bindings remain unchanged.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootMemberIdentityTest.java#inheritedMembersRemainDistinctAndMetadataEditsStayHeld"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "closure-durable-intent",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030DurableIntentCancellationTest.java"
      ],
      "behaviour": "Six ROOT/FILE/DELETE by mutable-wait cases retain durable intent and exact Q at cancellation before SQL, resume the same intent with actual mutable state, and preserve later winner/delete controls. Exact ea61 whole-source before has eighteen configured intended assertion failures; it is reused without rerun.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "Six ROOT/FILE/DELETE by mutable-wait cases retain durable intent and exact Q at cancellation before SQL, resume the same intent with actual mutable state, and preserve later winner/delete controls. Exact ea61 whole-source before has eighteen configured intended assertion failures; it is reused without rerun.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: durable retirement intent cancellation/recovery is a new mechanism. Historical runs and their original revision bindings remain unchanged.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030DurableIntentCancellationTest.java#durableIntentResumesBeneathPreservingRoot",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030DurableIntentCancellationTest.java#durableIntentResumesWithSameMutableRetry"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "closure-root-intent",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootIntentTest.java"
      ],
      "behaviour": "After-only direct API safety freezes explicit policy and retained membership, rejects historical/orphan policy borrowing, and preserves real per-file null inheritance. API absence is not fail-before. The stable public service driver separately demonstrates behavior repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "After-only direct API safety freezes explicit policy and retained membership, rejects historical/orphan policy borrowing, and preserves real per-file null inheritance. API absence is not fail-before. The stable public service driver separately demonstrates behavior repair.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "After-only direct API safety freezes explicit policy and retained membership, rejects historical/orphan policy borrowing, and preserves real per-file null inheritance. API absence is not fail-before. The stable public service driver separately demonstrates behavior repair.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootIntentTest.java#freshDefaultCannotBeReinterpreted",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootIntentTest.java#historicalMissingNamespaceCannotBeBackfilled",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootIntentTest.java#ordinaryRootInheritsEarlierAttributionAndClearsPlan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootIntentTest.java#freshCreatorCollectionIsFrozenAndClaimable",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootIntentTest.java#freshAutomaticCreatorCollectionIsFrozenWithoutBackfillingExistingRoot",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RootIntentTest.java#orphanNamespaceSlotDoesNotAuthorizeAnUnretainedReceipt"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "closure-live-admission",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java"
      ],
      "behaviour": "After-only current API safety retains same-B coalescing, blank-generation coexistence, exact raw metadata, cancellation and atomic checkpoint/SQL rollback. Public LIVE refresh never widens maintenance or automatic replay authority.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "After-only current API safety retains same-B coalescing, blank-generation coexistence, exact raw metadata, cancellation and atomic checkpoint/SQL rollback. Public LIVE refresh never widens maintenance or automatic replay authority.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "After-only current API safety retains same-B coalescing, blank-generation coexistence, exact raw metadata, cancellation and atomic checkpoint/SQL rollback. Public LIVE refresh never widens maintenance or automatic replay authority.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java#unreadableExactSourceRollsBackTheWholeAdmissionBatch",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java#cancellationDuringExactHashLeavesItsPriorAdmissionIntact",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java#automaticReplayKeepsTheExactRetryAndMintsNoAcknowledgement",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java#liveRootRefreshRetainsOriginalRawReceiptAcrossCoalescingAndRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java#rawCheckpointSaveFailureRollsBackSqlAndAllowsRetry",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030LiveSyncAdmissionTest.java#rawHashCancellationPreservesTheOriginalReceiptAndRetry"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "closure-claim-ownership",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java"
      ],
      "behaviour": "After-only current claim guard matrix preserves same-unit metadata fencing, recorded ownership and healthy disjoint work. Earlier CP-prerequisite and invalid provenance failures remain invalid setup, never intended guard assertion evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "After-only current claim guard matrix preserves same-unit metadata fencing, recorded ownership and healthy disjoint work. Earlier CP-prerequisite and invalid provenance failures remain invalid setup, never intended guard assertion evidence.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "After-only current claim guard matrix preserves same-unit metadata fencing, recorded ownership and healthy disjoint work. Earlier CP-prerequisite and invalid provenance failures remain invalid setup, never intended guard assertion evidence.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java#changedMetadataStaysHeldAfterBindingWasPreparedWhileDisjointRootClaims",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java#obsoleteMismatchedMetadataIsHeldRatherThanAdministrativelyErased",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java#historicalScopesPreserveLaterPendingOwnersAcrossReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java#rootIdentityCannotAuthorizeOutsideItsScopeOrExclusions",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java#nonhistoricalDeleteCannotAcquireHistoricalQueueProof",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RetirementClaimOwnershipTest.java#historicalReconciliationRetainsRecordedCoverageAndUnacknowledgedSeal"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "closureKey": "closure-inherited-policy",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementInheritedPolicyTest.java"
      ],
      "behaviour": "The real public null-policy ROOT preserves each existing namespace/provenance and permits normal new Default membership, including late watcher registration, correction and restart. This is preservation of released native policy, not the obsolete uniform-Default expectation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "The real public null-policy ROOT preserves each existing namespace/provenance and permits normal new Default membership, including late watcher registration, correction and restart. This is preservation of released native policy, not the obsolete uniform-Default expectation.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "New positive preservation coverage of released null-policy inheritance, distinct from explicit nonnull Default. One exact whole-check after passes on captured bbdd with unchanged invoked helpers. No before run or current-parent runtime pass is manufactured.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementInheritedPolicyTest.java#nullRootPreservesExistingCollectionAndAttributionAcrossLateRegistration"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java"
      ],
      "behaviour": "Preserve complete correction rollback/mutable-retry/ACK/disjoint and inherited attribution oracles; adapt only the private helper call signature.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "Preserve complete correction rollback/mutable-retry/ACK/disjoint and inherited attribution oracles; adapt only the private helper call signature.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "classificationRationale": "Direct current-scope API safety is after-only; explicit inherited provenance has genuine a6f9 intended assertion failure on identical final source. NoSuchMethodException is archived setup failure.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java#sameIntentResumesAfterRollbackWithActualMutableRetryPreserved",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java#laterUnwalkedRootHoldsAnInstalledCorrectionWhileDisjointRootClaims"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java"
      ],
      "behaviour": "Preserve complete correction rollback/mutable-retry/ACK/disjoint and inherited attribution oracles; adapt only the private helper call signature.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Evidence closure for the settled PR4 retirement and recovery contract; production behavior is frozen.",
          "choice": "Preserve complete correction rollback/mutable-retry/ACK/disjoint and inherited attribution oracles; adapt only the private helper call signature.",
          "label": "agent choice"
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: retirement correction provenance and rollback history are new mechanisms. Historical runs and their original revision bindings remain unchanged.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030CorrectionHistoryTest.java#explicitCollectionRootRetainsInheritedAttributionDuringSourceCorrection"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java"
      ],
      "behaviour": "New direct current queue API safety: native cohort capture survives preparation/retry, missing or conflicting cohort holds, cancellation retains intent, predecessor/claim/later-authority guards remain exact. R40 retains the native cohort and complete-row claim checks while allowing conditional-path settlement to consult separate existing owners. Its direct preservation checks still require a fresh R40 run.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "label": "agent choice",
          "delegationScope": "Retirement recovery safety and evidence closure; author metadata only, independent acceptance remains outstanding",
          "choice": "Bind the applied native v13 cohort before preparation and exact conditional owner at actual claim; unknown/modern/forged authority is held. Preserve all direct queue safety controls."
        }
      ],
      "repaired": false,
      "classificationRationale": "After-only direct API safety controls; no old API absence or blanket repaired-behavior claim. Actual R33 candidate2 executes all nine listed methods (17 cases).",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#changedNativePredecessorIsHeld",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#installedCorrectionNeedsOriginalCohortAtActualClaim",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#intentSurvivesSqlCancellationAndReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#laterAuthorityHoldsTheInstalledConditionalCorrection",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#changedNativeConditionalClaimsItsExactPathWithoutAnotherAck",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#checkpointWithoutOriginalCohortHoldsChangedSource",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#restoredConflictingV13CohortHoldsWithoutOverwritingCheckpoint",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#originalCohortSurvivesCurrentDatabasePreparation",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#failedNativeMigrationRetainsCapturedCohortForRetry"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java"
      ],
      "behaviour": "Freshly observed unknown namespace and modern-unowned rows remain held; a forged native cohort cannot lend original-row authority. The whole three-cell oracle is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "label": "agent choice",
          "delegationScope": "Retirement recovery safety and evidence closure; author metadata only, independent acceptance remains outstanding",
          "choice": "Bind the applied native v13 cohort before preparation and exact conditional owner at actual claim; unknown/modern/forged authority is held. Preserve all direct queue safety controls."
        }
      ],
      "classificationRationale": "Agent choice under task t-20261020-c2e24a request: New: captured retirement cohort and conditional correction authority are new mechanisms. Historical runs and their original revision bindings remain unchanged.",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030ConditionalCorrectionTest.java#freshlyObservedUnprovenRowIsHeld"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-after-r42.json",
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real."
    },
    {
      "class": "New",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030MigrationRetirementTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "behaviour": "U6: authentic MIGRATING and SWITCHING fixtures replay acknowledged updates and confirmed deletions onto the original serving generation, retire the obsolete build and survive restart. An already-DONE explicit file outside every root remains searchable without ingesting its neighbor. U13: backoff-only work must not enter live promotion or authorize an incomplete cutover; unrelated mutation admission remains available.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U6",
          "quote": "An upgrade from an interrupted 0.3.0 migration (state MIGRATING or SWITCHING, switch-buffer entries): acknowledged updates and deletes survive replay, including an interrupted replay, without losing the serving generation.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U13",
          "quote": "Waiting jobs do not authorize an incomplete cutover where a migration does run: backoff-only work is not treated as drained, and the failed-job budget and explicit overrides apply as configured.",
          "label": "agent-drafted, owner-adopted",
          "adoptedAt": "2026-10-20",
          "adoptionQuote": "i agree, proceed accordingly"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Retire obsolete interrupted release-0.3.0 migrations on base 8292c62e3, preserving #893 recovery; no commit, schema change or option.",
          "choice": "Cancel only with a positive compatibility certificate for A. Reconcile every A/B difference against sources and retain uncertain obligations and B. Keep new mutations ordered by accepted_order in the existing fenced journal during replay. Preserve preparation/root debt until retirement and both pointer copies are normalized. Use authentic fixtures and real admission for receipt-bearing variants.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You have full autonomy through the merges of #889 and PR 4, under the rules in that file; do not ask me for verdicts.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Resume PR4 and settle corrupt-commit and budget preservation loops before completing the original checks.",
          "choice": "Corrupt A follows base's backup-first boot while B, migration state and receipts match base; assert the same outcome on both trees. Run budget checks unchanged on base first; follow base if it also resumes MIGRATING, otherwise repair any candidate regression. These rulings reset the repeated-failure loops. Executed base budget checks show the same MIGRATING restart phase as the candidate; retain the first-boot FAILED budget assertion and use MIGRATING for that restart, with A retained. The earlier timing-only U13 probe passed base and is not fail-before evidence; observe promotion entry deterministically using the real booted queue and writer.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261020-c2e24a request",
          "delegationQuote": "the gate's before-state for this change is main, the PR's merge base (git merge-base HEAD origin/main), not an earlier candidate of this PR.",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Classify repaired versus new retirement checks and collect assertion fail-before only against the PR merge base.",
          "choice": "Use merge base 4f9df2c1f8b3c5c0377508c1b9ea8ae97d01d227. Entry 6: Split: the U6 authentic MIGRATING/SWITCHING boot, U13 backoff-only promotion, and DONE explicit-file outside roots exercise existing main behavior; keep repaired only for those checks. Crash cuts, ordering, uncertainty, completed-build reconciliation and shutdown are new retirement-path checks. Entry 8: New: committed retirement certificates and retirement walk ordering/progress are introduced by this PR. Entry 10: New: retaining retirement debt/B for an unavailable root is a new retirement path. Entry 12: New: retirement SQL ACK, cleanup continuation and ownership mechanics do not exist on main. Entry 14: New: retirement root-image and frozen prefix-successor mechanics do not exist on main. Entry 16: New: raw-receipt namespace attribution and recovery are new retirement mechanics; the existing DONE outside-root repair is separately pinned by entry 6. Entry 19: New: retirement correction and inherited namespace ownership are new mechanisms. Entry 21: New preservation: main already excludes exact approved gap versions from replayDeleteOrder; the additional approved-ROOT filtering protects the retirement-only root-intent decoder introduced in this PR. Entry 23: New: public sync ordering during retirement is a new retirement path. Entry 24: New: retained retirement root-member identity and metadata ownership are new mechanisms. Entry 25: New: durable retirement intent cancellation/recovery is a new mechanism. Entry 31: New: retirement correction provenance and rollback history are new mechanisms. Entry 33: New: captured retirement cohort and conditional correction authority are new mechanisms.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030MigrationRetirementTest.java#replayRetiresObsoleteBuildAndKeepsServingIdentity",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030MigrationRetirementTest.java#backoffNeverHoldsMutationAdmissionDuringForcedNativeCutover",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#alreadyDoneExplicitFileOnServingGenerationSurvivesResume"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-repaired-r42.json",
      "classificationRationale": "New repaired existing-main behavior: U6 authentic MIGRATING/SWITCHING boot, U13 backoff-only promotion, and already-DONE explicit file outside roots. Requires actual assertion failures against merge base 4f9df2c1f8b3c5c0377508c1b9ea8ae97d01d227; intermediate PR runs are history only.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "R42 canonical runner on HEAD 97309cf38 plus the uncommitted CI runtime repairs. Check bytes bind this candidate; every original assertion and parameter variant remains. Real application crash and restart processes remain real. Fail-before uses 4f9df2c1f8b3c5c0377508c1b9ea8ae97d01d227 with fixture overlays and an unused identity timing method and the identity-default scoped idle-delay seam solely for fixture compatibility; no repaired retirement behavior is added to the before tree.",
      "repaired": true
    },
    {
      "class": "Adaptation",
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030RecoveryStateFixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RootRecoveryIsolationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UnavailableRootTest.java"
      ],
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "Move the test-only one-argument checkpoint capture wrapper into the queue test fixture with the same exception translation. Use the shared shorter positive recovery timers in the isolated-root boot fixture. The denied-single-file check explicitly keeps production retry timings, including its one-minute-plus-jitter assertion. Every assertion, fault, process restart, scope and deadline bound stays in place. With faster scheduling, both independent-root progress waits also require actual Beta search visibility before the unchanged assertions; the attempt count and 90-second bound remain. The timing fixture uses a 2500 ms positive minimum delay and a 100 ms maintenance cadence. ReleasedModelIdentities also scopes the indexing loop idle sleep to 100 ms and restores its exact prior value with the existing fixture restorers. Production retains its 1000 ms default. Actual threads, owned shutdown drains and every assertion remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Never weaken or remove an assertion.",
          "location": "task PR951-CI request"
        }
      ]
    }
  ]
}
```

## Scenario map

| Check | Scenario | Evidence sought |
| --- | --- | --- |
| MigrationRetirementTest.replayRetiresObsoleteBuildAndKeepsServingIdentity | U6 | Both captured phases, original A, old/update/delete search, B retirement and restart. |
| MigrationRetirementTest.backoffNeverHoldsMutationAdmissionDuringForcedNativeCutover | U13 | Backoff-only and mixed work keep unrelated mutation admission available under forceSwitching; old results and the already-searchable queued update stay searchable on A through restart, with the same deleted-path visibility as base. |
| RetirementSafetyTest.alreadyDoneExplicitFileOnServingGenerationSurvivesResume | U6; U8 preservation | DONE explicit content survives without ingesting a real neighboring file. |
| RetirementSafetyTest.unavailableRootKeepsBuildAndOldSearchWhileOtherMutationsProceed | U6; U11/U14b preservation | An unconfirmed UPSERT or conditional DELETE retains old search, its receipt and B across restart, then resolves from returned sources. |
| RetirementSafetyTest.reappearedLegacyDeleteProvesCurrentBytesBeforeAcknowledgement | U6 | Present replacement bytes are committed and searchable before the legacy DELETE is acknowledged. |
| RetirementSafetyTest.uncertifiedServingGenerationKeepsOriginalMigration | U6/amendment 2 preservation | Analyzer/vector differences and unresolved model identity retain the native path. |
| RetirementSafetyTest.corruptCommitKeepsNativeRecoveryInsteadOfRetiring | U6/amendment 2 preservation | Identical backup-first boot, retained B/phase/receipts and no retirement certificate on base and candidate. |
| RetirementBudgetTest.nativeFailureBudgetAndForceSwitchingKeepTheirMeaning | U13 preservation | Finite/exceeded/exact/unlimited budgets, forceSwitching and the same restart phase as base; old/update/delete search follows the retained or promoted generation on both boots, preserving the queued update already searchable on A before a native cutover. |
| RetirementCompletedBuildTest.completedBuildEffectsAreReconciledAndUncertaintyRetainsBuild | U6/amendment 1 | Real committed B-only add/update/delete effects without receipts; unreadable DONE source retains old A and B across restart. |
| RetirementCrashTest.killedRetirementResumesAndBackupCannotReviveBuild | U6; U14b preservation | Both phases at cross-store cuts, a proven uncommitted A deletion, exact physical retirement, backup restoration and resumed scheduled roots. |
| RetirementOrderingTest.newerAcknowledgementWinsPausedReplayAndRestart | U6/amendment 3 | Newer updates, deletes and prefix deletes win accepted order; unrelated admission proceeds while an old attempt is paused. |
| RetirementPartialWalkTest.partialWalkRetainsReceiptAndBuildUntilACompleteWalk | U6/amendment 4; U11 preservation | Real denied subtree reports delete_detection_unverified, retaining SYNC_ROOT and B until complete reconciliation. |
| RetirementHttpTest.packagedSwitchingUpgradeAndRestartKeepReplayedSearch | U6 | Packaged production jars and bundled JVM serve old/update/delete HTTP search on first boot and restart, using one reusable Engine folder. |

| RetirementSafetyTest.modernUnownedAdmissionCannotBorrowRetiredBuildAuthority | U6; U8 preservation | Quarantine persists across two boots, A serves old bytes and B remains; deliberate exact-file admission settles it without admitting a neighbor. |

| RetirementPartialWalkTest.ordinaryPartialWalkReportsUnverifiedDeletionAcrossRestart | Amendment 4 | Real IDLE boot and restart expose partial-walk uncertainty directly; restored access reports a complete walk and indexes the hidden source. |

The installed-jar HTTP class is an opt-in evidence check. Its private Gradle runner
assembles bundleSidecarResources (jar, libraries, runtime, SSOT and native lifetime helper) and supplies its location; the test verifies production class
code sources are inside that distribution. It does not replace the full unit suite.

| RecoveryShutdownTest.closeDrainsMaintenanceWithoutInterruptingTheServingWriter | U6 interrupted replay; U14b preservation | Real boot, serving-writer commits in the maintenance executor, close joins without interruption, then old/update/delete search and unchanged generation after restart. Base fails the explicit interruption assertion. |

## R1 evidence scope

The original frozen81 raw invocations remain frozen-code evidence with the original missing historical argv limit. The unavailable-root and corrupt-commit historical display-name assignments were ambiguous; fresh method-only entries supersede their attribution. R1 current evidence binds exact test/helper/production inputs and actual XML/binary/commands. The independent rejection above remains unchanged; no builder acceptance is authored.


## R1 current checkpoint obligations

| Check | Scenario | Current proof scope |
| --- | --- | --- |
| CommittedCertificateTest.unknownStoredModelCannotCertifyRetirement | U6/amendment2 | Both phases and stored null/absent forms retain native migration through two boots. |
| CommittedCertificateTest.completeCommittedEvidenceRetiresAcrossRestart; changedCommittedModelKeepsNativeMigration | U6/amendment2 preservation | Complete digest-only/resolved-input identities retire; a genuine committed model change retains native behavior. |
| CommittedRetirementCertificateTest (all seven cases) | U6/amendment2 | Real nonempty commits distinguish stored/current uncertainty, determinate NOT_CONFIGURED, its transition to PRESENT and complete digest without optional diagnostics. |
| WalkOrderTest.acknowledgedPrefixThenForcedSyncSurvivesPendingWriterRetry | U6/amendment3 | Exactly one fault at the target completed later walk; actual attempts1/PENDING/future deadline,100s completion and restart. |
| WalkOrderTest.overlappingScopesKeepTheirAcceptedOrderAcrossRestart | U6/amendment3 | Six overlap cells, with a genuinely named member before collection deletion; later exact units/boundaries remain unchanged through older recovery and restart. |
| WalkOrderTest.ordinaryOrderedPrefixThenSyncSurvivesRetryAndRestart | U6 preservation | Ordinary same-order retry and restart remain available. |
| WalkOrderTest.repeatedFaultsRetainDebtAndDeletionProofAcrossRestart | U6/U14b | Real repeated target faults retain B/debt before eligibility; supported same-acknowledgement earlier DB replacement is exercised separately. |
| WalkOrderTest.newerDeleteUsingTheSameKeyCannotBorrowTheOldReceiptProof | U6/amendment3 | Real replacement UUID/order prevents borrowing an older deletion checkpoint through restart. |
| WalkCrashTest.completedWalkSurvivesAnActualKillAndRestart | U6/U14b | Both phases, actual child kills with full stdout retained, two boots, A and normalized pointers/B retirement. |
| RootProgressTest.uncertainEarlierAlphaDoesNotStarveUnqueuedBetaOrAcknowledgedMutation | U7/U11; U6 | Unqueued healthy beta and unrelated acknowledged mutation progress while alpha debt/B remain. |
| RetirementTransitiveOrderTest.transitiveDeferredCollectionCannotEraseLaterBetaSync | U6/amendment3; U7/U11 progress preservation | Real named AlphaSync→collection delete→BetaSync: reviewed R4 fails first-boot and restart visibility after retirement; propagating deferred scopes passes both. The unchanged independent-root progress check also passes. Exact method-level before/after XML and commands are retained in tmp/builder/retire-R1-repair/resume-012100/results.json; no final D4 envelope or acceptance is authored in this bounded probe phase. |

The prior reviewed R4-source broader local run completed indexer-worker1784pass/0fail/30skip and the selected adapter identity checks7pass/0fail/0skip. This is builder execution evidence, not independent acceptance. The additional ignored real-system probe prefix→exact-admission→later public forced sync fails on both b84 and the candidate with current DONE content but retained B: old exact receipt ownership is stale after the newer root admission. Final before refresh, method-only Safety bindings, D4 envelopes, remaining module/caller/PMD checks and final acceptance are unfinished. No scenario or deadline is withdrawn.

R7 bounded repair declaration (delegated-choice, task t-20261020-c2e24a request): `Release030RetirementPublicSyncTest.laterPublicSyncMaySupersedeAnEarlierExactAdmission` covers U6/amendment 3 prefix delete → exact public admission → later public forced sync, watched and watcherless carried named collection, unchanged and changed bytes, actual retirement and restart. It asserts refreshed exact unit/source/receipt and ordinary sibling admission without a new exact receipt. Current focused proof and remaining controls are recorded under tmp/builder/retire-R1-repair/resume-020458; this declaration is not an acceptance record. Existing b84/R4 triple-operation failures remain original evidence; this source adds admission assertions and requires its own before binding before final D4.

R8 bounded repair declarations (delegated-choice, task t-20261020-c2e24a request; no acceptance):

- `Release030RetirementSurvivorProgressTest.unavailableExactUnderDeleteDoesNotStarveUnrelatedBeta`: U7/U11 with U6 ordered scope preservation. A public Alpha prefix delete precedes an exact Alpha admission; Alpha becomes genuinely unavailable. After two real attempts, independently unqueued Beta work and an unrelated acknowledgement proceed, unaffected Beta content remains searchable and the Alpha obligation/B stay. The corrected R7 producer fails for Beta starvation before this repair; the invalid Alpha old-content oracle remains separate setup evidence.
- `Release030RetirementSyncPolicyTest.liveSyncKeepsItsOrdinaryRetryPolicy`: preserve normal public API MAINTENANCE/DEFAULT behavior across retirement routing. Real IO_FAILED/PARSER_TIMEOUT deadlines on ordinary and retained-exact members remain unchanged under maintenance, including unit and receipts; explicit DEFAULT resets retry metadata as before. Genuine b84 control and current-before/after evidence use the same corrected public-service producer; initial invalid provenance is retained as setup failure.
- `Release030RetirementSyncPolicyTest.legacyHashEntryStillReadsOnAnInterruptedThread`: legacy source hashing remains usable on an already interrupted caller as on b84; the live caller alone supplies interruption/cancellation to the new overload. Existing cancellable hash and transactional rollback controls remain required.

Raw focused source-bound output is under tmp/builder/retire-R1-repair/resume-023511 and sibling r8-* labels. Full suites, final D4/acceptance and the distinct later-uncertain-root-image hypothesis are not covered by these declarations.

R12 declarations: ACK packets are removal authorization, not current queue or ACK-won bits. Opaque ROOT ownership is distinct from complete membership. New RootImage fixture setup normalizes only the historical default/null collection equivalence and proves a real unrelated acknowledgement rather than expecting a member explicitly deleted by collection C to remain. Its named-C oracle remains exact. All original rejection records, evidence and scenario obligations remain; no independent acceptance is authored.

R16 bounded declarations (no acceptance): current-generation public LIVE ROOT sync refreshes trusted retained raw paths through ordinary transactional exact admission; maintenance coverage and automatic replay retain their existing authority. The existing checkpoint keeps passive UUID-keyed complete raw history before SQL replacement, with immutable original expectations and no removal authorization. Collection deletion proves its own namespace against current Q and committed A. Ordinary ROOT members receive no per-file receipt/hash census. `Release030LiveSyncAdmissionTest` exercises same-B coalescing, blank-generation coexistence, checkpoint-save rollback/retry/restart and cancellable raw hashing. The primary WalkOrder regression uses an explicit LIVE caller, gates the relevant attempt, creates its new file only inside the pause after the earlier delete checkpoint, and rejects any crossed attempt or changed PENDING owner before injecting one queue outcome. Runtime limitations and invalid intermediate probes are recorded in resume-r16; the original rejection above and all final verification obligations remain.

R17 checkpoint (blocked; no acceptance): retained the R16 patch and R15 metadata assertions, repaired the current WalkOrder compile reference and restored its target to existing Beta, and used the unscoped API to seed the blank-generation raw fixture. Historical committed-A deletion reconciliation preserves positively later ROOT-owned current Q rows without requiring their writer to have finished; this is only a Q exclusion, never permission to repeat an A delete. Matching collection proof additionally requires the deletion current-Q checkpoint. Current raw refresh and all seven queue cases pass. The migrating postwalk probe remains invalid: a later exact target receipt is present at the gate, the earlier delete is deferred during pending survivor certification, the attempt releases its hold, and the later target walk follows after its writer finishes. No queue outcome was injected; both original primary phases and remaining controls are unverified. Source-bound commands, intermediate failures, stores and final hashes are in tmp/builder/retire-R1-repair/resume-r17. No broader protocol or pause mechanism was introduced.

R18 implementation declarations (no acceptance): extend the existing queue-owned 256-row reconciliation with full exact/ROOT metadata validation, preserve positively later pending owners and administrative recorded coverage, and fence unknown or mismatched owners at every claim even with unchanged receipt binding. Renew historical exact-delete current-Q proofs only after SQL commit; omit only those proved historical deletes from physical survivor preflight and broad Q removal. Nonhistorical deletes retain strict A/current-source/DONE certification. Refresh eligibility is decoded once per existing live-sync batch, with no persistent cache. Focused checks distinguish ordinary ROOT and public exact owners at the same fresh postwalk checkpoint, preserve the one-injection/100-second/content/retirement/restart oracles and include changed metadata with a cached binding plus a disjoint healthy ROOT. These are intended checks, not results or an author acceptance record.

R18 conservative ownership clarification (no acceptance): a legacy cohort prefix alone cannot authorize administrative removal without full original metadata. Such an overlapping remaining row is held by deletion ordering; no metadata is invented. Existing strict final writer/source/A/Q and ACK checks still decide whole-build cleanup. Initial baseline guard setup and the canonical trailing-separator implementation failure are retained as intermediate evidence, not successful checks.

## Current source and whole-oracle classifications

These declarations precede acceptance and participate in its digest. They classify expectations, not results. The old rejection and frozen81 evidence remain exact history, including eighteen native, one identity, fifty repair and twelve certificate/budget executions, original fifty assertion failures/thirty-one preservation passes, nineteen historical helper input differences and unknown original argv. Frozen after output is not a claim about final production. Raw original inputs and producer hashes are never relabelled.

Obsolete expectation: a released null ROOT collection uniformly means explicit Default or clears per-file collection/provenance/planned identity. The primary released producer is `SqliteJobQueue.enqueueEntries` at 8292c62e381684eb506c96f50990b212ea1fb879, whose conflict policy expressly carries prior collection/scan membership when a caller states nothing; its released `SwitchBufferSyncRoot` stores root, force and provenance without an explicit collection. Current null INHERIT preserves each actual old namespace and provenance; a normally new missing member is logical Default. An explicit nonnull `default` is distinct. A known validated ROOT2 owner with absent native collection is logical Default; an unknown raw Q namespace remains held. A packet alone does not certify a source, ACK or native policy.

New direct API safety: `Release030RootIntentTest` freezes explicit creator policy, prevents historical/orphan backfill, preserves actual native inheritance and permits only retained exact owners. These direct retirementRoot APIs are after-only safety coverage. Their absence on an older baseline is not an assertion failure. The real old-interface public service driver is `Release030RetirementPublicSyncTest`, with all four watcher/watcherless by unchanged/changed cells and complete admission/content/retirement/restart assertions. Its existing boot/collection/snapshot/writer helper bodies were moved byte-for-byte into that class so old-interface checks need not compile unrelated new-API checks; forwarding methods preserve current users. No WalkOrder class split or wait/deadline change is authorized.

Current checkpoint authority: passive original raw metadata and immutable predecessor/source/replacement history remain frozen. P→C1→C2→restored old P must reuse the same retained ACK and preserve actual mutable attempts/deadlines/outcomes. `Release030CorrectionHistoryTest` covers rollback/resume with mutable waits, later UNWALKED ROOT priority and disjoint claims, and explicit-C null-provenance inherited attribution. Its first two direct-scope API cases are after-only safety on that interface; explicit-C provenance has historical genuine feature-present before input manifests (shared HEAD plus overlay is not clean b035). The final check adapts the private helper signature and requires fresh identical-source before/after execution; the NoSuchMethodException is setup evidence, not assertion evidence. `Release030DurableIntentCancellationTest` has six ROOT/FILE/DELETE by mutable-wait cases: durable intent is retained before SQL, original Q remains exact on cancellation, same intent resumes, current later winners/deletes still fence it. Its genuine ea61 before has eighteen actual configured assertion failures, zero errors/skips; do not repeat identical before or use stale R26 copied XML.

ROOT2 authority and claims: `Release030RootMemberIdentityTest` covers native INHERIT and same-unit metadata tampering on the stable queue API, with genuine f69 source-bound assertion failure. `Release030LiveSyncAdmissionTest` covers same-B coalescing, blank-generation coexistence, source hash cancellation and checkpoint save rollback without widening maintenance/replay authority. `Release030RetirementClaimOwnershipTest` retains every metadata guard and healthy disjoint control. Earlier thirty-three CP-prerequisite failures and twelve invalid-provenance IllegalArgumentExceptions are setup/exception evidence, never intended claim-guard assertions. Source-only review and queue boundary probes do not establish full Engine cancellation behavior.

Whole WalkOrder oracle: all fourteen invocations remain required—four pending-writer ordinary/exact by phase, one ordinary preservation, six overlaps, two repeated-fault restore=false/true cases on the SWITCHING fixture and one replacement-delete control. The captured final-selected-after executes all fourteen invocations: fourteen pass, zero skipped, on bbdd production. These do not establish an after result for the later applied production. The actual postwalk gate must prove PENDING ordinary/exact ownership in both phases before its single injected queue outcome; the original 100-second/content/retirement/restart conditions and later metadata guards remain. Current winner includes later UNWALKED ROOT and deletes; ordinary scan progress differs from real recorded ownership. No packet grants deletion authority, ROOT census, schema or configuration option.

Evidence handling: the kernel's display-name regex cannot attribute parameter labels such as [1]. The bounded artifact collector resolves actual listener/binary invocation identities, applies canonical XML parsing, summaries and assertion-vs-exception classification, and self-digests the envelope. Its actual collector path/hash and immutable command/source/XML bindings are disclosed; it does not claim that the canonical execution launcher ran. Skipped, interrupted, compile/setup-error, stale XML and raw exceptions are never promoted to passes or intended assertion failures. Missing actual bindings stay unverified. Root owns independent final acceptance.

## Provisional evidence expectations after the applied repairs

The original rejection below is unchanged. The bbdd runs remain historical production evidence after e948216 and 917af9e; unchanged check bytes alone do not renew a runtime claim. The raw final-public-progress sourceStable=false flag remains false: its only changed captured input at the original audit was this changeset. Corrected fixtures retain their PMD-only exit 1 and metadata archive discrepancy. Executed check/helper/producer/listener/binary bindings are recorded separately.

R35 adds actual narrow R33 Safety and conditional-correction evidence with the applied production input hashes and unchanged invoked source scope. The earlier failed Safety after and its unchanged frozen before leaves remain attached. The original six WalkCrash marker failures are invalid fixture preconditions, never final-fixture product failures; the repaired final whole file still needs genuine before and current-product after proof. No author acceptance record is supplied.

Expanded LegacyMetadata, all pending/overlap/repeated-fault WalkOrder cases, ACK/Continuation, RootProgress and the final public driver keep their before obligations. The unknown raw namespace still requires copper search and indexed mcp-ingest on both boots. Native null inheritance never becomes uniform Default; explicit nonnull Default remains distinct. The exact finite missing-proof plan and unchanged-input audits are in tmp/builder/retire-R35-evidence-audit; root owns execution and independent acceptance.

R40 builder checkpoint: comparisons and executions are retained in tmp/builder/r40-20261027T005428Z/. The first after run had 25 cases, two SWITCHING completed-build failures and zero skips. The unchanged-code diagnostic reproduced those two failures. The second repair passes all five classes: 25 cases, zero failures/errors/skips. The combined compile and main/test PMD command for indexer-worker, worker-services, worker-core and adapters-lucene succeeds; all eight PMD reports have zero violations/errors (unchanged tasks may be UP-TO-DATE). Current production differs from the historical envelopes above. The broader Release030/crash run, full indexer-worker suite and fresh after-only evidence remain unrun. No historical evidence is presented as fresh R40 verification and no new acceptance is claimed. Builder is returning under the bounded-overrun rule after nearly four hours and substantial shared Gradle slot waits; continuation should reuse the preserved commit comparisons and compiled distribution.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17514-715f-72e0-b9ec-8525ba24fa25",
  "verdict": "reject",
  "digest": "sha256:39dbaf2614bf31501d56e0a6d48313c815d9a8b3c7d44e5fc4c55cfebb868701",
  "note": "F1: unknown stored model identity authorizes cancellation. F2: retirement replay loses later forced-sync admissions after an older prefix delete. See verification-report.md in a125vcwh evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17c1b-734c-7f13-9cc5-587f8b41f3aa",
  "verdict": "accept",
  "digest": "sha256:fb7986e218bf52de715d2f0fe37516efeed4144fde52604947a30ea71fc5012d"
}
```
