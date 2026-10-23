---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: 01a16851-2fb9-75c1-bc0b-81091fc6bb24
---

# Test-intent entries

Agent choices adopted under the user's quoted delegation; never owner words. All checks are durable. Acceptance is left to the verifier. Execution evidence and live results are recorded in the builder handover.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts"
      ],
      "class": "New",
      "behaviour": "Complete consent before deletion at every depth, cancellation without DELETE, leaf-first progress, whole-tree active-work preflight (also after consent), stop on refusal and authoritative per-identity reconciliation of partial and lost outcomes. Durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.2 [TD2] (S9.6) Deleting a conversation with a branch tree of any depth asks once, naming every descendant and how they nest. On yes, every named conversation and its agent runs are gone from the sidebar, the pager, /api/thread and the store, also after reload and Engine restart. On no, nothing is deleted.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.3 [TD3] (ch. 1; broken today) A delete is reported as done only when the conversation's files and its agent runs are gone. With a conversation file or a run directory that cannot be deleted (fault injected), the window reports the delete as incomplete and names what remains; list, reload, restart and /api/thread agree with what is on disk. Applies to a single conversation without branches too.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.4",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.4 [TD4] (S9.7) If any delete in the tree is refused or fails, deletion stops: nothing not named is deleted; no remaining conversation is left with a deleted parent; the list is read again; the user is told which conversations were deleted and which remain; the message never says nothing was removed when something was (D9).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.5",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.5 [TD5] (ch. 2) A branch created during a tree delete, from this window, another window or the API, never ends with its parent deleted: that parent's delete is refused and reported as S17.4.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.6 [TD6] (S9.8, ch. 2) A tree containing a conversation with a running answer or agent run, or one waiting for a decision, started in this window, another window or through the API, is refused before anything is deleted, and the window says why.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.7 [TD7] (ch. 3) If a delete's response is lost, or the window or Engine stops between two deletes or between a conversation and its runs, then on reconnection the window reads the actual state, reports outcomes it cannot confirm as unconfirmed (neither deleted nor kept) until the list settles them, and does not continue deleting by itself; deleting again finishes leftover runs. An open conversation that was in fact deleted is closed even when the cascade failed.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.9 [TD9] (S9.10, ch. 15) The confirmation works by keyboard; cancelling (including Escape) deletes nothing and returns focus to where the delete was pressed; a screen reader reads the tree in order with its levels; long trees and long labels stay inside the dialog; both themes, forced colours, narrow width.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "Check the confirmed tree for active work before starting; known active work refuses the operation without deletion. Coordinate each deletion with work admission. If work starts on a remaining conversation during the cascade, stop and report partial progress under S17.4.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "Reconcile each affected conversation and its run data through authoritative checks; absence from the recent list does not establish deletion. Exercise leftover non-agent runs and interruption after run metadata removal. Keep incomplete cleanup discoverable and explicitly retryable after restart; unavailable or locked state remains unconfirmed.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.5",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "Race branch creation against deletion in both orderings. If branch creation commits first, refuse the parent’s deletion; if deletion commits first, refuse branch creation visibly. No successfully created branch loses its parent.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-tree-delete-client.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD2 confirms every depth before any DELETE and removes leaves first",
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD2 TD9 cancellation sends no DELETE including a single conversation",
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD3 TD4 stops at disk failure and identifies deleted and remaining conversations",
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD6 refuses known active work in any descendant before deleting",
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD6 rechecks work admitted while the confirmation was open",
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD7 reconciles a lost DELETE response by exact existence rather than the recent list",
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD7 keeps unanswered outcomes unconfirmed and never resumes automatically",
        "modules/ui-web/src/shell-v0/state/conversationTreeDelete.test.ts#TD5 does not delete a newly discovered child outside the confirmed tree"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts",
        "modules/ui-web/src/shell-v0/components/chat/ConversationHistory.delete.test.ts"
      ],
      "class": "New",
      "behaviour": "A single complete confirmation identifies the nested tree and distinguishing facts; even a single conversation needs consent, and unknown creation time is disclosed without inventing a date. The legacy history preserves the server refusal within the exact remaining-outcome report. Durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.1 [TD1] (S9.5) The delete confirmation names each conversation it will delete so the user can tell them apart, including identical titles or questions and a locked store, using facts the store holds (such as where it branched, when it was created, how many messages it has).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.2 [TD2] (S9.6) Deleting a conversation with a branch tree of any depth asks once, naming every descendant and how they nest. On yes, every named conversation and its agent runs are gone from the sidebar, the pager, /api/thread and the store, also after reload and Engine restart. On no, nothing is deleted.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.3 [TD3] (ch. 1; broken today) A delete is reported as done only when the conversation's files and its agent runs are gone. With a conversation file or a run directory that cannot be deleted (fault injected), the window reports the delete as incomplete and names what remains; list, reload, restart and /api/thread agree with what is on disk. Applies to a single conversation without branches too.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.4",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.4 [TD4] (S9.7) If any delete in the tree is refused or fails, deletion stops: nothing not named is deleted; no remaining conversation is left with a deleted parent; the list is read again; the user is told which conversations were deleted and which remain; the message never says nothing was removed when something was (D9).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.9 [TD9] (S9.10, ch. 15) The confirmation works by keyboard; cancelling (including Escape) deletes nothing and returns focus to where the delete was pressed; a screen reader reads the tree in order with its levels; long trees and long labels stay inside the dialog; both themes, forced colours, narrow width.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "TN1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "TN1 The old window's history delete (components/chat/ConversationHistory.ts, same store function) as today or better.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.7 [TD7] (ch. 3) If a delete's response is lost, or the window or Engine stops between two deletes or between a conversation and its runs, then on reconnection the window reads the actual state, reports outcomes it cannot confirm as unconfirmed (neither deleted nor kept) until the list settles them, and does not continue deleting by itself; deleting again finishes leftover runs. An open conversation that was in fact deleted is closed even when the cascade failed.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-tree-delete-dialog.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#TD1 TD2 TD9 confirms distinguishing facts and the complete nested tree",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#asks about the branches, names them, and deletes them with it on consent",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts#discards the conversation and deletes it at the authority that owns its existence",
        "modules/ui-web/src/shell-v0/components/chat/ConversationHistory.delete.test.ts#classic delete displays the server refusal and keeps the conversation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#TD1 TD7 does not invent a creation date for an incomplete deletion record"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts"
      ],
      "class": "New",
      "behaviour": "Replace destructive discovery and one-level consent with read-only complete discovery. Cancellation issues no DELETE, every child precedes its parent, and child failure stops further deletes. Durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.2 [TD2] (S9.6) Deleting a conversation with a branch tree of any depth asks once, naming every descendant and how they nest. On yes, every named conversation and its agent runs are gone from the sidebar, the pager, /api/thread and the store, also after reload and Engine restart. On no, nothing is deleted.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.4",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.4 [TD4] (S9.7) If any delete in the tree is refused or fails, deletion stops: nothing not named is deleted; no remaining conversation is left with a deleted parent; the list is read again; the user is told which conversations were deleted and which remain; the message never says nothing was removed when something was (D9).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.9 [TD9] (S9.10, ch. 15) The confirmation works by keyboard; cancelling (including Escape) deletes nothing and returns focus to where the delete was pressed; a screen reader reads the tree in order with its levels; long trees and long labels stay inside the dialog; both themes, forced colours, narrow width.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-tree-delete-store-contract.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts#a complete tree declined by the reader issues no delete",
        "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts#a confirmed tree deletes children before the parent",
        "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts#cascade aborts if a child delete fails — parent stays intact"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java"
      ],
      "class": "New",
      "behaviour": "A distinct complete direct-child read includes old bases and all direct branch points, reports writer lifetime ownership including approval waits, and validates ids. An incomplete run-only deletion remains authoritatively present even after the run join metadata is lost. Durable.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.1 [TD1] (S9.5) The delete confirmation names each conversation it will delete so the user can tell them apart, including identical titles or questions and a locked store, using facts the store holds (such as where it branched, when it was created, how many messages it has).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.2 [TD2] (S9.6) Deleting a conversation with a branch tree of any depth asks once, naming every descendant and how they nest. On yes, every named conversation and its agent runs are gone from the sidebar, the pager, /api/thread and the store, also after reload and Engine restart. On no, nothing is deleted.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.3 [TD3] (ch. 1; broken today) A delete is reported as done only when the conversation's files and its agent runs are gone. With a conversation file or a run directory that cannot be deleted (fault injected), the window reports the delete as incomplete and names what remains; list, reload, restart and /api/thread agree with what is on disk. Applies to a single conversation without branches too.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.6",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.6 [TD6] (S9.8, ch. 2) A tree containing a conversation with a running answer or agent run, or one waiting for a decision, started in this window, another window or through the API, is refused before anything is deleted, and the window says why.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.7 [TD7] (ch. 3) If a delete's response is lost, or the window or Engine stops between two deletes or between a conversation and its runs, then on reconnection the window reads the actual state, reports outcomes it cannot confirm as unconfirmed (neither deleted nor kept) until the list settles them, and does not continue deleting by itself; deleting again finishes leftover runs. An open conversation that was in fact deleted is closed even when the cascade failed.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "TN3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "TN3 Deleting a conversation without branches on a cooperating disk works as today (same response fields); WP5.0's id checks still apply.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-tree-delete-engine.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java#completeChildReadIncludesOldBaseAndAllBranchPointsBeyondRecentCap",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java#childReadReportsActiveWorkIncludingApprovalWaitOwnership",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java#unsafeChildReadIsRefusedBeforeStoreAccess",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationDeletionTest.java#incompleteRunOnlyDeletionRemainsPresentAfterJoinMetadataIsLost"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts",
        "modules/ui-web/src/shell-v0/components/chat/ConversationHistory.delete.test.ts",
        "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts",
        "modules/ui-web/src/shell-v0/state/conversationDelete.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Fakes answer exact direct-child and existence reads separately from recents and forks; existing callers now complete the real confirmation through its composed native button. Existing focus, active-session clearing, history retirement and server-reason checks remain. Timer cleanup is awaited. The deliberately changed one-level, pre-consent DELETE and exact-outcome expectations are accounted for by the New entries above.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.2",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.2 [TD2] (S9.6) Deleting a conversation with a branch tree of any depth asks once, naming every descendant and how they nest. On yes, every named conversation and its agent runs are gone from the sidebar, the pager, /api/thread and the store, also after reload and Engine restart. On no, nothing is deleted.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.3",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.3 [TD3] (ch. 1; broken today) A delete is reported as done only when the conversation's files and its agent runs are gone. With a conversation file or a run directory that cannot be deleted (fault injected), the window reports the delete as incomplete and names what remains; list, reload, restart and /api/thread agree with what is on disk. Applies to a single conversation without branches too.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.4",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.4 [TD4] (S9.7) If any delete in the tree is refused or fails, deletion stops: nothing not named is deleted; no remaining conversation is left with a deleted parent; the list is read again; the user is told which conversations were deleted and which remain; the message never says nothing was removed when something was (D9).",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.7 [TD7] (ch. 3) If a delete's response is lost, or the window or Engine stops between two deletes or between a conversation and its runs, then on reconnection the window reads the actual state, reports outcomes it cannot confirm as unconfirmed (neither deleted nor kept) until the list settles them, and does not continue deleting by itself; deleting again finishes leftover runs. An open conversation that was in fact deleted is closed even when the cascade failed.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.8",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.8 [TD8] (S9.9) Deleting the open conversation, or one it branched from, leaves the window on a clean new session; Back and reload do not bring it back.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.9",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.9 [TD9] (S9.10, ch. 15) The confirmation works by keyboard; cancelling (including Escape) deletes nothing and returns focus to where the delete was pressed; a screen reader reads the tree in order with its levels; long trees and long labels stay inside the dialog; both themes, forced colours, narrow width.",
          "label": "agent choice, adopted under the user's delegation"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "TN1",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "TN1 The old window's history delete (components/chat/ConversationHistory.ts, same store function) as today or better.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerForkDiscoveryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "The existing list-read fixture also serves the three new direct-child checks. Its result record exposes the childrenOf response marker, and its list helper accepts an empty or actively held ConversationWork authority. The existing fork-membership, filter validation, recency cap, order and shape-filter assertions are unchanged. The three New child-read checks retain their inputs and assertions when consolidated from the provisional separate fixture."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationDeleteReconnect.test.ts"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.7 [TD7] (ch. 3) If a delete's response is lost, or the window or Engine stops between two deletes or between a conversation and its runs, then on reconnection the window reads the actual state, reports outcomes it cannot confirm as unconfirmed (neither deleted nor kept) until the list settles them, and does not continue deleting by itself; deleting again finishes leftover runs. An open conversation that was in fact deleted is closed even when the cascade failed.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-tree-delete-reconnect.json",
      "behaviour": "Engine-only restart with the window kept open reconciles single and interrupted tree deletes through the existing shared status signal; reports deleted/remaining/unconfirmed exactly, closes a deleted open conversation and never resumes deletion. An unreadable identity stays unconfirmed until a later successful read. After settlement, status samples cause no further conversation reads. Durable.",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationDeleteReconnect.test.ts#TD7 Engine-only restart settles single deletion without resuming it",
        "modules/ui-web/src/shell-v0/state/conversationDeleteReconnect.test.ts#TD7 Engine-only restart settles root deletion without resuming it",
        "modules/ui-web/src/shell-v0/state/conversationDeleteReconnect.test.ts#TD7 Engine-only restart closes the deleted open conversation",
        "modules/ui-web/src/shell-v0/state/conversationDeleteReconnect.test.ts#TD7 keeps an unreadable outcome unconfirmed until a later status can settle it"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/conversationDeleteReconnect.test.ts"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "scenario": "S17.7",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "The remaining UI/UX work; WP1.4b tree deletion S17.1–S17.10 and TN1–TN4, as amended by the agreed challenger pass 2.",
          "choice": "S17.7 [TD7] (ch. 3) If a delete's response is lost, or the window or Engine stops between two deletes or between a conversation and its runs, then on reconnection the window reads the actual state, reports outcomes it cannot confirm as unconfirmed (neither deleted nor kept) until the list settles them, and does not continue deleting by itself; deleting again finishes leftover runs. An open conversation that was in fact deleted is closed even when the cascade failed.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-tree-delete-reconnect-preservation.json",
      "behaviour": "Without an unconfirmed delete, status changes cause no conversation reads or DELETEs. Durable preservation check.",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/conversationDeleteReconnect.test.ts#TD7 status changes cause no conversation reads without an unconfirmed delete"
      ]
    }
  ]
}
```
