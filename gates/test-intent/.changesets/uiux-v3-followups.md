---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-v3-followups-repair-builder
---

# Test-intent entries

These entries cover every test file added or changed between origin/main 117e89be9 and this branch's working tree for the Search v3 conversation follow-ups (WP1.2). They include the repair of the independent verifier's findings F1–F7 and the repairs from the two pre-review reports (review-followups-backend.md, review-followups-frontend.md). Search mode (45171b78e) is merged on main with its own changeset and is not covered here. The sources cite the agreed WP1.2 scenarios under the gate ids S2.n → FSn and N2.n → FNn; each quote is the scenario text unchanged (for S2.0 and S2.9, the text after the "(broken today…)" status note).

Evidence: run-evidence.mjs run once over every new check with --before 117e89be9, under the shared Gradle lock, with TMPDIR and --before-root inside this checkout's tmp/. Entries are split by each check's recorded before-result: checks that failed by assertion on 117e89be9 carry repaired: true; checks that passed there, or failed for a reason other than an assertion, carry no repaired flag. The run's aggregate exit is therefore not 0; the per-check before-results decide the classification. Several review repairs fail on 117e89be9 for a reason that predates them (no stop route at all). Their failure on the unrepaired branch (04941d29e), recorded by the builder before each fix, is stated in the entry's behaviour.

Two edits serve this evidence without changing any expectation:
- run-evidence.mjs addresses a JUnit check by method name, but the JUnit XML names a method by its @DisplayName. So the new Java methods carry no method-level @DisplayName, like the neighbouring retrieval-explanation tests.
- On the before-state, a missing control used to throw a plain Error, which counts as an exception, not an assertion. Examples are a Delegate turn's Retry/Edit/Branch and Restore for a floor that is not shown. The press and menu helpers in SearchV3View.branch/context.test.ts now fail with vitest's `assert` instead. In the same way, InteractionThreadControllerTest asserts the note's presence before reading it, and ChatStopTurnHttpTest asserts the stop response's status before reading its body. As a result, those before-state failures are assertions.

S2.9 reproduced on the before-state: with the floor's own message excluded, the effective context returned [q1, a1, a2] instead of [a2].

Removed expectations (the Obsolete entries):
- SearchV3View.branch.test.ts "a delegate turn STAMPED with real store ids still withholds by KIND" and the two sv3-branch.test.ts cases that pinned an agent turn withholding Edit, Retry and Branch (tempdoc 863 §4.A.5 A-8) are replaced. Under S2.5, Retry and Edit on a Delegate turn run the agent again. The replacements pin the opposite: the acts are offered on store ids, and the Delegate re-run is pinned in SearchV3View.branch.test.ts.
- sv3-context.test.ts "drops out-of-context and hidden turns…" used to exclude only a turn's answer and expect the inspector to drop the whole turn. Under S2.0 what is sent matches the bar, so one hidden answer leaves its question in the prompt; SearchV3View.context.test.ts pins that. The case now excludes the whole turn.
- Stop and leaving (N2.3 / S2.3): SearchV3View.ask.test.ts "halts on Stop…" and SearchV3View.honesty.test.ts "never routes the reader's own Stop…" expected the turn halted the moment Stop was pressed; they now halt it on the Engine's CANCELLED terminal, because a Stop that lands after the answer is saved leaves a complete saved answer. SearchV3View.sessions.test.ts "halts an answer still streaming when the reader starts a new session" and SearchV3View.record.test.ts "appends NOTHING to the fresh thread…" expected an answer the reader left mid-stream to read "Stopped by you." with its partial text; leaving tells the Engine nothing, so the Engine saves the complete answer, and the window now drops its partial copy and shows what was saved.

Stop (S2.3 / N2.3): Search v3's Stop tells the Engine which turn to stop (POST /api/chat/conversations/{id}/stop with the dispatched turnId) and keeps the stream open until the Engine ends the turn. The route answers {stopped, pending}: stopped when the running turn was cancelled by this Stop, pending when the turn has not started yet, neither when the Stop changed nothing (the answer is already being saved, the turn finished, or it was cancelled for another reason). Only the stream's terminal settles the turn. CANCELLED means the turn is saved stopped, with its question, its note, its thinking, its sources and what had streamed, and reloads as "stopped by you". `done` means it finished first, and the turn shows the complete answer. Until then the turn says "Stopping…", or that the Stop did not reach the Engine and the answer goes on. A turn stopped during retrieval ends with one terminal. ChatStopTurnHttpTest pins it at the HTTP boundary against the saved history; SearchV3View.ask.test.ts and sv3-record.test.ts pin the client's request, its display and its reading of the saved turn. A closed connection without Stop still runs to its answer, and a Stop for a finished or different turn changes nothing.

