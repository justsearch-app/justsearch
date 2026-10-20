---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-e15wjfsg
---

# Test-intent entries

Gate-compatible aliases preserve the brief's scenario ids: PS1–PS3 mean S9.1–S9.3;
PN1–PN3 mean N9.1–N9.3. PS1b and PS1w name the batch-id and Windows amendments;
PN1e names the adopted amendment after verifier-3 for every store enumeration.
PS1x names the additional Windows-device amendment after verifier-2.
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

Review history: verifier-3 rejected the listing amendment citation and the ordinary Windows-name
citation, and reproduced valid deletion failing beside blank legacy folders. PN1e now quotes the
agreed amendment after verifier-3 verbatim; the ordinary Windows-name entry cites the explicit
reserved-name lists, including the later extension. New repaired checks cover all run scans,
conversation child checks through HTTP, file-history enumeration and constructor retention, with
assertion fail-before evidence against e7b4ceec7 in a separate evidence file. The previous
reclassifications remain preservation checks. Existing acceptance and rejection records remain
unchanged and require independent review of this candidate.

Review history: verifier-4 found invalid ids in saved branch parents, run/conversation links and backups still reaching request validation. PN1s quotes the agreed N9.1 amendment after verifier-4 (2026-10-20 07:00) verbatim. New HTTP checks cover those paths; additional repaired checks cover saved last-session pointers, session indexes and the restore notice. Invalid saved run references are filtered at the session-index producer before history reconciliation; its direct-id refusal remains unchanged. Evidence is against bf1713ab2. Existing review records remain unchanged.

Review history: verifier-5 found that the invalid saved-parent HTTP check already passes on the real base 117e89be9. It is now a preservation entry, retaining its expectation and PN1s source, with candidate-only evidence generated without --before. Existing acceptance and rejection records remain unchanged.

