---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-rest-a-builder
---

# The status panel while files are read, and Library's reread receipt and folder row, as drawn

The comparison of the packaged app at b63294eff8 with the first-minutes pictures by the session that
drew them found: the status panel's reading entry carried a duration picker (a white native select in
dark), Pause reading, Open AI Brain and Details, plus a second entry per folder; fm-15 draws one entry
of three lines and one action. Saved reading rules appeared as "Set reading rules, Completed" rows.
Library said "Reading", then "Read again accepted. Completion has not yet been confirmed.", then
"Reading" again, and the folder's meaning line said "word search availability not known"; the folder
row's text was smaller than fm-07 draws it, "Remove from library" was not red, and Pause reading had a
clock. The owner's words to that session were "takeover the remaining work and proceed until you think
its done" (2026-11-17); the decisions S1-S8 and L1-L7 below are that session's, delegated.

Decisions: S1 while files are read, Running has one entry for reading (title, one line of numbers, the
bar, one action "Pause reading"); no duration picker, no Details, no Open AI Brain, no separate entry
per folder for ordinary scanning, reading or enriching. S2 that Pause reading pauses until resumed
through core.indexing-pause, confirmation and error handling unchanged; paused, the entry offers Resume
reading. S3 no native select in the panel; the duration select that still renders (Settings › Indexing)
follows the theme. S4 with developer tools on, the reading entry's Details, the per-folder entries and
the extra action show as before. S5 folders that need the person, and uncertain observations, keep
their entries; Open AI Brain stays where the entry's words say AI is needed or missing. S6 a successful
core.set-indexing-rules adds no Earlier entry (failed, developer tools, or another operation with the
same label still show). S7 Library's Pause reading carries the pause icon. L1-L3 the reread receipt:
nothing besides the headline while reading visibly runs; "Starting to read everything again." before
reading shows; outcomes kept. L4 the meaning line while a folder is scanned or read says nothing about
word search. L5 row sizes 15 / 12 / 13 px. L6 Remove from library in the danger colour, a text action.

New checks (isolated new files): `state/frameStatus.readingEntry.test.ts`,
`components/StatusPanel.readingEntry.test.ts`, `components/IndexingControls.pauseIcon.test.ts`,
`views/library-first-minutes/reread-receipt.test.ts` and
`views/library-first-minutes/fm-rest-a-lockdown.test.ts`. On b63294eff8 the delivered decisions fail
by assertion (gates/test-intent/evidence/fm-rest-a.json); the kept ones pass before and after.

S1 changes what three existing files pinned: they open the reading entry's Details in the default
panel, which S1 removes and S4 keeps with developer tools. Each now turns developer tools on for the
panel it reads; every assertion is unchanged.

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
