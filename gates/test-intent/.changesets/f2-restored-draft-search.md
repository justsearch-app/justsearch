---
schema: test-intent.v1
task: t-20261027-4e4d6b
author-role: builder
author-session: 98e063d8-b77c-4ff7-bbe8-9668e0417bae
---

# Test-intent entries

Fix F2 for v0.3.6: a draft restored at app start must not block Search. On the base, Search waits
for Enter (no search-as-you-type), and plain Enter on a restored draft did nothing until the person
clicked a review button. The expectations are an agent choice under the quoted release-workflow
delegation (fix release blockers forward), not owner-agreed words. The owner words establish only
that a defect blocking Search for every user matters for a release; they do not choose this fix.
No decision record or owner-sourced document at the base states a conflicting rule (see
conflictSearch).

Repaired checks fail on 025fd3e9e0 with an assertion (no Search issued, the review prompt still
shown, or the refused write not announced). Preservation checks pass both before and after.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.urlConversation.test.ts"
      ],
      "class": "New",
      "behaviour": "Repaired F2: plain Enter in Search mode on a plain draft restored at app start searches it. F2-1: a restored 'orchid', as raw text only or with saved Search settings, makes no request until Enter. Enter then makes exactly one Search for 'orchid' carrying the current folder filter, with no AI stream or send. The review prompt goes and the row shows. F2-2: clearing the field and searching 'lighthouse' searches it. A further search behaves normally. F2-3: a restored Ask (all files / chosen files), Summarize, Structured, Delegate or model-only proposal shown in Search mode. Enter searches only the composer text 'newer orchid', with no saved file path in the request and no AI request. The mode stays Search with no scope shown, and the saved proposal remains a distinct companion recovery draft. F2-6: a refused or stale attachment write is announced ('Recovery could not be saved safely'). It searches nothing and keeps the text, the other writer's data and the companion. After a successful Search the saved preparation is Search; it survives a same-WebView reload, and after a full reopen Enter searches the text again. Existing checks whose expected outcome changed to the repaired behaviour: X8 (Enter searches the quoted draft as typed with Search syntax, and the prompt goes); DS1 ambiguous ownership for both raw keys (Enter searches 'Boundary needle' and never streams or sends); DS1 independent search C (after C, Enter searches the restored 'ambiguous B'); fm-rest emptying a restored draft (the review stays through emptying and retyping, then Enter searches); N14 (an edit stages no Search, and Enter searches the edited text and removes the prompt).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "once your task is fully implemented you can autonomously proceed with running the first release workflow btw",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27T21:08Z",
          "delegationScope": "running the release workflow autonomously up to the owner's final validation, including fixing release blockers forward per docs/how-to/cut-a-release.md section 4 (docs/how-to/cut-a-release.md:304 at 025fd3e9e0); resumed by the user on 2026-11-17: \"id like you to resume the release workflow. ... you need to determine how in depth this needs to be.\"",
          "choice": "Plain Enter in Search mode on a plain draft restored at app start (the raw slot, legacy adoption, or the session attachment's current draft on first connect) searches the text currently in the composer with the current filters. It counts as the same confirmation as 'Use Search with current filters', and the review prompt goes. It makes no AI request, applies no saved file choice and never widens chosen files to all files. A saved proposal stays a distinct companion recovery draft. Every other review rule stays. Ctrl/Cmd+Enter and non-Search modes stay blocked until review, and both review buttons only confirm. Hand-off blocks, retired hand-off text, the departure block, Forget reselection, drafts restored by navigating to a conversation and companion copies keep blocking plain Enter and keep their state. A refused or stale storage write is visible, keeps the text and companions, and searches nothing. After a successful write the saved preparation is Search.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "for each release, owner attention should only be necessary, and thats for final validation. also, the release workflow doesnt need to have every issue fixed before being shipped, but only the critical ones every user would experience and that would block using justsearch in general",
          "location": "task t-20261027-4e4d6b request"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/f2-restored-draft-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#X8 F2 restored quoted Search draft keeps its text, runs nothing until Enter, and Enter searches it as typed",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#DS1 ambiguous restored draft ownership F2 Enter on restored search-v3.composer only searches it, and never sends it to AI",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#DS1 ambiguous restored draft ownership F2 Enter on restored unified-chat.composer only searches it, and never sends it to AI",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#DS1 review establishes current intent without issuing it an independent shared search C runs while restored B stays in its composer",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#fm-rest Q1-Q5 — emptying the search field returns Search to its idle screen emptying a restored draft under review neither approves nor discards the review; Enter then searches (F2-2)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-1 Enter on a restored draft with raw text only searches it with the current filters",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-1 Enter on a restored draft with saved Search settings searches it with the current filters",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-2 clearing the restored text and searching a new query searches it, then behaves as a normal session",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-3 a restored Ask over all files proposal shown in Search mode: Enter only searches the composer text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-3 a restored Ask over chosen files proposal shown in Search mode: Enter only searches the composer text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-3 a restored Summarize proposal shown in Search mode: Enter only searches the composer text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-3 a restored Structured proposal shown in Search mode: Enter only searches the composer text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-3 a restored Delegate proposal shown in Search mode: Enter only searches the composer text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-3 a restored model-only proposal shown in Search mode: Enter only searches the composer text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-6 a refused or stale attachment write is visible, keeps the text and companions, and searches nothing",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-6 after a successful Search the saved preparation is Search and survives reload and a full reopen",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.urlConversation.test.ts#N14 DS1 F2 every nonempty raw string is reviewed: an edit stages no Search, and Enter searches the edit"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation for F2-4 and F2-5. F2-4: with a pending review, Ctrl+Enter, Ask mode and Delegate mode stay blocked: no search, stream or send, and the prompt stays. 'Use reviewed settings' only confirms, and a later send streams exactly the reviewed chosen file. 'Use Search with current filters' only confirms, and a later Enter searches. F2-5: plain Enter still issues nothing and keeps the state of a blocked hand-off (its notice stays), the departure block, retired hand-off text, a Forget that requires reselection, a draft restored by navigating to a conversation, and a companion copy taken up for review. Existing DS1 checks 'keeps typed A separate from %s' (both cases) and 'real chosen-row preparation survives same-tab reload' keep every blocking expectation, now driven by Ctrl+Enter instead of plain Enter. Plain Enter in Search mode is now the repaired search (see the Obsolete entry).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "once your task is fully implemented you can autonomously proceed with running the first release workflow btw",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27T21:08Z",
          "delegationScope": "running the release workflow autonomously up to the owner's final validation, including fixing release blockers forward per docs/how-to/cut-a-release.md section 4 (docs/how-to/cut-a-release.md:304 at 025fd3e9e0); resumed by the user on 2026-11-17: \"id like you to resume the release workflow. ... you need to determine how in depth this needs to be.\"",
          "choice": "Plain Enter in Search mode on a plain draft restored at app start (the raw slot, legacy adoption, or the session attachment's current draft on first connect) searches the text currently in the composer with the current filters. It counts as the same confirmation as 'Use Search with current filters', and the review prompt goes. It makes no AI request, applies no saved file choice and never widens chosen files to all files. A saved proposal stays a distinct companion recovery draft. Every other review rule stays. Ctrl/Cmd+Enter and non-Search modes stay blocked until review, and both review buttons only confirm. Hand-off blocks, retired hand-off text, the departure block, Forget reselection, drafts restored by navigating to a conversation and companion copies keep blocking plain Enter and keep their state. A refused or stale storage write is visible, keeps the text and companions, and searches nothing. After a successful write the saved preparation is Search.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f2-restored-draft-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#DS1 review establishes current intent without issuing it keeps typed A separate from same bytes A until current review",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#DS1 review establishes current intent without issuing it keeps typed A separate from newer raw B until current review",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#DS1 review establishes current intent without issuing it real chosen-row preparation survives same-tab reload as data requiring explicit review",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-4 Ctrl+Enter and ask mode stay blocked while the review is pending",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-4 Ctrl+Enter and delegate mode stay blocked while the review is pending",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-4 Use reviewed settings only confirms, and a later send uses exactly the reviewed scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-4 the Search review button only confirms; a later Enter searches with that scope",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-5 a blocked hand-off stays blocked and keeps its notice",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-5 retired hand-off text and the departure block keep blocking a restored draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-5 a Forget that requires reselection keeps the restored draft blocked",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-5 a draft restored by navigating to a conversation keeps blocking Search",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts#F2 a draft restored at app start does not block Search F2-5 a companion copy taken up for review keeps blocking Search"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.urlConversation.test.ts"
      ],
      "class": "Obsolete",
      "retired": "The expectation that plain Enter in Search mode on a plain draft restored at app start issues no Search, and that 'Use Search with current filters' must be clicked first. It is removed from: X8 (Enter, then the review-button click, before the searching Enter); DS1 'does not issue an unscoped Enter from restored %s' (both keys; now renamed to the repaired check); DS1 'keeps typed A separate from %s' (the plain-Enter half of the {} / ctrlKey loop); DS1 'an independent shared search C runs while restored B stays in its composer' (Enter before checking that no request carried B); DS1 'real chosen-row preparation survives same-tab reload' (plain Enter replaced by Ctrl+Enter); fm-rest 'emptying a restored draft under review' (Enter after retyping issued nothing); N14 (Enter after the edit issued nothing, and the review button was needed before Search). The no-AI, no-stream, no-send and scope expectations in these tests stay.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "once your task is fully implemented you can autonomously proceed with running the first release workflow btw",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27T21:08Z",
          "delegationScope": "running the release workflow autonomously up to the owner's final validation, including fixing release blockers forward per docs/how-to/cut-a-release.md section 4 (docs/how-to/cut-a-release.md:304 at 025fd3e9e0); resumed by the user on 2026-11-17: \"id like you to resume the release workflow. ... you need to determine how in depth this needs to be.\"",
          "choice": "Plain Enter in Search mode on a plain draft restored at app start (the raw slot, legacy adoption, or the session attachment's current draft on first connect) searches the text currently in the composer with the current filters. It counts as the same confirmation as 'Use Search with current filters', and the review prompt goes. It makes no AI request, applies no saved file choice and never widens chosen files to all files. A saved proposal stays a distinct companion recovery draft. Every other review rule stays. Ctrl/Cmd+Enter and non-Search modes stay blocked until review, and both review buttons only confirm. Hand-off blocks, retired hand-off text, the departure block, Forget reselection, drafts restored by navigating to a conversation and companion copies keep blocking plain Enter and keep their state. A refused or stale storage write is visible, keeps the text and companions, and searches nothing. After a successful write the saved preparation is Search.",
          "label": "agent choice"
        },
        {
          "kind": "owner-words",
          "quote": "for each release, owner attention should only be necessary, and thats for final validation. also, the release workflow doesnt need to have every issue fixed before being shipped, but only the critical ones every user would experience and that would block using justsearch in general",
          "location": "task t-20261027-4e4d6b request"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. docs/decisions/ at 025fd3e9e0 has no record about restored drafts, the draft review or what Enter does in Search. Other documents searched at the base: docs/reference/search-ui-behavior.md:32 ('The initial hero composer starts in Search mode. Enter searches files.') agrees. docs/how-to/cut-a-release.md:293-295 lists search as a normal path whose breakage blocks a release, which agrees. In governance/search-window-scenarios.v1.json, SW-DRFT-02 ('Restoring a draft displays it for review without automatically searching, asking, or delegating.') is kept: nothing runs until Enter. SW-DRFT-09 (known gap: Enter, Send and Use reviewed settings were inert after recovery) agrees. Neither scenario entry carries an owner source. The DS1 review contract these tests guarded (gates/test-intent/.changesets/uiux-cutover-intent-final.md) is itself an agent choice under the delegation 'you dont need to wait for my opinion regarding the designs, i agree with you on them' (task t-20261019-bd718f agreement). It is not owner words or a decision record. Its rule that restored tasks cannot confer scope is kept, because Search uses the current filters and never a saved file choice. Tempdocs 687 and 852 are agent-written and quote no owner words about drafts. They say Enter is the verb and Ctrl/Cmd+Enter delegates from either mode; both are kept."
    }
  ]
}
```

## Acceptance records
