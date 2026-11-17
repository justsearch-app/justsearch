---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-rest-a-builder
---

# The status panel while files are read or paused, Library's reread receipt and folder row, every native select, as drawn

The comparison of the packaged app at b63294eff8 with the first-minutes pictures by the session that
drew them found: the status panel's reading entry carried a duration picker (a white native select in
dark), Pause reading, Open AI Brain and Details, plus a second entry per folder; fm-15 draws one entry
of three lines and one action. Saved reading rules appeared as "Set reading rules, Completed" rows.
Library said "Reading", then "Read again accepted. Completion has not yet been confirmed.", then
"Reading" again, and the folder's meaning line said "word search availability not known"; the folder
row's text was smaller than fm-07 draws it, "Remove from library" was not red, and Pause reading had a
clock. The owner's words to that session were "takeover the remaining work and proceed until you think
its done" (2026-11-17); the decisions below are that session's, delegated.

A verifier of the first candidate in the packaged app then saw: "After confirming, 'Starting to read
everything again.' lasts about 0.1 s. It is then replaced, before reading shows, by 'Read again
accepted. Completion has not yet been confirmed.' with a 'Check reading outcome' button. The same pair
returns in the gap between folders. After reading ends, 'Read again completed for the recorded request.
Current reading activity is shown below.' stays until you leave Library."; "Paused, the panel still
shows the folder entry and 'Recent file activity · Open AI Brain · Details', same as Before."; "Settings
'Enter action' select is white with unreadable text in dark."; and that System > Activity's heading
computes to 22.5 px, off the six-size ladder. The same session replaced its decisions L1-L3 by L1'-L3
and added S9, S10 and G2 (quoted in full in the second entry's sources).

Decisions: S1 while files are read, Running has one entry for reading (title, one line of numbers, the
bar, one action "Pause reading"); no duration picker, no Details, no Open AI Brain, no separate entry
per folder for ordinary scanning, reading or enriching. S2 that Pause reading pauses until resumed
through core.indexing-pause, confirmation and error handling unchanged; paused, the entry offers Resume
reading. S3 no native select in the panel; the duration select that still renders (Settings › Indexing)
follows the theme. S4 with developer tools on, the reading entry's Details, the per-folder entries and
the extra action show as before. S5 folders that need the person, and uncertain observations, keep
their entries; Open AI Brain stays where the entry's words say AI is needed or missing. S6 a successful
core.set-indexing-rules adds no Earlier entry (failed, developer tools, or another operation with the
same label still show). S7 Library's Pause reading carries the pause icon. S9 paused or held, no entry
per folder that merely waits and no "Recent file activity" without developer tools. S10 every native
select in shell-v0 is drawn by the page in the theme's colours (one shared fragment,
primitives/selectStyles.ts). G2 System › Activity's heading is 20 px through --font-size-lg. L1' from
the confirmation until the outcome completes or fails, one constant line "Reading everything again.";
L2' complete says "Everything has been read again."; L3 failed and uncertain outcomes keep their
messages. L4 the meaning line while a folder is scanned or read says nothing about word search. L5 row
sizes 15 / 12 / 13 px. L6 Remove from library in the danger colour, a text action.

Why the repository's size-ladder check (scripts/ci/check-type-ladder.mjs) did not see G2: it reads the
font-size, font and radius declarations written in the sources and tokens. Activity's h2 had no
font-size declaration at all; its 22.5 px is the browser's own h2 default (1.5em of the 15 px body),
which no source states, so a static scan of declarations cannot see it. Only a computed-style check in
a browser does (the G2 case below).

