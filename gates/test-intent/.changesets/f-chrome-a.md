---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: d99fb790-ca92-48ed-a665-bb43b6bf2af6
---

# Test-intent entries

Follow-up batch A (FA1–FA4): the top bar at small sizes, Settings wording, first-use status and own-file counts.
The scenarios are the orchestrator's decisions under the owner's quoted delegation, recorded as agent choices.
Builder execution evidence: `gates/test-intent/evidence/f-chrome-a.json` (one run, `--before 83b6a6a577`, the revision `origin/main` named when it was first made) and, for the amendments FA1a, FA3a, FA3b, FA3c and FA4a, `gates/test-intent/evidence/f-chrome-a-amend.json` (one run, `--before 448edc33c3`).
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
      "behaviour": "FA3: fresh install without AI shows 'Getting started · 1 of 3' with nothing under Needs you; after first use 'Keyword search ready'; a running AI install shows its progress, not Needs you. The fixture's search verdict is the one a real Engine without AI models reports (degraded: index.dense_unavailable, lambdamart.not_configured, inference.deactivated, encoders.health.embedding_not_ready).",
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
      "behaviour": "FA3 preservation: installed models that cannot run, semantic search turned on that cannot run, and another enabled producer that cannot run all still show as Needs you (over the search verdict a real Engine without AI models reports). A failed or interrupted install: FA3c.",
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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FA1a: the status panel opened at 900x800 (window controls present) stays inside the window when it shrinks to 600x400 and when the text then grows to 200%.",
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
          "choice": "FA1a A top-bar menu or the status panel that is open while the window shrinks (for example 900x800 to 600x400) stays inside the window.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#the status panel opened at 900x800 stays inside as the window shrinks to 600x400 and the text grows to 200%"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "FA1a preservation: More opened at 900x800 already stayed inside the window as it shrank to 600x400 and the text grew to 200%, and still does.",
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
          "choice": "FA1a A top-bar menu or the status panel that is open while the window shrinks (for example 900x800 to 600x400) stays inside the window.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts#More opened at 900x800 stays inside as the window shrinks to 600x400 and the text grows to 200%"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FA3a: over the real fresh-install status (verbatim from an isolated Engine without models) and the real derivations, the pill reads 'Keyword search ready' after first use with the semantic queue pending or empty, nothing under Needs you or Problems; after a reload 'Checking' lasts only until the AI install status is observed; chat switched on without AI models needs the person instead of 'Checking': through the blocked semantic work, or with the semantic queue empty one Needs-you row saying semantic search is degraded (opening AI Brain).",
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
          "choice": "FA3a After first use, a fresh install whose only gap is AI nobody asked for reaches a calm, honest state (for example 'Keyword search ready') on the real Engine, where the AI verdict is degraded/not_installed and the semantic index unavailable; never a lasting 'Checking search status'. 'Checking' is only for an actual unknown, and must resolve. Agent choices: 'nobody asked for AI' is the AI verdict's own fact (notRequested: not installed, no install running, failed or interrupted, chat and semantic search not switched on); chat switched on without AI models is AI asked for and needs the person.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#after first use the pill says keyword search is ready (semantic queue pending), never \"Checking\"",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#after first use the pill says keyword search is ready (semantic queue empty), never \"Checking\"",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#after a reload \"Checking\" lasts only until the AI install status is observed",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#chat switched on without AI models: not calm, and not \"Checking\" — the person asked for AI",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#chat switched on, semantic queue empty: one Needs-you row says semantic search is degraded, never \"Checking\""
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FA3a preservation: the embedded real status still yields the verdicts the real page computed (search degraded with the four measured reasons, AI not installed, semantic work blocked).",
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
          "choice": "FA3a After first use, a fresh install whose only gap is AI nobody asked for reaches a calm, honest state (for example 'Keyword search ready') on the real Engine, where the AI verdict is degraded/not_installed and the semantic index unavailable; never a lasting 'Checking search status'. 'Checking' is only for an actual unknown, and must resolve. Agent choices: 'nobody asked for AI' is the AI verdict's own fact (notRequested: not installed, no install running, failed or interrupted, chat and semantic search not switched on); chat switched on without AI models is AI asked for and needs the person.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#the fixture reproduces what the real page computed: search degraded, AI not installed"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FA3b: no search banner above the composer, and no 'Service degraded' screen-reader announcement from the status pill, when the only gap is AI nobody asked for.",
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
          "choice": "FA3b The same rule elsewhere in the in-between UI: no 'Semantic search degraded' banner (or similar warning) above the composer when nobody asked for AI; it stays when the person asked for AI and it cannot run. Agent choice: the status pill's screen-reader verdict announcement follows the same rule.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#says nothing when the only gap is AI nobody asked for (semantic queue pending or empty)",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#announce no search warning when the only gap is AI nobody asked for"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FA3b preservation: the 'Semantic search degraded.' banner and the verdict announcement stay when the person asked for AI and it cannot run (chat switched on without models, a failed install, an interrupted install).",
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
          "choice": "FA3b The same rule elsewhere in the in-between UI: no 'Semantic search degraded' banner (or similar warning) above the composer when nobody asked for AI; it stays when the person asked for AI and it cannot run. Agent choice: the status pill's screen-reader verdict announcement follows the same rule.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#stays when the person asked for AI and it cannot run: chat is switched on without AI models",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#stays when the person asked for AI and it cannot run: the AI install the person started failed",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#stays when the person asked for AI and it cannot run: the AI install the person started was interrupted",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#still announce it when the person asked for AI and it cannot run"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts"
      ],
      "class": "New",
      "behaviour": "FA3c: an interrupted (idle, not fully installed, resumable) or failed AI install the person started shows as one Needs-you row ('1 needs you', opening AI Brain), with an empty or a pending semantic queue, worded by the AI verdict.",
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
          "choice": "FA3c With an empty semantic queue, an AI install the person started that was interrupted (idle, not fully installed, resumable) or failed shows as needing them ('Needs you'), not 'Up to date' and not only 'Problems right now'. Agent choice: one Needs-you row for the install, which also stands for the semantic work waiting on it, with or without a pending semantic queue.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#interrupted, with an empty or a pending semantic queue: one Needs-you row",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#failed, with an empty or a pending semantic queue: one Needs-you row",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts#the rows say what happened in the AI verdict's own words"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.ownCount.test.ts"
      ],
      "class": "New",
      "behaviour": "FA4a: with the own count unknown, Library's headline does not say the person's files are searchable; AI Brain's 'Your files' counts the own files (Help excluded), singular for one, 'None yet' for none, 'File count unknown' when unknown.",
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
          "choice": "FA4a When the own count is unknown, no headline claims the person's files are searchable; Library says the count is not known. AI Brain's 'Your files · N' uses the own count (Help excluded) like the composer and Library. Agent choices: Library's headline then reads 'Your folders are indexed' (or the indexing activity while files are being indexed); AI Brain says 'None yet' for no own files and 'File count unknown' when unknown.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/f-chrome-a-amend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownCount.test.ts#FA4a with the own count unknown (only Help and an indexed folder reported), no headline says the person's files are searchable",
        "modules/ui-web/src/shell-v0/views/BrainSurface.ownCount.test.ts#FA4a counts the person's own files, not the bundled Help",
        "modules/ui-web/src/shell-v0/views/BrainSurface.ownCount.test.ts#FA4a one own file reads in the singular",
        "modules/ui-web/src/shell-v0/views/BrainSurface.ownCount.test.ts#FA4a with no own files it says so in words, whatever Help holds",
        "modules/ui-web/src/shell-v0/views/BrainSurface.ownCount.test.ts#FA4a an Engine that does not report the own count: unknown in words, never a number"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
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
          "choice": "FA3c With an empty semantic queue, an AI install the person started that was interrupted (idle, not fully installed, resumable) or failed shows as needing them ('Needs you'), not 'Up to date' and not only 'Problems right now'. Agent choice: one Needs-you row for the install, which also stands for the semantic work waiting on it, with or without a pending semantic queue. Removed: ST5's expectation that a failed AI install is listed under Problems (covered by the New FA3c checks in state/unrequestedAi.test.ts).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for AI install, install failed, needs you, Your files are searchable, searchable documents, Library headline)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.transitions.test.ts"
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
          "choice": "FA4a When the own count is unknown, no headline claims the person's files are searchable; Library says the count is not known. AI Brain's 'Your files · N' uses the own count (Help excluded) like the composer and Library. Agent choices: Library's headline then reads 'Your folders are indexed' (or the indexing activity while files are being indexed); AI Brain says 'None yet' for no own files and 'File count unknown' when unknown. Removed: the expectation that Library's headline says 'Your files are searchable' (and 'Indexing your files' for 0) from a status that does not report the own count (covered by the New FA4a check in views/LibrarySurface.ownCount.test.ts).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions/* searched for AI install, install failed, needs you, Your files are searchable, searchable documents, Library headline)"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.transitions.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The status helpers report ownSearchableDocuments equal to indexedDocuments (redesign: when a count is given; transitions: 20 beside 20). Observations and expected headlines ('Checking your folders', 'Indexing your files', 'Your files are searchable', the unconfirmed, catching-up and restarting headlines) are unchanged."
    }
  ]
}
```

## Acceptance records
