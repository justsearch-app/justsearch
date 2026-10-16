---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b6d536-last-two-20261016
---

# b85-auto-vdu — complete intent and round 4 repairs

Whole-change reference: 0a79303bb. Round-4 fail-before: 83f07cc4c.
All still-applicable round-1, round-2 and round-3 entries are retained below, including
the adaptations when later rounds changed instrumentation or narrowed behaviour.
Refreshed evidence executes the current checks against their original failing revisions.
Independent verification supplies acceptance; the builder does not accept this changeset.
The new round-4 cases use named regular methods instead of parameterized display names
so the unchanged evidence producer can select every case; their oracles are identical.

Phase B refresh on b599d4d7e. When b6d536/vdu-auto merged (bc5978095), the checks were adapted to
the integration line: QuitBudget, ProcessTerminator.open(ProcessHandle), the lease-less
RuntimeReconciler constructor and the budgeted shutdown sequence. None of these exist on the
vdu-auto lane. Each evidence file keeps its lane before-state, the parent of the commit that added
its checks, and applies a compat patch:

- r4-r1 runs on 0a79303bb (parent of 13524ddf0) with b85-auto-vdu-r4-r1-refresh-compat.patch.
- r4-r2 runs on 13524ddf0 (parent of 14a7eb8a2) with b85-auto-vdu-r4-r2-refresh-compat.patch.
- r3-java runs on 14a7eb8a2 (parent of 83f07cc4c) with b85-auto-vdu-r3-java-compat.patch.

The patches add three kinds of code:

- QuitBudget, unchanged, with the two EngineSupervisionPolicy constants it reads.
- Delegating overloads:
  - RuntimeReconciler without a lease, which creates one.
  - EngineShutdownSequence.run with a budget, which ignores it.
  - HeadlessApp.orderedShutdownSteps with a Duration-taking producer drain. It is varargs, so
    existing callers that pass null keep their binding.
- Immediate no-op returns for API that arrived with the checks:
  - HeadAssembly.cancelEnrichmentForQuit and awaitEnrichmentCancelForQuit, plus
    quiesceEnrichmentForTerminalShutdown on 13524ddf0.
  - KnowledgeClient.recoverVduProcessingForOwner.
  - InferenceLifecycleManager.beginTerminalShutdown.
  - OfflineCoordinator.cancelForShutdown.

The r4-r1 and r4-r2 patches are add-only: no existing line is deleted or replaced. A new
B85CompatibilityFakeLlamaServer supplies controlled completion, refusal and launch-journal
endpoints. Additions in the historical SlowStartFakeLlamaServer installer select that standalone
fixture, and additions in its cleanup/observation methods delegate only for the marked fixture.
The historical main, launch parser and cleanup bodies remain untouched. The separate fixture
records and checks PID plus creation time, including the launcher's lifetime; it does not alter
product controller, idle, restoration or shutdown behavior. The app-services test dependency on
app-engine is also add-only. Delegating overloads preserve the historical implementation;
the terminal-fence and cancel stubs use compile-only varargs, so the copied tests' optional
zero-argument reflection still observes their absence. Other neutral API stubs remain unreached.
Fresh replay repair on 31bbdf349 also covers the retained r2 and r4 records:

- r2-java selects only its nine retained preservation checks on 13524ddf0. The add-only
  b85-auto-vdu-r2-preservation-compat.patch adds the lease-less RuntimeReconciler constructor,
  delegating to the historical constructor with a fresh lease. The current test fixture is the
  sole overlay. The historical build file is retained, avoiding a mismatched dependency lock.
- r4-java runs all eleven declared checks on 83f07cc4c with the add-only
  b85-auto-vdu-r4-replay-compat.patch. It adds the same delegating constructor and the later
  QuitBudget type/constants, budget-delegating EngineShutdownSequence overload, and neutral
  HeadAssembly quit-cancel APIs used only by unselected fixture methods. None of the seven
  selected retry-budget checks reaches these shutdown stubs. Overlays are the two declared
  test fixtures; the existing build file already includes app-engine and is retained.