New checks (isolated new files): `state/frameStatus.readingEntry.test.ts`,
`state/frameStatus.pausedEntry.test.ts` (S9), `components/StatusPanel.readingEntry.test.ts`,
`components/IndexingControls.pauseIcon.test.ts`, `views/library-first-minutes/reread-receipt.test.ts`
(L1'-L4, rewritten for L1'/L2' in this repair: its earlier cases for "Starting to read everything
again." and for nothing besides the headline while reading runs pinned the replaced L1-L2),
`views/library-first-minutes/fm-rest-a-lockdown.test.ts` and
`views/library-first-minutes/fm-rest-a-selects-lockdown.test.ts` (S10 over every select in dark and
light, G2). On b63294eff8 the delivered decisions fail by assertion (gates/test-intent/evidence/fm-rest-a.json);
the kept ones pass before and after.

S1 changes what three existing files pinned: they open the reading entry's Details in the default
panel, which S1 removes and S4 keeps with developer tools. Each now turns developer tools on for the
panel it reads; every assertion is unchanged. L1'/L2' change what three more existing files pinned:
the sentence after the confirmation and after completion (second entry).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts",
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design set after the comparison of the packaged app with the pictures",
          "choice": "S1: while files are being read, the panel's Running section has one entry for reading: its title, one line of numbers, the bar and one action Pause reading; no duration picker, no Details, no Open AI Brain, and no separate entry per folder for ordinary scanning, reading or enriching. S4: with developer tools on, the reading entry's Details, the per-folder entries and the extra action show as before.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The reading entry's Details disclosure (queued and failed counts, stage fractions, the file window, their last-observed qualification) is offered with developer tools on; these tests read it there. Without developer tools the entry offers Pause reading only (frameStatus.readingEntry.test.ts, StatusPanel.readingEntry.test.ts).",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for status panel, developer tools, disclosure and Details: no record names the status panel or developer tools; 'disclosure' appears only in 0015 (MCP tool responses' progressive disclosure), and 'Details' in 0002, 0003, 0005, 0009, 0015, 0016, 0025 and 0038 as ordinary prose about IPC, Lucene, bindings, CI, MCP, query boosts, DTOs and wire contracts. None governs which disclosures the status panel's reading entry offers. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "StatusPanel.running.test.ts 'enrichment stays discoverable without SSE tasks and exposes its real stage fractions': the panel rendered without developer tools offers Details on the reading entry, whose stage lines read 60 / 100.",
          "newAssertion": "The same panel rendered with developer tools on (render(m, true)) offers Details with the same stage lines; the model assertions are unchanged.",
          "reason": "S1 removes Details from the default reading entry; S4 keeps it with developer tools."
        },
        {
          "oldAssertion": "indexingSurfaceSweep.test.ts framePanel(): the reading entry's expanded Details render without developer tools (S33.4 cases: file rows, last observed, stage disclosure).",
          "newAssertion": "framePanel() sets developer = true; every S33.4 assertion is unchanged.",
          "reason": "S1/S4 as above."
        },
        {
          "oldAssertion": "indexingJobsQueueRail.test.ts: the status pill's panel offers the reading entry's Details (queued, failed and file-window lines) with developer tools off.",
          "newAssertion": "beforeEach turns developer tools on (setShowDeveloperTools(true)), afterEach off; every assertion is unchanged.",
          "reason": "S1/S4 as above."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs.test.ts",
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b1.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design set after the comparison of the packaged app with the pictures, and repair what the verifier of the packaged candidate found",
          "choice": "L1' deliver: from the moment \"Read everything again\" was confirmed until its outcome is complete or failed (the outcome states accepted and running), Library shows exactly one line about it, constant, under the headline: \"Reading everything again.\" It is there whether or not the headline says reading at that instant, before reading shows, while it runs and in the gaps between folders. During that time the page never shows \"accepted\", \"Completion has not yet been confirmed\", the \"Check reading outcome\" action, or the extra paragraph with the activity summary. The line does not flicker: a status refresh, a folder finishing or an outcome read that still says accepted or running leaves the same element in place. L2' deliver: when the outcome is complete the line becomes \"Everything has been read again.\" (no \"recorded request\", no second sentence) and stays as today until the person leaves Library; after leaving and returning, and after a restart, it is gone, as today. L3 keep: a failed outcome keeps its message and alert role. An outcome whose state is neither accepted, running, complete nor failed, or that cannot be read (the request fails), keeps today's uncertain sentence with \"Check reading outcome\". A lost or unconfirmed status (not live, contact or folders unconfirmed) while the outcome is accepted or running still shows only the L1' line: the outcome says the request is in progress; the headline says what is known about status. Request keys, the outcome reads and their timing, late answers of an older read (the serial guard) are unchanged.",
          "label": "agent choice"
        }
      ],
      "behaviour": "After \"Read everything again\" is confirmed and its outcome reads accepted or running, Library says \"Reading everything again.\" (one line, no Check reading outcome action); once the outcome reads complete it says \"Everything has been read again.\"; a failed outcome keeps \"Read again did not complete ...\" with role alert. The rest of each case (the operation and its arguments, consent, the counted folder re-read, Up to date once on the page, the failed path) is unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for read again, reindex, Read everything, receipt, operation-history and Library: no record governs Library's wording after a reread; 'operation-history' appears only in 0030 (the audit field that suppresses or emits an operation-history record), 'receipt' only in 0038 (the catalog read on receipt) and 'reindex'/'Library' in no record's decision text. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "LibrarySurface.folderActions.test.ts 'distinguishes accepted reindex from the recorded completion and failure': after the confirmation (outcome running) the page contains 'Read again accepted' and not 'Read again completed'; the case then sets the outcome complete and activates 'Check reading outcome', and expects 'Read again completed'.",
          "newAssertion": "After the confirmation the page contains 'Reading everything again.' and not 'Everything has been read again.'; the case sets the outcome complete and lets the status tick 5 s later (Date.now advanced, one status fed), which reads the outcome again as Library does, and expects 'Everything has been read again.'. The failed path (alert 'Read again did not complete', 'retry') is unchanged.",
          "reason": "L1' says the accepted/running request in exactly the line 'Reading everything again.' and removes the 'Check reading outcome' action while it is in progress, so the step that pressed it reads the outcome on the status tick instead; L2' gives the completion its sentence."
        },
        {
          "oldAssertion": "review-repairs.test.ts 'V6: when Read everything again completes, the folders are read again so the rows show the new file counts': the page contains 'Read again completed'.",
          "newAssertion": "The page contains 'Everything has been read again.'; the counted re-read and '12 files' are unchanged.",
          "reason": "L2' replaces the completion sentence."
        },
        {
          "oldAssertion": "slice-6b1.test.ts '6b1-2: confirmation uses consent and recorded outcome' and '6b1-4: after no-AI completion Up to date occurs once on the page': the page contains 'Read again completed'.",
          "newAssertion": "The page contains 'Everything has been read again.'; consent, the operation's arguments and 'Up to date' once are unchanged.",
          "reason": "L2' replaces the completion sentence."
        }
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
  "session": "d5886b95-ce0e-4a81-a866-f77d0505bec4",
  "verdict": "accept",
  "digest": "sha256:2a83b19476982a1788919f8166c86e0eceaa1a33c7120538fc3da37e030e3a21"
}
```
