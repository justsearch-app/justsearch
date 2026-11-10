---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s5-continuation
---

# First launch and Getting started (S5)

First use reads the Engine's nullable ownSearchableDocuments count and watched roots.
The Engine counts the default search population and the population without InternalCollections
on one acquired serving reader. The field crosses IPC, CoreIndexView, wire status, JSON schema
and generated UI validation. An older worker or unknown index means unknown, never zero.
There is no first-use collection-facet request or separate Help-count state.

The factual empty view survives Hide and reload. Getting started and the calm pill use the
same current step; the card shows a progress bar. While its own-document progress line shows,
Search suppresses the ordinary corpus line. Starting another folder attempt clears the old error.
The existing shared Library add operation, consent, plugin progress and navigation remain.

## Saved state and retired checks

Progress remains in cross-profile justsearch.userState.v2.walkthroughState under
core.getting-started, with try-search and reopened-from-help markers. No new storage key or
legacy welcome migration is introduced. The old core walkthrough is retired; plugin tours
retain their completion triggers. Fixtures suppress the card except the deliberately unseeded
first-use and first-use-reading captures.

Facet subtraction, cadence, retry, abort and zero-total shortcut expectations are superseded
by the 2026-11-10 SB16 design. Their entries are Obsolete and their evidence files are removed.
Surviving New entries retain their classes. Mock and fixture inputs encode the same populations
through the new Engine field. Test file changes and the retained oracle are described below.

## Evidence

The unchanged run-evidence.mjs producer writes each shell-s5-redesign record. New Engine,
mapper, worker and Chromium repair checks fail with assertions on bd0882cb6b and pass after.
Surviving initial-feature repair checks fail on e3a479db7d and pass after. Navigation
preservation passes before and after. The reading repair fails with the intended missing
“still reading” suffix on dee85d8794; its explicit compatibility patch only reads the new count
input, leaving that older reading calculation intact. The producer's full-file before record
also includes preservation cases; only the named repair cases require assertion failure.

