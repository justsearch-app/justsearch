---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b85-auto-vdu-round4
---

# b85-auto-vdu — complete intent and round 4 repairs

Whole-change reference: 0a79303bb. Round-4 fail-before: 83f07cc4c.
All still-applicable round-1, round-2 and round-3 entries are retained below, including
the adaptations when later rounds changed instrumentation or narrowed behaviour.
Refreshed evidence executes the current checks against their original failing revisions.
Independent verification supplies acceptance; the builder does not accept this changeset.
The new round-4 cases use named regular methods instead of parameterized display names
so the unchanged evidence producer can select every case; their oracles are identical.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Idle loaded chat lends its managed process to one captured VDU batch, restores exactly once, yields to a human demand or activity at the current document boundary, refuses a racing lease without draining it, rolls back failed entry without retry loops, and shuts down without relaunching or admitting waiting chat. The real controller/lifecycle/tracker/pacing/coordinator/processor wiring is exercised through controlled process endpoints.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Borrow loaded chat after the full five-minute user and generation idle window; use atomic IF_IDLE entry, one attempt per uninterrupted idle period and an additional five-minute failure back-off. A human demand waits for the current document and one restoration; fake-process checks bound the post-document hand-off to five seconds.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r4-r1-refresh.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#idleLoadedChatProcessesOneBoundedPassAndRestoresItsConfiguration",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#humanDemandFinishesAfterOnlyTheCurrentDocumentAndOneRestore",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#activityBetweenDocumentsRestoresChatAndDoesNotRelaunchAgainInTheSameIdlePeriod",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#failedAutomaticLaunchRestoresLoadedChatAndBacksOff",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#automaticEntryRefusesARacingLeaseWithoutWaitingOrChangingChat",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#shutdownDuringAutomaticPassStopsTheServerAndRefusesTheWaitingPerson"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve generation/AUX/ASSIST/VDU lease exclusion, queued-demand exclusion, power and pressure gates, generation idle dwell, explicit pass entry and the chat-off automatic return to off intent.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Keep explicit VDU and chat-off automatic entry unchanged. Borrowing loaded chat requires confirmed AC, a full idle interval, and no queued/open exchange or resource hold.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r4-r1-refresh.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#generationAuxLeasePowerPressureAndShortIdleAllBlockAutomaticEntry",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#explicitPassStillWorksWithoutAnIdleWindow",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java#chatOffAutomaticPathStillReturnsToTheOffIntent"
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
      "whatMoved": "The process fixture records PID plus creation time and verifies both before cleanup. It additionally exposes controlled vision-launch refusal and a bounded paused model response so new module checks exercise actual lifecycle launches and HTTP inference, without a real model or GPU.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Use identity-safe process stand-ins with controlled launch/response boundaries, plus admission and external resource-probe fixtures for module verification. Existing process fixture health and model-launch oracles are retained.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts"
      ],
      "class": "New",
      "behaviour": "The panel names picture reading and the document-boundary chat return only while an automatic pass borrows loaded chat. Normal wording returns after the hold, and stale snapshots never claim active picture reading. The round-2 evidence covers these current checks; the round-2 entries below document the caption restriction and instrumentation adaptation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Use the existing VDU_BATCH state and a supplemental AI-panel sentence explaining the document-boundary hand-off, without adding a new wire state.",
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
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Failed normal restoration leaves the normal snapshot offline, with bounded backoff or immediate human retry. Automatic BUSY entry defers without a false offline reason. Shutdown abandons the current document for all pass kinds without FAILED acknowledgement or normal relaunch; the five-second live-work drain follows the normal terminal stop allowance. Both automatic paths wait the full process startup idle interval. Status identifies only loaded automatic borrowing. Later rounds retain these oracles and add the repairs documented below.",
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
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r4-r2-refresh.json",
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
      "behaviour": "When enrichment is active, production shutdown fences and cancels it before closing admission and waiting for durable live work. The round-3 fixture adaptation explicitly selects an active pass and preserves this order oracle.",
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
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r4-r2-refresh.json",
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
    },
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
      "whatMoved": "Retain all ten round-2 behaviour oracles. The mock index endpoint forwards the additive shutdown-scoped recovery operation to its existing live-admission recovery fixture. A controlled controller clock advances restoration pressure time without forty seconds of sleeping. The refreshed round-2 before135 run projects out only three unselected round-3 test methods referencing Head or terminal APIs absent on that revision; all selected round-2 method bodies and helpers remain byte-identical. The external execution guard records the projection hashes.",
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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Automatic restoration stops after five consecutive failures including the failed return; status remains config_apply_failed and says retries stopped. Human chat, recorded settings, committed prepared settings and off/on permit a fresh bounded attempt series. A record-only prepared refresh retains the newest restore target so its next demand actually starts chat. Explicit entry while recovery is pending performs normal restoration then vision entry without timer ticks, new activity or a manual activation; two healthy fake starts delayed by two seconds reach the first document within twelve seconds.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Under the orchestrator's delegated round-4 decisions, cap consecutive restoration failures at five, reset the budget on a person's request or settings/off-on action, return the cancelled document's retry, and repair confirmed explicit entry through pending recovery. Preserve terminal result ownership and general startup recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r4-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java#automaticRestorationStopsAfterFiveFailuresAndReportsStoppedRetries",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java#humanRequestStartsAFreshFiveAttemptBudget",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java#recordedSettingsStartAFreshFiveAttemptBudget",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java#preparedSettingsStartAFreshFiveAttemptBudget",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java#turningChatOffAndOnMakesTheNextAttemptAfterRetriesStop",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java#explicitPendingPassEntersAfterTwoHealthyStartsWithoutAnotherEvent",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRetryBudgetTest.java#recordOnlyPreparedSettingsRetainTheNewestRestorationTarget"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Orderly owner-scoped cancellation atomically returns PROCESSING to PENDING and restores the pre-claim retry count, including a final permitted attempt. A later pass can claim the document again without cancellation spending its retry.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Under the orchestrator's delegated round-4 decisions, cap consecutive restoration failures at five, reset the budget on a person's request or settings/off-on action, return the cancelled document's retry, and repair confirmed explicit entry through pending recovery. Preserve terminal result ownership and general startup recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r4-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java#shutdownReturnsTheUnusedRetryAndAllowsTheNextPass",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java#shutdownReturnsTheFinalRetryAndAllowsTheNextPass"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation: a completed attempt winning the reset retains its success text and claimed retry count. Unscoped startup recovery after lost worker ownership retains the claimed retry count.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Under the orchestrator's delegated round-4 decisions, cap consecutive restoration failures at five, reset the budget on a person's request or settings/off-on action, return the cancelled document's retry, and repair confirmed explicit entry through pending recovery. Preserve terminal result ownership and general startup recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-r4-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java#completedAttemptKeepsItsRetryWhenShutdownResetLoses",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java#startupRecoveryKeepsTheClaimedRetryWhenItsOwnerWasLost"
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
  "session": "VC-vduauto-20261014",
  "verdict": "accept",
  "digest": "sha256:0bcfbf96a4ff5733f9a2c5e121682dfb503e95cbd3826909fae1c29f1ba21582",
  "note": "VC-vduauto (independent Claude verifier) over four rounds, accepting 5554abe87 vs 0a79303bb (rejected 83f07cc4c for uncovered tests and a dead wrapper only): automatic restoration stops after 5 failures and a person's chat request, a settings apply or chat off/on resets it (live with a launch fault injector: exactly 5 attempts, status says retries stopped, no sixth in 2.5 min); a quit during chat-off and loaded-chat passes is clean (exit 0, no llama-server left) and the interrupted picture returns PENDING with its retry count; an explicit pass during a pending restore starts its first document in 5.4 s live; a committed settings publication while offline becomes the restore target (live context 16384 restored through passes and restart); automatic passes start about 305 s after the last activity and never while chat is offline; game mode, later settings, chat off, start race and shutdown during entry hold. Each new check fails on 83f07cc4c by assertion. Suites show no test passing at the base that fails now; ArchUnit failures identical to the base; PMD clean on changed lines. Named limits: worker document ownership is in memory; loaded-chat automatic reading off on non-Windows unknown power source. Digest bound with --base 0a79303bb, the lane's base."
}
```
