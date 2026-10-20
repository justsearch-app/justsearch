---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-eplotgqg
---

# Test-intent entries

Gate-compatible aliases preserve the brief's scenario ids: PS1–PS3 mean S9.1–S9.3;
PN1–PN3 mean N9.1–N9.3. PS1b and PS1w name the batch-id and Windows amendments;
PS1l names the adopted invalid-folder listing amendment.
The gate schema accepts letters/digits, without dots or hyphens; all quotes remain verbatim.

Review history: verifier-1 rejected the removed blank-id no-op expectation. AgentRunStoreTest
is now byte-identical to 117e89be9, and appendSearchEvent again returns null without filesystem
access for an absent conversation. Its old New/repaired entry is removed; no existing expectation
is retired, so no Obsolete warrant is needed. The new HTTP refusal test omits optional blank body
ids, whose original behavior is restored and covered by N9.3 checks. Windows names and batch ids
are repaired against b359747af in separate evidence. Earlier regression and preservation checks
are rerun with the final test files. A later regression covers Unicode line separators
in reserved-device extensions; the shared pattern uses DOTALL so its extension match is
complete. The rejection record below is retained unchanged.

Review history: verifier-2 rejected two repaired classifications that already pass on
117e89be9. They are now preservation checks with evidence without --before; the search-event
entry cites PS1 only. PN3 describes normalization, including the old dispatch discrepancy,
without claiming identical historical storage behavior. The Unicode-extension entry records
its base compile limitation. Uncited repair/preserve evidence was removed. New regressions
cover generic undo before confirmation, invalid legacy-folder listings and additional Windows
aliases, with assertion fail-before evidence against bcbbb6e5c. Acceptance records remain unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Refuse unsafe ids before filesystem access or HTTP dispatch; retain external sentinels.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1",
          "quote": "Every store that maps a conversation, session or run id to a folder or file (at least FileConversationStore, AgentRunStore, RunEventStore; find any other) refuses an id that is not a single plain name: empty or blank, \".\" or \"..\", containing a path separator (/ or \\) or a drive or stream colon or a control character, absolute, or resolving outside its root after normalisation. The refusal happens before any filesystem access, for every operation that takes the id (read, write, append, list by id, branch, fork, delete).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS3",
          "quote": "A sentinel file and folder next to and above the store root survive every attempt (delete, read, write, branch, fork, resume) through every entry point.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-rework-original.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java#refusesUnsafeIdsBeforeAnyOperationAndPreservesSentinels",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java#recursiveDeleteCannotRemoveSentinelsBesideAndAboveRoot",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java#validatesBranchDestinationBeforeReadingParent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/agenthistory/AgentHistorySafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Refuse unsafe ids before filesystem access or HTTP dispatch; retain external sentinels.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1",
          "quote": "Every store that maps a conversation, session or run id to a folder or file (at least FileConversationStore, AgentRunStore, RunEventStore; find any other) refuses an id that is not a single plain name: empty or blank, \".\" or \"..\", containing a path separator (/ or \\) or a drive or stream colon or a control character, absolute, or resolving outside its root after normalisation. The refusal happens before any filesystem access, for every operation that takes the id (read, write, append, list by id, branch, fork, delete).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS3",
          "quote": "A sentinel file and folder next to and above the store root survive every attempt (delete, read, write, branch, fork, resume) through every entry point.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-rework-original.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/agenthistory/AgentHistorySafeIdsTest.java#refusesUnsafeRunIdsBeforeSchedulingOrReadingTranscripts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Refuse unsafe ids before filesystem access or HTTP dispatch; retain external sentinels.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1",
          "quote": "Every store that maps a conversation, session or run id to a folder or file (at least FileConversationStore, AgentRunStore, RunEventStore; find any other) refuses an id that is not a single plain name: empty or blank, \".\" or \"..\", containing a path separator (/ or \\) or a drive or stream colon or a control character, absolute, or resolving outside its root after normalisation. The refusal happens before any filesystem access, for every operation that takes the id (read, write, append, list by id, branch, fork, delete).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS3",
          "quote": "A sentinel file and folder next to and above the store root survive every attempt (delete, read, write, branch, fork, resume) through every entry point.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-rework-original.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java#refusesUnsafeIdsAcrossRunAndEventOperationsBeforeFilesystemAccess"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Refuse unsafe ids before filesystem access or HTTP dispatch; retain external sentinels.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS2",
          "quote": "Every HTTP route that takes such an id answers an unsafe id with a client error (4xx) and touches no store, including percent-encoded and mixed forms (%2F, %5C, %2E%2E, ..%5C.., %00), under both Windows and Linux path rules.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS3",
          "quote": "A sentinel file and folder next to and above the store root survive every attempt (delete, read, write, branch, fork, resume) through every entry point.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-rework-original.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java#refusesUnsafeBodyAndQueryIdsBeforeStartingWork",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java#realStoreSentinelsSurviveEncodedReadWriteBranchAndDelete"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve valid id formats, legacy saved bytes and synchronous successful HTTP response shapes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN1",
          "quote": "Every id format the app creates or has created (UUIDs, the throwaway title-session prefix, branch ids, run ids, ids in saved data from earlier versions) keeps working for every operation; saved data from before the change loads unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-rework-preserve.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java#preservesCreatedAndLegacyIdsWithoutRewritingSavedData"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve valid id formats, legacy saved bytes and synchronous successful HTTP response shapes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN1",
          "quote": "Every id format the app creates or has created (UUIDs, the throwaway title-session prefix, branch ids, run ids, ids in saved data from earlier versions) keeps working for every operation; saved data from before the change loads unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-rework-preserve.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java#preservesRunIdFormatsAndConversationJoins"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve valid id formats, legacy saved bytes and synchronous successful HTTP response shapes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN1",
          "quote": "Every id format the app creates or has created (UUIDs, the throwaway title-session prefix, branch ids, run ids, ids in saved data from earlier versions) keeps working for every operation; saved data from before the change loads unchanged.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN2",
          "quote": "Responses for valid ids are unchanged (status, body, timing class).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-rework-preserve.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java#validDeleteAndReadKeepTheirResponses"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Reject unsafe batch ids before journal reads, writes and updates, preserving external journals.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1",
          "quote": "Every store that maps a conversation, session or run id to a folder or file (at least FileConversationStore, AgentRunStore, RunEventStore; find any other) refuses an id that is not a single plain name: empty or blank, \".\" or \"..\", containing a path separator (/ or \\) or a drive or stream colon or a control character, absolute, or resolving outside its root after normalisation. The refusal happens before any filesystem access, for every operation that takes the id (read, write, append, list by id, branch, fork, delete).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1b",
          "quote": "S9.1 covers every id from a request that becomes a path, including batch/file-history ids on agent routes (the file-history route still read any .json by a traversal id; undo uses the same lookup).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1w",
          "quote": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS3",
          "quote": "A sentinel file and folder next to and above the store root survive every attempt (delete, read, write, branch, fork, resume) through every entry point.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-amendments.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogSafeIdsTest.java#refusesTraversalBeforeReadingOrUpdatingExternalJournal",
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogSafeIdsTest.java#refusesWindowsAliasesBeforeJournalAccess"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Reject Windows device names and trailing dot/space aliases before store access.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1w",
          "quote": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-amendments.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java#refusesWindowsAliasesBeforeRunOrEventAccess",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreSafeIdsTest.java#refusesWindowsAliasesBeforeConversationAccess"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Refuse unsafe history/undo ids and Windows aliases on HTTP before querying stores.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1b",
          "quote": "S9.1 covers every id from a request that becomes a path, including batch/file-history ids on agent routes (the file-history route still read any .json by a traversal id; undo uses the same lookup).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1w",
          "quote": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS2",
          "quote": "Every HTTP route that takes such an id answers an unsafe id with a client error (4xx) and touches no store, including percent-encoded and mixed forms (%2F, %5C, %2E%2E, ..%5C.., %00), under both Windows and Linux path rules.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS3",
          "quote": "A sentinel file and folder next to and above the store root survive every attempt (delete, read, write, branch, fork, resume) through every entry point.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-amendments.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java#historyRefusesExternalBatchIdsBeforeQuerying",
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java#undoRefusesUnsafeExecutionIdsBeforeQuerying",
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java#runtimeInstanceIdsRefuseWindowsDevicesBeforeResolvingTheRoot",
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java#rejectsEncodedIdsOnEveryAddressedRouteWithoutTouchingStores"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Normalize optional blank body ids to absence before dispatch and approval; pin that behavior even where the old dispatch path used blank sessionId as a store key.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN3",
          "quote": "An optional id field in a request body that is empty or blank is treated as absent, exactly as before (not refused); stores still refuse a blank id.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-amendments.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java#optionalBlankBodyIdsDispatchAsAbsent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Retain appendSearchEvent's original no-op for an absent conversation, before filesystem access.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1",
          "quote": "Every store that maps a conversation, session or run id to a folder or file (at least FileConversationStore, AgentRunStore, RunEventStore; find any other) refuses an id that is not a single plain name: empty or blank, \".\" or \"..\", containing a path separator (/ or \\) or a drive or stream colon or a control character, absolute, or resolving outside its root after normalisation. The refusal happens before any filesystem access, for every operation that takes the id (read, write, append, list by id, branch, fork, delete).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-review-preserve.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java#appendSearchEventWithoutConversationCreatesNoFiles"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogSafeIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve legacy batch bytes, valid names and normal journal updates.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN1",
          "quote": "Every id format the app creates or has created (UUIDs, the throwaway title-session prefix, branch ids, run ids, ids in saved data from earlier versions) keeps working for every operation; saved data from before the change loads unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-amendments-preserve.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogSafeIdsTest.java#legacyAndCreatedBatchIdsKeepTheirJournalBytes"
      ]
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/StoreIdsUnicodeExtensionsTest.java"
      ],
      "class": "New",
      "behaviour": "Refuse Windows reserved devices with Unicode line separators in extensions. This test cannot compile on base 117e89be9 because StoreIds was introduced in this branch; its assertion fail-before uses b359747af.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1w",
          "quote": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-unicode-extensions.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/StoreIdsUnicodeExtensionsTest.java#refusesDeviceNamesWithUnicodeLineSeparatorExtensions"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve the HTTP status and body for optional blank conversationId compared with an absent field.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN3",
          "quote": "An optional id field in a request body that is empty or blank is treated as absent, exactly as before (not refused); stores still refuse a blank id.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-review-preserve.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdAmendmentsHttpTest.java#blankConversationIdRetainsLegacyHttpResponse"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdReviewHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Reject unsafe execution ids before the generic undo dispatcher can request approval or touch a store, with or without a supplied confirmation token.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS2",
          "quote": "Every HTTP route that takes such an id answers an unsafe id with a client error (4xx) and touches no store, including percent-encoded and mixed forms (%2F, %5C, %2E%2E, ..%5C.., %00), under both Windows and Linux path rules.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-review-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdReviewHttpTest.java#genericUndoRefusesUnsafeIdsBeforeApprovalOrDispatchEvenWithConfirmation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/StoreIdsWindowsAliasesTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationInvalidFoldersTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdReviewHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Refuse superscript Windows device names, console devices, and reserved device names with spaces before an extension through the shared resolver, conversation store and decoded HTTP route.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1w",
          "quote": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS2",
          "quote": "Every HTTP route that takes such an id answers an unsafe id with a client error (4xx) and touches no store, including percent-encoded and mixed forms (%2F, %5C, %2E%2E, ..%5C.., %00), under both Windows and Linux path rules.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-review-repair.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/StoreIdsWindowsAliasesTest.java#refusesAdditionalWindowsDevicesBeforeResolving",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationInvalidFoldersTest.java#refusesAdditionalWindowsDevicesAtConversationStore",
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreIdReviewHttpTest.java#historyRefusesAdditionalWindowsDevicesBeforeStoreQuery"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationInvalidFoldersTest.java"
      ],
      "class": "New",
      "behaviour": "Omit invalid legacy conversation folders before metadata reads, preserve their exact file bytes, keep valid rows and list filters, and warn once per skipped folder across repeated listings.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1l",
          "quote": "The conversation list leaves out folders whose names are not valid ids (they cannot be addressed safely); their files stay on disk untouched (a data reset still removes them). Log one line per skipped folder at most once per start.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1",
          "quote": "Every store that maps a conversation, session or run id to a folder or file (at least FileConversationStore, AgentRunStore, RunEventStore; find any other) refuses an id that is not a single plain name: empty or blank, \".\" or \"..\", containing a path separator (/ or \\) or a drive or stream colon or a control character, absolute, or resolving outside its root after normalisation. The refusal happens before any filesystem access, for every operation that takes the id (read, write, append, list by id, branch, fork, delete).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-review-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationInvalidFoldersTest.java#omitsInvalidLegacyFoldersWithoutReadingOrChangingTheirFilesAndWarnsOnce"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/StoreIdsWindowsAliasesTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve ordinary names that resemble Windows reserved names.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PN1",
          "quote": "Every id format the app creates or has created (UUIDs, the throwaway title-session prefix, branch ids, run ids, ids in saved data from earlier versions) keeps working for every operation; saved data from before the change loads unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-review-preserve.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/StoreIdsWindowsAliasesTest.java#preservesOrdinaryNamesNearWindowsDevices"
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
  "session": "agent-session-cioh0sjw",
  "verdict": "reject",
  "digest": "sha256:4363abd91f9de9515925a0922f5d10beb19a28f9380c310c106aec2a03c314fa",
  "note": "AgentRunStoreTest.java is classed New/repaired, but the change removes an existing expectation (appendSearchEvent returns null for a blank or null conversationId) and replaces it with a throw. That removal needs an Obsolete entry with a conflict search and a source that changes this behaviour. PS1 does not: the old null return already refused a blank id before any filesystem access, so PS1 neither makes the old behaviour a defect nor requires an exception. Either keep the old expectation, or cite a source (owner words) that a blank id must raise. The other entries hold: their expectations follow from PS1-PS3/PN1-PN2, and the fail-before evidence is an assertion failure on 117e89be9 for each repaired check."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-3e60obt8",
  "verdict": "reject",
  "digest": "sha256:15e550765692d88cb934dc4996298d75c7c0c3c536011e7f6405f6abb68605d9",
  "note": "Re-ran run-evidence with every repaired check against the real base 117e89be9 (scratch: evidence/run-evidence/repaired-vs-117e89be9.json). 16 of 19 fail there by assertion and pass on d0de611d2; those entries hold (expectations follow PS1-PS3, PS1b, PS1w, PN1-PN3). Omissions: (1) RunStoresSafeIdsTest#appendSearchEventWithoutConversationCreatesNoFiles is entered as repaired:true, but it passes on 117e89be9 (base already returns null for a blank id without touching disk); its only fail-before is against b359747af, an intermediate commit of this same change, so it is a preservation check and needs an entry without repaired and evidence without --before. Its source PA4 quotes the orchestrator's brief to the builder, labelled owner-adopted-scenario; a brief is never a citation, so only PS1 can support it. (2) StoreIdAmendmentsHttpTest#blankConversationIdRetainsLegacyHttpResponse is also repaired:true but passes on 117e89be9; same fix (preservation, PN3). Notes, not omissions: StoreIdsUnicodeExtensionsTest cannot compile on the base (StoreIds is new); its fail-before against b359747af is acceptable but the entry should say so. Uncited stale evidence files uiux-safe-ids-repair.json and uiux-safe-ids-preserve.json remain; repair.json names the removed check AgentRunStoreTest#appendSearchEventRefusesBlankConversationId. PN3's 'exactly as before' does not hold literally: on 117e89be9 a blank sessionId on /api/chat/dispatch was used as a store key (wrote messages.jsonl/meta.json into the conversations root, created folders ' ' and tab); the N9.3 check's fail-before is that difference."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-ococis6g",
  "verdict": "reject",
  "digest": "sha256:8768393db6b5f38d0012bf5509db3aa0cd78e716f391866066ea32c2ccb194a1",
  "note": "Re-ran run-evidence on 6a7dffcb6 to scratch (evidence/run-evidence/repaired-vs-117e89be9.json): all 20 repaired checks outside the StoreIds unit tests fail on the real base 117e89be9 by assertion and pass on the candidate, including the new StoreIdReviewHttpTest and ConversationInvalidFoldersTest checks whose committed fail-before is against bcbbb6e5c; the 9 others pass on the candidate (preserve-and-storeids.json). The two earlier omissions are fixed (both checks now preservation entries, PS1 only for the search-event check, Unicode compile limit stated). Removing uiux-safe-ids-repair.json and uiux-safe-ids-preserve.json is honest: nothing cited them since b359747af and the history keeps them. Omissions: (1) Entry for ConversationInvalidFoldersTest#omitsInvalidLegacyFoldersWithoutReadingOrChangingTheirFilesAndWarnsOnce cites PS1l as an owner-adopted scenario, but PS1l is not among the agreed scenarios and amendments given to this acceptor (S9.1-S9.3, N9.1-N9.3 and the amendments after verifier-1); I cannot confirm the owner adopted it, and hiding saved conversations from the list bears on N9.1 (saved data from before the change loads unchanged). The orchestrator must confirm it with its location, or the entry needs another source. (2) StoreIdsWindowsAliasesTest#preservesOrdinaryNamesNearWindowsDevices cites PN1, which covers id formats the app creates or has created; COM0, LPT0, COM10, NULled.txt, a .txt and the other names are not app-made ids, so PN1 does not state these expectations. Cite PS1w, whose list is COM1-9/LPT1-9, or drop names no source covers. Note: entry for StoreIdsWindowsAliasesTest#refusesAdditionalWindowsDevicesBeforeResolving does not say it cannot compile on the base; its bcbbb6e5c fail-before is acceptable for that reason. Separately, verification found N9.1 failing (a valid conversation cannot be deleted once a blank-named folder from the before build is in the store), so the tests will change anyway."
}
```
