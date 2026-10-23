---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp1.3b-uiux-handoffs-builder
---

# Test-intent entries

These tests are durable. The earlier receiver-repair evidence overlays the current Explain sender and its selection type onto 12bb2beb3 so that its fail-before still reaches the original receiver refusal (zero dispatches), rather than stopping on the newly appended trace text. The new provenance checks fail on unmodified f05b44c2a in the manual shared clone and on unmodified 12bb2beb3 in the evidence runner. The evidence launcher adds only --cache=false and --maxWorkers=2 to Vitest through a temporary npx wrapper; all assertions and evidence classification use the repository runner unchanged. HO/HN scenarios and design decisions are agent-adopted choices under the quoted design delegation, as the agreed source specifies.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/composeHandoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Durable hand-off reception, source fidelity, isolation and refusal checks; repaired expectations must fail by assertion on 77587606f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO6 \u2014 agent-adopted scenario:\nS15.6 [HO6] (S3.14, ch. 5; broken today in both windows by code reading) Browse \"Ask about\" and Browse \"Summarize\" use the right-clicked file, with no other selection and with an unrelated selection active, in both windows, checked on the request sent and on the answer's source. Today compose() resolves the forced shape from the caller's absent affordance, so Ask becomes free chat and Summarize carries no document; both fall back to whatever is selected (D8).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO9 \u2014 agent-adopted scenario:\nS15.9 [HO9] (S3.17) One-shot hand-off data (forced shape, selection, auto-run flag) is used only by the window that shows the hand-off. It never reaches a later, unrelated send in either window, including after the user abandons the prepared task (switches mode or conversation, clears, navigates away).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN2 \u2014 agent-adopted scenario:\nHN2 While the old window is the main window it receives every hand-off as today, except the S15.6 repair and any shared Summarize change from D12.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-frontend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/composeHandoffs.test.ts#HO6 Browse core.ask carries the clicked document without an affordance",
        "modules/ui-web/src/shell-v0/utils/composeHandoffs.test.ts#HO6 Browse core.summarize carries the clicked document without an affordance",
        "modules/ui-web/src/shell-v0/utils/composeHandoffs.test.ts#HO9 a second compose without a selection replaces the first one"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Durable hand-off reception, source fidelity, isolation and refusal checks; repaired expectations must fail by assertion on 77587606f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO1 \u2014 agent-adopted scenario:\nS15.1 [HO1] (S3.10) Opening Search v3 with a query in its address shows that query in the composer in Search mode with its results, sending the same search requests as typing it (S1.3). `#`, `&`, `%`, quotes, non-Latin script and long text arrive intact. A blank query runs nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO2 \u2014 agent-adopted scenario:\nS15.2 [HO2] (S3.11) Reload and Back/Forward after a query arrived never run a question or an agent; the search re-runs only as decision D2 allows. Query text is not kept in saved navigation or per-tab state beyond what WP1.1 keeps.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO3 \u2014 agent-adopted scenario:\nS15.3 [HO3] (S3.12, ch. 15) Delivered to Search v3 through the real navigation path, every hand-off form today's in-app senders produce (query; docIds with ask or summarize; each selection kind; forced shape; answer verb) lands as S15.1-S15.13 require. Verified on the exact WP1.3b candidate. Each real sender (palette \"Search for\u2026\" and its fallback, \"Find related to selection\", the router's query verb, every compose() caller) end to end into Search v3 is verified on the WP1.5 cutover candidate (D1).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO4 \u2014 agent-adopted scenario:\nS15.4 [HO4] (S3.13) \"Ask about this\" from Browse, a search hit, a result set, a citation, a text selection or a plugin prepares an Ask limited to exactly the chosen files (S1.6/S1.7: fixed when asked, never widened; proved with an unchosen file holding a unique answer). For a text selection or citation the answer is about that passage. Suggested wording lands in the composer and the window waits for the user's send (D4).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO5 \u2014 agent-adopted scenario:\nS15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO6 \u2014 agent-adopted scenario:\nS15.6 [HO6] (S3.14, ch. 5; broken today in both windows by code reading) Browse \"Ask about\" and Browse \"Summarize\" use the right-clicked file, with no other selection and with an unrelated selection active, in both windows, checked on the request sent and on the answer's source. Today compose() resolves the forced shape from the caller's absent affordance, so Ask becomes free chat and Summarize carries no document; both fall back to whatever is selected (D8).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO7 \u2014 agent-adopted scenario:\nS15.7 [HO7] (S3.15) Retry and Edit of a Summarize turn or a file-limited turn re-run the same kind on the same document, or say visibly what they will do instead; they never silently become an Ask over all files, live or after reload.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO8 \u2014 agent-adopted scenario:\nS15.8 [HO8] (S3.16, ch. 11) A hand-off arriving while a conversation is open, streaming, running an agent, waiting for a decision or locked does not append to, steer, approve or stop it, and it stays one click away (D5). The unsent draft and chosen files of the place the user was in are kept with that place. Conflicting incoming state (query, conversationId, mode, forced shape, a restored pointer or draft) resolves by one rule (D5) to one visible task. A hand-off arriving before Search v3 has opened (cold start, first navigation) is applied exactly once; two in quick succession leave the later one prepared and nothing runs twice; an older asynchronous result (search response, record load) never overwrites a later hand-off.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO9 \u2014 agent-adopted scenario:\nS15.9 [HO9] (S3.17) One-shot hand-off data (forced shape, selection, auto-run flag) is used only by the window that shows the hand-off. It never reaches a later, unrelated send in either window, including after the user abandons the prepared task (switches mode or conversation, clears, navigates away).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO10 \u2014 agent-adopted scenario:\nS15.10 [HO10] (S3.18) With chat unavailable, an Ask, Summarize or Structured hand-off still lands in Search v3 with the existing unavailable reason; prepared text and files are kept; never a silent no-op. Search hand-offs work fully.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO11 \u2014 agent-adopted scenario:\nS15.11 [HO11] (S3.19, ch. 14) An answer link (`justsearch://answer?q=\u2026&shape=\u2026`, or the router's answer verb) prepares an Ask, or a Summarize or Structured run when the link names one, and behaves on arrival as decision D6. No link starts a Delegate or agent run, whatever extra state (shape, affordance, conversationId) it carries; an unknown shape is refused visibly or falls back visibly to Ask.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO12 \u2014 agent-adopted scenario:\nS15.12 [HO12] (S3.20) After any hand-off, keyboard focus is in the composer; what was prepared (mode, files or document) is visible and announced; holds in both themes, forced colours and at narrow width; the palette path works by keyboard alone.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO13 \u2014 agent-adopted scenario:\nS15.13 [HO13] (S3.21) Bad hand-off data (no files, a deleted or missing file, an unknown mode or shape) never becomes a question over the whole index or a crash: the window says what is wrong and opens normally. A link that cannot be parsed runs nothing and changes nothing in the current place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN1 \u2014 agent-adopted scenario:\nHN1 Search v3 conversation links (`?conversationId=`), Back/Forward between conversations and results, per-tab restore (the address outranks the pointer, SearchV3View restoreLastViewed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN3 \u2014 agent-adopted scenario:\nHN3 WP1.1 S1.x and WP1.2 S2.x on the merged baseline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN4 \u2014 agent-adopted scenario:\nHN4 Ask and Delegate streaming, stop and late stop, sidebar, context bar, J/K.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-frontend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO1 HO2 a cold address query arrives intact and is consumed from the address",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO3 HO4 HO5 core.rag-ask prepares the chosen files and waits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO3 HO4 HO5 core.summarize prepares the chosen files and waits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO3 HO4 HO5 core.extract prepares the chosen files and waits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO3 HO5 HO9 a selection is local to its prepared turn",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO8 two arrivals replace preparation without sending",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO8 an incoming query replaces typed Search without creating a conversation place",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO8 a cold hand-off keeps the restored draft with its previous conversation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO11 an answer link ignores agent affordance and conversation and waits",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO13 an empty file hand-off cannot widen to the whole index",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO7 a reloaded summary Retry menu names the original summary inputs",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO7 a reloaded summary retry states its missing inputs and never sends an all-files Ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO7 a reloaded summary edit states its missing inputs and never sends an all-files Ask",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO8 an outgoing conversation keeps its unsent draft and files one click away",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO9 abandoning a prepared selection cannot attach it to another mode send",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO9 abandoning a hand-off for an unvisited conversation clears its file scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO9 leaving the window retires prepared context while a layout reparent keeps it",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO10 unavailable chat still shows the prepared task and chosen files",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO12 an incoming task puts keyboard focus in the composer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO13 malformed selection data is refused visibly without a crash",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO8 a streaming Ask stays live and keeps its draft and files across a hand-off",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO8 a held real controller keeps its decision and reopens from the sidebar",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO8 a locked conversation is left intact by incoming preparation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO13 a refused unreadable-document hand-off keeps the instruction and files",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO3 D7 a Structured hand-off with no files uses typed text and waits for send"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve promptless summaries, unreadable Ask refusal, Engine admission refusals and keyboard palette behaviour.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO1 \u2014 agent-adopted scenario:\nS15.1 [HO1] (S3.10) Opening Search v3 with a query in its address shows that query in the composer in Search mode with its results, sending the same search requests as typing it (S1.3). `#`, `&`, `%`, quotes, non-Latin script and long text arrive intact. A blank query runs nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO2 \u2014 agent-adopted scenario:\nS15.2 [HO2] (S3.11) Reload and Back/Forward after a query arrived never run a question or an agent; the search re-runs only as decision D2 allows. Query text is not kept in saved navigation or per-tab state beyond what WP1.1 keeps.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO3 \u2014 agent-adopted scenario:\nS15.3 [HO3] (S3.12, ch. 15) Delivered to Search v3 through the real navigation path, every hand-off form today's in-app senders produce (query; docIds with ask or summarize; each selection kind; forced shape; answer verb) lands as S15.1-S15.13 require. Verified on the exact WP1.3b candidate. Each real sender (palette \"Search for\u2026\" and its fallback, \"Find related to selection\", the router's query verb, every compose() caller) end to end into Search v3 is verified on the WP1.5 cutover candidate (D1).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO4 \u2014 agent-adopted scenario:\nS15.4 [HO4] (S3.13) \"Ask about this\" from Browse, a search hit, a result set, a citation, a text selection or a plugin prepares an Ask limited to exactly the chosen files (S1.6/S1.7: fixed when asked, never widened; proved with an unchosen file holding a unique answer). For a text selection or citation the answer is about that passage. Suggested wording lands in the composer and the window waits for the user's send (D4).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO5 \u2014 agent-adopted scenario:\nS15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO6 \u2014 agent-adopted scenario:\nS15.6 [HO6] (S3.14, ch. 5; broken today in both windows by code reading) Browse \"Ask about\" and Browse \"Summarize\" use the right-clicked file, with no other selection and with an unrelated selection active, in both windows, checked on the request sent and on the answer's source. Today compose() resolves the forced shape from the caller's absent affordance, so Ask becomes free chat and Summarize carries no document; both fall back to whatever is selected (D8).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO7 \u2014 agent-adopted scenario:\nS15.7 [HO7] (S3.15) Retry and Edit of a Summarize turn or a file-limited turn re-run the same kind on the same document, or say visibly what they will do instead; they never silently become an Ask over all files, live or after reload.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO8 \u2014 agent-adopted scenario:\nS15.8 [HO8] (S3.16, ch. 11) A hand-off arriving while a conversation is open, streaming, running an agent, waiting for a decision or locked does not append to, steer, approve or stop it, and it stays one click away (D5). The unsent draft and chosen files of the place the user was in are kept with that place. Conflicting incoming state (query, conversationId, mode, forced shape, a restored pointer or draft) resolves by one rule (D5) to one visible task. A hand-off arriving before Search v3 has opened (cold start, first navigation) is applied exactly once; two in quick succession leave the later one prepared and nothing runs twice; an older asynchronous result (search response, record load) never overwrites a later hand-off.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO9 \u2014 agent-adopted scenario:\nS15.9 [HO9] (S3.17) One-shot hand-off data (forced shape, selection, auto-run flag) is used only by the window that shows the hand-off. It never reaches a later, unrelated send in either window, including after the user abandons the prepared task (switches mode or conversation, clears, navigates away).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO10 \u2014 agent-adopted scenario:\nS15.10 [HO10] (S3.18) With chat unavailable, an Ask, Summarize or Structured hand-off still lands in Search v3 with the existing unavailable reason; prepared text and files are kept; never a silent no-op. Search hand-offs work fully.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO11 \u2014 agent-adopted scenario:\nS15.11 [HO11] (S3.19, ch. 14) An answer link (`justsearch://answer?q=\u2026&shape=\u2026`, or the router's answer verb) prepares an Ask, or a Summarize or Structured run when the link names one, and behaves on arrival as decision D6. No link starts a Delegate or agent run, whatever extra state (shape, affordance, conversationId) it carries; an unknown shape is refused visibly or falls back visibly to Ask.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO12 \u2014 agent-adopted scenario:\nS15.12 [HO12] (S3.20) After any hand-off, keyboard focus is in the composer; what was prepared (mode, files or document) is visible and announced; holds in both themes, forced colours and at narrow width; the palette path works by keyboard alone.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO13 \u2014 agent-adopted scenario:\nS15.13 [HO13] (S3.21) Bad hand-off data (no files, a deleted or missing file, an unknown mode or shape) never becomes a question over the whole index or a crash: the window says what is wrong and opens normally. A link that cannot be parsed runs nothing and changes nothing in the current place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN1 \u2014 agent-adopted scenario:\nHN1 Search v3 conversation links (`?conversationId=`), Back/Forward between conversations and results, per-tab restore (the address outranks the pointer, SearchV3View restoreLastViewed).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN3 \u2014 agent-adopted scenario:\nHN3 WP1.1 S1.x and WP1.2 S2.x on the merged baseline.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN4 \u2014 agent-adopted scenario:\nHN4 Ask and Delegate streaming, stop and late stop, sidebar, context bar, J/K.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-frontend-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO12 keyboard palette search after a hand-off returns focus to the composer"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "New",
      "behaviour": "Durable hand-off reception, source fidelity, isolation and refusal checks; repaired expectations must fail by assertion on 77587606f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO4 \u2014 agent-adopted scenario:\nS15.4 [HO4] (S3.13) \"Ask about this\" from Browse, a search hit, a result set, a citation, a text selection or a plugin prepares an Ask limited to exactly the chosen files (S1.6/S1.7: fixed when asked, never widened; proved with an unchosen file holding a unique answer). For a text selection or citation the answer is about that passage. Suggested wording lands in the composer and the window waits for the user's send (D4).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO5 \u2014 agent-adopted scenario:\nS15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO13 \u2014 agent-adopted scenario:\nS15.13 [HO13] (S3.21) Bad hand-off data (no files, a deleted or missing file, an unknown mode or shape) never becomes a question over the whole index or a crash: the window says what is wrong and opens normally. A link that cannot be parsed runs nothing and changes nothing in the current place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN5 \u2014 agent-adopted scenario:\nHN5 Recorded summarize and file-limited ask turns parse for /history, /api/thread, MCP and API; Summarize citations, including short documents (WP4.4), as today.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#ordinaryDocumentFailuresRetainSummaryFallbacks",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#summarySourcesSurviveReloadForDocumentsAndSelections"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java"
      ],
      "class": "New",
      "behaviour": "Durable hand-off reception, source fidelity, isolation and refusal checks; repaired expectations must fail by assertion on 77587606f.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO4 \u2014 agent-adopted scenario:\nS15.4 [HO4] (S3.13) \"Ask about this\" from Browse, a search hit, a result set, a citation, a text selection or a plugin prepares an Ask limited to exactly the chosen files (S1.6/S1.7: fixed when asked, never widened; proved with an unchosen file holding a unique answer). For a text selection or citation the answer is about that passage. Suggested wording lands in the composer and the window waits for the user's send (D4).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO5 \u2014 agent-adopted scenario:\nS15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO13 \u2014 agent-adopted scenario:\nS15.13 [HO13] (S3.21) Bad hand-off data (no files, a deleted or missing file, an unknown mode or shape) never becomes a question over the whole index or a crash: the window says what is wrong and opens normally. A link that cannot be parsed runs nothing and changes nothing in the current place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN5 \u2014 agent-adopted scenario:\nHN5 Recorded summarize and file-limited ask turns parse for /history, /api/thread, MCP and API; Summarize citations, including short documents (WP4.4), as today.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#unreadableDocumentIsRefused",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#editedInstructionReachesModel",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#summarizeRefusesUnreadableMembers",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#summarizeDeduplicatesDocuments",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#summarizeRefusesExcessDocuments",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#summarizeRefusesPerDocumentTruncationBelowTotalLimit",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#summarizeRefusesSelectionWindowTruncation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#summarizeRefusesLegacyCharacterTruncation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#ragAskDeclaresSelectionBeforeRetrieval",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#ragAskUsesSelectedPassageWithoutSearchingOtherDocuments"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve promptless summaries, unreadable Ask refusal, Engine admission refusals and keyboard palette behaviour.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO4 \u2014 agent-adopted scenario:\nS15.4 [HO4] (S3.13) \"Ask about this\" from Browse, a search hit, a result set, a citation, a text selection or a plugin prepares an Ask limited to exactly the chosen files (S1.6/S1.7: fixed when asked, never widened; proved with an unchosen file holding a unique answer). For a text selection or citation the answer is about that passage. Suggested wording lands in the composer and the window waits for the user's send (D4).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO5 \u2014 agent-adopted scenario:\nS15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO13 \u2014 agent-adopted scenario:\nS15.13 [HO13] (S3.21) Bad hand-off data (no files, a deleted or missing file, an unknown mode or shape) never becomes a question over the whole index or a crash: the window says what is wrong and opens normally. A link that cannot be parsed runs nothing and changes nothing in the current place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN5 \u2014 agent-adopted scenario:\nHN5 Recorded summarize and file-limited ask turns parse for /history, /api/thread, MCP and API; Summarize citations, including short documents (WP4.4), as today.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#documentInjectorRefusalsTerminateRealSummaryDispatch"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve promptless summaries, unreadable Ask refusal, Engine admission refusals and keyboard palette behaviour.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO4 \u2014 agent-adopted scenario:\nS15.4 [HO4] (S3.13) \"Ask about this\" from Browse, a search hit, a result set, a citation, a text selection or a plugin prepares an Ask limited to exactly the chosen files (S1.6/S1.7: fixed when asked, never widened; proved with an unchosen file holding a unique answer). For a text selection or citation the answer is about that passage. Suggested wording lands in the composer and the window waits for the user's send (D4).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO5 \u2014 agent-adopted scenario:\nS15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO13 \u2014 agent-adopted scenario:\nS15.13 [HO13] (S3.21) Bad hand-off data (no files, a deleted or missing file, an unknown mode or shape) never becomes a question over the whole index or a crash: the window says what is wrong and opens normally. A link that cannot be parsed runs nothing and changes nothing in the current place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HN5 \u2014 agent-adopted scenario:\nHN5 Recorded summarize and file-limited ask turns parse for /history, /api/thread, MCP and API; Summarize citations, including short documents (WP4.4), as today.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#promptlessContentKeepsDefaultTask",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#promptlessDocumentKeepsDefaultTaskAndCitation",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#unreadableAskSelectionIsRefused"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Register the optional UserPromptInjector in the real summary-engine test fixture. All admission-refusal assertions and inline-content/citation fallback assertions remain. The result-set refusal expectation is separately sourced and evidenced above.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b hand-off designs and scenarios, agent-adopted under the design delegation; agreed source tmp/verify/agreed/wp1.3b.md.",
          "choice": "HO5 \u2014 agent-adopted scenario:\nS15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX agreed designs and WP1.3b hand-offs, including pass-2 amendments and HO8 clarification.",
          "choice": "Out of scope: the flip and old-URL aliases, real-sender end-to-end checks (WP1.5), global shortcut and launcher (WP2.2), filters (WP2.3), recents (WP2.4), workflow-run mode, an in-app Delegate hand-off (no in-app sender produces one; see rejected part of challenger 14).\n3. **D3 Structured Retry/Edit after reload.** Options: (a) save schema and source identity with the turn (new saved field, own scenarios); (b) no saved field: after reload Retry/Edit open the instruction in Structured with the box's schema and stated inputs and wait for the user's send; (c) hide Retry/Edit after reload. Recommend (b). Why: no new saved state, and no one-click silent substitution (meets challenger 10's \"explicit replacement\").\n4. **D3b Schema text on reload.** Options: (a) memory only per tab, lost on reload; (b) saved like the draft (localStorage, shared by tabs). Recommend (a). Why: parity with the old window (schemaDraft is memory only) and no cross-tab state; the draft's premise \"per tab like the draft\" was wrong.\n8. **D7 Structured with no files.** Recommend allow, stating it extracts from the typed text and this conversation. Why: real use (extract from an earlier answer); ExtractShape injects context for it.\n11. **D10 Where Structured output is validated.** Options: Engine (ValidationConsumer parses and validates with com.networknt json-schema-validator, already a dependency of app-services) or client-side check only. Recommend Engine. Why: both windows and API readers get it; failed validation drives the existing retry.\n12. **D11 Inputs the extraction cannot fully read.** Options: (a) refuse: block more files than the limit before send, fail the turn naming unreadable files, reject over-limit input as Summarize already does (summaryLimitRejection); (b) send and disclose omissions with the turn (new saved key). Recommend (a). Why: no new saved state; an omitted file cannot silently change the task.\n- P2-3 (amends D12): \"Edited summary instructions reach the model. Existing document-only and content-only Summarize requests remain valid and retain their default summary task.\" Adding the user-prompt injector as written would terminate promptless requests (spi/UserPromptInjector.java:45-54; views/SummarizeView.ts:360-368 sends only {docId}); the instruction must be optional.\n- P2-4 (amends D11, S15.5): \"For Extract and Summarize, exercise unreadable members, duplicate documents, excess files and every applicable truncation threshold - including a single document that fits the total budget but exceeds the per-document allocation. Refuse or visibly disclose omitted material; never claim the complete selected input was used.\" (spi/SelectionContextInjector.java:390-419, 523-526; spi/ExternalContextInjector.java:94-121.) Extract's side shipped in WP1.3a; this package owns the Summarize side for hand-offs.\n- P2-6 (decides S15.11/HN2/D6): while the old window is the target of answer links, they keep today's auto-run until WP1.5 (no old-window edits); prepare-and-wait applies when Search v3 is the destination. HN2 stands; verify on the exact candidate.\nHO8 clarification: incoming queries replace shared Search results; conversation hand-offs open a separate place while existing work continues and original drafts/files return.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Exact shared Explain sender accepts its valid ranking trace despite docIds:[]; a forced mode that ignores chosen files visibly becomes an Ask over exactly those files.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO3/HO5 and HO4/HO13, including independent-verifier follow-up decisions.",
          "choice": "HO3/HO5: the exact whyThisResult.ts sender payload, including docIds:[], must dispatch its valid search-trace selection as Summarize and survive reload.\nHO4/HO13 orchestrator decision: when chosen files arrive with a forced shape that cannot read files, prepare a file-limited Ask over exactly those files and say visibly that the requested mode was replaced; never show a file limit on a send that ignores it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: the Explain turn lists the result it explains as its source, opening it the same way other sources do; the ranking trace the model was given stays inspectable with the turn. Reuse existing summarize turn, sources disclosure and record fields. If the result has no document id, disclose a trace-only explanation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-followup.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO3 HO5 the real Explain sender with empty docIds sends the ranking trace",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO4 HO13 a file-incompatible forced mode becomes a visibly file-limited Ask"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/whyThisResult.handoff.test.ts"
      ],
      "class": "New",
      "behaviour": "Explain carries the hit identity and exact ranking trace in its prepared user text; a missing identity is visibly trace-only. A known document path wins over a chunk identity; a bare chunk identity has no document link.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: the Explain turn lists the result it explains as its source, opening it the same way other sources do; the ranking trace the model was given stays inspectable with the turn. Reuse existing summarize turn, sources disclosure and record fields. If the result has no document id, disclose a trace-only explanation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-trace-frontend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/searchResults/whyThisResult.handoff.test.ts#HO5 Explain carries the hit document as trace provenance",
        "modules/ui-web/src/shell-v0/components/searchResults/whyThisResult.handoff.test.ts#HO5 Explain keeps the exact model trace in the prepared text",
        "modules/ui-web/src/shell-v0/components/searchResults/whyThisResult.handoff.test.ts#HO5 Explain without a document identity visibly keeps a trace-only record",
        "modules/ui-web/src/shell-v0/components/searchResults/whyThisResult.handoff.test.ts#HO5 Explain links the document path when the result identity is a chunk",
        "modules/ui-web/src/shell-v0/components/searchResults/whyThisResult.handoff.test.ts#HO5 an unmerged chunk without a document path stays visibly trace-only"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Edited Explain instructions retain the exact model trace in the submitted user turn and dispatch body.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: the Explain turn lists the result it explains as its source, opening it the same way other sources do; the ranking trace the model was given stays inspectable with the turn. Reuse existing summarize turn, sources disclosure and record fields. If the result has no document id, disclose a trace-only explanation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-trace-edits.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 editing Explain instructions keeps its model trace inspectable in the submitted turn"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java"
      ],
      "class": "New",
      "behaviour": "Explain emits its hit document as a live source without fetching document content or inventing a quotation or chunk; the same source and prepared trace text survive reopening the conversation store.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: the Explain turn lists the result it explains as its source, opening it the same way other sources do; the ranking trace the model was given stays inspectable with the turn. Reuse existing summarize turn, sources disclosure and record fields. If the result has no document id, disclose a trace-only explanation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-trace-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#explainTraceSourceAndPreparedTextSurviveStoreReopen"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.imports.test.ts"
      ],
      "class": "New",
      "behaviour": "Source previews on document-content summaries remain readable in all existing card states and themes. Explain subject cards have no excerpt preview.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: the Explain turn lists the result it explains as its source, opening it the same way other sources do; the ranking trace the model was given stays inspectable with the turn. Reuse existing summarize turn, sources disclosure and record fields. If the result has no document id, disclose a trace-only explanation.\nS15.12 [HO12] (S3.20) After any hand-off, keyboard focus is in the composer; what was prepared (mode, files or document) is visible and announced; holds in both themes, forced colours and at narrow width; the palette path works by keyboard alone.\nAccessibility choice: document-content source preview text meets WCAG AA 4.5:1 on resting, hovered, selected and selected-hovered cards in both themes.\nFollow-up: Explain now names the subject without an excerpt preview; this contrast check preserves other turns' document previews.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-trace-contrast.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.imports.test.ts#HO5 source preview text clears AA in both themes and every card state"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts"
      ],
      "class": "New",
      "behaviour": "Explain names the ranking trace as its basis in the existing answer frame, presents the linked result as the subject without an empty excerpt, and omits the cited/retrieved header when opening it. Other document summaries and citations keep their existing presentation. This presentation uses the saved prepared-text Ranking trace context label and existing citations; no new saved field or component.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: Explain names the ranking trace as its basis in the existing answer frame, presents the linked result as the subject without an empty excerpt, and omits the cited/retrieved header when opening it. Other document summaries and citations keep their existing presentation. This presentation uses the saved prepared-text Ranking trace context label and existing citations; no new saved field or component. The subject disclosure also omits the ordinary sentence-grounding legend and names only the actual panel in aria-controls; ordinary sources retain their legend.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-trace-honesty.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts#HO5 Explain frame names its ranking trace rather than document content",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts#HO5 Explain sources name the subject without an empty excerpt",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts#HO5 Explain subject pane makes no cited or retrieved claim",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts#HO5 Explain subject names a Windows result without exposing its full path as the name",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts#HO5 Explain subject disclosure omits sentence-grounding instructions"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts"
      ],
      "class": "New",
      "behaviour": "Explain names the ranking trace as its basis in the existing answer frame, presents the linked result as the subject without an empty excerpt, and omits the cited/retrieved header when opening it. Other document summaries and citations keep their existing presentation. This presentation uses the saved prepared-text Ranking trace context label and existing citations; no new saved field or component.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: Explain names the ranking trace as its basis in the existing answer frame, presents the linked result as the subject without an empty excerpt, and omits the cited/retrieved header when opening it. Other document summaries and citations keep their existing presentation. This presentation uses the saved prepared-text Ranking trace context label and existing citations; no new saved field or component.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: name the action Explain in its scope, Edit, Retry-menu and refusal guidance. State only the observed restoration limit: live Retry/Edit reuse the original trace selection and docId; after reload they preserve the question but cannot restore that selection, so sending is refused and the user must start Explain from the result again. Real Summarize keeps its current line and behavior. This is a wording repair, not a Retry/Edit behavior or record change.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-trace-honesty-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts#HO5 document summaries and ordinary citations keep their existing presentation"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "name the action Explain in its scope, Edit, Retry-menu and refusal guidance. State only the observed restoration limit: live Retry/Edit reuse the original trace selection and docId; after reload they preserve the question but cannot restore that selection, so sending is refused and the user must start Explain from the result again. Real Summarize keeps its current line and behavior. This is a wording repair, not a Retry/Edit behavior or record change.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP1.3b HO5, including the orchestrator continuation requiring the Explain hit source and inspectable trace live, after cold reload and after Engine restart.",
          "choice": "S15.5 [HO5] (S3.14, ch. 12) \"Summarize\" from Browse, a search hit, a result set or a text selection, and \"Explain why this result\", produce after the user's send a summary of exactly that document, passage or ranking trace, as a turn in Search v3, the same live and after reload, with its sources (WP4.4 short-document behaviour). An instruction the composer lets the user edit actually reaches the model; otherwise the task is presented as fixed (D12).\nContinuation choice: name the action Explain in its scope, Edit, Retry-menu and refusal guidance. State only the observed restoration limit: live Retry/Edit reuse the original trace selection and docId; after reload they preserve the question but cannot restore that selection, so sending is refused and the user must start Explain from the result again. Real Summarize keeps its current line and behavior. This is a wording repair, not a Retry/Edit behavior or record change.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-explain-actions.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.explainHonesty.test.ts#HO5 Explain guidance names Explain and only its observed reload limitation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 a reloaded Explain Retry menu states its actual restoration limit",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 a reloaded Explain retry keeps its trace and states the selection restoration limit",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 a reloaded Explain edit keeps its trace and states the selection restoration limit"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