Early Stop: a Stop that reaches the Engine before the dispatch has registered its turn (pressed in the instant after Send, or while the dispatch waits for capacity or the model) is kept by TurnStops until that turn starts, however long that takes (at most 256 kept, the oldest dropped first, each used once). It stops the turn as it starts; the turn is saved with its question and an empty stopped answer, with no note because nothing was rewritten. A late Stop for a finished turn is not kept, so it never stops a later dispatch naming the same turn. Search v3 names each dispatch with a fresh random id rather than the turn's position, which a reload or a second window could hand out again. ChatStopTurnHttpTest pins both at the HTTP boundary against the saved history; TurnStopsTest pins the bounds (count, once, finished) and the answers. TurnStops is new, and its test would not compile on 117e89be9, which would also stop every other app-services check there from compiling. So the fail-before applies the compat before-patch gates/test-intent/evidence/uiux-v3-followups-compat.patch. It adds only a TurnStops that does nothing (its stop answers UNCHANGED, and it keeps and cancels nothing), which is 117e89be9's behaviour, where no Stop reaches the Engine, plus the ConversationEngine.TurnStop outcome type it returns. No production code at 117e89be9 reads either. The old window now shows a saved stopped turn as "Stopped by you." with its note, and an early Stop's empty answer is not drawn as an answer (AskTerminalReload.test.ts).

Context writes (S2.10): a reload overtaken by a newer reload of the same conversation applies nothing and leaves its write's confirmation to the newer read, so an older answer can never undo a newer saved state; a turn saved after the last /history read is placed below a floor not shown here (S2.0).

Coverage limits: no live check was run. The client's switch from the turn's position to a random dispatch id has no check of its own; the existing SearchV3View.ask.test.ts cases pin that each dispatch carries an id and the Stop names that same id. Exempting the stop route from Engine admission (backend review F6) is not done; the client waits and retries a Stop refused for capacity, as it does every request the Engine marks retry-safe, and says so when the Stop fails.