An external adapter uses owned shared clones and caps Vitest at four workers. Its source and
the compatibility patch are retained under build/s5-redesign-checks, alongside acceptance
logs, measured axe captures, the nine CI flow captures and first-use proportion measurements.
No stash, commit, push, fetch, rebase, shared dev stack or owner application is used.
The proportion register retains its bounds and additionally requires visible current-step and
progress-bar witnesses. Independent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "class": "New",
      "behaviour": "SB1–SB11: unknown/zero/positive facts, queue wording, empty watched roots, ad-hoc content, optional AI, every legacy welcome shape, Hide/reload/Help, profile and reset projection.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Unknown is not empty: while roots or counts are unsettled or stale, unavailable or erroring, Search shows its ordinary composer and no setup advice, with no flash of \"Nothing to search yet\" on the way from unknown to positive."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn 2",
          "quote": "Step 1 is done when the person has searchable documents of their own; a folder being read shows step 1 as under way. Step 3 is done when AI is active. Step 2 is done when a search returned while the person's own documents were searchable; a search over Help alone does not count. Only step 2 needs a saved mark."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "People who finished or dismissed the old welcome do not see Getting started, unless they have no folders (C5). Partial old progress does not count as any new step being done. Old records are left in place unmodified; absent, partial, finished, dismissed and malformed records are all handled against both empty and populated engines."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Existing content without watched folders (for example ad-hoc ingest): searchable documents make Search usable, with no demand for another folder."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "A watched folder yielding nothing (empty, excluded, unsupported, or failed): the card stays truthful, step 1 is not presented as success for search, and it points to Library."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.test.ts"
      ],
      "class": "New",
      "behaviour": "SB3–SB6/SB12: shared add operation, desktop cancel, retry and focus after leaving, suggestion refusals/deduplication/absence, count-qualified search completion, remount during indexing and a pending plugin-only card.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Add a folder uses the existing add flow: native picker on desktop, manual entry in the browser. Cancelling changes nothing and returns focus to the button. An invalid path, refusal or failure says so and can be retried."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Suggested folders from the suggested-roots API show name and path, no file counts. Choosing one adds only that folder, once, through the same operation, even if clicked repeatedly."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn 2",
          "quote": "Step 1 is done when the person has searchable documents of their own; a folder being read shows step 1 as under way. Step 3 is done when AI is active. Step 2 is done when a search returned while the person's own documents were searchable; a search over Help alone does not count. Only step 2 needs a saved mark."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "When the core steps are finished and a plugin walkthrough is pending and not dismissed, the card shows with only that plugin's section; today's behaviour shows a pending contributed walkthrough without being asked, and SB12 keeps its behaviour."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.persistence.test.ts"
      ],
      "class": "New",
      "behaviour": "SB10/SB11/SB14: actual shared UserStateDocument instances adopt Hide/Help saves, preserve unrelated values, and viewing malformed legacy storage does not rewrite it.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "People who finished or dismissed the old welcome do not see Getting started, unless they have no folders (C5). Partial old progress does not count as any new step being done. Old records are left in place unmodified; absent, partial, finished, dismissed and malformed records are all handled against both empty and populated engines."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Restored navigation and explicit URLs win: Getting started never replaces a saved destination or adds a Back/Forward entry. Co-resident saved values survive."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.persistence.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.pill.test.ts"
      ],
      "class": "New",
      "behaviour": "SB9: current step number replaces calm or merely unknown only once first-use facts are known; unreachable, limited, needs, problems, working and finished statuses retain precedence.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn 2",
          "quote": "The pill shows \"Getting started · n of 3\", where n is the step the person is on (1 at first launch, as in ref-01), only where it would otherwise be calm or merely unknown, once the first-use state is known. A fresh install without AI models must not sit at \"Checking search status\" instead. Every higher status (unreachable, limited, needs you, problems, working) still wins."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-pill.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.pill.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.observations.test.ts"
      ],
      "class": "New",
      "behaviour": "SB2/SB4/SB6: unavailable/unknown/stale and aged observations never become empty or progress claims; malformed suggested-root wire items are rejected.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Unknown is not empty: while roots or counts are unsettled or stale, unavailable or erroring, Search shows its ordinary composer and no setup advice, with no flash of \"Nothing to search yet\" on the way from unknown to positive."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Suggested folders from the suggested-roots API show name and path, no file counts. Choosing one adds only that folder, once, through the same operation, even if clicked repeatedly."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "api/domains/indexing.ts:70-88 getSuggestedRoots has no UI caller today and does not validate items: validate the wire shape."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.observations.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.reset.test.ts"
      ],
      "class": "New",
      "behaviour": "SB10: the real pre-boot reset storage gate removes Hide and Help state before a fresh launch.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.reset.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.plugins.test.ts"
      ],
      "class": "New",
      "behaviour": "SB12 preservation: command, setting and extension-install events still complete plugin walkthrough steps and retain their saved progress on reload.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/GettingStarted.plugins.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/manifest/walkthroughContributions.test.ts"
      ],
      "class": "New",
      "behaviour": "SB12 preservation: contributed manifest registration retains provenance/source/steps, duplicate manifests are refused without a duplicate tour, and uninstall removes the contribution.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Write these tests first and show each failing before your change for the reason under test:",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "Retire the core welcome contribution while keeping plugin walkthrough installation and removal behaviour.",
          "choice": "Use a sample contributed manifest for lifecycle coverage after retiring the core welcome installer. Its duplicate-install rejection is the unchanged manifest API contract; the deleted core wrapper's no-op guard is retired with that wrapper.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/manifest/walkthroughContributions.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB2/SB3/SB5/SB6/SB10/SB13: real Shell/Search in private Chromium checks facts, focus, cross-window storage, no-AI search and Help after results, reload during indexing, pending-add navigation, three viewport sizes and three palettes at 200% text.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Unknown is not empty: while roots or counts are unsettled or stale, unavailable or erroring, Search shows its ordinary composer and no setup advice, with no flash of \"Nothing to search yet\" on the way from unknown to positive."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Add a folder uses the existing add flow: native picker on desktop, manual entry in the browser. Cancelling changes nothing and returns focus to the button. An invalid path, refusal or failure says so and can be retried."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn 2",
          "quote": "Step 1 is done when the person has searchable documents of their own; a folder being read shows step 1 as under way. Step 3 is done when AI is active. Step 2 is done when a search returned while the person's own documents were searchable; a search over Help alone does not count. Only step 2 needs a saved mark."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "After the first folder: \"n files searchable so far\" and, while the observed queue has work, \"m waiting to be read\"; when work is under way without a known queue, \"still reading\"."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Hide removes it and the pill. Help reopens it, including for finished or old-welcome people, and it then stays until hidden. These survive reload and apply across windows. An ordinary profile switch keeps this progress, as walkthrough progress is cross-profile today (the data it reflects is not per-profile); a completed factory reset behaves as fresh."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Dark, light, forced colours; 1280x800, 900x800, 600x400 and enlarged text: nothing overflows sideways, actions stay reachable, keyboard order and focus after add or failure are right, and status changes are announced politely."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Write these tests first and show each failing before your change for the reason under test:",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "Verify the requested first-use interactions, storage boundaries, navigation, accessibility and responsive cases in real Chromium.",
          "choice": "Drive the real Shell and Search using owned Vite/Chromium processes and controlled API/platform responses, without a shared dev stack. All browser checks are durable.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#unknown → zero → positive changes the hero only with confirmed facts and preserves the ordinary composer",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#browser cancel and add failure restore keyboard focus; duplicate submission stays single",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#Hide, a real second window, reload, Help reopen and profile switch share the same progress",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#keyword search needs no AI and Help reopening stays visible after a returned search",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#reloading during indexing rediscovers the folder and live count before offering search",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#leaving and returning during an add keeps failure actionable and focuses the new path field",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#1280x800, dark, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#1280x800, light, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#1280x800, forced, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#900x800, dark, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#900x800, light, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#900x800, forced, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#600x400, dark, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#600x400, light, enlarged text keeps setup actions reachable without horizontal overflow",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#600x400, forced, enlarged text keeps setup actions reachable without horizontal overflow"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB14 preservation: an explicit Library URL and a subsequent journal restore retain their destination, journal and history when first-use facts arrive.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "Restored navigation and explicit URLs win: Getting started never replaces a saved destination or adds a Back/Forward entry. Co-resident saved values survive."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-browser-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#explicit addresses and the saved journal win without an onboarding history entry"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/manifest/canonicalManifest.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "The old 4-step core walkthrough is retired. Plugin walkthroughs appear in the card, keep their completion triggers and keep their saved progress. Their install and removal behave as today."
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/substrates/manifest/canonicalManifest.ts"
          ]
        }
      ],
      "conflictSearch": "Searched repository walkthrough/manifest references and the supplied SB12/design. The core welcome installer is explicitly retired; the registry and public plugin contribution API are retained."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n \"suggested-roots|activity|observation|user input\" and read the matching pacing decision docs/decisions/0048-extraction-isolation-and-indexing-pacing.md. No decision requires automatic suggested-folder reads to count as user input. Only the old GET /api/indexing/suggested-roots activity-advances expectation is retired; all other routes and assertions are preserved."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"
      ],
      "class": "New",
      "behaviour": "GET /api/indexing/suggested-roots is an automatic observation and does not advance user activity. All other route expectations, attribution hints, admission conditions and existing assertions remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-route-activity.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n \"suggested-roots|activity|observation|user input\" and read the matching pacing decision docs/decisions/0048-extraction-isolation-and-indexing-pacing.md. No decision requires automatic suggested-folder reads to count as user input. Only the old GET /api/indexing/suggested-roots activity-advances expectation is retired; all other routes and assertions are preserved."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"
      ],
      "class": "New",
      "behaviour": "GET /api/indexing/suggested-roots is an automatic observation and does not advance user activity. All other route expectations, attribution hints, admission conditions and existing assertions remain unchanged. The literal production declaration is also required to be OBSERVATION.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-route-activity.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierRoundFourActivityTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n \"suggested-roots|activity|observation|user input\" and read the matching pacing decision docs/decisions/0048-extraction-isolation-and-indexing-pacing.md. No decision requires automatic suggested-folder reads to count as user input. Only the old GET /api/indexing/suggested-roots activity-advances expectation is retired; all other routes and assertions are preserved."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierRoundFourActivityTest.java"
      ],
      "class": "New",
      "behaviour": "GET /api/indexing/suggested-roots is an automatic observation and does not advance user activity. All other route expectations, attribution hints, admission conditions and existing assertions remain unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i agree, proceed",
          "location": "task t-20261103-shell agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "How the frame is built, including the agreed empty Search folder prompt with suggestions.",
          "choice": "An automatic read of suggested folders is an observation, not user input. GET /api/indexing/suggested-roots must leave the user-activity boundary unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-route-activity.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierRoundFourActivityTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts",
        "modules/ui-web/src/shell-v0/components/GettingStarted.reading-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB6: live INDEXING with an absent or zero queue reports still reading, in the shared projection and the production card in headless Chromium.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "observed queue greater than zero gives \"m waiting to be read\"; work under way with the queue absent, or zero while a scan is still running, gives \"still reading\"; paused or idle with no work gives neither; stale or unknown gives nothing."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-reading.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts#reports still reading during INDEXING with queue undefined",
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts#reports still reading during INDEXING with queue 0",
        "modules/ui-web/src/shell-v0/components/GettingStarted.reading-lockdown.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts"
      ],
      "class": "New",
      "behaviour": "SB6 preservation: a positive observed queue reports waiting files, idle/paused without work adds neither suffix, stale/unknown/unavailable and expired observations show no progress. Pausing does not erase an observed positive queue.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell request",
          "quote": "observed queue greater than zero gives \"m waiting to be read\"; work under way with the queue absent, or zero while a scan is still running, gives \"still reading\"; paused or idle with no work gives neither; stale or unknown gives nothing."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-reading.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.reading.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "class": "New",
      "behaviour": "SB5/SB7: a watched folder with zero own searchable documents points to Library and does not complete step 1.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn 2",
          "quote": "Step 1 is done when the person has searchable documents of their own; a folder being read shows step 1 as under way. Step 3 is done when AI is active. Step 2 is done when a search returned while the person's own documents were searchable; a search over Help alone does not count. Only step 2 needs a saved mark."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts#a watched folder with zero searchable documents points to Library and does not finish"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.observations.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched the existing S5 changeset and repository firstUse/collection-facet/walkthrough references. Earlier frontend Help subtraction, request cadence, retry and zero-total shortcut decisions are superseded by the settled 2026-11-10 SB16 redesign. No conflict with the retained unknown/freshness, Help-exclusion, Hide/reload or plugin-progress scenarios. Retired expectations: SB1/SB2/SB5/SB6: unavailable, malformed, scan-truncated and top-100-omitted Help counts remain unknown; exact Help counts survive status polls; Help-only searches cannot complete step 2."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB1/SB5/SB6/SB7/SB8/SB9 through Chromium: Help-only boot and search, no-model pill, changing Help size, unknown own count, reading/empty folders and own ad-hoc content. Wire inputs now carry the Engine own count; retained visible outcomes are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn 2",
          "quote": "Step 1 is done when the person has searchable documents of their own; a folder being read shows step 1 as under way. Step 3 is done when AI is active. Step 2 is done when a search returned while the person's own documents were searchable; a search over Help alone does not count. Only step 2 needs a saved mark."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#Help alone keeps setup current, and unknown own counts stay unknown",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts#a folder is under way until own documents exist; empty folders point to Library"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.pill.test.ts"
      ],
      "class": "New",
      "behaviour": "SB9 repair: first-use known replaces merely unknown without AI models; current step 1, 2 or 3 replaces the completed-step count. Unknown first-use facts keep the ordinary unknown pill.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn 2",
          "quote": "The pill shows \"Getting started · n of 3\", where n is the step the person is on (1 at first launch, as in ref-01), only where it would otherwise be calm or merely unknown, once the first-use state is known. A fresh install without AI models must not sit at \"Checking search status\" instead. Every higher status (unreachable, limited, needs you, problems, working) still wins."
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-pill.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.pill.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/frameStatusController.firstUse.test.ts",
        "modules/ui-web/src/shell-v0/state/firstUse.observations.test.ts",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched the existing S5 changeset and repository firstUse/collection-facet/walkthrough references. Earlier frontend Help subtraction, request cadence, retry and zero-total shortcut decisions are superseded by the settled 2026-11-10 SB16 redesign. No conflict with the retained unknown/freshness, Help-exclusion, Hide/reload or plugin-progress scenarios. Retired expectations: SB2: status polls retain a successful Help count; slow answers survive newer snapshots; successful observations refresh no sooner than 60 seconds, failed or truncated observations become unknown and retry no sooner than 15 seconds. Chromium keeps the first-use view stable across several polls."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/frameStatusController.firstUse.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched the existing S5 changeset and repository firstUse/collection-facet/walkthrough references. Earlier frontend Help subtraction, request cadence, retry and zero-total shortcut decisions are superseded by the settled 2026-11-10 SB16 redesign. No conflict with the retained unknown/freshness, Help-exclusion, Hide/reload or plugin-progress scenarios. Retired expectations: An in-flight Help request is aborted when first use stops needing it or the controller disconnects, and late aborted responses never land."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "class": "New",
      "behaviour": "SB1: a known total searchable count of zero with no Help observation and no folders is known, empty and visible, with step 1 current and no step completed.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261103-shell user turn zero-searchable correction",
          "quote": "when searchable is a known zero, the own count is zero without any Help count, because Help documents are part of searchable. An unknown searchable stays unknown. Nothing else changes."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts#zero searchable documents confirms first use without Help"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: an unknown own searchable count offers neither an empty view nor setup advice.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-existing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts#unknown searchable documents stay unknown without Help"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched the existing S5 changeset and repository firstUse/collection-facet/walkthrough references. Earlier frontend Help subtraction, request cadence, retry and zero-total shortcut decisions are superseded by the settled 2026-11-10 SB16 redesign. No conflict with the retained unknown/freshness, Help-exclusion, Hide/reload or plugin-progress scenarios. Retired expectations: the removed projection test computed own count by subtracting a separately observed Help count."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched the existing S5 changeset and repository firstUse/collection-facet/walkthrough references. Earlier frontend Help subtraction, request cadence, retry and zero-total shortcut decisions are superseded by the settled 2026-11-10 SB16 redesign. No conflict with the retained unknown/freshness, Help-exclusion, Hide/reload or plugin-progress scenarios. Retired expectations: the removed positive-total/unknown-Help test depended on frontend subtraction. The absent own-count status is now checked directly, including older workers."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.engine.test.ts"
      ],
      "class": "New",
      "behaviour": "SB16: an older status without own count is unknown even at total zero; one Engine snapshot excludes Help, admits ad-hoc documents and keeps the empty view after Hide.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-engine.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.engine.test.ts#older status without an own count is unknown even when searchable is zero",
        "modules/ui-web/src/shell-v0/state/firstUse.engine.test.ts#one Engine snapshot excludes Help, includes ad-hoc documents and stays empty after Hide"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/firstUse.engine.test.ts"
      ],
      "class": "New",
      "behaviour": "SB16 preservation: indexedDocumentsUnknown invalidates a present own count.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/firstUse.engine.test.ts#unknown index counts invalidate the own count in the same snapshot"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.engine-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "SB16/SB17 through headless Chromium: Help indexing never flips first use or invokes facets; Hide/reload preserves factual emptiness; card/pill agree and show progress; one own count is displayed; a new path clears the previous add error before preview.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-engine.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/FirstUse.engine-lockdown.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/OwnSearchableDocCountTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperOwnCountTest.java"
      ],
      "class": "New",
      "behaviour": "SB16 through real Lucene, IPC mapper and serialized status: exclude Help/history and chunks, include ad-hoc documents, Help-only zero, unknown index counts and absent older-worker field stay unknown.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s5-redesign-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/OwnSearchableDocCountTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperOwnCountTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusReadFailureTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "The existing single-query count mock is replaced by countQueriesOrThrow(Query[]) with [searchable, own] fixtures. Original reader-selection, error injection, stale/recovery timestamps, generation ownership and searchable expectations are retained. The synthetic mocks have no internal collection documents. Executed evidence: gates/test-intent/evidence/shell-s5-redesign-java-adapted.json.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The schema-valid live synthetic status fixture supplies ownSearchableDocuments beside searchableDocuments, both 104 for this fixture of user files. No other fixture expectation changes; schema and frontend suites execute against it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "2026-11-10 SB16/SB17 redesign: the Engine reports own searchable documents in the same serving-reader snapshot as searchable documents, excluding InternalCollections. First use reads only that nullable field. The facet request, cache, timers and their tests are retired. Unknown is never zero; Hide affects the card, not the factual empty view. Card and pill share the current step and a progress bar; the first-use count replaces the corpus line; a new add attempt clears an old error.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

Independent acceptance is pending.
