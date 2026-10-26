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
      "behaviour": "Keep the existing native-migration preservation scenarios on the native path by making A's analyzer fingerprint genuinely incompatible after authentic fixture unpack/verification. Preserve all original assertions: exact-file recovery, independent and indexed exhausted debt, deadline failure/restart, completed Green admission identity and retention, unchanged fast path, preparation before claims and completed native cutover. Compatible migration retirement is covered separately by U6 checks. These replace the former compatible-fixture native precondition, which the adopted U6 retirement intentionally changes; this is not claimed as a mechanical adaptation. The native assertions remain preservation checks, to pass on both base and candidate. The identity helper has an explicit native-analyzer mode which asserts exactly the declared analyzer mismatch, while retaining the default compatibility assertion and requiring all other identities to match.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030ChallengerRecoveryTest.java#interruptedUpgradePreparesBeforeClaimsAndCompletesNativeCutover"
      ],
      "evidence": "gates/test-intent/evidence/upgrade-retire-native-preservation.json",
      "classificationRationale": "New, not mechanical Adaptation or Obsolete: the actual diff changes authentic native-fixture inputs to a genuine analyzer incompatibility. U6 supersedes the former assumption that a compatible interrupted release must migrate. The same native recovery/retention obligations and assertions are exercised under a true native precondition, before and after; compatible retirement has separate checks. No guarded obligation is removed.",
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
      "evidence": "gates/test-intent/evidence/upgrade-retire-serving-identity.json",
      "classificationRationale": "New preservation oracle, not mechanical Adaptation or Obsolete: the diff removes whole-fingerprint equality and explicitly retains A generation, analyzer and all three model identities plus every legacy queue row. U6 and amendment 2 permit additive optional-field restamping, so the full serialized fingerprint was a stronger input assumption than the adopted compatibility proposition. The retained proposition is established before and after, rather than treating code output as a source.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "Frozen source on execution HEAD 8292 plus its original uncommitted manifest, later materialized unchanged as d9663d16. Check/helper bytes still match integrated HEAD 1bb20567; changed production dependencies are enumerated separately. This is not a fresh integrated recovery execution."
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementHttpTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCompletedBuildTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RecoveryShutdownTest.java"
      ],
      "behaviour": "U6: authentic MIGRATING and SWITCHING data boot through KnowledgeServer, replay current updates and confirmed deletions onto the original serving generation, preserve old search, retire B and migration leftovers, and survive restart. The DONE explicit-file case preserves content outside roots. Unavailable-root checks keep old search, B and durable debt across restart while unrelated mutations proceed, then retire after confirmation. Negative certificate checks preserve the native path on analyzer/vector differences, unresolved models and corrupt commits. Process crashes cover checkpoint/pointer publication, queue/sidecar preparation, difference reconciliation, committed replay before acknowledgement, acknowledgement before replay-complete publication, physical deletion, retirement before pointer-backup normalization, and final cleanup. Restoring the pointer backup cannot revive the obsolete build; scheduled root recovery resumes. Paused replay races acknowledged updates, deletes and prefix deletes before a real restart; unrelated admission remains available. A genuinely unreadable subtree reports delete_detection_unverified over the live sync interface and keeps its SYNC_ROOT and B until a full walk. Packaged HTTP evidence loads production classes from the complete bundled headless jars and runtime and reuses one fixed switching.zip Engine folder across first boot and restart, searching old, replayed and deleted paths. Both phase variants also build completed changes through real B admissions, commit and verify their effects, then remove only those exact receipts to model released unjournalled completion. A B-only file, B-only update and B-only deletion must reconcile onto A; real historical writer closure proves B's sibling clean-shutdown marker exists, and its obsolete rebuild-pending marker is also retired; denying the completed update retains old A content, sidecar debt and B through restart. Upgrade-root walks are journaled once before new mutations are accepted and their completion is durable before acknowledgement, preventing subsequent scheduled walks from resurrecting newer deletes. The root-journal transaction/sidecar publication gap is an additional process-kill cut; the deletion cut creates a real empty B commit so a payload removal exists to observe. A newer unscoped admission cannot borrow an A/B snapshot path as exact-file authority: old A bytes and B remain across restart, unrelated scoped mutations proceed, and only a deliberate exact-file admission indexes the new bytes and settles debt; its neighbor is never ingested. A separate ordinary-sync check on an IDLE release fixture directly proves incomplete-walk uncertainty reporting before/after and across restart; the interrupted native base cannot execute that ordinary request before retirement exists. Each successful exact SYNC_ROOT walk checkpoints its durable admissions before waiting for the writer, so retries do not repeatedly reset DONE rows. Real process kills cut root admission before/after that checkpoint and prove an unqueued root-only file survives. A public directory sync during retirement is journaled and fenced before a newer file update/delete/prefix-delete, and the race check asserts its scoped receipt exists. A deterministic real-boot shutdown control runs real serving-writer commits on the actual maintenance executor, observes whether close interrupts that work, waits for close, then restarts and verifies old/update/delete search and the original serving generation. The before-state unsafe signal is recorded before completing the real commit safely, and fails the final no-interruption assertion; no failure is hidden. This repairs the observed partial-walk restart race where forced maintenance interruption invalidates Lucene's shared native lock channel.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030MigrationRetirementTest.java#replayRetiresObsoleteBuildAndKeepsServingIdentity",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030MigrationRetirementTest.java#backoffNeverHoldsMutationAdmissionDuringForcedNativeCutover",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#alreadyDoneExplicitFileOnServingGenerationSurvivesResume",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCrashTest.java#killedRetirementResumesAndBackupCannotReviveBuild",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementOrderingTest.java#newerAcknowledgementWinsPausedReplayAndRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPartialWalkTest.java#partialWalkRetainsReceiptAndBuildUntilACompleteWalk",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementHttpTest.java#packagedSwitchingUpgradeAndRestartKeepReplayedSearch",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementCompletedBuildTest.java#completedBuildEffectsAreReconciledAndUncertaintyRetainsBuild",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#modernUnownedAdmissionCannotBorrowRetiredBuildAuthority",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementPartialWalkTest.java#ordinaryPartialWalkReportsUnverifiedDeletionAcrossRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RecoveryShutdownTest.java#closeDrainsMaintenanceWithoutInterruptingTheServingWriter",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java#reappearedLegacyDeleteProvesCurrentBytesBeforeAcknowledgement"
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/upgrade-retire-recovery-repaired.json",
      "classificationRationale": "New repaired behavior: each named check requires an actual assertion failure on 8292 and a real completed frozen-code execution. Evidence explicitly distinguishes frozen production dependencies from integrated main; no current integrated recovery pass is inferred. Reappeared DELETE is explicitly listed so all 50 repaired invocations are bound. The unavailable-root method now has a separate fresh method-only before/current evidence entry because historical duplicate display names were ambiguous. Original frozen raw evidence remains unchanged.",
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
      "evidence": "gates/test-intent/evidence/upgrade-retire-certificates-budgets.json",
      "classificationRationale": "New preservation coverage: all 12 invocations pass before and on frozen code. Negative certificates and budget protections are not labelled repairs. Corrupt A keeps native backup-first behavior; restarted over-budget cases follow actual base MIGRATING while first-boot FAILED, exact/unlimited limits, overrides and completion protections remain asserted. Corrupt-commit attribution now uses a separate fresh method-only before/current entry; historical duplicate display names are retained without asserting unique binding.",
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
      "repaired": true,
      "evidence": "gates/test-intent/evidence/upgrade-retire-r1-repaired.json",
      "classificationRationale": "New repaired checks, with preservation cells retained within their ordering table. The original sync-then-collection fixture had no named member and remains excluded as intended fail-before proof. Its corrected precondition uses public root registration, an actual named queue member and an ordered root receipt. The reviewed R4 exact R1 sources pass all32 new cases in the completed local run, including unchanged100s/search/A/B/restart oracles and two real process kills with full child stdout. The matching before refresh for the changed WalkOrder/WalkCrash inputs and final D4 envelopes remain pending; no compile/setup failure or invalid old fixture is counted. A separate real prefix→exact-admission→later-public-sync probe fails100s completion on both b84 and this candidate: the newer root admission replaces the file unit without replacing its old exact receipt. That remaining ownership/completion gap is not accepted or hidden by these passing cells. Original repeated-injection failed fixtures remain immutable.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementOrderingTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java"
      ],
      "evidenceScope": "Current exact R1 working source and actual command/input manifests, with b84 unchanged-production controls plus identical new test overlay. Historical frozen81 invocations remain frozen; no acceptance inferred."
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
      "evidence": "gates/test-intent/evidence/upgrade-retire-r1-preservation.json",
      "classificationRationale": "New preservation coverage:13 exact-source invocations must pass before and after. No blanket null rejection, native comparator change, model assets/inference, retry shortening or timeout change. Receipt revisions are fresh opaque UUID versions; accepted_order supplies ordering. The same-key check uses real admissions and restarts, never fabricated identities.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementOrderingTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementWalkOrderTest.java"
      ],
      "evidenceScope": "Current exact R1 working source and actual command/input manifests, with b84 unchanged-production controls plus identical new test overlay. Historical frozen81 invocations remain frozen; no acceptance inferred."
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
      "repaired": true,
      "evidence": "gates/test-intent/evidence/upgrade-retire-r1-unavailable-method.json",
      "classificationRationale": "The existing check source/assertions are unchanged. Unique method-only8292/current refresh is required to close the historical duplicate-display-name ambiguity and remains pending. Original frozen evidence/unknown historical argv are retained.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "Method-only8292/current refresh remains pending; existing historical frozen81 results and missing historical argv are retained unchanged. No independent acceptance is claimed."
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
      "evidence": "gates/test-intent/evidence/upgrade-retire-r1-corrupt-method.json",
      "classificationRationale": "The existing check source/assertions are unchanged. Unique method-only8292/current preservation runs are required and remain pending; duplicate historical display names are never assigned by status.",
      "sourceDependencies": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030Fixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030RetirementSafetyTest.java"
      ],
      "evidenceScope": "Method-only8292/current refresh remains pending; existing historical frozen81 results and missing historical argv are retained unchanged. No independent acceptance is claimed."
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
