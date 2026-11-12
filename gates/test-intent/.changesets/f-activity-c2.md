---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: 1b9a57ec-4aff-4d94-8769-ca04d8745758
---

# Test-intent entries

Follow-up batch C2 (FC6–FC12): Activity arrival, file names by Engine, many Activity records, Activity rows in words, the status pill's spoken status and failed-install wording.
The scenarios are the orchestrator's decisions under the owner's quoted delegation, recorded as agent choices.
Builder execution evidence: `gates/test-intent/evidence/f-activity-c2.json` (one run, `--before origin/main` = d8f6c9d13f) for the repaired checks, each failing on the before-state with an assertion in the named check; `gates/test-intent/evidence/f-activity-c2-fc12.json` (`--before a3457998b6`, the branch head before FC12, since FC12 was found after that run) for FC12's repaired checks; and `gates/test-intent/evidence/f-activity-c2-preserved.json` (candidate only) for checks that pass on the before-state too or cannot run there, as each entry says.
Existing tests changed in this branch are classified as Obsolete (expectations FC9, FC10 or FC11 replace, with the checks that replace them named) or Adaptation (the arrival request's addressed signature, FC6).
What each Obsolete entry replaces:
- `ActionLedgerView.test.ts` (Obsolete, FC9): The outcome cell's text 'SUCCESS'/'FAILURE' and the who cell's 'agent' (row text in recorded codes) are expectations FC9 replaces; tone, glyph, structure and the filtering are unchanged. Replaced by: modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts#projects occurredAt + outcome — the "timing" and "outcomes" the subtitle promises (tempdoc 558 Deepening 3), modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts#renders a FAILURE outcome as the error tone + glyph — failed must read as failed (tempdoc 558), modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts#filters the stream by originator via FilterChip facets (tempdoc 558 §E1), modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#the backend's records: who, what and how it ended in words, never their codes, modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#index records say indexed or failed, and who did it is the engine, modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#this window's journal: its actions in words, an invocation by its operation, modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#after a reload, the restored journal's rows read in words too.
- `ActionLedgerView.feed-lockdown.test.ts` (Obsolete, FC9): A19's outcome text '<glyph> FAILURE' is replaced by '<glyph> Failed'; the forced-colours observation is unchanged. Replaced by: modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a light forced-colours palette, the unread mark stays visible with its label, and an outcome reads as a glyph and a word, modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a dark forced-colours palette, the unread mark stays visible with its label, and an outcome reads as a glyph and a word.
- `ActivitySurface.details.test.ts` (Obsolete, FC9): The journal arrival check's row text 'open-pane' (the effect's raw kind) is replaced by the worded title 'Open pane "here"'. Replaced by: modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#this window's journal: its actions in words, an invocation by its operation, modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#after a reload, the restored journal's rows read in words too.
- `StatusPill.transitions.test.ts` (Obsolete, FC10): 'an error verdict assertively' expected an assertive verdict announcement for an unreachable verdict while the pill shows 'Checking search status'; FC10 has the spoken status agree with the pill. Replaced by: modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#mirrors the verdict politely and an error verdict assertively, never as "All systems operational".
- `aiVerdict.test.ts` (Obsolete, FC11): 'install_failed surfaces the install service error text in the body' expected the body to be the service's raw text; FC11 has the body say what went wrong and what to do. Replaced by: modules/ui-web/src/shell-v0/state/aiVerdict.test.ts#install_failed says in words what went wrong and what to do; the service's own text is not the body (FC11).
- `unrequestedAi.test.ts` (Obsolete, FC11): FA3c's 'the rows say what happened in the AI verdict's own words' expected the failed row's detail to be the Engine's raw message; FC11 has it say in words what went wrong and what to do, the message kept once as detail for support. Replaced by: modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#the rows say what happened in the AI verdict's own words.

