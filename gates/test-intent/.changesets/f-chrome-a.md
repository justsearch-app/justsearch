---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: d99fb790-ca92-48ed-a665-bb43b6bf2af6
---

# Test-intent entries

Follow-up batch A (FA1–FA4): the top bar at small sizes, Settings wording, first-use status and own-file counts.
The scenarios are the orchestrator's decisions under the owner's quoted delegation, recorded as agent choices.
Builder execution evidence: `gates/test-intent/evidence/f-chrome-a.json` (one run, `--before origin/main`).
Repaired checks (`repaired: true`) fail on the before-state with an assertion in the named check; preservation
checks (`repaired: false`) keep their passing before-state results. Existing tests changed in this branch are
classified as Obsolete (expectations FA4 replaces, with the New checks that replace them named) or Adaptation.
Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FA1: at 600x400, 200% text, window controls present, More and the area menu open inside the window with their words uncut, also in forced colours and after the window is resized while open.",
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA1 At 600x400 with 200% text, with the window controls present: every top-bar menu (More, the area switcher, the status menu) opens entirely inside the window (left >= 0, right <= window width), labels not clipped; the area switcher itself fits inside the window. 1280x720 and 900x800 unchanged. Checks assert element bounds, not the document's scroll width.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#More opens entirely inside the window, its words not cut (forced colours: false)",
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#More opens entirely inside the window, its words not cut (forced colours: true)",
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#the area switcher and its menu fit inside the window (forced colours: false)",
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#the area switcher and its menu fit inside the window (forced colours: true)",
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#a menu opened in a small window follows the window when it grows and shrinks"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FA1 preservation: the status panel stays inside the small window; at 1280x720 and 900x800 (with and without window controls) More still hangs under its button with its end edge on the button's; at 600x400 normal text the area menu still starts at its button.",
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA1 At 600x400 with 200% text, with the window controls present: every top-bar menu (More, the area switcher, the status menu) opens entirely inside the window (left >= 0, right <= window width), labels not clipped; the area switcher itself fits inside the window. 1280x720 and 900x800 unchanged. Checks assert element bounds, not the document's scroll width.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#the status panel opens inside the window",
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#1280x720: More hangs under its button, its end edge on the button's",
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#900x800: More hangs under its button, its end edge on the button's",
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#600x400 at normal text: the area menu hangs under its button, its start edge on the button's"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.internalReferences.test.ts"
      ],
      "class": "New",
      "behaviour": "FA2: no Settings section shows internal design references to the Operator or Developer audience (Plugin permissions, Workspace profiles).",
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA2 No Settings section, at any audience (user or developer), shows internal design references as text (for example 'Tempdoc 543 §14.3', '§13.6 substrate', 'slice 486'); Plugin permissions and Workspace profiles say in plain words what they do. A guard renders every Settings section and fails on such patterns.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.internalReferences.test.ts#FA2 no section shows internal design references to the OPERATOR audience",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.internalReferences.test.ts#FA2 no section shows internal design references to the DEVELOPER audience"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.internalReferences.test.ts"
      ],
      "class": "New",
      "behaviour": "FA2 preservation: the User audience already showed none and still shows none.",
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA2 No Settings section, at any audience (user or developer), shows internal design references as text (for example 'Tempdoc 543 §14.3', '§13.6 substrate', 'slice 486'); Plugin permissions and Workspace profiles say in plain words what they do. A guard renders every Settings section and fails on such patterns.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.internalReferences.test.ts#FA2 no section shows internal design references to the USER audience"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FA3: fresh install without AI shows 'Getting started · 1 of 3' with nothing under Needs you; after first use 'Keyword search ready'; a running AI install shows its progress, not Needs you.",
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA3 Fresh install, no AI models, nothing asked of AI: the status pill shows the first-use progress ('Getting started · 1 of 3'), not 'Needs you · Semantic search waiting for AI install'. AI is optional: missing AI models the person never asked to install are not a Needs-you state; Needs you stays for things that block what the person asked for (an AI install they started that failed, an AI feature they turned on that cannot run). Agent choices within it: semantic work waiting while an install is running or starting is not Needs you either; after first use the calm pill reads 'Keyword search ready', not 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#fresh install, no AI models, nothing asked of AI: the pill shows first-use progress, nothing needs the person",
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#after first use, keyword search reads as ready, never \"Up to date\" over missing semantic coverage",
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#an AI install the person started shows its progress, not \"Needs you\", while it runs"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FA3 preservation: a failed install, an interrupted (paused) install, installed models that cannot run, semantic search turned on that cannot run, and another enabled producer that cannot run all still show as Needs you.",
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA3 Fresh install, no AI models, nothing asked of AI: the status pill shows the first-use progress ('Getting started · 1 of 3'), not 'Needs you · Semantic search waiting for AI install'. AI is optional: missing AI models the person never asked to install are not a Needs-you state; Needs you stays for things that block what the person asked for (an AI install they started that failed, an AI feature they turned on that cannot run). Agent choices within it: semantic work waiting while an install is running or starting is not Needs you either; after first use the calm pill reads 'Keyword search ready', not 'Up to date'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#an AI install they started that failed shows as needing them",
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#an interrupted install left waiting shows as needing them",
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#installed AI models that are not running shows as needing them",
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#semantic search turned on that cannot run shows as needing them",
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts#another enabled producer that cannot run shows as needing them"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiStateStore.ownCount.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts"
      ],
      "class": "New",
      "behaviour": "FA4: the composer and Library count the person's own files, never bundled Help; none is said in words; unknown (before a status, or an older Engine) is said in words, never a number; a late own count replaces the unknown one; the store projects and retains the own count.",
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA4 The composer's 'Searching N files' and Library's 'N searchable documents' count only the person's own files (ownSearchableDocuments), not bundled Help; with no own files they say so in words; when the count is unknown they say it is unknown, never 0. Agent choices: the words 'None of your files are searchable yet', 'Searching your files · how many is not known yet' and 'Searchable document count not known'; the status store projects and retains the own count; an older Engine that does not report it leaves the count unknown.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts#states the own count, not the count that includes Help",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts#one own file reads in the singular",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts#with no own files it says so in words and offers the way to add some, whatever Help holds",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts#an Engine that does not report the own count: the count is said to be unknown, never a number",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts#before any status: unknown in words, never 0",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ownCount.test.ts#an own count reported late replaces the unknown one",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts#FA4 counts the person's own files, not the bundled Help",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts#FA4 one own file reads in the singular",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts#FA4 with no own files it says so in words, whatever Help holds",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts#FA4 an Engine that does not report the own count: unknown in words, never a number",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts#FA4 the own count reported late replaces the unknown one",
        "modules/ui-web/src/shell-v0/state/aiStateStore.ownCount.test.ts#FA4 the status store projects and retains the own count; a reported 0 stays 0, an unreported one stays unknown"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiStateStore.test.ts"
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA4 The composer's 'Searching N files' and Library's 'N searchable documents' count only the person's own files (ownSearchableDocuments), not bundled Help; with no own files they say so in words; when the count is unknown they say it is unknown, never 0. Agent choices: the words 'None of your files are searchable yet', 'Searching your files · how many is not known yet' and 'Searchable document count not known'; the status store projects and retains the own count; an older Engine that does not report it leaves the count unknown. Removed: 'C-4: a settled poll retains searchableDocumentCount; 0 is retained as 0, not as null' — the settled index no longer retains the Help-inclusive default-scope count, it retains the own count instead (covered by the New check in state/aiStateStore.ownCount.test.ts).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for searchable documents, default scope, corpus, ownSearchableDocuments, bundled Help, 811)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/aiStateStore.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The three E2 retention expectations name the settled index's second field ownDocumentCount instead of searchableDocumentCount; inputs (no own or searchable field reported), the provisional fallback poll and the expected value (null: not reported) are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-honesty.test.ts"
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA4 The composer's 'Searching N files' and Library's 'N searchable documents' count only the person's own files (ownSearchableDocuments), not bundled Help; with no own files they say so in words; when the count is unknown they say it is unknown, never 0. Agent choices: the words 'None of your files are searchable yet', 'Searching your files · how many is not known yet' and 'Searchable document count not known'; the status store projects and retains the own count; an older Engine that does not report it leaves the count unknown. Removed: 'prefers the DEFAULT-SCOPE population over the whole-index count' and 'falls back to the whole-index count when the scope population is not reported' — the composer's count is the own count only; an unreported own count is unknown, never the Help-inclusive or whole-index count (covered by the New checks in SearchV3View.ownCount.test.ts).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for searchable documents, default scope, corpus, ownSearchableDocuments, bundled Help, 811)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-honesty.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "input re-encoding"
      ],
      "whatMoved": "The settled-index fixture's default and the retained-zero fixture name ownDocumentCount instead of searchableDocumentCount; the reported-zero cases report their zero as ownDocumentCount: 0 (with documentCount 0 and 900 as before). Expected outcomes kept: unknown before a settled poll, 'empty' for a reported zero, unknown for an unknown or stale observation."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
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
          "delegationScope": "Design choices for the in-between UI of the main windows (top bar, Settings, status pill, counts): i think it would be to bring the aspects of the UI that are inbetween the main windows up to date, and remove bad practices and code that was implemented by necessity without an overarching design.",
          "choice": "FA4 The composer's 'Searching N files' and Library's 'N searchable documents' count only the person's own files (ownSearchableDocuments), not bundled Help; with no own files they say so in words; when the count is unknown they say it is unknown, never 0. Agent choices: the words 'None of your files are searchable yet', 'Searching your files · how many is not known yet' and 'Searchable document count not known'; the status store projects and retains the own count; an older Engine that does not report it leaves the count unknown. Removed: 'states the default-scope population when there is one' (the line now states the own count) and 'claims nothing before a settled poll — the case the shipped landing gets wrong' (FA4: an unknown count is said to be unknown, in words; never 0 is kept by the New check 'before any status: unknown in words, never 0', which the governance/search-window-scenarios.v1.json SW-READY-02 observation now names).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for searchable documents, default scope, corpus, ownSearchableDocuments, bundled Help, 811); governance/search-window-scenarios.v1.json SW-READY-02 ('Unknown status never invents ... a document count') is kept and rebound to the New check"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The status helper can report ownSearchableDocuments; the reported-zero remedy case reports own: 0 beside documents 0 and searchable 0, and the 'no chrome once working' case reports own: 12 beside searchable 12. Observations and expected outcomes (remedy text and navigation; corpus line present on the landing and gone once docked) are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.controls.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The no-documents status reports ownSearchableDocuments: 0 beside indexedDocuments: 0, the zero the composer's remedy now reads. The remedy activation through the primitive and its single navigation to Library are unchanged."
    }
  ]
}
```

## Acceptance records
