---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: 01a185ae-4d4b-7b41-b21b-992777acac9e
---

# Test-intent entries

All sources remain agent choices under the specified design delegation. Builder execution evidence now records each test file separately on the candidate and the exact before revision. Entries distinguish assertion-proven repairs, preservation passes and after-only new-API controls. The runner adaptation and execution limits are documented in gates/test-intent/evidence/wp3.8a-rules/README.md. Independent acceptance remains for the orchestrator/verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingRulesHoldTest.java"
      ],
      "class": "New",
      "behaviour": "D2 table including every minute/day, overnight Sunday wrap, endpoints, DST wall-time membership, clock/zone jumps, residual reasons and lease acknowledgment; disabled schedules do not sample local time, queued work automatically continues. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingSchedule and IndexingHold.LocalMinute/setLocalTime/setPolicy are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesHoldTest.json",
      "newChecks": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingRulesHoldTest.java#localMinuteTableIncludesEndpointsOvernightSundayAndDisabledHours",
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingRulesHoldTest.java#dstRepeatedSkippedHoursClockAndZoneChangesAreReevaluatedInTheRealHold",
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingRulesHoldTest.java#combinedReasonsKeepAdmissionClosedThroughResumeExpiryAndLeasePublication",
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingRulesHoldTest.java#queuedWorkAutomaticallyContinuesAndDisabledSchedulesNeverReadLocalTime"
      ]
    },
    {
      "items": [
        "modules/app-util/src/test/java/io/justsearch/app/util/WindowsLocalTimeTest.java"
      ],
      "class": "New",
      "behaviour": "D3 native-local weekday/minute mapping, Windows Sunday mapping, fallback after binding/invocation failure and successful native read on the next evaluation; physical Windows invocation remains unverified. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because the WindowsLocalTime type is absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/WindowsLocalTimeTest.json",
      "newChecks": [
        "modules/app-util/src/test/java/io/justsearch/app/util/WindowsLocalTimeTest.java#sundayMappingAndMinuteSnapshotAreNativeLocalValues",
        "modules/app-util/src/test/java/io/justsearch/app/util/WindowsLocalTimeTest.java#nativeFailureFallsBackAndNextReadTriesNativeAgain"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingRulesPersistenceTest.java"
      ],
      "class": "New",
      "behaviour": "D4/S38.6 old absent/null/three-field policies retain manual fields and load with unrestricted rules; future-version files remain fail-loud and untouched even with duplicate rule tokens. Preservation also passes before. Execution: preservation checks pass on both before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 and the candidate; no fail-before or repair is claimed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesPersistenceTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingRulesPersistenceTest.java#futureVersionWithDuplicateRuleTokensFailsLoudWithoutQuarantine",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingRulesPersistenceTest.java#oldPoliciesKeepManualFieldsAndHaveNoRules"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingRulesPersistenceTest.java"
      ],
      "class": "New",
      "behaviour": "D1/D4/S38.2/S38.6 malformed saved rules quarantine before coercion/duplicate loss and stay in recovery on a second restart; old policies retain manual fields; disabled hours persist and reset clears rules. Execution: every named check passes on the candidate and fails by assertion for the guarded behavior on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesPersistenceTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingRulesPersistenceTest.java#malformedSavedRulesQuarantineAndRemainInRecoveryOnSecondRestart",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingRulesPersistenceTest.java#disabledHoursAndBatteryPreferenceSurviveAcceptedSerializationAndResetClearsThem"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesIntentTest.java"
      ],
      "class": "New",
      "behaviour": "D4 UI-only accepted rules operation, whole-rule-pair replacement, witness/key/replay/conflict, atomic refusal and preservation with Pause/Resume/unrelated settings writes. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingHold.LocalMinute/setLocalTime are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesIntentTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesIntentTest.java#acceptedRulesPauseResumeUnrelatedWritesAndReplayPreserveEachOther",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesIntentTest.java#rulesPreserveSavedRecoveryAndExpiredManualIntentAcrossRestart",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesIntentTest.java#invalidRulesAreRefusedAtomicallyAndStalePreparationConflicts"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/IndexingRulesPowerTest.java"
      ],
      "class": "New",
      "behaviour": "D7/S38.9: default-off fresh activation does not wait for a blocked first probe. Preservation expectation also passes before. Execution: preservation checks pass on both before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 and the candidate; no fail-before or repair is claimed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesPowerTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/IndexingRulesPowerTest.java#disabledBatteryRuleSkipsFreshBlockedActivationWait"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/IndexingRulesPowerTest.java"
      ],
      "class": "New",
      "behaviour": "D6 independent source/intent matrix; D7 fresh first reading, bounded UNKNOWN timeout, delayed publication, battery before timeout, first exception and default-off no-wait. Execution: every named check passes on the candidate and fails by assertion for the guarded behavior on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesPowerTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/IndexingRulesPowerTest.java#powerMatrixAppliesThroughPollerAndHoldWithoutChangingEnergySaver",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/IndexingRulesPowerTest.java#firstReadingWaitIsBoundedUnknownUntilKnownAndFreshOnEveryActivation",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/IndexingRulesPowerTest.java#batteryBeforeTimeoutHoldsAndDefaultOffDoesNotWaitForBlockedProbe",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/IndexingRulesPowerTest.java#firstExceptionCompletesUnknown"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStateActivationTest.java"
      ],
      "class": "New",
      "behaviour": "D7/S38.9 old blocked probe returning after restart cannot overwrite a fresh observation, in either source direction. Execution: every named check passes on the candidate and fails by assertion for the guarded behavior on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/EnergyStateActivationTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStateActivationTest.java#closedActivationCannotOverwriteNewReadingInEitherDirection"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/IndexingRulesVduTest.java"
      ],
      "class": "New",
      "behaviour": "S38.8 actual OfflineCoordinator automatic yield, queued explicit picture reading and final-publication lease acknowledgment under the shared battery rule. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingHold.setOnBattery/setPolicy are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesVduTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/IndexingRulesVduTest.java#explicitPassWaitsAndAnActivePassMustFinishBeforeQuiet"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/IndexingRulesGplTest.java"
      ],
      "class": "New",
      "behaviour": "S38.8 actual GplJobCoordinator queued continuation and final-publication acknowledgment under the shared battery rule. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingHold.setOnBattery/setPolicy are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesGplTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/IndexingRulesGplTest.java#queuedGplWaitsAndActiveGenerationKeepsPausingUntilItsOutcomeIsPublished"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopRulesTest.java"
      ],
      "class": "New",
      "behaviour": "S38.8 IndexingLoop schedule restriction blocks queue polls, preserves GPU/transition handshakes and waits for reader publication before PAUSED. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingSchedule and IndexingHold.LocalMinute/setLocalTime/setPolicy are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingLoopRulesTest.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopRulesTest.java#startsWithoutPollingAndCutoverAndUpgradeStillComplete",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopRulesTest.java#quietWaitsForTheReadersPublicationAndNoFurtherQueuePollStarts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIndexingRulesTest.java"
      ],
      "class": "New",
      "behaviour": "D5 every representable recovery/manual/schedule/battery combination yields ordered complete reasons, matching primary code, truthful quiet state and no expired manual deadline. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingSchedule and IndexingHold.LocalMinute/setLocalTime/setOnBattery/setPolicy are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/StatusIndexingRulesTest.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIndexingRulesTest.java#everyPolicyCombinationHasOrderedReasonsCoherentCodeAndNoExpiredDeadline"
      ]
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/IndexingRulesBootstrapIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "BP38 full unchanged HeadlessApp.main with only the existing constructor Supplier replaced: durably queued work, blocked first observation, BATTERY before timeout starts held, timeout UNKNOWN permits work, delayed BATTERY then applies; real 15s cadence and actual same-bootstrap close/start re-arm. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingHold.setPolicy is absent in the shared test child source, so neither method can run. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesBootstrapIntegrationTest.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/IndexingRulesBootstrapIntegrationTest.java#fullMainHoldsFreshBatteryAndTimesOutUnknownWithDurablyQueuedWork",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/IndexingRulesBootstrapIntegrationTest.java#sameBootstrapCloseStartRearmsTheFreshReadingBarrier"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesRegistrationTest.java"
      ],
      "class": "New",
      "behaviour": "D4 catalog registration exposes the rules mutator to UI only, with no MCP/agent executor. Execution: every named check passes on the candidate and fails by assertion for the guarded behavior on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesRegistrationTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingRulesRegistrationTest.java#rulesOperationIsUiOnly"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStateCachePreservationTest.java"
      ],
      "class": "New",
      "behaviour": "Must-not-break energy saver: with battery pause off, a blocked or failed first probe after restart preserves the previously known energy-saver intent and gauge. These preservation checks pass on the baseline and repaired candidate. Execution: preservation checks pass on both before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 and the candidate; no fail-before or repair is claimed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/EnergyStateCachePreservationTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStateCachePreservationTest.java#defaultOffRestartKeepsEnergySaverUntilAFreshReading",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStateCachePreservationTest.java#defaultOffRestartFirstFailureKeepsExistingBestEffortEnergySaver"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillIndexingRulesTest.java"
      ],
      "class": "New",
      "behaviour": "S38.8 actual BackfillScheduler, in-process signal bus and shared hold prevent queued NER encoding until schedule and battery both clear, then publish the same pending work without manual Resume. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingSchedule and IndexingHold.LocalMinute/setLocalTime/setPolicy/setOnBattery are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/BackfillIndexingRulesTest.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillIndexingRulesTest.java#scheduleAndBatteryHoldPendingNerUntilBothClearWithoutManualResume"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusRecoveryPausingTest.java"
      ],
      "class": "New",
      "behaviour": "D5 recovery primary reason code agrees with PAUSING while an admitted lease drains and PAUSED after its final acknowledgment. Execution: every named check passes on the candidate and fails by assertion for the guarded behavior on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/StatusRecoveryPausingTest.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusRecoveryPausingTest.java#recoveryCodeDistinguishesDrainingFromAcknowledgedQuiet"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingRulesDeletionTest.java"
      ],
      "class": "New",
      "behaviour": "S38.8 actual EngineRoot, accepted Forget operation, SQLite and Lucene remove indexed parent/chunks/jobs while the actual lane remains held by schedule and battery; source bytes and sibling survive. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingSchedule, IndexingHold.LocalMinute/setLocalTime/setOnBattery and Snapshot.reasons are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesDeletionTest.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/IndexingRulesDeletionTest.java#recordedForgetRemovesIndexedDataWhileScheduleAndBatteryKeepProcessingHeld"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ForgetSettingsProjectionTest.java"
      ],
      "class": "New",
      "behaviour": "D4/S38.6: real coordinator Forget projection preserves complete indexing policy; a later rules edit preserves exclusions; withdrawal preserves updated hours/battery/manual fields and existing query-role selection. Existing expectations remain. Execution: every named check passes on the candidate and fails by assertion for the guarded behavior on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/ForgetSettingsProjectionTest.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ForgetSettingsProjectionTest.java#RF55ProjectionIsNonterminalAndWD55WithdrawalRemovesOnlyOwnedEntryAfterFreshMerge"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/BulkWorkVocabularyMirrorTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Automatic BulkWorkGate reasons retain their exact vocabulary and precedence; effective global policy reasons have the separate IndexingHold owner.",
      "whySurvives": "D5 explicitly keeps SCHEDULE and ON_BATTERY out of the bulk-gate mirror, alongside the existing manual/recovery exclusions. No automatic reason assertion is weakened.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "governance/logic-seams.v1.json#law-targetTests"
      ],
      "class": "New",
      "behaviour": "D2 local-wall-clock membership law is registered with its exhaustive minute/day and owner-level hold guards; existing seams unchanged. Execution: after-only checks pass on the candidate. The transplanted test file cannot compile on before revision 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093 because IndexingSchedule and IndexingHold.LocalMinute/setLocalTime/setPolicy are absent. The recorded compile failure is not fail-before evidence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "WP3.8 indexing-hours, battery and verification design within existing user policy choices",
          "choice": "Implement settled revision 3 D1-D9 and S38.1-S38.6/S38.8/S38.9 Engine behavior and adopted BP38 test-only supplier seam; S38.7 and UI wording remain in the following package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp3.8a-rules/IndexingRulesHoldTest.json",
      "newChecks": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingRulesHoldTest.java#localMinuteTableIncludesEndpointsOvernightSundayAndDisabledHours"
      ]
    }
  ]
}
```

## Acceptance records