- r4-r1 and r4-r2 keep their existing add-only patches. The UI shutdown entry now cites the
  same r4-r2 evidence as its ten app-services checks. The round-3 UI entries likewise cite
  r3-java, eliminating duplicate evidence with older patch digests.
- Integration recovery preservation now has a before-pass on 1d23c9d95 alongside the repair's
  assertion fail-before.

Every declared setup is replayed from scratch with the current check files, exactly its named
patch and overlays, and exact method selectors. No test method is projected out, no product
behavior is overlaid, and no candidate-only fallback is introduced. The builder report records
commands, non-zero executed case counts, outcomes and retained logs. Evidence is regenerated
with run-evidence; its general fail-before exit policy returns 1 for preservation passes too.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Idle loaded chat lends its managed process to one captured VDU batch, restores exactly once, yields to a human demand or activity at the current document boundary, refuses a racing lease without draining it, rolls back failed entry without retry loops, and shuts down without relaunching or admitting waiting chat. The real controller/lifecycle/tracker/pacing/coordinator/processor wiring is exercised through controlled process endpoints. Fail-before: 0a79303bb with b85-auto-vdu-r4-r1-refresh-compat.patch, by assertion.",
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
      "whatMoved": "The process fixture records PID plus creation time and verifies both before cleanup. It additionally exposes controlled vision-launch refusal and a bounded paused model response so new module checks exercise actual lifecycle launches and HTTP inference, without a real model or GPU. Build cleanup removes redundant qualification in SlowStartFakeLlamaServer, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "behaviour": "Failed normal restoration leaves the normal snapshot offline, with bounded backoff or immediate human retry. Automatic BUSY entry defers without a false offline reason. Shutdown abandons the current document for all pass kinds without FAILED acknowledgement or normal relaunch; the five-second live-work drain follows the normal terminal stop allowance. Both automatic paths wait the full process startup idle interval. Status identifies only loaded automatic borrowing. Later rounds retain these oracles and add the repairs documented below. Fail-before: 13524ddf0 with b85-auto-vdu-r4-r2-refresh-compat.patch, by assertion.",
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
      "behaviour": "When enrichment is active, production shutdown fences and cancels it before closing admission and waiting for durable live work. The round-3 fixture adaptation explicitly selects an active pass and preserves this order oracle. Fail-before: 13524ddf0 with b85-auto-vdu-r4-r2-refresh-compat.patch, by assertion. Build cleanup removes redundant qualification in HeadlessAppVduShutdownTest, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "whatMoved": "Inject an already-expired process startup clock for existing non-startup scenarios. All nine original behavioural oracles remain. Before replay on 13524ddf0 uses b85-auto-vdu-r2-preservation-compat.patch and the current SlowStartFakeLlamaServer test fixture overlay; the constructor delegates to the historical reconciler and all nine selected cases execute and pass. The historical build file and dependency locks remain in use.",
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
      "whatMoved": "Add controlled normal-configuration launch refusal and capture the full launch arguments. Retain PID plus creation-time cleanup and all existing health/model fixtures. The fake exits directly with its captured launcher identity, avoiding HttpServer graceful-stop waiting on a deliberately paused response; no external process is stopped by the watcher. Build cleanup removes redundant qualification in SlowStartFakeLlamaServer, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "behaviour": "New normal configuration supersedes a pending restore, both restarted and record-only. Restoration timer and human demand obey game/GPU pressure, including load-anyway and reload dwell. An explicit pass during pending restoration retains its original demand gate. A start race rearms at the next full idle window without human activity. A three-second owned-process stop during a cancelled pass retains PENDING and drains within the normal terminal allowance. An explicit entry waiting on normal chat preserves that exchange until ordinary interactive cancellation; terminal preparation fences every later process launch. Fail-before: 14a7eb8a2 with b85-auto-vdu-r3-java-compat.patch, by assertion.",
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
      "behaviour": "No-pass shutdown preserves the original cancellation/drain/terminal-stop order. An active pass receives the normal twelve-second terminal allowance; a three-second stop remains clean and later steps all execute. Fail-before: 14a7eb8a2 with b85-auto-vdu-r3-java-compat.patch, by assertion. Build cleanup removes redundant qualification in HeadlessAppVduShutdownTest, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "behaviour": "Success and shutdown reset serialize in both orders. A selected processing document cannot reset a later success; reset retires the same owner and refuses its late result. Scoped reset cannot change another procedure's claim. Fail-before: 14a7eb8a2 with b85-auto-vdu-r3-java-compat.patch, by assertion.",
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
      "whatMoved": "Retain all ten round-2 behaviour oracles. The mock index endpoint forwards the additive shutdown-scoped recovery operation to its existing live-admission recovery fixture. A controlled controller clock advances restoration pressure time without forty seconds of sleeping. All current methods compile unchanged under the declared add-only compatibility scaffolding; no test method is removed or projected out for replay.",
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
      "whatMoved": "Retain the previous active-enrichment-before-admission oracle; its Head fixture now explicitly identifies an active pass instead of implying every shutdown has one. Build cleanup removes redundant qualification in HeadlessAppVduShutdownTest, retaining original inputs, resource lifetimes and every existing assertion.",
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
      "behaviour": "Automatic restoration stops after five consecutive failures including the failed return; status remains config_apply_failed and says retries stopped. Human chat, recorded settings, committed prepared settings and off/on permit a fresh bounded attempt series. A record-only prepared refresh retains the newest restore target so its next demand actually starts chat. Explicit entry while recovery is pending performs normal restoration then vision entry without timer ticks, new activity or a manual activation; two healthy fake starts delayed by two seconds reach the first document within twelve seconds. Fail-before: 83f07cc4c with b85-auto-vdu-r4-replay-compat.patch and the two named test-fixture overlays. The selected cases do not call the added quit/shutdown APIs; the historical retry controller remains under test.",
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
      "behaviour": "Orderly owner-scoped cancellation atomically returns PROCESSING to PENDING and restores the pre-claim retry count, including a final permitted attempt. A later pass can claim the document again without cancellation spending its retry. Fail-before: 83f07cc4c under the r4-java setup; both selected cases execute and fail by assertion.",
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
      "behaviour": "Preservation: a completed attempt winning the reset retains its success text and claimed retry count. Unscoped startup recovery after lost worker ownership retains the claimed retry count. Preservation replay: 83f07cc4c under the same r4-java setup; both selected cases execute and pass.",
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
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduRecoveryReplayCompletenessTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/VduGenerationBoundaryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Retain every row, commit, ordering, acknowledgement and refusal oracle. The recovery reset is stubbed and verified on the conditional mutation the lane introduced, updateDocumentIf(docId, {vdu_status: PROCESSING}, {vdu_status: PENDING}), instead of updateDocument; the update and mark operations keep updateDocument. A missing selected document is now encoded as a lost comparison while the mocked field reader reports no stored document (its default answer), where it used to be updateDocument returning false. The empty-selection case verifies that neither mutation is called.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Integration repair after the vdu-auto merge (bc5978095), decided by the orchestrator under the owner's delegation: the recovery reset stays the conditional PROCESSING-to-PENDING change. When it loses its comparison, a selected document that is absent stays a miss, so a replayed recovery keeps its row as before the conditional reset; a present document that is no longer PROCESSING, for example after a later success, is resolved: it is not reset, is not a miss and does not keep the row.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduRecoveryLostComparisonTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "On a real index, a selected PROCESSING document that is removed after recovery selects it and before its conditional reset is a miss: the replayed recovery keeps its row for a later replay.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Integration repair after the vdu-auto merge (bc5978095), decided by the orchestrator under the owner's delegation: the recovery reset stays the conditional PROCESSING-to-PENDING change. When it loses its comparison, a selected document that is absent stays a miss, so a replayed recovery keeps its row as before the conditional reset; a present document that is no longer PROCESSING, for example after a later success, is resolved: it is not reset, is not a miss and does not keep the row.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-integration-recovery-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduRecoveryLostComparisonTest.java#selectedDocumentRemovedBeforeItsResetStaysAMissAndKeepsTheRow"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduRecoveryLostComparisonTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation: on a real index, a selected PROCESSING document that completes after recovery selects it and before its conditional reset is not reset to PENDING, does not count as a miss and does not keep the recovery row. Preservation replay: 1d23c9d95, with the current check file and no patch or overlay; one selected case executes and passes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "choice": "Integration repair after the vdu-auto merge (bc5978095), decided by the orchestrator under the owner's delegation: the recovery reset stays the conditional PROCESSING-to-PENDING change. When it loses its comparison, a selected document that is absent stays a miss, so a replayed recovery keeps its row as before the conditional reset; a present document that is no longer PROCESSING, for example after a later success, is resolved: it is not reset, is not a miss and does not keep the row.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-integration-recovery-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduRecoveryLostComparisonTest.java#selectedDocumentAlreadyCompletedIsNotResetAndDoesNotKeepTheRow"
      ]
    }
  ]
}
```

## Integration repair: lost comparisons in VDU recovery (on b6d536/integration after 1d23c9d95)

83f07cc4c made the recovery reset the conditional PROCESSING-to-PENDING change
(updateDocumentIf). It also dropped the recovery's treatment of a reset that changes nothing as
incomplete, so after bc5978095 every lost comparison counted as resolved, including a selected
document that is gone. The indexer-worker VduRecoveryReplayCompletenessTest and
VduGenerationBoundaryTest still stubbed updateDocument, so their recovery cases failed from
bc5978095 on (first failing first-parent commit; they pass on a000e15c7 and on 83f07cc4c^).

- WorkerIngestService.resetVduStatusToPending: after a lost comparison it reads whether the
  document is still stored. Absent stays a miss (the recovery reports incomplete and a replay
  keeps its row); present is resolved. The owner-scoped early return for another procedure's claim
  is unchanged.
- The two indexer-worker tests follow the conditional reset (Adaptation entry above).
- VduRecoveryLostComparisonTest pins both outcomes on a real index (New entries above).

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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "accept",
  "digest": "sha256:f6c6d90b538be12d74431f45424a22d9ecc772dc1a08c3691286be23e8e0918b",
  "note": "Reviewed current tests, fixture adaptations, refreshed evidence and patches; selected current candidate checks pass. Every changed before record/patch matches today's b1 independent review exactly, including the separately executed entry-25 preservation before-pass."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5a-20261016",
  "verdict": "reject",
  "digest": "sha256:96aa90ae1aab72278d8127863b5802074b0a916788fd3900afccc8f0887a36c1",
  "note": "Reject: the declared r4-r1 and r4-r2 before patches replace existing SlowStartFakeLlamaServer fixture behavior, including launcher checks and removing server.stop(0). They exceed the permitted add-only, stated abstract-to-default or unreached neutral-stub patch forms."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5d-20261016",
  "verdict": "reject",
  "digest": "sha256:fcba94beacaa49f30c37944b60c66805b98f504f7467098ade00bf41f52a93f6",
  "note": "The repaired add-only r4-r1/r4-r2 setups reproduce their declared outcomes. Reject: retained r2 (13524ddf0) and r4 (83f07cc4c) setups compile zero cases for 16 selectors because of the IdleSampler constructor and missing QuitBudget/shutdown APIs, instead of the recorded passes/assertion failures."
}
```
