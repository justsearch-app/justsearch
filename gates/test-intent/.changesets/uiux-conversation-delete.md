---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp52-builder-toei4xfg
---

# WP5.2 conversation deletion

Gate aliases DS1–DS6 map to agreed S52.1–S52.6, and DN2–DN5 to N52.2–N52.5 (the gate does not accept dotted ids). Quotes are verbatim.

All checks are durable. Compatibility patch adds signatures only to the before-state and delegates to its unchanged deletion paths; it contains no repaired behavior. Scenario quotes are agent-drafted, owner-adopted; DC1/DC2/CD1 are agent choices under the quoted design delegation. Existing tests in modified files retain their prior expectations except the explicit locked refusal. Its display name now starts with its method name so the evidence runner can match the JUnit XML case; no inputs, oracle, selection or conditions change.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentRunStoreConversationsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The locked run-store delete explicitly refuses while retaining the target and neighbouring runs; unchanged unlocked deletion/listing expectations remain.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS3",
          "quote": "While the encrypted store is locked, the delete is refused with a reason and nothing is deleted (decision DC2). The conversation stays listed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentRunStoreConversationsTest.java#deleteFailsClosedWhileLocked"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationDeleteRefusalTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Locked and live run deletion refuse before removing metadata. Startup/unlock reconciliation owns the conversation through all directive-event writes, so deletion during an event callback refuses before later events can recreate a removed run.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS2",
          "quote": "Deleting while work for that conversation is in flight either is refused with a reason before anything is deleted, or stops or finishes that work first (decision DC1). Work in flight includes:\n  - an Ask streaming in any window or client;\n  - an agent run, in the foreground or background, including one waiting for approval;\n  - a queued history write or a pending history ingest.\n  After a reported success, nothing recreates any part of the conversation, including after a restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS3",
          "quote": "While the encrypted store is locked, the delete is refused with a reason and nothing is deleted (decision DC2). The conversation stays listed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC1: Refuse live Asks, runs and approval waits. Inside the delete, wait out or cancel the invisible history writer.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationDeleteRefusalTest.java#lockedRunDeleteIsARefusalRatherThanAnEmptySuccess",
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationDeleteRefusalTest.java#reconciliationOwnsTheConversationUntilItsLastEventWrite",
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationDeleteRefusalTest.java#liveRunDeleteRefusesBeforeRemovingAnyRun"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationHistoryRetentionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Startup preserves the existing run ownership link at first upgraded start, newly written ownership at later starts, and sealed ownership at locked startup beyond the former pruning age. Retained runs remain deletable with their events.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS1",
          "quote": "After a delete reported as done, none of these can be listed, opened or found anywhere the app lists conversations or runs, or in any search scope including Agent history:\n  - the conversation's records;\n  - its agent runs and their events;\n  - its agent-history transcripts and pending markers;\n  - its agent-history index documents.\n  This holds after an Engine restart, after unlock and after boot reconciliation, and also for a conversation that consists only of agent runs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD1: S52.1 covers every agent-history transcript, pending marker and index document whose conversation can be determined. It must stay determinable for everything written from now on and for everything whose run metadata still exists at the first start of this version, so that later pruning never makes it unattributable. Keep directories containing meta.json out of age pruning; retaining their existing event projection avoids phantom resumable runs and adds no mapping/migration state. Pre-upgrade orphaned history is unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationHistoryRetentionTest.java#startupKeepsExistingAndNewRunOwnershipPastTheOldPruningAge",
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationHistoryRetentionTest.java#lockedStartupCannotPruneTheSealedOwnershipLink"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeleteWorkTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Shape execution retains writer ownership through terminal delivery and final persistence: a concurrent delete refuses, saved turns survive, and a subsequent idle delete works.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS2",
          "quote": "Deleting while work for that conversation is in flight either is refused with a reason before anything is deleted, or stops or finishes that work first (decision DC1). Work in flight includes:\n  - an Ask streaming in any window or client;\n  - an agent run, in the foreground or background, including one waiting for approval;\n  - a queued history write or a pending history ingest.\n  After a reported success, nothing recreates any part of the conversation, including after a restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC1: Refuse live Asks, runs and approval waits. Inside the delete, wait out or cancel the invisible history writer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeleteWorkTest.java#refusesDeleteThroughoutShapeRunIncludingTerminalDelivery"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Deletion removes conversation messages, all run/event directories, transcripts/temporary files/pending markers, exact Worker documents, and retired replay while preserving another conversation. Run-only conversations delete fully. Index or real filesystem deletion failure retains a visible recovery row, blocks writes and permits retry. A simulated Engine stop after intent publication recovers on restart even after run metadata loss. Deletion waits behind a queued history writer and later reconciliation does not recreate deleted history.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS1",
          "quote": "After a delete reported as done, none of these can be listed, opened or found anywhere the app lists conversations or runs, or in any search scope including Agent history:\n  - the conversation's records;\n  - its agent runs and their events;\n  - its agent-history transcripts and pending markers;\n  - its agent-history index documents.\n  This holds after an Engine restart, after unlock and after boot reconciliation, and also for a conversation that consists only of agent runs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS2",
          "quote": "Deleting while work for that conversation is in flight either is refused with a reason before anything is deleted, or stops or finishes that work first (decision DC1). Work in flight includes:\n  - an Ask streaming in any window or client;\n  - an agent run, in the foreground or background, including one waiting for approval;\n  - a queued history write or a pending history ingest.\n  After a reported success, nothing recreates any part of the conversation, including after a restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS4",
          "quote": "A delete that could not remove everything (a file in use, permission denied) is reported as not done or incomplete. The row stays, or says what remains. Repeating the delete finishes it. Nothing is ever reported deleted while part of it can come back.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS5",
          "quote": "A crash or Engine stop partway through a delete never leaves conversation content on disk that the list cannot show. After the next start the conversation is either listed and deletable, or fully gone.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DN2",
          "quote": "Agent-history search and reconciliation for runs that still exist.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC1: Refuse live Asks, runs and approval waits. Inside the delete, wait out or cancel the invisible history writer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#removesBothPlanesAndHistoryButPreservesAnotherConversation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#runOnlyConversationIsDeletedCompletely",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#failureStaysListedBlocksNewWritesAndRetryFinishes",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#interruptedDeleteRecoversOnNextStartEvenAfterRunMetadataWasLost",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#successfulDeleteDropsRetiredReplayAndItsRecordHint",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#waitsOutQueuedHistoryAndReconciliationCannotRecreateIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#fileDeletionFailureKeepsTheRecoveryRowAndRetryFinishes"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteHttpTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "HTTP deletion refuses a locked store with 423/STORE_LOCKED before touching either store, and refuses a streaming Ask from another HTTP client with 409/CONVERSATION_BUSY while preserving both turns.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS2",
          "quote": "Deleting while work for that conversation is in flight either is refused with a reason before anything is deleted, or stops or finishes that work first (decision DC1). Work in flight includes:\n  - an Ask streaming in any window or client;\n  - an agent run, in the foreground or background, including one waiting for approval;\n  - a queued history write or a pending history ingest.\n  After a reported success, nothing recreates any part of the conversation, including after a restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS3",
          "quote": "While the encrypted store is locked, the delete is refused with a reason and nothing is deleted (decision DC2). The conversation stays listed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC1: Refuse live Asks, runs and approval waits. Inside the delete, wait out or cancel the invisible history writer.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteHttpTest.java#lockedDeleteRefusesBeforeTouchingEitherStore",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteHttpTest.java#anotherHttpClientCannotDeleteWhileAnAskIsStreaming"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/HistoryDeleteAcknowledgementTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The Worker refuses a strict history delete during a cutover that would only buffer it, and reports failure when a pending ingest job could not be removed rather than acknowledging deletion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS1",
          "quote": "After a delete reported as done, none of these can be listed, opened or found anywhere the app lists conversations or runs, or in any search scope including Agent history:\n  - the conversation's records;\n  - its agent runs and their events;\n  - its agent-history transcripts and pending markers;\n  - its agent-history index documents.\n  This holds after an Engine restart, after unlock and after boot reconciliation, and also for a conversation that consists only of agent runs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS2",
          "quote": "Deleting while work for that conversation is in flight either is refused with a reason before anything is deleted, or stops or finishes that work first (decision DC1). Work in flight includes:\n  - an Ask streaming in any window or client;\n  - an agent run, in the foreground or background, including one waiting for approval;\n  - a queued history write or a pending history ingest.\n  After a reported success, nothing recreates any part of the conversation, including after a restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS4",
          "quote": "A delete that could not remove everything (a file in use, permission denied) is reported as not done or incomplete. The row stays, or says what remains. Repeating the delete finishes it. Nothing is ever reported deleted while part of it can come back.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/HistoryDeleteAcknowledgementTest.java#settledDeleteRefusesCutoverInsteadOfAcknowledgingABufferedDelete",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/HistoryDeleteAcknowledgementTest.java#queueCleanupFailureCannotAcknowledgeDeletion"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationDelete.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Delete returns the server live-work or locked reason; a failed cascade identifies the failed branch and all branches already deleted without misclassifying a live-work conflict as a branch question.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS2",
          "quote": "Deleting while work for that conversation is in flight either is refused with a reason before anything is deleted, or stops or finishes that work first (decision DC1). Work in flight includes:\n  - an Ask streaming in any window or client;\n  - an agent run, in the foreground or background, including one waiting for approval;\n  - a queued history write or a pending history ingest.\n  After a reported success, nothing recreates any part of the conversation, including after a restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS3",
          "quote": "While the encrypted store is locked, the delete is refused with a reason and nothing is deleted (decision DC2). The conversation stays listed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS6",
          "quote": "A branch cascade that fails partway reports what was and was not deleted, as above.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationDelete.test.ts#returns a live-work reason without opening the branch confirmation",
        "modules/ui-web/src/shell-v0/state/conversationDelete.test.ts#returns the locked reason",
        "modules/ui-web/src/shell-v0/state/conversationDelete.test.ts#reports the failed branch and the branches already deleted"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/chat/ConversationHistory.delete.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Classic history deletion displays the server locked reason and keeps the visible conversation row.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS3",
          "quote": "While the encrypted store is locked, the delete is refused with a reason and nothing is deleted (decision DC2). The conversation stays listed.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/chat/ConversationHistory.delete.test.ts#classic delete displays the server refusal and keeps the conversation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Search v3 displays a cross-client live-work refusal without asking the branch cascade question, and reports partial branch failure; existing branch-cascade behavior remains covered.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS2",
          "quote": "Deleting while work for that conversation is in flight either is refused with a reason before anything is deleted, or stops or finishes that work first (decision DC1). Work in flight includes:\n  - an Ask streaming in any window or client;\n  - an agent run, in the foreground or background, including one waiting for approval;\n  - a queued history write or a pending history ingest.\n  After a reported success, nothing recreates any part of the conversation, including after a restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DS6",
          "quote": "A branch cascade that fails partway reports what was and was not deleted, as above.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DN3",
          "quote": "The branch refusal (409 with child ids) and Search v3's cascade question.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#displays a refusal from another client without asking to delete branches",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#reports which branch failed after a partially completed cascade"
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
  "session": "verifier-bhht7zbd",
  "verdict": "accept",
  "digest": "sha256:1ac3729b213b522ca85473c872022d083789fa63e07ee4b53e443df20c4b18c6",
  "note": "Reviewed before-state tests, agreed scenarios and sources before the diff. All 24 named checks passed without skips on the exact candidate and failed by assertion before, independently replayed to scratch evidence. Test expectations accepted. Product verification fails S52.2: in-flight compaction recreates deleted conversation metadata and its private summary."
}
```
