---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp52-cd4-au04h3xz
---

# WP5.2 conversation deletion

Gate aliases DS1–DS6 map to agreed S52.1–S52.6, and DN2–DN5 to N52.2–N52.5 (the gate does not accept dotted ids). Quotes are verbatim.

All checks are durable. CD2 adds queued-work ownership, a dispatch-time existence check, and source ownership for resume/fork. Its compatibility patch supplies no-op signatures only on 97439fc54, leaving old writes and deletion unchanged. The same nine checks also fail by assertion on c5204f626 without a compatibility patch (tmp/wp52-cd2/c5204f626-evidence.json). Sealed run-only identity remains unavailable while locked, as CD2 permits.

All checks are durable. The compaction repair extends DC1 to the user-visible compaction request, and makes creation explicit so later updates cannot create missing records. The new before-state patch supplies signatures only, retaining the old implicit creation behavior; its exception type has no implementation. The unchanged evidence producer runs through a shim that uses private shared clones and hard-linked dependencies.

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
      "behaviour": "Deletion removes conversation messages, all run/event directories, transcripts/temporary files/pending markers, exact Worker documents, and retired replay while preserving another conversation. Run-only conversations delete fully. Index or real filesystem deletion failure retains a visible recovery row, blocks writes and permits retry. A simulated Engine stop after intent publication recovers on restart even after run metadata loss. Deletion waits behind a queued history writer and later reconciliation does not recreate deleted history. Fixture setup now explicitly creates the conversation before appending, with unchanged message inputs and expectations.",
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-repair-java.json",
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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Compaction, rewind, message/source exclusion, turn status and message append refuse absent conversations and cannot recreate deleted content after restart. Turn-status updates own writer exclusion throughout their store write.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-repair-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java#compactionCannotCreateAbsentOrDeletedConversation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java#rewindCannotCreateAbsentOrDeletedConversation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java#messageExclusionCannotCreateAbsentOrDeletedConversation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java#sourceExclusionCannotCreateAbsentOrDeletedConversation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java#turnStatusCannotCreateAbsentOrDeletedConversation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java#messageAppendCannotCreateAbsentOrDeletedConversation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationLateWriteTest.java#turnStatusOwnsConversationWhileWriting"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationLateRunWriteTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Late checkpoints leave deleted runs absent. Live and imported event writes require existing run metadata. Workflow metadata updates refuse a deleted run rather than recreating it.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-repair-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationLateRunWriteTest.java#checkpointCannotRecreateDeletedRun",
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationLateRunWriteTest.java#metadataUpdateCannotRecreateDeletedRun",
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationLateRunWriteTest.java#liveEventCannotCreateMissingRun",
        "modules/app-agent/src/test/java/io/justsearch/agent/ConversationLateRunWriteTest.java#importedEventsCannotCreateMissingRun"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationCompactionDeleteTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A compaction waiting for summarization keeps the conversation busy: another HTTP client receives 409 with CONVERSATION_BUSY and both original turns survive. The lease lasts through summary persistence and releases after success or model failure, allowing a later complete deletion.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-repair-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationCompactionDeleteTest.java#deleteRefusesUntilCompactionHasSavedItsSummary",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationCompactionDeleteTest.java#failedCompactionReleasesDeletionExclusion"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunEventStoreTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationInvalidFoldersTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/CitationInclusionPersistenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/feedback/AgentDispositionWiringTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/AgentHistoryExecutorRecoveryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerDelegateHistoryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/SavedStoreIdsHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreEnumerationInvalidIdsHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Conversation and event fixtures explicitly create their existing conversation/run before appending, because append now updates only an existing record. Message/event payloads, preconditions, faults, expected outcomes, tags and selection remain unchanged. Every file is executed in the cited producer-written preservation evidence.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-repair-preservation.json"
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/DeferredConversationWorkTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Resume/approval-after-restart and fork retain source ownership until execution ends, stale captured resume snapshots cannot start after source deletion, and legacy plaintext run-only identities stay listed while locked without their preview or deleting bytes; structural activity times are preserved so locking does not move the row out of the merged list window.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD2: An accepted but not yet started background run is work in flight under DC1. Refuse deletion while it is queued: this reuses the existing work lease and preserves already accepted work. Check conversation existence and deletion state again at dispatch. While locked, keep legacy plaintext run-only identities listed without their preview; sealed identities remain hidden until unlock because determining them requires decryption.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd2-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/DeferredConversationWorkTest.java#resumedApprovalOwnsConversationUntilItFinishes",
        "modules/app-agent/src/test/java/io/justsearch/agent/DeferredConversationWorkTest.java#forkOwnsSourceConversationUntilItFinishes",
        "modules/app-agent/src/test/java/io/justsearch/agent/DeferredConversationWorkTest.java#staleResumeSnapshotCannotStartAfterSourceDeletion",
        "modules/app-agent/src/test/java/io/justsearch/agent/DeferredConversationWorkTest.java#plaintextRunOnlyIdentityStaysListedWhileLocked"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/QueuedConversationDeleteTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Accepted queued and delayed background runs refuse deletion until completion or shutdown; duplicate acceptance releases its extra reservation; dispatch rechecks absent/incompletely deleting conversations; missing conversations are refused before acceptance. The HTTP fixture constructs the real ResourceApiModule, whose separate presence conversation store must share the deletion authority.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD2: An accepted but not yet started background run is work in flight under DC1. Refuse deletion while it is queued: this reuses the existing work lease and preserves already accepted work. Check conversation existence and deletion state again at dispatch. While locked, keep legacy plaintext run-only identities listed without their preview; sealed identities remain hidden until unlock because determining them requires decryption.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd2-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/QueuedConversationDeleteTest.java#acceptedQueuedRunRefusesDeleteUntilItFinishes",
        "modules/ui/src/test/java/io/justsearch/ui/api/QueuedConversationDeleteTest.java#deferredRunRechecksExistenceBeforeEnteringAgent",
        "modules/ui/src/test/java/io/justsearch/ui/api/QueuedConversationDeleteTest.java#missingConversationIsRefusedBeforeAcceptance",
        "modules/ui/src/test/java/io/justsearch/ui/api/QueuedConversationDeleteTest.java#delayedDuplicateAndShutdownReleaseEveryReservation",
        "modules/ui/src/test/java/io/justsearch/ui/api/QueuedConversationDeleteTest.java#deferredRunRefusesAnIncompleteDeletion"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PresenceRunOperationKeyTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing mocked conversation store now explicitly reports the existing conversation metadata used by the operation-key test. All prompts, keys, contexts, statuses, terminal outcomes, deduplication counts, fault injection, selection and assertions are unchanged. CD2 now validates that precondition instead of ignoring it.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD2: An accepted but not yet started background run is work in flight under DC1. Refuse deletion while it is queued: this reuses the existing work lease and preserves already accepted work. Check conversation existence and deletion state again at dispatch. While locked, keep legacy plaintext run-only identities listed without their preview; sealed identities remain hidden until unlock because determining them requires decryption.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd2-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "CD3 removes confirmed deleted sessions, cached turns, open views and last-viewed pointers from both windows after partial cascades and refreshed lists; reports name deleted and failed branches. Captured list/history/thread replies and stale navigation cannot restore deleted content or claims. Deletion cleanup preserves another window’s active-conversation claim.",
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
          "scenario": "DS6",
          "quote": "A branch cascade that fails partway reports what was and was not deleted, as above.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD3: Remember successful deletions in the existing process-wide ConversationWork authority and refuse stale writes before allocating records, using CONVERSATION_DELETED with a reason. The list carries these exact deletedIds because omission from its recent 20 rows cannot establish deletion. Both windows purge confirmed cached state and ignore earlier list/history/thread replies. Explicit backup imports alone can restore identities. No new saved state: markers are lost at Engine restart, when stale first-write ids are again indistinguishable from new ids.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd3-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#a deletion refresh clears this window without claiming another windows active conversation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#forgets the open deleted child and cached turns when the next cascade child fails",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#forgets a cached open conversation when a refreshed list confirms its deletion",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts#clears the old window transcript and pointer after a confirmed local delete",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts#clears the old window transcript and pointer when a refreshed list confirms deletion",
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#a list requested before DELETE cannot resurrect a confirmed deletion",
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#a history response captured before DELETE cannot reclaim its deleted conversation",
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#a stale navigation cannot claim or restore a confirmed deleted identity",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts#a history read already in flight cannot restore deleted cached turns",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts#a thread read already in flight cannot restore deleted interaction events"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/LateConversationWriteTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/LateConversationWriteHttpTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "CD3 refuses SEARCH and managed-run writes after successful deletion with a reason and without allocating new run or conversation content; first-message creation and every authored context/title control observe the same completed-deletion authority. A list identifies confirmed deleted ids independently of its recent window.",
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD3: Remember successful deletions in the existing process-wide ConversationWork authority and refuse stale writes before allocating records, using CONVERSATION_DELETED with a reason. The list carries these exact deletedIds because omission from its recent 20 rows cannot establish deletion. Both windows purge confirmed cached state and ignore earlier list/history/thread replies. Explicit backup imports alone can restore identities. No new saved state: markers are lost at Engine restart, when stale first-write ids are again indistinguishable from new ids.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd3-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LateConversationWriteHttpTest.java#lateSearchIsRefusedAndCannotRecreateRunOrThread",
        "modules/ui/src/test/java/io/justsearch/ui/api/LateConversationWriteHttpTest.java#refreshedListIdentifiesConfirmedDeletionsBeyondItsRecentWindow",
        "modules/ui/src/test/java/io/justsearch/ui/api/LateConversationWriteHttpTest.java#staleManagedStreamIsRefusedWithAReasonBeforeOpeningARun",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/LateConversationWriteTest.java#deletedIdentityCannotBeRecreatedByAFirstMessageOrAnyControl"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts"
      ],
      "class": "New",
      "behaviour": "N52.4: explicit backup restoration can reuse a deleted identity, show its restored row, extend its messages and retain skip-existing import behavior. These preservation checks pass on 5a71c91bf and the candidate.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DN4",
          "quote": "The encrypted backup exports exactly the data that remains; import behaves as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD3: Remember successful deletions in the existing process-wide ConversationWork authority and refuse stale writes before allocating records, using CONVERSATION_DELETED with a reason. The list carries these exact deletedIds because omission from its recent 20 rows cannot establish deletion. Both windows purge confirmed cached state and ignore earlier list/history/thread replies. Explicit backup imports alone can restore identities. No new saved state: markers are lost at Engine restart, when stale first-write ids are again indistinguishable from new ids.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd3-preservation-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#an authoritative list permits an intentionally restored backup identity"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationRestoreAfterDeleteTest.java"
      ],
      "class": "New",
      "behaviour": "N52.4: explicit backup restoration can reuse a deleted identity, show its restored row, extend its messages and retain skip-existing import behavior. These preservation checks pass on 5a71c91bf and the candidate.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "DN4",
          "quote": "The encrypted backup exports exactly the data that remains; import behaves as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "CD3: Remember successful deletions in the existing process-wide ConversationWork authority and refuse stale writes before allocating records, using CONVERSATION_DELETED with a reason. The list carries these exact deletedIds because omission from its recent 20 rows cannot establish deletion. Both windows purge confirmed cached state and ignore earlier list/history/thread replies. Explicit backup imports alone can restore identities. No new saved state: markers are lost at Engine restart, when stale first-write ids are again indistinguishable from new ids.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX work, area 5 user data control, WP5.2 conversation deletion",
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd3-preservation-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationRestoreAfterDeleteTest.java#explicitBackupImportCanRestoreAndExtendADeletedConversation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "CD4 F1: both windows forget missing held server conversations after explicit two-store absence, including when Engine restart loses markers. Server content counts even without a listed row.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
          "choice": "CD4: F1, CD3's \"learns only from a refreshed server list\" after an Engine restart (deletion markers lost): when a refreshed list lacks a conversation that the window had previously seen on the server (it had a server row or server content), the window asks the Engine about that one id with a read that tells \"does not exist\" apart from \"exists\" and from \"could not answer\". \"Does not exist\" removes it like a deletion (row, cached turns, open view, last-viewed pointer). \"Could not answer\" keeps it. A conversation that exists only locally (no first write yet) is never removed this way, and a conversation merely beyond the list's limit stays. Bounded to the conversations the window holds; no full-history scan. F3: every route that refuses a late write to a deleted conversation uses the same typed reason (CONVERSATION_DELETED), as a client error (an HTTP 4xx, or the same code in a stream's error event), never a 500 or a generic BAD_REQUEST/INVALID_STATE. F4: the deletion markers carried in the list are bounded (a fixed maximum, newest kept, or since a client cursor; choose the cheapest and say why). A window that missed more than the bound is covered by the F1 rule. Choose the newest 100 wire markers, keeping all process-lifetime write refusals internally. Reuse the history route with existsOnly=true for an exact two-store identity read, under the key lifecycle monitor in the production deletion owner. Track only identities already observed by the window, including server history and unified thread content.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd4-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#confirms missing held server rows individually after markers are lost",
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#server content without a listed row also qualifies for the missing-id read",
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#server thread content qualifies even when no history row was ever listed",
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#one unanswered identity read cannot hold back another confirmed deletion",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts#clears cached content after restart when the missing server row is confirmed absent",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#forgets cached turns after restart when the missing server row is confirmed absent"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts"
      ],
      "class": "New",
      "behaviour": "CD4 F1 preservation: local first-write identities are never probed or discarded; locked, unavailable, failed and malformed existence answers retain the held conversation; stale absence replies cannot override a newer restored list.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
          "choice": "CD4: F1, CD3's \"learns only from a refreshed server list\" after an Engine restart (deletion markers lost): when a refreshed list lacks a conversation that the window had previously seen on the server (it had a server row or server content), the window asks the Engine about that one id with a read that tells \"does not exist\" apart from \"exists\" and from \"could not answer\". \"Does not exist\" removes it like a deletion (row, cached turns, open view, last-viewed pointer). \"Could not answer\" keeps it. A conversation that exists only locally (no first write yet) is never removed this way, and a conversation merely beyond the list's limit stays. Bounded to the conversations the window holds; no full-history scan. F3: every route that refuses a late write to a deleted conversation uses the same typed reason (CONVERSATION_DELETED), as a client error (an HTTP 4xx, or the same code in a stream's error event), never a 500 or a generic BAD_REQUEST/INVALID_STATE. F4: the deletion markers carried in the list are bounded (a fixed maximum, newest kept, or since a client cursor; choose the cheapest and say why). A window that missed more than the bound is covered by the F1 rule. Choose the newest 100 wire markers, keeping all process-lifetime write refusals internally. Reuse the history route with existsOnly=true for an exact two-store identity read, under the key lifecycle monitor in the production deletion owner. Track only identities already observed by the window, including server history and unified thread content.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd4-preservation-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#keeps local first-write identities and inconclusive existence answers",
        "modules/ui-web/src/shell-v0/state/conversationDeletionRefresh.test.ts#an existence response superseded by a newer restored list cannot delete the restored row"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.deletion.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "CD4 F2: capture only the target thread GET and wait until it reaches fetch before replacing the fetch stub for DELETE. Inputs, fault injection, expected events and pointer assertions remain unchanged. The behavioral assertion fails on 5a71c91bf and passes after; gates/test-intent/evidence/uiux-conversation-delete-cd4-harness.json records this execution. All earlier per-file evidence was regenerated without changing its accepted checks.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
          "choice": "CD4: F1, CD3's \"learns only from a refreshed server list\" after an Engine restart (deletion markers lost): when a refreshed list lacks a conversation that the window had previously seen on the server (it had a server row or server content), the window asks the Engine about that one id with a read that tells \"does not exist\" apart from \"exists\" and from \"could not answer\". \"Does not exist\" removes it like a deletion (row, cached turns, open view, last-viewed pointer). \"Could not answer\" keeps it. A conversation that exists only locally (no first write yet) is never removed this way, and a conversation merely beyond the list's limit stays. Bounded to the conversations the window holds; no full-history scan. F3: every route that refuses a late write to a deleted conversation uses the same typed reason (CONVERSATION_DELETED), as a client error (an HTTP 4xx, or the same code in a stream's error event), never a 500 or a generic BAD_REQUEST/INVALID_STATE. F4: the deletion markers carried in the list are bounded (a fixed maximum, newest kept, or since a client cursor; choose the cheapest and say why). A window that missed more than the bound is covered by the F1 rule. Choose the newest 100 wire markers, keeping all process-lifetime write refusals internally. Reuse the history route with existsOnly=true for an exact two-store identity read, under the key lifecycle monitor in the production deletion owner. Track only identities already observed by the window, including server history and unified thread content.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteCd4HttpTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "CD4 F1/F3/F4: exact history identity reads distinguish empty, older and run-only conversations from absent ones, refusing locked reads; all late controls and agent streams use CONVERSATION_DELETED client errors; list markers retain only the newest 100 and older markers still refuse writes. HTTP fixtures use private stores and servers. The inaccessible-metadata check uses POSIX permissions and runs only on filesystems that support them; no Windows pass is claimed for that check.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
          "choice": "CD4: F1, CD3's \"learns only from a refreshed server list\" after an Engine restart (deletion markers lost): when a refreshed list lacks a conversation that the window had previously seen on the server (it had a server row or server content), the window asks the Engine about that one id with a read that tells \"does not exist\" apart from \"exists\" and from \"could not answer\". \"Does not exist\" removes it like a deletion (row, cached turns, open view, last-viewed pointer). \"Could not answer\" keeps it. A conversation that exists only locally (no first write yet) is never removed this way, and a conversation merely beyond the list's limit stays. Bounded to the conversations the window holds; no full-history scan. F3: every route that refuses a late write to a deleted conversation uses the same typed reason (CONVERSATION_DELETED), as a client error (an HTTP 4xx, or the same code in a stream's error event), never a 500 or a generic BAD_REQUEST/INVALID_STATE. F4: the deletion markers carried in the list are bounded (a fixed maximum, newest kept, or since a client cursor; choose the cheapest and say why). A window that missed more than the bound is covered by the F1 rule. Choose the newest 100 wire markers, keeping all process-lifetime write refusals internally. Reuse the history route with existsOnly=true for an exact two-store identity read, under the key lifecycle monitor in the production deletion owner. Track only identities already observed by the window, including server history and unified thread content.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd4-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteCd4HttpTest.java#everyLateControlWriteHasTheDeletedClientError",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteCd4HttpTest.java#lateAgentStreamHasTheDeletedReason",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteCd4HttpTest.java#deletionMarkersKeepOnlyTheNewestBoundedWireWindow",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteCd4HttpTest.java#missingIdentityReadDistinguishesAbsentFromEmptyAndOlderConversations",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteCd4HttpTest.java#missingIdentityReadRecognizesRunOnlyAndRefusesLockedStores",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationDeleteCd4HttpTest.java#inaccessibleMetadataIsNotReportedAsAnAbsentConversation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.stream-deletion.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts",
        "modules/ui-web/src/shell-v0/state/conversationServerIdentity.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "CD4 F1: real server content from modern streamed chunks or final answers, Search v3 streams, legacy agent streams and HTTP replay reads, and confirmed branch/title/history writes establishes server ownership before a list row arrives. A later exact absence clears the held identity; local exported replay never establishes Engine ownership.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
          "choice": "CD4: F1, CD3's \"learns only from a refreshed server list\" after an Engine restart (deletion markers lost): when a refreshed list lacks a conversation that the window had previously seen on the server (it had a server row or server content), the window asks the Engine about that one id with a read that tells \"does not exist\" apart from \"exists\" and from \"could not answer\". \"Does not exist\" removes it like a deletion (row, cached turns, open view, last-viewed pointer). \"Could not answer\" keeps it. A conversation that exists only locally (no first write yet) is never removed this way, and a conversation merely beyond the list's limit stays. Bounded to the conversations the window holds; no full-history scan. F3: every route that refuses a late write to a deleted conversation uses the same typed reason (CONVERSATION_DELETED), as a client error (an HTTP 4xx, or the same code in a stream's error event), never a 500 or a generic BAD_REQUEST/INVALID_STATE. F4: the deletion markers carried in the list are bounded (a fixed maximum, newest kept, or since a client cursor; choose the cheapest and say why). A window that missed more than the bound is covered by the F1 rule. Choose the newest 100 wire markers, keeping all process-lifetime write refusals internally. Reuse the history route with existsOnly=true for an exact two-store identity read, under the key lifecycle monitor in the production deletion owner. Track only identities already observed by the window, including server history and unified thread content.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd4-server-sources.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.stream-deletion.test.ts#server chunk content counts before any list or history read returns it",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.stream-deletion.test.ts#server done content counts before any list or history read returns it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts#a streamed answer counts as server content before any list or record read returns it",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts#legacy agent stream content confirms a server conversation before any record read",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts#a one-shot agent answer confirms server content without any chunk frames",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts#a server replay read confirms content without a conversation list row (pinned=true)",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts#a server replay read confirms content without a conversation list row (pinned=false)",
        "modules/ui-web/src/shell-v0/state/conversationServerIdentity.test.ts#a created branch is server-owned even before its first listed row or message",
        "modules/ui-web/src/shell-v0/state/conversationServerIdentity.test.ts#a successful title write confirms a server conversation before its first list reply",
        "modules/ui-web/src/shell-v0/state/conversationServerIdentity.test.ts#a message-id refresh counts as server content without a preceding list or resume"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts",
        "modules/ui-web/src/shell-v0/state/conversationServerIdentity.test.ts"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "CD4 F1 preservation: a bare modern or legacy run identity, a local exported replay, an empty history read, or an unaccepted pending title does not turn an unwritten local conversation into a server-owned identity.",
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
          "choice": "DC2: Refuse deletion while the encrypted store is locked.",
          "label": "agent choice"
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
          "choice": "CD4: F1, CD3's \"learns only from a refreshed server list\" after an Engine restart (deletion markers lost): when a refreshed list lacks a conversation that the window had previously seen on the server (it had a server row or server content), the window asks the Engine about that one id with a read that tells \"does not exist\" apart from \"exists\" and from \"could not answer\". \"Does not exist\" removes it like a deletion (row, cached turns, open view, last-viewed pointer). \"Could not answer\" keeps it. A conversation that exists only locally (no first write yet) is never removed this way, and a conversation merely beyond the list's limit stays. Bounded to the conversations the window holds; no full-history scan. F3: every route that refuses a late write to a deleted conversation uses the same typed reason (CONVERSATION_DELETED), as a client error (an HTTP 4xx, or the same code in a stream's error event), never a 500 or a generic BAD_REQUEST/INVALID_STATE. F4: the deletion markers carried in the list are bounded (a fixed maximum, newest kept, or since a client cursor; choose the cheapest and say why). A window that missed more than the bound is covered by the F1 rule. Choose the newest 100 wire markers, keeping all process-lifetime write refusals internally. Reuse the history route with existsOnly=true for an exact two-store identity read, under the key lifecycle monitor in the production deletion owner. Track only identities already observed by the window, including server history and unified thread content.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-conversation-delete-cd4-server-sources-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts#a run identity alone does not assert that a local first write has landed",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts#a legacy run identity alone does not assert that a local first write has landed",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.deletion.test.ts#an exported replay alone never asserts that a local conversation exists on the Engine",
        "modules/ui-web/src/shell-v0/state/conversationServerIdentity.test.ts#an empty history read does not assert that a local first write happened",
        "modules/ui-web/src/shell-v0/state/conversationServerIdentity.test.ts#a title still waiting for the first write never turns a local id into server ownership"
      ]
    }
  ]
}
```

All CD3 checks are durable. CD3 compatibility scaffolding adds only the old version's missing signatures (including a no-op completed notification); it adds no deletion repair. The test-intent evidence producer is unchanged. A temporary npx shim uses the installed Vitest entry point to repair the producer's dereferenced .bin copy; it changes neither test selection nor product code. Exact-head fail-before evidence and the writer sweep are in tmp/wp52-cd3/.

Legacy frontend evidence was regenerated against dependencies installed from its own 97439fc54 package lock; newer before refs use the matching candidate lock. Comparison copies were removed after use.

CD4 checks are durable. F2 changes only harness synchronization. Repaired CD4 checks use exact 4cab228fe before evidence, except F2 which uses 5a71c91bf. Existing accepted entries and acceptance records remain unchanged. The newest 100 markers bound the wire without expiring older process-lifetime write protection; exact missing-id reads cover windows that missed that bound or an Engine restart.

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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-nedof9ll",
  "verdict": "accept",
  "digest": "sha256:a2257255f55ae416996bd968608285b167454834867cb311dace5b366408c92e",
  "note": "Reviewed baseline tests, agreed scenarios and sources before the diff. Independently replayed 37 repair checks: all passed without skips and failed by assertion before. All 146 preservation cases passed on both versions. Test intent accepted; product verification fails S52.1/S52.2 for accepted queued background work recreating a deleted conversation and S52.3 for locked run-only listing."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-7ljkbx2z",
  "verdict": "reject",
  "digest": "sha256:41572e5297d5234ab458420310ffdf5bb43a937269a79354aea9ef36a58e6783",
  "note": "Sources and adaptation oracles reviewed against the supplied S52/N52 and DC/CD decisions. Reject current execution evidence: UnifiedChatView.deletion.test.ts, a thread read already in flight cannot restore deleted interaction events, repeatedly throws TypeError: release is not a function before its behavioral assertion on exact snapshot 4cab228fe. Reproduced in the full suite, isolated with no cache, and the independent run-evidence replay. The real-browser delayed-response behavior passes; that does not make the named check a pass."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-lx2kf4nn",
  "verdict": "accept",
  "digest": "sha256:1a86f3e5b1502f881f807a7a1916c9031f3d4c7b18783689fec420c8a7f8cf3f",
  "note": "CD4 entries (#22-#27) checked against the CD4 amendment and DS1; all 8 UI and 8 Java evidence files replayed on 08ebd793f: every cited new check executes and passes, every cited repaired check fails by assertion on its before revision (ConversationDeleteWorkTest#refusesDeleteThroughoutShapeRunIncludingTerminalDelivery confirmed alone, since its module-mate ConversationDeletionTest changed after uiux-conversation-delete-java.json was recorded and no longer compiles at 97439fc54; those 7 checks are uncited there and covered by repair-java). Exempt CD4 files isolated (globals unstubbed, permissions restored). CD4 F1-F4 and guards verified live, fail on c1cd13378."
}
```
