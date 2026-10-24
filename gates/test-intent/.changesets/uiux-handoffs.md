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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.mergeHandoff.test.ts"
      ],
      "class": "New",
      "behaviour": "Durable model-action/hand-off merge integration and preservation checks. The controller combination fails by assertion in the provisional merge (before-tests.log); the transport check also fails on 1573b367b.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement and merge continuation",
          "delegationScope": "WP1.3b hand-offs merged with main model-answer action and no-evidence notice.",
          "choice": "An explicit Answer without files action takes precedence over pending hand-off inputs: use core.free-chat with no file scope, selection, schema or context. Normal Send preserves the forced shape and exact selection. A failed Summarize input does not mean no indexed files matched.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-merge-model.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.mergeHandoff.test.ts#merge model action overrides forced hand-off shape and omits its inputs"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.mergeHandoff.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Durable model-action/hand-off merge integration and preservation checks. The controller combination fails by assertion in the provisional merge (before-tests.log); the transport check also fails on 1573b367b.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement and merge continuation",
          "delegationScope": "WP1.3b hand-offs merged with main model-answer action and no-evidence notice.",
          "choice": "An explicit Answer without files action takes precedence over pending hand-off inputs: use core.free-chat with no file scope, selection, schema or context. Normal Send preserves the forced shape and exact selection. A failed Summarize input does not mean no indexed files matched.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-merge-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.mergeHandoff.test.ts#merge ordinary hand-off keeps its forced shape and exact selection",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#merge model action after a no-match hand-off preserves the prepared draft and excludes its selection",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#merge an unscoped Summarize input failure is not a no-matching-files result"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Durable selection-owned Explain identity live, recorded and without a document; text cannot forge or remove provenance.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement and selection-trust continuation",
          "delegationScope": "WP1.3b hand-off framing and HO13/P2-4a repair, agent-adopted requirements under the design delegation.",
          "choice": "Requirement: whether an answer is framed as a ranking explanation follows the request's selection (kind search-trace), never the composer wording. The Explain frame, the subject card and the caption must survive any edit of the text and a reload. Nothing may claim document grounding for a trace-only request.\nRequirement: a file hand-off whose file no longer exists on disk at send time refuses visibly, keeps the prepared text, and dispatches nothing that reads the file's indexed content. Do the least that holds: check existence where the hand-off's file selection is resolved, if that is one place. Do not change index retention or search results; say if the only fix would.\nRe-run all HO and HN checks for preservation, plus Explain edited in other ways: emptied, shortened, a different question.\nChecks use their own temporary resources: never use another session's running service or write into a dependency tree other checkouts use.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-selection-frontend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 selection identity survives edited text and record reload: 'inline trace'",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 selection identity survives edited text and record reload: 'shortened'",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 selection identity survives edited text and record reload: 'different question'",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 selection identity survives edited text and record reload: 'empty record question'",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 a document summary cannot acquire Explain framing by editing its question",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 legacy trace subject remains Explain without consulting editable words",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 a trace with no document identity remains trace-only after reload",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 edited Explain keeps selection identity before send: 'inline trace'",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 edited Explain keeps selection identity before send: 'shortened'",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 edited Explain keeps selection identity before send: 'different question'"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts"
      ],
      "class": "New",
      "behaviour": "Durable preservation: emptying a prepared Explain never dispatches and retains its selection. Untyped, source-free legacy turns do not acquire a document-search claim when removing text-derived identity.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement and selection-trust continuation",
          "delegationScope": "WP1.3b hand-off framing and HO13/P2-4a repair, agent-adopted requirements under the design delegation.",
          "choice": "Requirement: whether an answer is framed as a ranking explanation follows the request's selection (kind search-trace), never the composer wording. The Explain frame, the subject card and the caption must survive any edit of the text and a reload. Nothing may claim document grounding for a trace-only request.\nRequirement: a file hand-off whose file no longer exists on disk at send time refuses visibly, keeps the prepared text, and dispatches nothing that reads the file's indexed content. Do the least that holds: check existence where the hand-off's file selection is resolved, if that is one place. Do not change index retention or search results; say if the only fix would.\nRe-run all HO and HN checks for preservation, plus Explain edited in other ways: emptied, shortened, a different question.\nChecks use their own temporary resources: never use another session's running service or write into a dependency tree other checkouts use.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-selection-frontend-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#HO5 edited Explain keeps selection identity before send: 'emptied'",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selectionTrust.test.ts#HO5 untyped legacy traces without a subject never acquire a document-search claim"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java"
      ],
      "class": "New",
      "behaviour": "Durable request identity persistence and read-time projection, and refusal before any index-content read for deleted selected files.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement and selection-trust continuation",
          "delegationScope": "WP1.3b hand-off framing and HO13/P2-4a repair, agent-adopted requirements under the design delegation.",
          "choice": "Requirement: whether an answer is framed as a ranking explanation follows the request's selection (kind search-trace), never the composer wording. The Explain frame, the subject card and the caption must survive any edit of the text and a reload. Nothing may claim document grounding for a trace-only request.\nRequirement: a file hand-off whose file no longer exists on disk at send time refuses visibly, keeps the prepared text, and dispatches nothing that reads the file's indexed content. Do the least that holds: check existence where the hand-off's file selection is resolved, if that is one place. Do not change index retention or search results; say if the only fix would.\nRe-run all HO and HN checks for preservation, plus Explain edited in other ways: emptied, shortened, a different question.\nChecks use their own temporary resources: never use another session's running service or write into a dependency tree other checkouts use.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-selection-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/SubstrateDrivenEngineTest.java#explainTraceSourceAndPreparedTextSurviveStoreReopen",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#deletedIndexedFileIsRefusedBeforeFetching",
        "modules/ui/src/test/java/io/justsearch/ui/api/InteractionThreadControllerTest.java#recordedSelectionIdentityReachesTheThreadResponse"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java"
      ],
      "class": "New",
      "behaviour": "Durable preservation: existing local files still resolve for Ask and Summarize.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement and selection-trust continuation",
          "delegationScope": "WP1.3b hand-off framing and HO13/P2-4a repair, agent-adopted requirements under the design delegation.",
          "choice": "Requirement: whether an answer is framed as a ranking explanation follows the request's selection (kind search-trace), never the composer wording. The Explain frame, the subject card and the caption must survive any edit of the text and a reload. Nothing may claim document grounding for a trace-only request.\nRequirement: a file hand-off whose file no longer exists on disk at send time refuses visibly, keeps the prepared text, and dispatches nothing that reads the file's indexed content. Do the least that holds: check existence where the hand-off's file selection is resolved, if that is one place. Do not change index retention or search results; say if the only fix would.\nRe-run all HO and HN checks for preservation, plus Explain edited in other ways: emptied, shortened, a different question.\nChecks use their own temporary resources: never use another session's running service or write into a dependency tree other checkouts use.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-selection-java-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SummarizeHandoffTest.java#presentIndexedFileStillResolves"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorLiteralTextTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: replace fictional absolute file paths with real per-test @TempDir files, preserving the content, literal verification, budget and citation oracles. The missing-file fixture remains absent.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement and selection-trust continuation",
          "delegationScope": "WP1.3b hand-off framing and HO13/P2-4a repair, agent-adopted requirements under the design delegation.",
          "choice": "Requirement: whether an answer is framed as a ranking explanation follows the request's selection (kind search-trace), never the composer wording. The Explain frame, the subject card and the caption must survive any edit of the text and a reload. Nothing may claim document grounding for a trace-only request.\nRequirement: a file hand-off whose file no longer exists on disk at send time refuses visibly, keeps the prepared text, and dispatches nothing that reads the file's indexed content. Do the least that holds: check existence where the hand-off's file selection is resolved, if that is one place. Do not change index retention or search results; say if the only fix would.\nRe-run all HO and HN checks for preservation, plus Explain edited in other ways: emptied, shortened, a different question.\nChecks use their own temporary resources: never use another session's running service or write into a dependency tree other checkouts use.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-handoffs-selection-fixture-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/SelectionContextInjectorLiteralTextTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/NoEvidenceDispatchHttpTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/NoEvidencePersistenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ModelStructuredPersistenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Register the real SelectionContextInjector (core.selection) in the three private fixture registries that dispatch core.rag-ask or core.extract, using each fixture's DocumentService and AI supplier. NoEvidenceDispatchHttpTest uses the production RAGAskShape injector list, which now requires this registration. The two persistence fixtures retain their deliberate synthetic shape injector lists and terminal fault paths. All request inputs, preconditions, fault injection, observations, assertions, expected outcomes, tags and conditions remain unchanged. No production behavior or expectation changes."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.backgroundChecking.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Merge #938 checking-status coverage with WP1.3b per-conversation live Ask ownership. The new integration case uses the existing SSE, navigation and sidebar interfaces: checking starts while its conversation is in the background, appears and is announced once on return, never leaks to the separate prepared hand-off, is not reannounced after another round trip, and clears when the citation result arrives in the background. Existing checking outcome, announcement and hand-off expectations are unchanged. Before the per-conversation merge repair, this case assertion-fails because the checking status is absent on return; after it passes."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-5-4scz2v7l",
  "verdict": "accept",
  "digest": "sha256:f14f5c8c424885c85726685ee43f0ca972daacdbe9269a51d2b438971b9c9227",
  "note": "Reviewed agreed delegated-choice scenarios and before-state tests before production diff. Default-link scratch evidence independently executed: 64 frontend and 22 JVM expectations pass; 10 frontend and 3 JVM repair checks assertion-fail on f25592889 and pass this candidate. Real CPU-model/Shell/Chromium checks reproduce edited-trace misframing and deleted-file disclosure before and verify repaired behavior after. Temporary readable-file fixtures preserve prior literal-content, citation and budget oracles. Latest selection-kind requirement supersedes historical text-derived/no-new-field wording. Acceptance concerns test intent; pre-existing runtime and exact-main rendering differences are reported separately."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "52nqoepx",
  "verdict": "accept",
  "digest": "sha256:fe00fa2ccf3e9b01303327a706fb96c6d378ca6418c38f72d7a009329a33cd0a",
  "note": "Accept the post-merge Adaptation only, under the package's prior accepted intent. The three fixture registrations change no assertions, expectations, inputs or injected faults. NoEvidenceDispatchHttpTest fails before on unregistered core.selection and all five cases pass after; both persistence fixtures already pass before and after (ten tests). Production assembly is unchanged and real CPU Engine Ask, no-match Ask and BAD_SELECTION checks pass on both revisions. No hand-off scenarios were re-judged."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-oct55-q9",
  "verdict": "accept",
  "digest": "sha256:06bedc54dcfc1eb430b1d8c456965aac5abb8aefec1f165b9f35895322691439",
  "note": "Correction: initial main-* checks used the source local main and are excluded. Exact ef349ea16c3f checks now pass: 57 UI and 59 JVM tests. Accept the merge Adaptation: reverting only per-conversation handling makes the new test assertion-fail on missing returned status; exact candidate passes. Independent outcomes and private CPU Engine/Chromium checks pass, including checking starting after navigation away, once-per-attempt announcements and two live Asks. This accepts test intent only. The separate explicit current-origin/main gate fails after PR #939 advanced the base beyond this snapshot."
}
```
