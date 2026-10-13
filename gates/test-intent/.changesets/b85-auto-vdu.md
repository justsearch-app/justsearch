---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b85-auto-vdu-round2
---

# b85-auto-vdu — round 2 repairs

Starting revision: 13524ddf0. The original whole-change reference remains 0a79303bb. Independent verification supplies acceptance; the builder does not judge this changeset.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Failed normal restoration leaves chat offline with its normal snapshot, then retries with bounded backoff or on a human request. Automatic BUSY entry defers without an offline reason and retries at the next idle window even without new human activity. Shutdown abandons the current document for all pass kinds within five seconds, with a live-owner PENDING reset, without FAILED acknowledgement or normal relaunch. Both automatic paths wait a full process startup idle interval. Status identifies only loaded automatic borrowing.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Repair the five rejected behaviours under the delegated design decisions. Preserve the real controller, lifecycle, generation tracker, reconciler, pacing policy and batch wiring; use the actual Engine admission owner for shutdown and controlled process/index/power endpoints.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r2-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#failedAutomaticRestoreLeavesNormalConfigurationOfflineAndHumanRequestRecovers",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#failedExplicitRestoreAlsoLeavesNormalConfigurationOfflineAndRecovers",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#failedRestoreRetriesWithBackoffAndRecoversWithoutHumanDemand",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#racingLeaseAtEntryLeavesNoFalseOfflineReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#shutdownMidLoadedAutomaticDocumentDrainsCleanlyAndLeavesItPending",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#shutdownMidChatOffAutomaticDocumentDrainsCleanlyAndLeavesItPending",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#shutdownMidExplicitDocumentDrainsCleanlyAndLeavesItPending",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#chatOffAutomaticPassWaitsForTheFullProcessStartupIdleWindow",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#loadedAutomaticPassWaitsForTheFullProcessStartupIdleWindow",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java#statusIdentifiesOnlyAutomaticBorrowingOfLoadedChat"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppVduShutdownTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The production shutdown sequence fences and cancels enrichment before closing admission and waiting for durable live work, enabling a clean dependency drain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Quiesce the current enrichment procedure at the operation-admission shutdown step, before admission closure and the five-second drain.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r2-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppVduShutdownTest.java#terminalEnrichmentQuiescesBeforeTheLiveWorkDrain"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Inject an already-expired process startup clock for existing non-startup scenarios. All nine original behavioural oracles remain, and pass on 13524ddf0 and the repaired candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Isolate startup dwell from the original loaded-chat scenarios without sleeping five minutes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r2-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#idleLoadedChatProcessesOneBoundedPassAndRestoresItsConfiguration",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#humanDemandFinishesAfterOnlyTheCurrentDocumentAndOneRestore",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#activityBetweenDocumentsRestoresChatAndDoesNotRelaunchAgainInTheSameIdlePeriod",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#failedAutomaticLaunchRestoresLoadedChatAndBacksOff",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#automaticEntryRefusesARacingLeaseWithoutWaitingOrChangingChat",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#shutdownDuringAutomaticPassStopsTheServerAndRefusesTheWaitingPerson",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#generationAuxLeasePowerPressureAndShortIdleAllBlockAutomaticEntry",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#explicitPassStillWorksWithoutAnIdleWindow",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#chatOffAutomaticPathStillReturnsToTheOffIntent"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduOfflineTriggerSamplerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Supply an already-expired process startup clock for the existing positive chat-off trigger test; keep its trigger assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Preserve the original pacing predicate check while testing startup dwell separately through real wiring.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Add controlled normal-configuration launch refusal and capture the full launch arguments. Retain PID plus creation-time cleanup and all existing health/model fixtures. The fake exits directly with its captured launcher identity, avoiding HttpServer graceful-stop waiting on a deliberately paused response; no external process is stopped by the watcher.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Reproduce failed restoration and verify exact parallelism and previous profile flags without a real model or GPU.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Explicit passes and chat-off automatic passes retain the original panel sentence; only automatic borrowing of loaded chat describes the document-boundary handoff.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Scope the caption using VDU_BATCH plus the documented automatic-vdu engine reason, which is published only for loaded automatic borrowing.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r2-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts#keeps the original panel sentence for explicit and chat-off automatic passes"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Identify loaded automatic borrowing with engineReason automatic-vdu in the original component fixture. Preserve its active, normal and stale-state assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Use the existing engineReason wire field to distinguish the three pass kinds; no additional wire field.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r2-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts#names picture reading while loaded chat lends its server to a VDU pass",
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts#keeps normal chat wording after the hold closes and never claims stale picture reading"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VduProcedureExecutorLifetimeTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Supply an already-expired process startup clock in the original positive trigger and executor lifetime checks. Their ownership, cancellation, controller claim and actual-exit accounting oracles remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Inject an already-expired startup clock into existing non-startup positive fixtures. Retain every controller-eligibility and actual-executor-lifetime assertion.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
