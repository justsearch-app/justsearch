---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp15b-A-continuation-3
---

Exact removed files/members license only retired old-host policies. Retained or moved expectations are listed separately. Historical test-intent records remain unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/primitives/compositionLayout.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/primitives/compositionLayout.ts",
            "modules/ui-web/src/shell-v0/views/unifiedChatStyles.ts",
            "modules/ui-web/src/shell-v0/views/unifiedChatRequest.ts#CONVERSATION_ZONES"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 and decision 1 retire the old three-zone/grid/short-viewport machinery, minimap and spine policies; Shell viewport subscription stays.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/primitives/adaptiveSpacing.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/primitives/adaptiveSpacing.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 and decision 1 retire private old spacing/geometry policies.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/agencyPosture.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/agencyPosture.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 retires old-host posture; Settings autonomy, consent and public dial remain.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/runSpinePresentation.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/runSpinePresentation.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 and decision 1 retire private minimap/spine placement, not public RunNode/ToolCallCard/runStepPresentation capabilities.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/routeHeuristic.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/state/routeHeuristic.ts#inferRoute"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 retires old automatic route inference; describeRoute/TurnRoute and public RouteChip remain.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/budgetProjection.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/budgetProjection.ts#projectBudget",
            "modules/ui-web/src/shell-v0/views/budgetProjection.ts#BudgetProjection"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 retires only old bar projection; context horizon, gate facts and continuation ladder assertions stay.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/compose.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/utils/compose.ts#resolveDispatchShape",
            "modules/ui-web/src/shell-v0/utils/compose.ts#wireSelectionKind",
            "modules/ui-web/src/shell-v0/utils/compose.ts#setPendingAutoRun",
            "modules/ui-web/src/shell-v0/utils/compose.ts#takePendingAutoRun"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 and C6 retire old chip/send resolver and legacy auto-run register; prepare-and-wait and shared selection/compose transport remain.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/unifiedChatRequest.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/unifiedChatRequest.ts#SHAPE_LABELS"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 retires old-host labels; builder, sentinel, human-load marker and request-shaping assertions remain.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/unifiedThreadProjection.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/unifiedThreadProjection.ts#terminalAssistantIds",
            "modules/ui-web/src/shell-v0/views/unifiedThreadProjection.ts#compareUnifiedTurnItems",
            "modules/ui-web/src/shell-v0/views/unifiedThreadProjection.ts#projectLiveAgentActivity"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 retires old live-merge, terminal-node and comparator assertions only; canonical record projection/order/segments/direction wording remain.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/unifiedThreadProjection.ts#projectLiveAgentActivity"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 retires only old projection halves of direction tests, preserving live controller direction outcomes and Search production projection.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskFallbackStream.test.ts",
        "modules/ui-web/src/shell-v0/views/LegacyThreadOccurrenceOrder.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatRefusalExplanation.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.acceptedOutput.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.extractResult.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.history-locked.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.interruptedOutput.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.stopTurn.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.stream-deletion.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.terminalOutput.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.thread-refresh-signal.test.ts",
        "modules/ui-web/src/shell-v0/views/unifiedChatResidency.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/UnifiedChatView.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1\u2013S2 retire the old host. Obsolete portions are its root-specific selectors, old header/rail/dropdown/grid/minimap/posture/inference, committed SEARCH/pin-run updates (A9), answer auto-run (C6), local ordinary Stop superseded by addressed Search Stop, and XN1 chunk fallback superseded by D-T43 accepted-terminal-only Structured output. Shared behavioral obligations are adapted to Search, not retired by this entry. Model-only Search uses persistent store history (existing sv3-ask D6), not the old host client-context injection.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions and surviving Search callers with rg for the removed host/member and contrary retained obligations; source map sections 1\u20133 and settled SA15/S2 decisions agree. No contrary decision found. Surviving behavior is separately adapted in wp15b-A-preservation.md and the report ledger."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/UnifiedChatView.ts"
          ]
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "Decision 4: only the old-host per-source-toggle test retires; SourcesPane/exclusion store and endpoint and stored exclusions/Include all preservation stay. This is a pre-existing Search reachability gap, not preserved user-action coverage.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched SearchV3View/Sv3Main/Sv3Pane/CitationsPanel/SourcesPane and Search context tests for source-toggle, SourcesPane, excludedSourceIds and Include all. Search never mounts SourcesPane or a per-source exclusion control. Include all preserves state but cannot prove toggle reachability. Decision 4 authorizes no new control and explicitly requires reporting the gap. No contrary decision found in docs/decisions."
    }
  ]
}
```

## Acceptance records