Review history: the independent audit rejected the batch-name preservation citation. That entry now also cites S9.1’s single-plain-name rule and the Windows reserved-name bound. Every cited orchestrator amendment after verifier-1 through verifier-4 is now a delegated choice quoting the user’s delegation; the restore notice’s exact wording has its own delegated choice alongside the saved-data scenario. Existing review records and execution evidence remain unchanged.

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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 covers every id from a request that becomes a path, including batch/file-history ids on agent routes (the file-history route still read any .json by a traversal id; undo uses the same lookup).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 covers every id from a request that becomes a path, including batch/file-history ids on agent routes (the file-history route still read any .json by a traversal id; undo uses the same lookup).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.3 An optional id field in a request body that is empty or blank is treated as absent, exactly as before (not refused); stores still refuse a blank id.",
          "label": "agent choice"
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
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "PS1",
          "quote": "Every store that maps a conversation, session or run id to a folder or file (at least FileConversationStore, AgentRunStore, RunEventStore; find any other) refuses an id that is not a single plain name: empty or blank, \".\" or \"..\", containing a path separator (/ or \\) or a drive or stream colon or a control character, absolute, or resolving outside its root after normalisation. The refusal happens before any filesystem access, for every operation that takes the id (read, write, append, list by id, branch, fork, delete).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.3 An optional id field in a request body that is empty or blank is treated as absent, exactly as before (not refused); stores still refuse a blank id.",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1: a folder or file with a name that is not a valid id inside any store (blank-named conversation folders made by the old dispatch bug, or any other invalid name found on disk) is skipped by every operation that enumerates the store (conversation and session lists, branch and child checks, presence, thread views, run lists); it never makes an operation on a valid id fail, and its files stay on disk.",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse ids that Windows would alter or treat specially: trailing dot or space (e.g. \"...\", \"a.\"), and reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9, with or without extension, any case).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "S9.1 Windows names: also refuse superscript device names (COM¹-³, LPT¹-³), CONIN$, CONOUT$, and reserved names followed by spaces before an extension (\"NUL .txt\").",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-review-preserve.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/StoreIdsWindowsAliasesTest.java#preservesOrdinaryNamesNearWindowsDevices"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoreEnumerationInvalidIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Skip invalid run folders before reads in every run projection and scoped deletion, including startup retention; leave invalid files unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1: a folder or file with a name that is not a valid id inside any store (blank-named conversation folders made by the old dispatch bug, or any other invalid name found on disk) is skipped by every operation that enumerates the store (conversation and session lists, branch and child checks, presence, thread views, run lists); it never makes an operation on a valid id fail, and its files stay on disk.",
          "label": "agent choice"
        },
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-enumeration-repair.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoreEnumerationInvalidIdsTest.java#everyRunProjectionAndDeletionSkipsInvalidFolders",
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoreEnumerationInvalidIdsTest.java#startupRetentionLeavesInvalidRunFoldersUntouched"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogEnumerationInvalidIdsTest.java"
      ],
      "class": "New",
      "behaviour": "Skip journals with invalid batch ids before reads and limits, including startup retention; leave invalid files unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1: a folder or file with a name that is not a valid id inside any store (blank-named conversation folders made by the old dispatch bug, or any other invalid name found on disk) is skipped by every operation that enumerates the store (conversation and session lists, branch and child checks, presence, thread views, run lists); it never makes an operation on a valid id fail, and its files stay on disk.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-enumeration-repair.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogEnumerationInvalidIdsTest.java#listSkipsInvalidBatchNamesBeforeReadingOrApplyingLimit",
        "modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationLogEnumerationInvalidIdsTest.java#startupRetentionLeavesInvalidBatchFilesUntouched"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreEnumerationInvalidIdsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Through running HTTP handlers backed by real stores, retain valid conversation lists, branching, inherited history and child protection, deletion, sessions, presence and thread views beside invalid folders; preserve invalid files.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1: a folder or file with a name that is not a valid id inside any store (blank-named conversation folders made by the old dispatch bug, or any other invalid name found on disk) is skipped by every operation that enumerates the store (conversation and session lists, branch and child checks, presence, thread views, run lists); it never makes an operation on a valid id fail, and its files stay on disk.",
          "label": "agent choice"
        },
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-enumeration-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreEnumerationInvalidIdsHttpTest.java#validConversationCanBranchReadAndDeleteBesideInvalidFolders",
        "modules/ui/src/test/java/io/justsearch/ui/api/StoreEnumerationInvalidIdsHttpTest.java#runListsPresenceThreadAndDeleteWorkBesideInvalidRunFolders"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SavedStoreIdsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Over HTTP, valid branches with invalid saved parents retain their own history and thread events.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1 (after verifier-4, 2026-10-20 07:00; orchestrator decision under the user's delegation) An invalid id read from saved data (a branch's parent link, a run's conversation id, an entry in a backup) is treated as missing, never as a request error: the item that names it keeps working exactly as when that id does not exist (a branch with an invalid parent opens as when its parent is gone; the conversation list ignores a run with an invalid conversation id), and restoring a backup restores every valid conversation and skips the invalid ones, saying how many were skipped. Ids from requests are still refused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-saved-parent-preserve.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SavedStoreIdsHttpTest.java#branchWithInvalidSavedParentOpensLikeMissingParentOverHttp"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SavedStoreIdsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Over HTTP, valid conversation lists ignore runs naming invalid conversations while keeping those runs, and v1 backups restore valid conversations and report skipped invalid entries.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1 (after verifier-4, 2026-10-20 07:00; orchestrator decision under the user's delegation) An invalid id read from saved data (a branch's parent link, a run's conversation id, an entry in a backup) is treated as missing, never as a request error: the item that names it keeps working exactly as when that id does not exist (a branch with an invalid parent opens as when its parent is gone; the conversation list ignores a run with an invalid conversation id), and restoring a backup restores every valid conversation and skips the invalid ones, saying how many were skipped. Ids from requests are still refused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-saved-data.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/SavedStoreIdsHttpTest.java#conversationListIgnoresSavedRunsWithInvalidConversationIdsOverHttp",
        "modules/ui/src/test/java/io/justsearch/ui/api/SavedStoreIdsHttpTest.java#legacyFormatBackupRestoresValidConversationsAndReportsInvalidEntriesOverHttp"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/SavedRunIdsTest.java"
      ],
      "class": "New",
      "behaviour": "An invalid saved last-session pointer reads as missing, without trimming it into another valid id or changing its bytes; session and presence indexes skip invalid saved session references and retain valid runs.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1 (after verifier-4, 2026-10-20 07:00; orchestrator decision under the user's delegation) An invalid id read from saved data (a branch's parent link, a run's conversation id, an entry in a backup) is treated as missing, never as a request error: the item that names it keeps working exactly as when that id does not exist (a branch with an invalid parent opens as when its parent is gone; the conversation list ignores a run with an invalid conversation id), and restoring a backup restores every valid conversation and skips the invalid ones, saying how many were skipped. Ids from requests are still refused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-saved-data.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/SavedRunIdsTest.java#invalidLastSessionPointerReadsAsMissingWithoutChangingSavedBytes",
        "modules/app-agent/src/test/java/io/justsearch/agent/SavedRunIdsTest.java#sessionIndexesSkipInvalidSavedSessionReferences"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SecuritySurface.restore-skipped.test.ts"
      ],
      "class": "New",
      "behaviour": "The existing restore notice states both restored and skipped counts, handles singular skipped entries, and does not claim invalid entries already exist when all entries are skipped.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "N9.1 (after verifier-4, 2026-10-20 07:00; orchestrator decision under the user's delegation) An invalid id read from saved data (a branch's parent link, a run's conversation id, an entry in a backup) is treated as missing, never as a request error: the item that names it keeps working exactly as when that id does not exist (a branch with an invalid parent opens as when its parent is gone; the conversation list ignores a run with an invalid conversation id), and restoring a backup restores every valid conversation and skips the invalid ones, saying how many were skipped. Ids from requests are still refused.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "designs and agreed scenarios of the UI/UX work, areas 1-5",
          "choice": "The restore notice uses \"Restored 2 conversations.\" and \"Skipped 5 entries with invalid ids.\" when two conversations are restored and five entries are skipped. When no conversations are restored and one entry is skipped, it uses \"Nothing new to restore. Skipped 1 entry with an invalid id.\".",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-saved-data.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SecuritySurface.restore-skipped.test.ts#reports restored and skipped counts when saved backup entries have invalid ids",
        "modules/ui-web/src/shell-v0/views/SecuritySurface.restore-skipped.test.ts#does not say everything already exists when all backup entries have invalid ids"
      ],
      "repaired": true
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-c2ak6c7g",
  "verdict": "reject",
  "digest": "sha256:ba362094003666d9ab3bd2c4b5026b083ec429587aeb1ec4fcc212412ff00c9a",
  "note": "Re-ran run-evidence on 78837eb65 to scratch (verifier-4 evidence/run-evidence/): the 6 enumeration checks fail by assertion on e7b4ceec7, the 20 S9 checks on 117e89be9 and the 2 StoreIds unit checks on b359747af, and all pass on the candidate; the 7 preservation checks pass. Every cited quote is verbatim in the agreed text; classes New and New/repaired fit. Reject for omissions: the PN1/PN1e entries claim valid ids keep working beside invalid saved names, but no check covers three paths where they do not, each reproduced live or over HTTP against 117e89be9: (1) restoring an encrypted backup exported by the before build that holds the blank-named conversations of the old dispatch bug answers 400 and restores none of the valid conversations (before: all restored; ConversationApiAssembly import sink -> appendMessage -> StoreIds); (2) a valid branch whose saved parentSessionId is invalid (\" \" from the old bug, or \"a.\", \"CON\") cannot be opened: history 400, thread 500 (before: 200; ChatController.handleLoadHistory getSessionMeta(parent), FileConversationStore.loadHistory parent walk); (3) with the agent service present, one saved run whose conversationId is \"a.\", \"CON\" or \"x:y\" makes GET /api/chat/conversations answer 500 (before: 200; ChatController.hasStoreSession -> getSessionMeta). Each needs a fix and a repaired check with assertion fail-before."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-kzvxqr09",
  "verdict": "reject",
  "digest": "sha256:abfb00771caf84cf9dce9591d5feb0848d3e7c093be63cd9022b6909fbc56c21",
  "note": "Re-ran run-evidence on f498321a9 to scratch for the 7 saved-data checks (verifier-5 evidence/run-evidence/): vs bf1713ab2 all 7 fail by assertion and pass on the candidate; vs the real base 117e89be9, 6 fail by assertion. The PN1s quote is verbatim in the agreed text and supports each expectation; class New fits. Verifier-4's three N9.1 failures are fixed live (backup restore, invalid-parent branches) and in a scratch HTTP check (run join). Omission: SavedStoreIdsHttpTest#branchWithInvalidSavedParentOpensLikeMissingParentOverHttp is entered repaired:true, but it passes on 117e89be9: its fixture never saves the parent conversation, so the base already treats the parent as missing. Its only fail-before is against bf1713ab2, an intermediate commit of this change, which the second rejection on this changeset ruled out (preservation check, needs an entry without repaired and evidence without --before). Fix: either split it into a preservation entry, or save the parent under the invalid name as the before build does (live on 117e89be9 the branch then shows the parent turns), so it fails on 117e89be9 by assertion."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-mgpgjl4p",
  "verdict": "accept",
  "digest": "sha256:1548ae3af9e0ca83c6372e46276061887749b21984376edff7c6f26aa1ac6bf4",
  "note": "Judged e97f404cb against the fifth rejection (08da0572d). git diff 08da0572d..e97f404cb touches only this changeset and the new evidence file uiux-safe-ids-saved-parent-preserve.json; no test or product file changed. SavedStoreIdsHttpTest#branchWithInvalidSavedParentOpensLikeMissingParentOverHttp is now its own New entry without repaired, citing the same verbatim PN1s quote, with its expectation (branches with invalid saved parents keep their own history and thread events) kept and the behaviour text split accurately; the two other SavedStoreIdsHttpTest checks stay repaired with the unchanged uiux-safe-ids-saved-data.json (assertion fail-before). Re-ran run-evidence for the check to scratch (verifier evidence/run-evidence/saved-parent-preserve-rerun.json): same command, content digest sha256:c5605cd3..., both cases passed, failBefore null, matching the committed file except revision/tree. On a detached 117e89be9 with only the test file added it passes (2 tests, 0 skipped, 0 failures), so it is a valid preservation check. The rejection's point is resolved. Note: saved-data.json still lists this check with its bf1713ab2 fail-before, but no entry relies on that for it."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "agent-session-_d6djxnl",
  "verdict": "reject",
  "digest": "sha256:1548ae3af9e0ca83c6372e46276061887749b21984376edff7c6f26aa1ac6bf4",
  "note": "Audit of bfbea0def (merge of e97f404cb with main f003a5c64). Provisional class before reading entries: New for all 11 flagged files (all added; base has unchecked rootDir.resolve(id) in every store). Checked: all 11 cited quotes are verbatim in the agreed wp5.0 text; every entry's evidence file lists its checks as executed and passed, and each repaired check has an assertion fail-before. Ran the candidate test files on a detached f003a5c64 (the real base): every compilable repaired check fails there by assertion (incl. those whose committed fail-before uses b359747af, bcbbb6e5c, e7b4ceec7, bf1713ab2), all 7 Java preservation checks pass; the StoreIds unit tests cannot compile there, as stated. Re-ran run-evidence to scratch: all 40 Java checks pass on the merged head 5e9de3249 with content digests equal to the committed evidence; 3 repaired checks (realStoreSentinels..., optionalBlankBodyIdsDispatchAsAbsent, recursiveDelete...) with --before f003a5c64 fail by assertion and pass after. Omission (reject): entry for FileOperationLogSafeIdsTest#legacyAndCreatedBatchIdsKeepTheirJournalBytes cites only PN1, but batch ids have only ever been server-made UUIDs (FileOperationExecutor.java:198 since the first release; no backup imports journals), so PN1 does not state that the batch names b-1, batch_1, '旧履歴 1', COM10 and connection keep working. This is the same gap the third review found and fixed for StoreIdsWindowsAliasesTest#preservesOrdinaryNamesNearWindowsDevices. Fix: cite the source that does state them (PS1's single-plain-name rule with PS1w's COM1-9/LPT1-9 bound), or limit the check to app-made ids. Notes, not omissions: (1) RunStoresSafeIdsTest#appendSearchEventWithoutConversationCreatesNoFiles: its null-for-blank expectation rests on the unchanged AgentRunStoreTest#appendSearchEventReturnsNullForBlankConversationId, not on PS1 (whose text refuses blank ids); acceptable only as preservation of that existing expectation. (2) The PN1e/PN1s amendments are orchestrator decisions under the user's delegation; a delegated-choice citation quoting the delegation would be more exact than owner-adopted-scenario. (3) SecuritySurface.restore-skipped pins exact wording; PN1s supports only that the count is stated."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-arklqrc8",
  "verdict": "reject",
  "digest": "sha256:3356024f0c3a9f3b79e36a50e57ad99aed0d8d7afcbaade64f9f7b716c7a6172",
  "note": "Acceptance of cd8af635e (tree equal to 321e9d61a). Diff 12460668f..cd8af635e changes only sources and adds one Review history line: classes, items, newChecks, repaired flags, evidence and behaviour of all 26 entries are byte-identical (evidence/compare-sources.out). Every quote and choice is verbatim in the agreed wp5.0 text; every source quoting an orchestrator amendment (after verifier-1 to verifier-4) is now delegated-choice/agent choice and every source quoting S9.1-S9.3, N9.1-N9.2 stays owner-adopted. The restore-notice choice matches the wording SecuritySurface.restore-skipped.test.ts pins. The choices lie within the delegation (designs of area 5) and no owner source conflicts: the blank-folder/enumeration choice resolves S9.1 against N9.1 while keeping files on disk. Re-ran run-evidence through js-gradle-slot on this candidate for FileOperationLogSafeIdsTest#legacyAndCreatedBatchIdsKeepTheirJournalBytes (no --before) and for #refusesTraversalBeforeReadingOrUpdatingExternalJournal and #refusesWindowsAliasesBeforeJournalAccess (--before b359747af): content digests, executed/passed and assertion fail-before match the committed evidence. Omission (reject): the entry for FileOperationLogSafeIdsTest#legacyAndCreatedBatchIdsKeepTheirJournalBytes now cites PN1, PS1 and the Windows-names choice, but PS1 by its text binds only stores that map conversation, session or run ids. Only the verifier-1 amendment (S9.1 covers every id from a request that becomes a path, including batch/file-history ids) brings batch journals under the single-plain-name rule, and without it no cited source states that the non-UUID batch names b-1, batch_1, 旧履歴 1, COM10 and connection must keep working. Fix: add that delegated choice, as the refusal entry for the same file already does. Unverified: the delegation quote opening words allright i agree. (the task record is outside a verifier brief); the rest matches wp1.2.md and uiux-short-doc-cites.md. Note: StoreIdReviewHttpTest relies on PS2 (4xx, no store) for refusal before approval; the S9.2 approval-ordering amendment would be more exact."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "agent-session-jeckwh3m",
  "verdict": "accept",
  "digest": "sha256:3356024f0c3a9f3b79e36a50e57ad99aed0d8d7afcbaade64f9f7b716c7a6172",
  "note": "Audit of cd8af635e (merge of the safe-ids change with main f003a5c64). Provisional class before reading entries: New for all 11 flagged files (all added; base resolves request ids unchecked in every store). cd8af635e changes only this changeset (sources and a Review history line); no test, product or evidence file changed. The prior audit omission is fixed: FileOperationLogSafeIdsTest#legacyAndCreatedBatchIdsKeepTheirJournalBytes now cites PS1's single-plain-name rule and the Windows delegated choice bounded to COM1-9/LPT1-9; b-1, batch_1, '旧履歴 1', COM10 and connection match none of PS1's refusal conditions nor the later device-name choice. Source kinds: every amendment citation is now delegated-choice with all table fields (delegationQuote, delegationLocation, delegationScope, choice, label 'agent choice'); every choice is verbatim in the agreed wp5.0 text, and the owner-adopted quotes PS1-PS3, PN1, PN2 are verbatim. Scope: the amendments are scenario/design decisions under the user's design delegation; PN1e/PN1s resolve the S9.1-vs-N9.1 conflict over blank legacy ids toward S9.1, which is also owner-adopted, so no stronger source conflicts. The new restore-notice wording choice is UI copy within the design delegation and consistent with PN1s ('saying how many were skipped'); it originates in this change, not in the agreed text. Evidence: all 42 cited checks are executed and passed in their files, self-digests valid, content digests equal the current test files, every repaired check has an assertion fail-before. Re-ran run-evidence to scratch with --before f003a5c64 (the real base): refusesTraversalBeforeReadingOrUpdatingExternalJournal and refusesWindowsAliasesBeforeJournalAccess fail by assertion there and pass on the candidate; legacyAndCreatedBatchIdsKeepTheirJournalBytes passes on both (valid preservation check). Unverified: the delegation quote's opening and closing words (task record not read; its middle sentence is corroborated in the agreed wp1.2 text); the vitest checks were not re-run (no node_modules, npm ci not allowed)."
}
```
