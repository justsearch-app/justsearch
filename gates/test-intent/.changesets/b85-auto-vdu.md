---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b85-auto-vdu-round3
---

# b85-auto-vdu — round 3 repairs

Starting revision: 14a7eb8a2. The original whole-change reference remains 0a79303bb. Each reported defect was established before its production repair. No-pass order also passes on the original whole-change reference. Round-1 and round-2 evidence remains in the existing evidence files and run archives. Independent verification supplies acceptance; the builder does not judge this changeset.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "New normal configuration supersedes a pending restore, both restarted and record-only. Restoration timer and human demand obey game/GPU pressure, including load-anyway and reload dwell. An explicit pass during pending restoration retains its original demand gate. A start race rearms at the next full idle window without human activity. A three-second owned-process stop during a cancelled pass retains PENDING and drains within the normal terminal allowance. An explicit entry waiting on normal chat preserves that exchange until ordinary interactive cancellation; terminal preparation fences every later process launch.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the confirmed round-3 restoration, pressure, shutdown, conditional reset and sampler race defects under the orchestrator's delegated decisions. Preserve no-pass shutdown order, explicit demand semantics, existing failed-restoration status and the round-2 behaviour oracles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r3-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#newerRunningConfigurationSupersedesPendingRestore",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#newerRecordedConfigurationIsStartedByPendingHumanDemand",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#newerResolvedConfigurationSupersedesPendingRestore",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#newerPreparedPublicationSupersedesPendingRestore",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#pendingRestorationWaitsFortySecondsUnderGamePressure",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#pendingHumanDemandUnderGameGetsUsualRefusalAndLoadAnyway",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#pendingRestorationWaitsUnderExternalMemoryPressure",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#humanDemandDuringExplicitHoldWithPendingRestoreMatchesOriginalExplicitPass",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#samplerStartRaceRetriesAtNextWindowWithoutHumanActivity",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#slowOwnedModelStopDuringPassUsesTerminalAllowanceAndKeepsDocumentPending",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#shutdownFenceRejectsModelLaunchBeforeCancellingTheCurrentDocument",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#explicitEntryShutdownPreservesGeneratingChatUntilInteractiveCancellation"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppVduShutdownTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "No-pass shutdown preserves the original cancellation/drain/terminal-stop order. An active pass receives the normal twelve-second terminal allowance; a three-second stop remains clean and later steps all execute.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the confirmed round-3 restoration, pressure, shutdown, conditional reset and sampler race defects under the orchestrator's delegated decisions. Preserve no-pass shutdown order, explicit demand semantics, existing failed-restoration status and the round-2 behaviour oracles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r3-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppVduShutdownTest.java#noPassPreservesOriginalCancellationAndStopOrder",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppVduShutdownTest.java#activePassAllowsThreeSecondStopAndRunsLaterSteps"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Success and shutdown reset serialize in both orders. A selected processing document cannot reset a later success; reset retires the same owner and refuses its late result. Scoped reset cannot change another procedure's claim.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the confirmed round-3 restoration, pressure, shutdown, conditional reset and sampler race defects under the orchestrator's delegated decisions. Preserve no-pass shutdown order, explicit demand semantics, existing failed-restoration status and the round-2 behaviour oracles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r3-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java#successWinningShutdownResetKeepsItsTerminalStateAndText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java#shutdownResetWinningLateSuccessKeepsPendingWithOriginalText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java#shutdownResetCannotResetAnotherProceduresClaim"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation: a person waiting when restoration fails sees offline with config_apply_failed; the next demand retains the immediate retry behaviour. CPU chat retains its normal GPU-pressure residency exemption.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the confirmed round-3 restoration, pressure, shutdown, conditional reset and sampler race defects under the orchestrator's delegated decisions. Preserve no-pass shutdown order, explicit demand semantics, existing failed-restoration status and the round-2 behaviour oracles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r3-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#waitingHumanSeesConfigApplyFailedWhenAutomaticRestorationFails",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#cpuPendingRestoreKeepsNormalResidencyPressureExemption"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Retain all ten round-2 behaviour oracles. The mock index endpoint forwards the additive shutdown-scoped recovery operation to its existing live-admission recovery fixture. A controlled controller clock advances restoration pressure time without forty seconds of sleeping.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the confirmed round-3 restoration, pressure, shutdown, conditional reset and sampler race defects under the orchestrator's delegated decisions. Preserve no-pass shutdown order, explicit demand semantics, existing failed-restoration status and the round-2 behaviour oracles.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppVduShutdownTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Retain the previous active-enrichment-before-admission oracle; its Head fixture now explicitly identifies an active pass instead of implying every shutdown has one.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the confirmed round-3 restoration, pressure, shutdown, conditional reset and sampler race defects under the orchestrator's delegated decisions. Preserve no-pass shutdown order, explicit demand semantics, existing failed-restoration status and the round-2 behaviour oracles.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Retain all original commit, reader-failure and acknowledgement oracles. Recovery mocks select the new conditional mutation API reflectively, using the original update API on the starting revision, so both revisions exercise the same faults.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the confirmed round-3 restoration, pressure, shutdown, conditional reset and sampler race defects under the orchestrator's delegated decisions. Preserve no-pass shutdown order, explicit demand semantics, existing failed-restoration status and the round-2 behaviour oracles.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

Independent verifier/orchestrator acceptance is pending.