The Adaptation entries: the arrival request is now addressed to the window its navigation lands on (FC6), so the checks that simulate an arrival without a Shell address it to the page, as the navigation would, and read it back for their own Activity.
Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC6 repaired: with a hidden, connected Activity present first (1280x720, 600x400 at 200% text, forced colours), and with Activity in the second window of a split, Ctrl+K's journal arrival is taken by the Activity the navigation shows in the window it lands on: its feed scoped to this window's records, the earlier filter cleared, the keyboard on it; the hidden Activity keeps its filter and no other Activity has the keyboard.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC6 With a hidden but connected Activity view present before a visible one (and in production navigation paths: split, second System pane, Activity in More), an arrival request (state/activitySection.ts) is consumed by the visible, mounted consumer navigation targets: its filters reset and it receives focus; the hidden one is untouched.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts#FC6 at 1280×720, a hidden Activity connected first leaves the arrival to the Activity navigation shows",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts#FC6 at 600×400 with 200% text, a hidden Activity connected first leaves the arrival to the Activity navigation shows",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts#FC6 at 1280×720 in forced colours, a hidden Activity connected first leaves the arrival to the Activity navigation shows",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts#FC6 split: with Activity in the second window, the arrival goes to the Activity the navigation shows"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC6 preservation: Activity reached from More › System (1280x720, and 600x400 at 200% text) takes the journal arrival: filters reset, keyboard on it (passes on the before-state too).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC6 With a hidden but connected Activity view present before a visible one (and in production navigation paths: split, second System pane, Activity in More), an arrival request (state/activitySection.ts) is consumed by the visible, mounted consumer navigation targets: its filters reset and it receives focus; the hidden one is untouched.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts#FC6 at 1280×720, Activity reached from More takes the arrival",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.arrival-lockdown.test.ts#FC6 at 600×400 with 200% text, Activity reached from More takes the arrival"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts"
      ],
      "class": "New",
      "behaviour": "FC7 repaired: the resolver keys names and not-found answers by the Engine's address; two Engines with the same hash each answer for themselves; the Tasks tray asks the Engine it reads its jobs from, and a late answer from the Engine it read before names nothing.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC7 The Tasks tray (substrates/tasks/indexingJobsBridge.ts) resolves file names at the Engine it reads from, like Activity, Library and the failed-files list; resolvePathLazy's cache is keyed by the Engine address as well as resource and hash, so two Engines (another address, or a profile switch to another Engine) never share a cached name or a cached not-found.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts#two Engines with the same hash: each name is read from, and remembered for, its own Engine",
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts#one Engine's \"not found\" is not another Engine's answer (a profile switched to another Engine)",
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts#a tray whose Engine has its own address names the file from that Engine, not the page's origin",
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts#after the tray moves to another Engine, the earlier Engine's late answer names nothing"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts"
      ],
      "class": "New",
      "behaviour": "FC7 preservation: the page's own Engine is one Engine whether named by its address or left unnamed (one read).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC7 The Tasks tray (substrates/tasks/indexingJobsBridge.ts) resolves file names at the Engine it reads from, like Activity, Library and the failed-files list; resolvePathLazy's cache is keyed by the Engine address as well as resource and hash, so two Engines (another address, or a profile switch to another Engine) never share a cached name or a cached not-found.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/hooks/resolvePathLazy.engineAddress.test.ts#the page's own Engine is one Engine, whether it is named by its address or left unnamed"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC8 repaired: at 600x400 with 200% text, Activity with 160 records (more than 100: the windowed list) logs no uncaught 'ResizeObserver loop' error while drawing and while scrolling to the oldest record and back, a screenful at a time; counted from the window's error event and the page's errors in real Chromium.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC8 Showing more than 100 records in Activity logs no uncaught 'ResizeObserver loop completed with undelivered notifications' error (lit-virtualizer). Prefer a fix without patching the dependency (for example a realistic row-size estimate so the virtualizer does not shrink its host inside its observer); count uncaught errors with 150+ rows in a real browser.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC8 at 600×400 with 200% text, 160 records draw and scroll without an uncaught ResizeObserver loop error"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC8 at 1280x720 and in forced colours: the same count stays zero on the candidate (3 of 3 runs). Not recorded as repaired: on the before-state these two sizes log the error only intermittently (failed in 2 of 4 observed runs), so they cannot show a deterministic fail-before; the 600x400 check above does.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC8 Showing more than 100 records in Activity logs no uncaught 'ResizeObserver loop completed with undelivered notifications' error (lit-virtualizer). Prefer a fix without patching the dependency (for example a realistic row-size estimate so the virtualizer does not shrink its host inside its observer); count uncaught errors with 150+ rows in a real browser.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC8 at 1280×720, 160 records draw and scroll without an uncaught ResizeObserver loop error",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC8 at 1280×720 in forced colours, 160 records draw and scroll without an uncaught ResizeObserver loop error"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC9 repaired: Activity's rows say who (You, The assistant, The engine), what (the details pane's titles: approvals, permissions, journaled actions, another window's copied actions by kind) and how it ended (Succeeded, Failed, Undone; Indexed, Failed) in words; no row shows SUCCESS/FAILURE/UNDONE, DENIED/GATED, DONE/FAILED, ISSUED, TYPED_CONFIRM, 'backend', 'copy-to-clipboard', 'open-pane', 'invoke-operation' or 'navigate:', for the backend's records, this window's journal and a journal restored after a reload; in real Chromium at 1280x720, 600x400 at 200% text and forced colours.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC9 Activity feed rows read in words, not raw codes: no SUCCESS/FAILURE, DENIED/GATED, DONE, 'backend', TYPED_CONFIRM, 'copy-to-clipboard' or 'invoke-operation:' as row text; reuse the details pane's wording functions (one place). Identifiers only where labelled for support.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC9 at 1280×720, the rows on screen read in words, never in recorded codes",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC9 at 600×400 with 200% text, the rows on screen read in words, never in recorded codes",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC9 at 1280×720 in forced colours, the rows on screen read in words, never in recorded codes",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#the backend's records: who, what and how it ended in words, never their codes",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#index records say indexed or failed, and who did it is the engine",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#this window's journal: its actions in words, an invocation by its operation",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.rowWords.test.ts#after a reload, the restored journal's rows read in words too"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC9 Activity feed rows read in words, not raw codes: no SUCCESS/FAILURE, DENIED/GATED, DONE, 'backend', TYPED_CONFIRM, 'copy-to-clipboard' or 'invoke-operation:' as row text; reuse the details pane's wording functions (one place). Identifiers only where labelled for support.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for activity feed, action ledger, outcome, originator, effect kind; README only)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts"
      ],
      "class": "New",
      "behaviour": "FC9 in the existing row checks: the outcome cell reads 'Succeeded'/'Failed' (tone and glyph unchanged) and the agent row's who cell reads 'The assistant'.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC9 Activity feed rows read in words, not raw codes: no SUCCESS/FAILURE, DENIED/GATED, DONE, 'backend', TYPED_CONFIRM, 'copy-to-clipboard' or 'invoke-operation:' as row text; reuse the details pane's wording functions (one place). Identifiers only where labelled for support.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts#projects occurredAt + outcome — the \"timing\" and \"outcomes\" the subtitle promises (tempdoc 558 Deepening 3)",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts#renders a FAILURE outcome as the error tone + glyph — failed must read as failed (tempdoc 558)",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts#filters the stream by originator via FilterChip facets (tempdoc 558 §E1)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC9 Activity feed rows read in words, not raw codes: no SUCCESS/FAILURE, DENIED/GATED, DONE, 'backend', TYPED_CONFIRM, 'copy-to-clipboard' or 'invoke-operation:' as row text; reuse the details pane's wording functions (one place). Identifiers only where labelled for support.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for activity feed, action ledger, outcome, originator, effect kind; README only)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC9 in A19 (forced colours, light and dark): an outcome still reads as a glyph and a word, the word now 'Failed'.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC9 Activity feed rows read in words, not raw codes: no SUCCESS/FAILURE, DENIED/GATED, DONE, 'backend', TYPED_CONFIRM, 'copy-to-clipboard' or 'invoke-operation:' as row text; reuse the details pane's wording functions (one place). Identifiers only where labelled for support.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a light forced-colours palette, the unread mark stays visible with its label, and an outcome reads as a glyph and a word",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed-lockdown.test.ts#A19 — on a dark forced-colours palette, the unread mark stays visible with its label, and an outcome reads as a glyph and a word"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC9 Activity feed rows read in words, not raw codes: no SUCCESS/FAILURE, DENIED/GATED, DONE, 'backend', TYPED_CONFIRM, 'copy-to-clipboard' or 'invoke-operation:' as row text; reuse the details pane's wording functions (one place). Identifiers only where labelled for support.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for activity feed, action ledger, outcome, originator, effect kind; README only)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts"
      ],
      "class": "New",
      "behaviour": "FC9 in the journal arrival check: the journaled row reads 'Open pane \"here\"'. Candidate-only evidence: the file uses the addressed arrival API (FC6), which the before-state lacks, so it cannot run there; the same wording is pinned with an assertion fail-before by the rowWords journal checks.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC9 Activity feed rows read in words, not raw codes: no SUCCESS/FAILURE, DENIED/GATED, DONE, 'backend', TYPED_CONFIRM, 'copy-to-clipboard' or 'invoke-operation:' as row text; reuse the details pane's wording functions (one place). Identifiers only where labelled for support.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts#a pointer to the journal resets the feed's filters to this window's records and puts the keyboard there"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.details.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The two arrival checks now address the request to the page, as the Shell's navigation does once applied (`addressActivitySection(() => document.body)`), and read it back for their own Activity (`takeActivitySection(el)`). Inputs, preconditions, the observations (focus, filters, the request consumed) and expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC6 With a hidden but connected Activity view present before a visible one (and in production navigation paths: split, second System pane, Activity in More), an arrival request (state/activitySection.ts) is consumed by the visible, mounted consumer navigation targets: its filters reset and it receives focus; the hidden one is untouched.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.render.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The four arrival checks address the request to the page after requesting it (`landOnPage()`), as the navigation does, and read it back for their own Activity (`takeActivitySection(el)` / `(staying)`). Inputs, preconditions, the observations (focus, filters, the request consumed) and expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC6 With a hidden but connected Activity view present before a visible one (and in production navigation paths: split, second System pane, Activity in More), an arrival request (state/activitySection.ts) is consumed by the visible, mounted consumer navigation targets: its filters reset and it receives focus; the hidden one is untouched.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.details.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "'Ctrl+K Show Effect Audit Log arrives at the journal in Activity' reads the pending request for the Activity window the Shell navigated to (`takeActivitySection(activity)`), which also requires the Shell to have addressed it there. Inputs, preconditions, the observations (focus, filters, the request consumed) and expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC6 With a hidden but connected Activity view present before a visible one (and in production navigation paths: split, second System pane, Activity in More), an arrival request (state/activitySection.ts) is consumed by the visible, mounted consumer navigation targets: its filters reset and it receives focus; the hidden one is untouched.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts"
      ],
      "class": "New",
      "behaviour": "FC10 repaired: on a fresh install where nobody asked for AI (verbatim real-Engine status, semantic queue pending), the pill shows 'Keyword search ready' and no live region says 'Semantic search waiting for AI install' or Needs you; after a reload, before the AI install status is read, pill and spoken status both say 'Checking search status' (never 'Service degraded'); during an Engine outage (real status poller, refused reads) the spoken search status is what the pill shows, never 'Catching up…'.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC10 (from batch A's real-Engine recheck) The status pill's screen-reader text (its hidden polite live region) follows the same rule as the visible pill: on a fresh install where nobody asked for AI it never announces 'Semantic search waiting for AI install' or any Needs-you wording. FC10 also: during an Engine outage the pill's hidden live text does not say 'Catching up…' while the visible pill says 'Checking search status'; visible and spoken text agree.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts#the pill says keyword search is ready (semantic queue pending), and nothing spoken says Needs you or waiting for AI",
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts#after a reload, before the AI install status is read again: the pill and its spoken status both say it is checking",
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts#the Engine stops answering: the spoken search status says what the pill shows, never \"Catching up…\""
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts"
      ],
      "class": "New",
      "behaviour": "FC10 preservation: with the semantic queue empty the fresh install was already silent about AI; an AI install the person started that failed or was interrupted still shows '1 needs you' and something spoken says so.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC10 (from batch A's real-Engine recheck) The status pill's screen-reader text (its hidden polite live region) follows the same rule as the visible pill: on a fresh install where nobody asked for AI it never announces 'Semantic search waiting for AI install' or any Needs-you wording. FC10 also: during an Engine outage the pill's hidden live text does not say 'Catching up…' while the visible pill says 'Checking search status'; visible and spoken text agree.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts#the pill says keyword search is ready (semantic queue empty), and nothing spoken says Needs you or waiting for AI",
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts#an AI install the person started that failed still needs them, shown and spoken alike",
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts#an AI install the person started that was interrupted still needs them, shown and spoken alike"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC10 (from batch A's real-Engine recheck) The status pill's screen-reader text (its hidden polite live region) follows the same rule as the visible pill: on a fresh install where nobody asked for AI it never announces 'Semantic search waiting for AI install' or any Needs-you wording. FC10 also: during an Engine outage the pill's hidden live text does not say 'Catching up…' while the visible pill says 'Checking search status'; visible and spoken text agree.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for live region, aria-live, screen reader, announcer, verdict, outage)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts"
      ],
      "class": "New",
      "behaviour": "FC10 in the verdict announcer check: within the reconnect grace the announcer says 'Checking search status' politely, as the pill does; once the pill says it cannot reach the engine, the announcer says \"Can't reach the engine\" assertively.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC10 (from batch A's real-Engine recheck) The status pill's screen-reader text (its hidden polite live region) follows the same rule as the visible pill: on a fresh install where nobody asked for AI it never announces 'Semantic search waiting for AI install' or any Needs-you wording. FC10 also: during an Engine outage the pill's hidden live text does not say 'Catching up…' while the visible pill says 'Checking search status'; visible and spoken text agree.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#mirrors the verdict politely and an error verdict assertively, never as \"All systems operational\""
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installFailure.test.ts"
      ],
      "class": "New",
      "behaviour": "FC11 repaired: a failed AI install (AiInstallService ALL_DOWNLOADS_FAILED, models folder not prepared) in the status panel and on AI Brain says what went wrong and what to do in words; the Engine's message appears once on each, labelled 'Detail for support', under the panel row's Details.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC11 A failed AI install's item in the status panel (and AI Brain's matching line) says in words what went wrong and what to do; the Engine's raw message (for example 'Failed to prepare download directory: /home/...') appears at most once, labelled as detail for support, never repeated.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installFailure.test.ts#says what went wrong and what to do, with the Engine's message once, under Details, labelled for support",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installFailure.test.ts#says what went wrong and what to do, with the Engine's message once, labelled for support"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiVerdict.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC11 A failed AI install's item in the status panel (and AI Brain's matching line) says in words what went wrong and what to do; the Engine's raw message (for example 'Failed to prepare download directory: /home/...') appears at most once, labelled as detail for support, never repeated.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for install failed, AI install, error message, support detail)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiVerdict.test.ts"
      ],
      "class": "New",
      "behaviour": "FC11 in the presentation projection: an install_failed verdict without a known code reads 'The AI install stopped before it finished. Install again to continue.', never the service's text.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC11 A failed AI install's item in the status panel (and AI Brain's matching line) says in words what went wrong and what to do; the Engine's raw message (for example 'Failed to prepare download directory: /home/...') appears at most once, labelled as detail for support, never repeated.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/aiVerdict.test.ts#install_failed says in words what went wrong and what to do; the service's own text is not the body (FC11)"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC11 A failed AI install's item in the status panel (and AI Brain's matching line) says in words what went wrong and what to do; the Engine's raw message (for example 'Failed to prepare download directory: /home/...') appears at most once, labelled as detail for support, never repeated.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for install failed, AI install, error message, support detail)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FC11 in FA3c: the failed install row's detail is the verdict's words for its code (IO_ERROR), and its one Details line is 'Detail for support: Could not write to the models folder'.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC11 A failed AI install's item in the status panel (and AI Brain's matching line) says in words what went wrong and what to do; the Engine's raw message (for example 'Failed to prepare download directory: /home/...') appears at most once, labelled as detail for support, never repeated.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#the rows say what happened in the AI verdict's own words"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC12 repaired: at 600x400 with 200% text, with and without forced colours, Activity with 160 records (the windowed list), whose list starts below what the window shows, draws its first rows without the person scrolling: on opening, after a quick filter change made with the filters at the top of the view, after a reload and after switching to Health and back; when the list is then brought to the top of the view, its first row is on screen in the same moment, in real Chromium.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC12 (found by the C2 builder; also on main) At 600x400 with 200% text (and every size), Activity's feed draws its rows as soon as they are loaded, without the person scrolling first; the first rows are visible on opening, after a filter change, after a reload and after returning to Activity.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2-fc12.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC12 at 600×400 with 200% text, the first rows are drawn on opening, after a filter change, a reload and a return from Health",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC12 at 600×400 with 200% text in forced colours, the first rows are drawn on opening, after a filter change, a reload and a return from Health"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FC12 at 1280x720: the same sequence draws the first rows at each step (passes on the before-state too: there the list starts inside the view, so it was drawn already).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn (2026-11-03)",
          "delegationScope": "Design choices for the in-between UI of the main windows (Activity, file names, the status pill and panel, AI Brain's install line): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FC12 (found by the C2 builder; also on main) At 600x400 with 200% text (and every size), Activity's feed draws its rows as soon as they are loaded, without the person scrolling first; the first rows are visible on opening, after a filter change, after a reload and after returning to Activity.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-activity-c2-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.manyRows-lockdown.test.ts#FC12 at 1280×720, the first rows are drawn on opening, after a filter change, a reload and a return from Health"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records
