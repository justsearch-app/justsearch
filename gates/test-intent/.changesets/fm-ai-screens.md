---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-ai-screens-builder
---

# AI Brain's stopped download, step sizes and foot, and Search / Ask, as drawn

The session that drew the first-minutes pictures compared the finished packaged app with them; the
owner's words to it were "takeover the remaining work and proceed until you think its done"
(2026-11-17). Its list ended: "Never seen in the real app (need the model download): installing, download
stopped, installed without a card, AI ready, search with the Search/Ask control." A source-only
comparison of those states with the pictures (fm-12, fm-12b, fm-13, fm-14, fm-16) followed; the
decisions below are that session's, delegated and reversible.

Decisions: A1 deliver: a stopped download says, under "The download stopped": "What is already here is kept, and search by exact words keeps working." The same fact has the same words in the status panel's entry. Not "The connection was lost.": the app does not know the cause (the Engine reports only that downloads failed), and a screen never states a cause it does not know. A2 deliver: the part that stopped reads "Stopped at 120 of 956 MB" (its own amounts), not "Stopped (had ...)". A3 deliver: a step's size on AI Brain is the amount alone ("1.2 GB", "341 MB", "5.7 GB"), before and after installing; no " download" after it. (The install dialog's own sizes are not part of this.) A4 deliver: with AI installed and answers not working (fm-13) the foot of the
page offers "Advanced settings" and "Remove AI models"; the second opens Advanced settings at the existing
controls that remove model files and moves focus there; it removes nothing by itself. With answers
working (fm-14) the foot is "Advanced settings" and "Engine history in Health". A5 deliver: the install
bar 8 px high; gaps above the bar, the amounts line and the parts list 18, 8 and 18 px; part-row columns
the name taking the rest, 190 px, 110 px; Search / Ask with 8 px corners; with the field empty and Search /
Ask shown, no empty space kept for Clear. A6 keep (as delivered here): Search / Ask is offered with
answers working on an NVIDIA card and not offered with answers switched off in AI Brain, the answering
model failed, installed without a card or AI not installed. A7, A8 keep.

New checks (isolated new files): `views/brain-install/BrainInstall.stoppedWords.test.ts` (A1, A2),
`views/brain-first-minutes/BrainOverview.sizesFoot.test.ts` (A3, A4, A7 keeps),
`views/brain-first-minutes/BrainOverview.askOffer.test.ts` (A6 keep) and
`views/brain-first-minutes/BrainScreens.measures-lockdown.test.ts` (A5 in Chromium at 1024×700, dark and
light, with the real AI state store over the Engine's recorded install statuses). On af6d2466a1 every
delivered check fails by assertion and passes on the candidate (gates/test-intent/evidence/fm-ai-screens.json);
the keep checks pass before and after.

A1-A3 change what four existing files pinned: the stopped download's sentence, the stopped part's words
and the word " download" after a step's size. Each change is listed with its inputs below.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.flow.test.ts",
        "modules/ui-web/src/shell-v0/views/brain-install/BrainInstall.topBar.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design set: the AI Brain screens for a stopped download, for AI installed without a graphics card and for AI ready, and the Search / Ask control, after a source-only comparison of these states with the pictures",
          "choice": "A1 deliver: a stopped download says, under \"The download stopped\": \"What is already here is kept, and search by exact words keeps working.\" The same fact has the same words in the status panel's entry. Not \"The connection was lost.\": the app does not know the cause (the Engine reports only that downloads failed), and a screen never states a cause it does not know. A2 deliver: the part that stopped reads \"Stopped at 120 of 956 MB\" (its own amounts), not \"Stopped (had ...)\".",
          "label": "agent choice"
        }
      ],
      "behaviour": "A stopped download (the Engine's failedPartsMissing status) says \"The download stopped\" and \"What is already here is kept, and search by exact words keeps working.\" on AI Brain and in the status panel's \"Download stopped\" entry; a part that stopped with bytes reads \"Stopped at <a> of <b>\"; a part placed before Cancel install reads Downloaded with no size. New checks: BrainInstall.stoppedWords.test.ts (A1 both places, A2 with 120 of 956 MB).",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for stopped download, download stopped, connection was lost, package size, download size, Remove AI models, install progress and download: 'install progress' appears only in 0037 (the SSE envelope that would carry it); 'download' in 0019 (which model variants a CPU-only computer downloads), 0024 (the per-user installer download) and 0039 (raw spec download). None governs AI Brain's words for a stopped download or how a step's size is written. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "BrainInstall.flow.test.ts 'a failed run with a part missing: amber stopped view, the parts, Try again and Cancel install' (failedPartsMissing): the panel contains 'Completed parts stay on this computer, and search by exact words keeps working.'",
          "newAssertion": "The panel contains 'What is already here is kept, and search by exact words keeps working.'; every other assertion (amounts, part rows, Try again and Cancel install, no connection/continue/resume words, Detail for support with curl exit 18) is unchanged.",
          "reason": "A1 replaces the sentence."
        },
        {
          "oldAssertion": "BrainInstall.flow.test.ts 'a part that had bytes says how far it got, as history' (failedPartsMissing with splade bytesDownloaded 57344 of 180849): the Related words row reads 'Stopped (had 56 of 177 KB)'.",
          "newAssertion": "The row reads 'Stopped at 56 of 177 KB'.",
          "reason": "A2 replaces the words for a part that stopped."
        },
        {
          "oldAssertion": "BrainInstall.flow.test.ts 'closes the stopped view; the placed parts stay listed under their steps; the same failed poll stays closed': the meaning step does not match /KB download|Not downloaded/.",
          "newAssertion": "The meaning step does not match /\\d KB|Not downloaded/ (it says Downloaded, with no size).",
          "reason": "A3 drops ' download' after sizes, so the old pattern could no longer see a size; the new one keeps the guarded fact (no size, not 'Not downloaded')."
        },
        {
          "oldAssertion": "BrainInstall.topBar.test.ts 'a failed run with a part missing: \"1 Download stopped\" in amber, never the red failure': the status panel contains 'Completed parts stay on this computer, and search by exact words keeps working.'",
          "newAssertion": "The panel contains 'What is already here is kept, and search by exact words keeps working.'; the entry, its count, tone and the absent red failure words are unchanged.",
          "reason": "A1: the same fact has the same words in the status panel's entry."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.steps.test.ts",
        "modules/ui-web/src/shell-v0/views/brain-first-minutes/BrainOverview.step-repairs.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design set: the AI Brain screens for a stopped download, for AI installed without a graphics card and for AI ready, and the Search / Ask control, after a source-only comparison of these states with the pictures",
          "choice": "A3 deliver: a step's size on AI Brain is the amount alone (\"1.2 GB\", \"341 MB\", \"5.7 GB\"), before and after installing; no \" download\" after it. (The install dialog's own sizes are not part of this.)",
          "label": "agent choice"
        }
      ],
      "behaviour": "A step's size on AI Brain is written as the amount alone ('1.2 GB', '341 MB', '5.7 GB', the compact answer model's '3.2 GB'), never as measured disk use and never led by a separator. New checks: BrainOverview.sizesFoot.test.ts (A3 in fm-10, fm-13 and fm-14; the step's size element equals the amount).",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for stopped download, download stopped, connection was lost, package size, download size, Remove AI models, install progress and download: 'install progress' appears only in 0037 (the SSE envelope that would carry it); 'download' in 0019 (which model variants a CPU-only computer downloads), 0024 (the per-user installer download) and 0039 (raw spec download). None governs AI Brain's words for a stopped download or how a step's size is written. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "BrainOverview.steps.test.ts 'says the picture’s words, lights only the steps that work, and has one filled action' (fm-10, not installed): the meaning step contains '1.2 GB download', the reranker step '341 MB download'.",
          "newAssertion": "They contain '1.2 GB' and '341 MB'; the rest of the case is unchanged.",
          "reason": "A3: no ' download' after a size."
        },
        {
          "oldAssertion": "BrainOverview.steps.test.ts 'omits the answer size for a %s' (an operator-selected answer model, all parts complete): the meaning step contains '1.2 GB download'.",
          "newAssertion": "The meaning step contains '1.2 GB'; '5.7 GB' still appears nowhere.",
          "reason": "A3."
        },
        {
          "oldAssertion": "BrainOverview.steps.test.ts 'labels package sizes as downloads, never as measured disk use, and never leads with a separator' (installed, answers online, standard profile): each of meaning 1.2 GB, reranker 341 MB and chat 5.7 GB has a .step-meta that matches /download/i.",
          "newAssertion": "Renamed 'writes package sizes as the amount alone, never as measured disk use, and never leads with a separator'; each such .step-meta equals the amount exactly ('1.2 GB', '341 MB', '5.7 GB'); the disk-use and separator assertions are unchanged.",
          "reason": "A3 reverses what the case pinned: the size is the amount alone."
        },
        {
          "oldAssertion": "BrainOverview.step-repairs.test.ts 'shows the compact answer model’s own package size, never the standard one' (compact profile, standard package 6807977504 bytes): the whole page does not contain '6.3 GB download'; the chat step contains '3.2 GB download'.",
          "newAssertion": "No step's size (.step-meta) contains '6.3 GB'; the chat step contains '3.2 GB'.",
          "reason": "A3 drops ' download'. The licence dialog lists the standard package as 'Answer questions 6.3 GB' (the install dialog's own sizes are outside A3), so the guard is scoped to the page's step sizes, which is what ' download' identified before."
        }
      ]
    }
  ]
}
```

## Acceptance records
