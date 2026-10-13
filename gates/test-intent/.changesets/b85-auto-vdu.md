---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b85-auto-vdu
---

# Test-intent entries

The Java checks cover six repaired behaviours and three preservation scenarios; their execution evidence records the before and after results. The independent verifier supplies acceptance; the builder does not judge this changeset.

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
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-java.json",
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
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-java.json",
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
      "behaviour": "The existing live VDU_BATCH procedure is described as picture reading while chat remains enabled. Normal wording returns after the hold and stale state cannot claim active picture reading; the existing chat action remains present. The new wording fails by assertion on 0a79303bb (web-before-guarded.log); preservation passes before and after.",
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
      "evidence": "gates/test-intent/evidence/b85-auto-vdu-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts#names picture reading while loaded chat lends its server to a VDU pass",
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts#keeps normal chat wording after the hold closes and never claims stale picture reading"
      ]
    }
  ]
}
```

## Acceptance records

