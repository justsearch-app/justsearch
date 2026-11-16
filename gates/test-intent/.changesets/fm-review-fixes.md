---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: builder-fm-ph-20261116
---

# Test intent: five first-minutes fixes from the packaged-app review

The picture author reviewed the packaged app; the owner delegated its open items ("just ask the other
agent to make the decisions"). The orchestrator's delegated decisions PH1-PH5 (PH6: nothing else
changes) are quoted in each entry with the review's words. Frontend only. Every new check was added to
an existing file; no existing assertion changed. Each fails on the base 012aae15e5 by assertion and
passes on the candidate (run-evidence with --before 012aae15e5).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.countWords-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Through the running app, after a folder is added and read with 0 files searchable while the top bar says \"Reading files\", no first-reading count line shows under the search field; at 1 file it reads \"1 file searchable so far\".",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "just ask the other agent to make the decisions",
          "delegationLocation": "task t-20261113-fmimpl agreement, the owner's reply delegating the open items of the picture author's packaged-app review (relayed verbatim in the fm-ph builder brief)",
          "delegationScope": "the open items of the picture author's review of the packaged app: fm-02, fm-10, fm-11, fm-15",
          "choice": "PH1 (review fm-02: \"'0 files searchable so far'. Show the line only from 1 file on; the bar already says 'Reading files'.\") The first-reading count line under the search field shows only when at least 1 file is searchable; at 0 nothing is shown there.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PH1 (review fm-02: \"'0 files searchable so far'. Show the line only from 1 file on; the bar already says 'Reading files'.\") The first-reading count line under the search field shows only when at least 1 file is searchable; at 0 nothing is shown there.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-review-fixes.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.countWords-lockdown.test.ts#a count of 0 shows no line while the top bar says \"Reading files\""
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.repairs-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "In dark and light, while a newly added folder is read the top bar's \"Reading files\" badge has a transparent background and no visible border, keeps its ring, and the status button reached by keyboard shows a solid focus outline of at least 2 px.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "just ask the other agent to make the decisions",
          "delegationLocation": "task t-20261113-fmimpl agreement, the owner's reply delegating the open items of the picture author's packaged-app review (relayed verbatim in the fm-ph builder brief)",
          "delegationScope": "the open items of the picture author's review of the packaged app: fm-02, fm-10, fm-11, fm-15",
          "choice": "PH2 (review fm-02 status: \"'Reading files' sits in an outlined pill; the picture has words without an outline.\") The top bar's working status is words without an outline or pill background; the ring is kept; keyboard focus stays visible.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PH2 (review fm-02 status: \"'Reading files' sits in an outlined pill; the picture has words without an outline.\") The top bar's working status is words without an outline or pill background; the ring is kept; keyboard focus stays visible.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-review-fixes.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.repairs-lockdown.test.ts#\"Reading files\" is words without an outline or fill in dark",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.repairs-lockdown.test.ts#\"Reading files\" is words without an outline or fill in light"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "With successful core.apply-settings ledger rows the panel shows no \"Apply Settings\" text; a failed one is an Earlier row saying Failed. An operation.completed notice for core.apply-settings is no Earlier row on success and \"Apply Settings failed\" on failure. The close button has no text and draws the shared x icon (paths M18 6 6 18 and m6 6 12 12).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "just ask the other agent to make the decisions",
          "delegationLocation": "task t-20261113-fmimpl agreement, the owner's reply delegating the open items of the picture author's packaged-app review (relayed verbatim in the fm-ph builder brief)",
          "delegationScope": "the open items of the picture author's review of the packaged app: fm-02, fm-10, fm-11, fm-15",
          "choice": "PH3 (review fm-15: \"'Apply Settings / Completed' once per theme change is an operation name in the panel. The close mark is a small text cross, unlike the other close icons.\") The status panel's default view lists no successful settings save (the Engine's settings operation core.apply-settings), from the action ledger or an operation.completed notice; a failed save keeps its row. The close button draws the app's shared close icon.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PH3 (review fm-15: \"'Apply Settings / Completed' once per theme change is an operation name in the panel. The close mark is a small text cross, unlike the other close icons.\") The status panel's default view lists no successful settings save (the Engine's settings operation core.apply-settings), from the action ledger or an operation.completed notice; a failed save keeps its row. The close button draws the app's shared close icon.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-review-fixes.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes.test.ts#a successful settings save is no row; a failed one is",
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes.test.ts#a settings save advisory that succeeded is no row; one that failed is",
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes.test.ts#the close button draws the app's close icon, not a text cross"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.placement.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "With a current graphics card reading, a not-installed AI Brain page (its steps rendered) has no graphics card section; an installed one shows \"3.00 GB of 12.00 GB in use · RTX 4070\".",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "just ask the other agent to make the decisions",
          "delegationLocation": "task t-20261113-fmimpl agreement, the owner's reply delegating the open items of the picture author's packaged-app review (relayed verbatim in the fm-ph builder brief)",
          "delegationScope": "the open items of the picture author's review of the packaged app: fm-02, fm-10, fm-11, fm-15",
          "choice": "PH4 (review fm-10, decided by the picture author on the owner's delegation: \"Before AI is installed it shows nowhere on the AI Brain page; it stays in System › Health. On AI Brain it appears once AI is installed.\") AI Brain before AI is installed shows no graphics card memory; once installed it shows as before.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PH4 (review fm-10, decided by the picture author on the owner's delegation: \"Before AI is installed it shows nowhere on the AI Brain page; it stays in System › Health. On AI Brain it appears once AI is installed.\") AI Brain before AI is installed shows no graphics card memory; once installed it shows as before.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-review-fixes.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.placement.test.ts#a not-installed page with a current reading shows no graphics card; installed, it does"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.focus-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "In dark and light at 1024x700, the opened consent has focus on Cancel; its box starts at or below the 44 px top bar height and its vertical centre is within 1 px of the centre of the area below the bar; its backdrop starts at 44 px, is not transparent, and does not cover the bar area.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "just ask the other agent to make the decisions",
          "delegationLocation": "task t-20261113-fmimpl agreement, the owner's reply delegating the open items of the picture author's packaged-app review (relayed verbatim in the fm-ph builder brief)",
          "delegationScope": "the open items of the picture author's review of the packaged app: fm-02, fm-10, fm-11, fm-15",
          "choice": "PH5 (review fm-11: \"The dialog sits over the top bar; centre it under the bar. First focus is on a link that leaves the app; put it on Cancel.\"; decided: \"centre it in the area below the top bar, never over it ... the picture has it centred with the dimming starting under the bar.\") The AI consent dialog is centred in the area below the top bar, its dimming starts under the bar, and it opens with focus on Cancel. Dialogs share no container, so only this dialog changes.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PH5 (review fm-11: \"The dialog sits over the top bar; centre it under the bar. First focus is on a link that leaves the app; put it on Cancel.\"; decided: \"centre it in the area below the top bar, never over it ... the picture has it centred with the dimming starting under the bar.\") The AI consent dialog is centred in the area below the top bar, its dimming starts under the bar, and it opens with focus on Cancel. Dialogs share no container, so only this dialog changes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-review-fixes.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.focus-lockdown.test.ts#the consent is centred below the top bar and opens on Cancel in dark",
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.focus-lockdown.test.ts#the consent is centred below the top bar and opens on Cancel in light"
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
  "session": "f266c593-d2c2-4d63-9275-08795a808c09",
  "verdict": "accept",
  "digest": "sha256:4927c16793e81d7b9fed372502480382d05993d2db711fe950695a411c71c543",
  "note": "Test intent accepted. Each new check fails on 012aae15e5 by assertion for the reason under test and passes on f992b6b045; 10 of 11 mutations killed. Surviving mutation: filtering successful settings saves by label text 'Apply Settings' instead of operationId (the fixture couples both), so PH3's 'not by the label text' is not pinned; suggested strengthening, not blocking."
}
```
