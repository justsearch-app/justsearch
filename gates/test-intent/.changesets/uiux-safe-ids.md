---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-sr90jh91
---

# Test-intent entries

Scenario ids map S9.1–S9.3 to PS1–PS3 and N9.1–N9.2 to PN1–PN2.
The blank-id search-event check is strengthened to require the agreed refusal.

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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-repair.json",
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-repair.json",
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-repair.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/RunStoresSafeIdsTest.java#refusesUnsafeIdsAcrossRunAndEventOperationsBeforeFilesystemAccess"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentRunStoreTest.java"
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
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-repair.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentRunStoreTest.java#appendSearchEventRefusesBlankConversationId"
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-repair.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java#rejectsEncodedIdsOnEveryAddressedRouteWithoutTouchingStores",
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-preserve.json",
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-preserve.json",
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
      "evidence": "gates/test-intent/evidence/uiux-safe-ids-preserve.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ConversationSafeIdsHttpTest.java#validDeleteAndReadKeepTheirResponses"
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