No acceptance record is supplied by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "New",
      "behaviour": "The Engine saves a rewritten follow-up's standalone question on that turn's own outcome row (never on the question row, never on the live done payload), and saves none for a follow-up that was not rewritten. A rewrite followed by retrieval failure (with retrieval metadata, and through the production RAGContext whose search fails outright with RETRIEVAL_FAILED and no rag.meta) or by generation failure still saves the question and its terminal outcome with the same note, read back from a reopened FileConversationStore.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS1",
          "quote": "A follow-up whose question was rewritten shows \"Interpreted as: …\" live and identically after a cold reload, in Search v3 and in the old window, read over /history and /api/thread; each note sits on its own turn (consecutive rewritten and unrevised turns never swap or inherit notes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS2",
          "quote": "A first question, a follow-up that was not rewritten, a rewrite that failed or timed out, and AI unavailable show no note, live or reloaded, and the answer is not blocked.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#rewrittenFollowUpPersistsTheStandaloneQuestion",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#rewriteThenRetrievalFailureKeepsTheNote",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#rewriteThenFailedRetrievalWithoutMetadataKeepsTheTurnAndItsNote",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#rewriteThenGenerationFailureKeepsTheNote"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "New",
      "behaviour": "The Engine saves a rewritten follow-up's standalone question on that turn's own outcome row (never on the question row, never on the live done payload), and saves none for a follow-up that was not rewritten. A rewrite followed by retrieval failure (with retrieval metadata, and through the production RAGContext whose search fails outright with RETRIEVAL_FAILED and no rag.meta) or by generation failure still saves the question and its terminal outcome with the same note, read back from a reopened FileConversationStore.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS1",
          "quote": "A follow-up whose question was rewritten shows \"Interpreted as: …\" live and identically after a cold reload, in Search v3 and in the old window, read over /history and /api/thread; each note sits on its own turn (consecutive rewritten and unrevised turns never swap or inherit notes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS2",
          "quote": "A first question, a follow-up that was not rewritten, a rewrite that failed or timed out, and AI unavailable show no note, live or reloaded, and the answer is not blocked.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#unrevisedTurnCarriesNoStandaloneQuestion"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "New",
      "behaviour": "A follow-up's forwarded conversation is answered with that conversation: the forwarded turns count as conversation (not retrieved material) in the context breakdown the bar reads, and a compaction or forwarded summary reaches the model folded into the one leading system message after the standing prompt (or leading the list when there is none), with the earlier turns kept in order. A context with no system message reaches the model unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS0",
          "quote": "A follow-up Ask in Search v3 is answered in the light of the conversation, as in the current window: retrieval runs on the rewritten standalone question (a real second question produces the rewrite), and the answer can refer to the earlier turn. Messages excluded from context and messages before the context starting point are not sent with the follow-up; the context bar's numbers match what was sent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN2",
          "quote": "Context floor / compact / exclude / summary-edit round-trips for on-screen turns, live and reloaded.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN5",
          "quote": "The old window's follow-up context and Free chat history behave as today.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#forwardedContextCountsAsConversationInTheBreakdown",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#compactionSummaryFoldsIntoTheLeadingSystemMessage",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#forwardedSummaryFoldsIntoTheLeadingSystemMessage",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#summaryWithoutSystemPromptLeadsTheList"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "New",
      "behaviour": "A follow-up's forwarded conversation is answered with that conversation: the forwarded turns count as conversation (not retrieved material) in the context breakdown the bar reads, and a compaction or forwarded summary reaches the model folded into the one leading system message after the standing prompt (or leading the list when there is none), with the earlier turns kept in order. A context with no system message reaches the model unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS0",
          "quote": "A follow-up Ask in Search v3 is answered in the light of the conversation, as in the current window: retrieval runs on the rewritten standalone question (a real second question produces the rewrite), and the answer can refer to the earlier turn. Messages excluded from context and messages before the context starting point are not sent with the follow-up; the context bar's numbers match what was sent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN2",
          "quote": "Context floor / compact / exclude / summary-edit round-trips for on-screen turns, live and reloaded.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN5",
          "quote": "The old window's follow-up context and Free chat history behave as today.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#contextWithoutSystemMessagesIsUnchanged"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreTest.java"
      ],
      "class": "New",
      "behaviour": "With the floor set on a message and that same message excluded, the effective context still starts at the floor: only the message after it is returned, nothing above the floor comes back.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS9",
          "quote": "Excluding the message the context starting point sits on does not bring back older history into what the AI receives.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/FileConversationStoreTest.java#excludingTheFloorMessageKeepsTheFloor"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java"
      ],
      "class": "New",
      "behaviour": "/api/thread lifts a saved standaloneQuestion onto its own turn's answer event and onto an ERROR event of a failed turn, and an unrevised turn's event carries no such key (the field is optional).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS1",
          "quote": "A follow-up whose question was rewritten shows \"Interpreted as: …\" live and identically after a cold reload, in Search v3 and in the old window, read over /history and /api/thread; each note sits on its own turn (consecutive rewritten and unrevised turns never swap or inherit notes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN4",
          "quote": "/api/thread and history reads by other clients (MCP, API) still parse; the new saved field is optional.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java#surfacesTheStandaloneQuestionFromTheRecord"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts"
      ],
      "class": "New",
      "behaviour": "Projecting the /api/thread record, each turn reads its note from its own answer or error event: consecutive rewritten and unrevised turns never swap or inherit notes, a rewrite followed by a failure keeps its note, and a record with no field or a blank one shows no note.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS1",
          "quote": "A follow-up whose question was rewritten shows \"Interpreted as: …\" live and identically after a cold reload, in Search v3 and in the old window, read over /history and /api/thread; each note sits on its own turn (consecutive rewritten and unrevised turns never swap or inherit notes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS4",
          "quote": "Conversations saved before the change load as before and show no note.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts#reads each turn’s note off its own answer, and none onto a turn that was not rewritten",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts#keeps the note on a turn whose rewrite was followed by a failure"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts"
      ],
      "class": "New",
      "behaviour": "Projecting the /api/thread record, each turn reads its note from its own answer or error event: consecutive rewritten and unrevised turns never swap or inherit notes, a rewrite followed by a failure keeps its note, and a record with no field or a blank one shows no note.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS1",
          "quote": "A follow-up whose question was rewritten shows \"Interpreted as: …\" live and identically after a cold reload, in Search v3 and in the old window, read over /history and /api/thread; each note sits on its own turn (consecutive rewritten and unrevised turns never swap or inherit notes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS4",
          "quote": "Conversations saved before the change load as before and show no note.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts#shows no note for a record written before the field existed, or a blank one"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "A follow-up Ask carries the earlier turns as context on the conversation's own id, oldest first, and none for a first question. It leaves out excluded messages and everything before the floor, leads with the summary, and does not bring older turns back when the floor's own message is excluded. A floor on a message not shown is placed by the saved order. Inside a combined (multi-message) answer, a hidden message is left out and a floor on a middle or last part sends only the saved messages from it on, as the bar counts them. Nothing older is sent until the /history settings have arrived, and no earlier turn is sent when they cannot be read. The inspector shows exactly the messages sent (one hidden answer, not its turn).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS0",
          "quote": "A follow-up Ask in Search v3 is answered in the light of the conversation, as in the current window: retrieval runs on the rewritten standalone question (a real second question produces the rewrite), and the answer can refer to the earlier turn. Messages excluded from context and messages before the context starting point are not sent with the follow-up; the context bar's numbers match what was sent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS8",
          "quote": "A context starting point (with or without a compaction summary) on a message that is not shown: the bar says the context starts at a message not shown here, the summary can still be viewed and edited, and Restore clears floor and summary in saved state; exclusions stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS9",
          "quote": "Excluding the message the context starting point sits on does not bring back older history into what the AI receives.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sends the earlier turns as context, oldest first, on the conversation’s own id",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#leaves out excluded messages and everything above the floor, and leads with the summary",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#does not bring older turns back when the floor’s own message is excluded (S2.9)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#places a floor that sits on a message not shown here by the saved order",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#leaves out a hidden message from INSIDE a combined answer, as the bar counts it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#starts at a floor set INSIDE a combined answer, sending nothing saved before it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#starts at a floor on the LAST message of a combined answer, sending only that message",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#does not send older turns before the conversation’s context settings have arrived",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows in the inspector exactly the messages it sends — one hidden answer, not its turn"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "A follow-up Ask carries the earlier turns as context on the conversation's own id, oldest first, and none for a first question. It leaves out excluded messages and everything before the floor, leads with the summary, and does not bring older turns back when the floor's own message is excluded. A floor on a message not shown is placed by the saved order. Inside a combined (multi-message) answer, a hidden message is left out and a floor on a middle or last part sends only the saved messages from it on, as the bar counts them. Nothing older is sent until the /history settings have arrived, and no earlier turn is sent when they cannot be read. The inspector shows exactly the messages sent (one hidden answer, not its turn).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS0",
          "quote": "A follow-up Ask in Search v3 is answered in the light of the conversation, as in the current window: retrieval runs on the rewritten standalone question (a real second question produces the rewrite), and the answer can refer to the earlier turn. Messages excluded from context and messages before the context starting point are not sent with the follow-up; the context bar's numbers match what was sent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS8",
          "quote": "A context starting point (with or without a compaction summary) on a message that is not shown: the bar says the context starts at a message not shown here, the summary can still be viewed and edited, and Restore clears floor and summary in saved state; exclusions stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS9",
          "quote": "Excluding the message the context starting point sits on does not bring back older history into what the AI receives.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sends no context with the first question of a new conversation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#sends no older turns when the conversation’s context settings cannot be read"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "In Search v3, each rewritten turn shows its own \"Interpreted as\" note live, after the record refresh and after a cold reload, and an unrevised turn between them shows none.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS1",
          "quote": "A follow-up whose question was rewritten shows \"Interpreted as: …\" live and identically after a cold reload, in Search v3 and in the old window, read over /history and /api/thread; each note sits on its own turn (consecutive rewritten and unrevised turns never swap or inherit notes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS2",
          "quote": "A first question, a follow-up that was not rewritten, a rewrite that failed or timed out, and AI unavailable show no note, live or reloaded, and the answer is not blocked.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows each note on its own turn live, after the record refresh, and after a reload"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "The bar counts unique hidden messages not shown here (as messages, with duplicate ids once, beside on-screen hidden turns, no invented turn count) and Include all clears every recorded exclusion once each, verified in the saved state after reload, leaving the floor, its summary and excludedSourceIds untouched. A floor on a message not shown is announced as such; its summary can be viewed and edited, and Restore clears floor and summary in the saved state while exclusions stay.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS7",
          "quote": "Exclusions of messages not in the shown part of the transcript are counted in the context bar (\"N hidden messages not shown here\", counted as messages), and \"Include all\" clears every recorded exclusion, verified in the saved state after reload. Unique messages are counted, with mixed on-screen and unseen exclusions and duplicate ids; no invented turn count. Include all leaves the context starting point, its summary and excluded sources (excludedSourceIds) untouched.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS8",
          "quote": "A context starting point (with or without a compaction summary) on a message that is not shown: the bar says the context starts at a message not shown here, the summary can still be viewed and edited, and Restore clears floor and summary in saved state; exclusions stay.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#counts unique hidden messages not shown here beside the hidden turns on screen",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows and undoes exclusions that are ALL off screen, without inventing a turn count",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#clears every recorded exclusion once each, on screen and off",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#says the context starts at a message not shown here, shows and edits its summary, and restores"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "A partially refused Include all is reported as a failure and the bar shows what was saved. A failed reload after Include all or Restore never wipes the bar: when the write landed the bar shows the saved result; when Restore is refused and the reload also fails the bar stays on the saved floor. The conversation the reader navigated to keeps its own saved state while the undo finishes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS10",
          "quote": "A failed or partially refused Include all / Restore does not announce success; a failed refresh, out-of-order responses or navigating away during the undo never leave the bar showing a state that is not the saved one.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#reports a PARTIALLY refused Include all as a failure and shows what was really saved",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#does not wipe the bar when the reload after Include all fails",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows the saved state when Include all landed but its reload failed",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#shows the saved state when Restore landed but its reload failed",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#leaves the bar on the saved floor when Restore is refused AND its reload fails",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps the conversation the reader moved to on ITS saved state while the undo finishes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts"
      ],
      "class": "New",
      "behaviour": "Opening a conversation reads the /history companion, now including its message order as ids only (what places a floor that no turn on screen names); the transcript still comes from the record.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS8",
          "quote": "A context starting point (with or without a compaction summary) on a message that is not shown: the bar says the context starts at a message not shown here, the summary can still be viewed and edited, and Restore clears floor and summary in saved state; exclusions stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN2",
          "quote": "Context floor / compact / exclude / summary-edit round-trips for on-screen turns, live and reloaded.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts#reads the /history COMPANION record when a conversation is opened (852 §2.3c)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "Retry and Edit on a Delegate turn run the agent again in a new version whatever the composer shows: for a tool-less agent turn after reload, at the first question (empty-prefix sentinel), with no documents indexed, and when re-editing a branch's own turn (a sibling of that branch) or a retry's own first turn (a third version under the original, pager 3/3). Branch from a Delegate turn runs nothing and leaves the composer in Delegate. Ask turns keep re-running as Ask, and re-editing a branch's own Ask question makes a third version of the base (pager 3/3).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS5",
          "quote": "Retry and Edit on a Delegate (agent) turn run the agent again in a new version, whatever mode the composer currently shows and whatever mode the conversation started in; including an agent turn with no tool calls, after reload. Branch from a Delegate turn leaves the composer in Delegate for the next send (it does not run anything by itself). Ask turns keep re-running as Ask. A Delegate Retry/Edit runs even when no documents are indexed (the Ask-only gate does not apply). Forks land where Ask forks land: the first question (empty prefix), a middle turn, and re-editing a branch's own turn makes the right sibling; the original conversation is unchanged.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN1",
          "quote": "Edit / Retry / Branch and the version pager on Ask turns.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#retries a tool-less Delegate turn as a Delegate, after reload, while the composer shows Ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#edits the FIRST question of a Delegate conversation at the empty-prefix sentinel",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#runs even when no documents are indexed — the Ask-only gate does not apply",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#re-edits a branch’s own Delegate turn as a SIBLING of that branch",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#re-edits a retry’s own first turn as a third version of the original, not a branch of the retry",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#branches from a Delegate turn without running anything, and leaves the composer in Delegate",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#re-edits a branch’s own question as a THIRD version of the base, not a branch of the branch"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "Retry and Edit on a Delegate turn run the agent again in a new version whatever the composer shows: for a tool-less agent turn after reload, at the first question (empty-prefix sentinel), with no documents indexed, and when re-editing a branch's own turn (a sibling of that branch) or a retry's own first turn (a third version under the original, pager 3/3). Branch from a Delegate turn runs nothing and leaves the composer in Delegate. Ask turns keep re-running as Ask, and re-editing a branch's own Ask question makes a third version of the base (pager 3/3).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS5",
          "quote": "Retry and Edit on a Delegate (agent) turn run the agent again in a new version, whatever mode the composer currently shows and whatever mode the conversation started in; including an agent turn with no tool calls, after reload. Branch from a Delegate turn leaves the composer in Delegate for the next send (it does not run anything by itself). Ask turns keep re-running as Ask. A Delegate Retry/Edit runs even when no documents are indexed (the Ask-only gate does not apply). Forks land where Ask forks land: the first question (empty prefix), a middle turn, and re-editing a branch's own turn makes the right sibling; the original conversation is unchanged.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN1",
          "quote": "Edit / Retry / Branch and the version pager on Ask turns.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#keeps Ask turns re-running as Ask, even when the composer shows Delegate"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "When the agent cannot run, a Delegate Retry/Edit says why and never runs an Ask: unavailable before the fork (edited text kept, nothing forked), unavailable after the fork succeeded (edited text kept in the composer in Delegate, one notice, no send or dispatch), another run active, and a refused fork. An inherited Delegate turn gains no Retry or Edit.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS6",
          "quote": "When the agent cannot run (unavailable, another run active or waiting, branch refused), Retry/Edit on a Delegate turn says why, keeps the edited text, and never runs an Ask instead. Turns that cannot be addressed (inherited from a parent, run-only/legacy) do not gain Retry/Edit.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#refuses while the model is unavailable, keeps the edited text, and forks nothing",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#keeps the edited text and says why when the agent becomes unavailable during the fork",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#refuses while another run is active, and says so",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#says the branch failed and runs nothing when the store refuses the fork"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "When the agent cannot run, a Delegate Retry/Edit says why and never runs an Ask: unavailable before the fork (edited text kept, nothing forked), unavailable after the fork succeeded (edited text kept in the composer in Delegate, one notice, no send or dispatch), another run active, and a refused fork. An inherited Delegate turn gains no Retry or Edit.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS6",
          "quote": "When the agent cannot run (unavailable, another run active or waiting, branch refused), Retry/Edit on a Delegate turn says why, keeps the edited text, and never runs an Ask instead. Turns that cannot be addressed (inherited from a parent, run-only/legacy) do not gain Retry/Edit.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts#gives an INHERITED Delegate turn no Retry or Edit — it belongs to the parent"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-branch.test.ts"
      ],
      "class": "New",
      "behaviour": "A store-backed agent turn, including a tool-less one whose kind only the record declares, is offered Retry, Edit and Branch on its store ids exactly as the same ask turn is (the first turn forks at the empty-prefix sentinel).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS5",
          "quote": "Retry and Edit on a Delegate (agent) turn run the agent again in a new version, whatever mode the composer currently shows and whatever mode the conversation started in; including an agent turn with no tool calls, after reload. Branch from a Delegate turn leaves the composer in Delegate for the next send (it does not run anything by itself). Ask turns keep re-running as Ask. A Delegate Retry/Edit runs even when no documents are indexed (the Ask-only gate does not apply). Forks land where Ask forks land: the first question (empty prefix), a middle turn, and re-editing a branch's own turn makes the right sibling; the original conversation is unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-branch.test.ts#reads a TOOL-LESS delegate turn as an agent turn, and offers it the acts on its store ids",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-branch.test.ts#an agent turn with fully valid STORE ids offers what the same ask turn offers"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts"
      ],
      "class": "New",
      "behaviour": "With a whole turn excluded (both messages, as the turn menu writes it) and a floor with a summary, the inspector drops the out-of-context and hidden turns and leads with the standing summary.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS0",
          "quote": "A follow-up Ask in Search v3 is answered in the light of the conversation, as in the current window: retrieval runs on the rewritten standalone question (a real second question produces the rewrite), and the answer can refer to the earlier turn. Messages excluded from context and messages before the context starting point are not sent with the follow-up; the context bar's numbers match what was sent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN2",
          "quote": "Context floor / compact / exclude / summary-edit round-trips for on-screen turns, live and reloaded.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts#drops out-of-context and hidden turns, and leads with the standing summary"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts"
      ],
      "class": "New",
      "behaviour": "A /history record whose assistant row the Engine saved with disposition CANCELLED and a standaloneQuestion reads back as the reader's halt: status halted, with the turn's note and its partial text, on the store-minted ids. It passes on the client as it stands; on 25ad8e309 it fails by assertion because the record did not yet project the note.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts#reads a turn the Engine saved as stopped as the reader’s halt, with its note and its text"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts"
      ],
      "class": "New",
      "behaviour": "The old window (Unified Chat) reading /api/thread records shows a turn the Engine saved as stopped (disposition CANCELLED) as \"Stopped by you.\": an early Stop shows its \"Interpreted as\" note and the Stop, with no answer block standing in for an empty answer; a late Stop keeps the text that had streamed and says it was stopped; a finished answer says nothing of the kind. On 25ad8e309 it fails by assertion: the stopped turn renders as a plain, empty answer bubble.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts#reloaded Unified Chat shows a turn the reader stopped as stopped, with its note, not as an answer"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-branch.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS5",
          "quote": "Retry and Edit on a Delegate (agent) turn run the agent again in a new version, whatever mode the composer currently shows and whatever mode the conversation started in; including an agent turn with no tool calls, after reload. Branch from a Delegate turn leaves the composer in Delegate for the next send (it does not run anything by itself). Ask turns keep re-running as Ask. A Delegate Retry/Edit runs even when no documents are indexed (the Ask-only gate does not apply). Forks land where Ask forks land: the first question (empty prefix), a middle turn, and re-editing a branch's own turn makes the right sibling; the original conversation is unchanged.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS0",
          "quote": "A follow-up Ask in Search v3 is answered in the light of the conversation, as in the current window: retrieval runs on the rewritten standalone question (a real second question produces the rewrite), and the answer can refer to the earlier turn. Messages excluded from context and messages before the context starting point are not sent with the follow-up; the context bar's numbers match what was sent.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions/*.md for delegate, agent turn, retry, edit, branch, exclusion, floor, follow-up context): none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-branch.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "signature"
      ],
      "whatMoved": "sv3-branch.test.ts: two describe titles renamed (\"the KIND the gate reads…\" → \"the KIND a re-run reads…\"; \"an agent turn withholds edit, retry and branch on its KIND…\" → \"an agent turn is gated like an ask turn…\"); the four cases 'reads the ask turn after it as an ask turn, which keeps its own affordances', 'falls back to the activity inference only for a record that cannot say', 'the IDENTICAL turn with kind \"ask\" offers both — the control proving case 1 is about kind' and 'the ask turn AFTER a delegate turn gains Edit (863 §4.A.5 A-8.2)' are byte-identical. sv3-context.test.ts 'counts a turn as hidden when EITHER of its messages is excluded': sv3ExcludedMessageIds now takes the /history settings instead of the turn contexts; the same input history is passed and the same expected [stored(4)] kept, as are the hasExcluded and turn-count expectations."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Over HTTP, against the real ConversationEngine and the file store, POST /api/chat/conversations/{id}/stop with the dispatched turnId stops that turn and the Engine saves it as stopped: after a Stop right after the rewrite note, while the documents are retrieved, while the finished answer is checked, or late in the answer, the saved history holds the question and an assistant row with disposition CANCELLED, the rewrite's standaloneQuestion and whatever had streamed (empty for an early Stop, the whole text when the answer was being checked), and never a failed or complete turn. Preserved: a connection closed without Stop keeps the turn running to its complete answer with its note; a Stop for a turn that already finished answers stopped:false and leaves the saved history unchanged; a Stop naming another turn answers stopped:false and the running turn completes. On 117e89be9 the stop route does not exist (404).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#stopRightAfterTheRewriteSavesTheTurnStoppedWithItsNote",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#stopWhileTheDocumentsAreRetrievedSavesTheTurnStoppedNotFailed",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#stopWhileTheAnswerIsCheckedSavesTheWholeTextAsStopped",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#lateStopSavesWhatStreamedAsStopped",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aClosedConnectionWithoutStopKeepsTheTurnRunningToItsAnswer",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aStopAfterTheTurnFinishedChangesNothing",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aStopNamingAnotherTurnLeavesTheRunningTurnAlone"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Over HTTP: a Stop that reaches the Engine before the dispatch of its turn answers pending:true (not stopped:false) and still stops that turn when it starts; the saved history holds the question and an assistant row with disposition CANCELLED, empty content, no terminalError and no standaloneQuestion, the stream carried no rag.rewrite and the model was never asked. A Stop for a turn that already finished answers stopped:false and is not kept, so a later dispatch naming the same turn runs to its complete answer. Review repairs: a stopped turn is saved with the thinking and the sources the stream showed before the Stop (backend F2); a Stop while the documents are retrieved ends the stream with ONE terminal, the CANCELLED error, never RETRIEVAL_FAILED as well (F9); a Stop that arrives once the answer is being saved answers stopped:false, the stream ends with done and the saved answer is complete with no disposition (F4/F5c). On 117e89be9 the stop route does not exist (404); on the unrepaired branch the thinking was not saved, the stream carried RETRIEVAL_FAILED, and the late Stop answered stopped:true while the answer was saved complete.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aStopThatOvertakesItsDispatchStopsTheTurnWhenItStarts",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aStopForAFinishedTurnIsNotKeptForALaterDispatch",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aStoppedTurnIsSavedWithTheThinkingAndSourcesItShowed",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aStopWhileTheDocumentsAreRetrievedEndsTheStreamStoppedNotFailed",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#aStopAfterTheAnswerIsSavedChangesNothingAndSaysSo"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java"
      ],
      "class": "New",
      "behaviour": "TurnStops, the Engine's record of the turns a Stop can reach, and what a Stop answers: a Stop on a running turn cancels its work with user_stop at once and answers STOPPED; a Stop before the turn starts answers PENDING and cancels it when it starts, used once, only for that conversation and turn id, however long the turn takes to start (no age limit: a turn can wait minutes for the model or for capacity), and of more than MAX_EARLY_STOPS early Stops the newest are kept. The age limit is gone (backend review F1): an early Stop is used whenever its turn starts. On 117e89be9, with the compat before-patch's do-nothing TurnStops (no Stop reaches any turn, as no stop route existed), these fail by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#aStopOnARunningTurnCancelsItAtOnce",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#aStopBeforeTheTurnStartsCancelsItWhenItStarts",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#anEarlyStopIsUsedOnce",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#anEarlyStopNamesOnlyItsOwnTurn",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#onlyTheNewestEarlyStopsAreKept"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java"
      ],
      "class": "New",
      "behaviour": "TurnStops never claims a Stop it did not make and keeps nothing it should not: a Stop on work already cancelled for another reason answers UNCHANGED (backend review F5b); a Stop once the answer is being saved (completing) cancels nothing and answers UNCHANGED (F4/F5c); a Stop after the turn finished is not kept, so a later start of the same turn is not cancelled. These pass on 117e89be9 with the compat before-patch's do-nothing TurnStops, whose only answer is UNCHANGED. On the unrepaired branch 04941d29e the first failed by assertion (TurnStopsTest.java:122, the Stop said it stopped); the second needs completing, which did not exist there, and its HTTP counterpart aStopAfterTheAnswerIsSavedChangesNothingAndSaysSo answered {\"stopped\":true} for an answer saved complete.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#aStopOnWorkAlreadyCancelledForAnotherReasonDoesNotSayItStopped",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#aStopOnceTheAnswerIsBeingSavedChangesNothingAndSaysSo",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/TurnStopsTest.java#aStopAfterTheTurnFinishedIsNotKept"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts"
      ],
      "class": "New",
      "behaviour": "Search v3's Ask dispatches each turn with its own non-empty turnId, and the reader's Stop POSTs that turnId to the conversation's /stop route once; two turns carry different ids and a Stop on the second names the second. The turn is called stopped only on the Engine's own terminal (an error with errorCode CANCELLED), and keeps what streamed. Until then it says \"Stopping…\" (role=status) and keeps every delta and the rewrite note the Engine sends after the Stop, which is what the Engine saves (frontend F1/F3, backend F3/F7/F8). A Stop the Engine answers too late for shows the finished answer, not \"Stopped by you.\" (F4/F5c). A Stop refused (500) or unanswered says so and leaves Stop pressable while the answer goes on (F1). A question stopped while its dispatch waits for Engine capacity (429, retried) is still sent, naming the turn the Stop named, so the Engine saves it stopped (backend F6). After a stopped turn the window reads the saved record, as after a finished one, so the turn gets its store ids (F7). On 117e89be9 the dispatch carries no turnId, no stop request is sent and Stop halts the turn locally at once.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#asks the Engine to stop the very turn it dispatched, and halts it on screen",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#names each turn apart, so a Stop can only reach the turn it was pressed on",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#says \"Stopping…\" until the Engine ends the turn, and keeps every word it sent before that",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#shows the finished answer, not \"Stopped by you.\", when the Stop came after it was saved",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#says so when the Stop was refused, and lets the answer go on",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#says so when the Stop was unanswered, and lets the answer go on",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#still sends a question stopped while it waits for Engine capacity, so the Engine saves it stopped",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#reads the saved record after a stopped turn, as after a finished one"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserved: an answer that finished sends no stop request, and the window going away mid-answer (unmount) lets the stream go without telling the Engine to stop, so a closed tab keeps today's behaviour; Stop ends a streaming turn as halted (\"Stopped by you.\") with what streamed once the Engine ends it as stopped. Passes on 117e89be9.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#halts on Stop, keeps what streamed, and says the reader stopped it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#sends no stop for an answer that finished, nor when the window goes away mid-answer"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts"
      ],
      "class": "New",
      "behaviour": "Leaving a conversation mid-answer (New session) is not a Stop: the Engine is told nothing and finishes and saves the answer, so the window drops its unsaved partial copy of the turn instead of calling it \"Stopped by you.\", and back in the conversation the saved, complete answer shows (frontend F2). A late delta or evidence event from the stream it let go reaches neither the parked conversation nor the fresh one. On 117e89be9 the parked turn read as halted with its partial text.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts#does not call leaving mid-answer a Stop: back in the conversation, the saved answer shows",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts#appends NOTHING to the fresh thread when a late delta arrives"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts"
      ],
      "class": "New",
      "behaviour": "Starting a new session while an answer streams does not label it: back on the parked session no turn reads as stopped (\"halted\") or as forever streaming (frontend F2). On 117e89be9 the parked turn read halted.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts#does not call an answer left streaming \"stopped\" when the reader starts a new session"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserved: the reader's own Stop, now ended by the Engine with errorCode CANCELLED, never goes through the stream-failure wording: the turn is halted with no failure detail and keeps what streamed. Passes on 117e89be9, where Stop halted the turn locally.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts#never routes the reader's own Stop through that mapping"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts"
      ],
      "class": "New",
      "behaviour": "When an older context write's reload (Restore) lands after a newer write's reload (Reset the floor to a turn), the bar keeps the newer saved state: a reload overtaken by a newer read of the same conversation applies nothing and leaves the older write's confirmation to that read (frontend review F6). It passes on 117e89be9, which had no confirmation fallback; the race came with the branch's own S2.10 repair, and on the unrepaired branch 04941d29e it failed by assertion (the window floor was undefined, not the newer saved floor).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS10",
          "quote": "A failed or partially refused Include all / Restore does not announce success; a failed refresh, out-of-order responses or navigating away during the undo never leave the bar showing a state that is not the saved one.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.context.test.ts#keeps the newer saved state when an older write’s reload lands after a newer one"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts"
      ],
      "class": "New",
      "behaviour": "A turn saved after the last /history read (its ids are not in the saved message order) counts as newer than every listed id, so with the context floor on a message not shown here it stays in context rather than being dropped as if it were above the floor (frontend F8).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS0",
          "quote": "A follow-up Ask in Search v3 is answered in the light of the conversation, as in the current window: retrieval runs on the rewritten standalone question (a real second question produces the rewrite), and the answer can refer to the earlier turn. Messages excluded from context and messages before the context starting point are not sent with the follow-up; the context bar's numbers match what was sent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS8",
          "quote": "A context starting point (with or without a compaction summary) on a message that is not shown: the bar says the context starts at a message not shown here, the summary can still be viewed and edited, and Restore clears floor and summary in saved state; exclusions stay.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-v3-followups.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-context.test.ts#keeps a turn saved after the last /history read in context, below a floor not shown here"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "signature"
      ],
      "whatMoved": "SearchV3View.ask.test.ts stubStream(reply): the shared fetch stand-in answers POST /api/chat/dispatch with the stream, the Stop's POST with the given reply (default 200 {stopped: true, pending: false}; a status and body, or 'unreachable' to throw) and any other request with a plain 200 {}, and keeps capturing the abort signal from the dispatch alone. sv3-ask.test.ts: Sv3AskSink gained onLeft() (the window let the stream go), so both sink stand-ins add a no-op onLeft beside their no-op onHalted; every case's input and expected values are unchanged.",
      "sources": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FN3",
          "quote": "Streaming, stop, late stop (\"stopped by you\" after reload).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "FS3",
          "quote": "A rewrite followed by retrieval failure, generation failure or Stop: after reload the turn shows the same note it showed live (or none in both), never a mismatch.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions/*.md for stop, halt, stopped by you, cancel, abort, leaving, new session): none"
    }
  ]
}
```

## Acceptance records
