---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: codex-uiux-cutover-cold-repair-bhz9se_e
---

# Test-intent entries

Every source below is an agent choice under the quoted delegation, never owner-agreed. Execution evidence is produced by the sanctioned runner with the unchanged before-state `98e2d9bc56`. Explicitly requested new-file entries are included even where the gate would exempt them. Preservation checks within these files also run before and after; added behavior has official assertion fail-before evidence; original shared-clone logs remain under tmp/wp1.5a/. Supplemental official per-case evidence in `gates/test-intent/evidence/uiux-cutover-preservation.json` distinguishes unchanged before-state passes from additional repair assertions. No acceptance is authored here. The added FC15 entries below use required baseline `19f1fc10239bf5e3b2d686beedfd8d090cbefe0b`; their repair and preservation evidence is separate from the historical original package. The existing acceptance record is retained as history for its original digest and does not accept the FC15 repair.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/windowCutover.test.ts"
      ],
      "class": "New",
      "behaviour": "Promote the main window and prevent an upgrade catalog cache from retaining its retired peer; resolve retired and promoted addresses directly; preserve bookmark identity and distinct places; normalize journal and all profile preferences with explicit Search preferences winning and reset surviving restart; default query/answer verbs target Search and wait.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C1 Main window. A fresh USER launch shows one Search window in the rail (no old chat window, no \"Search v3\" developer entry), opens in Search mode, and searching works with chat unavailable; Ask, Structured and Delegate are offered when ready, with the existing reason when not. Fallbacks (Settings close, no saved surface) land in it. Library's split pairing points at it and it reciprocates.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C2 Old addresses. Old-window, retired Search and retired Agent URLs and bookmarks (browser and in-app) open Search v3, keeping supported state: a query arrives as in HO1 (encoded punctuation, non-Latin, long, blank runs nothing); a conversation id opens that conversation; files and forced shape arrive as the hand-offs define; unknown or invalid state is dropped without error. The in-app bookmark star recognises an old bookmark of the same place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C3 Upgrade restore. With saved state from the previous version naming the old window (last surface, journal, Back/Forward, Recents, per-tab conversation pointer), the app opens Search v3 at the intended place; the address outranks the pointer; a deleted or locked conversation shows its existing state and never adopts another tab's conversation; nothing runs a question or agent on restore.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C4 Draft and preferences. W2 for drafts (both cases). Saved split pane, rail order and visibility, surface overrides and override targets, in the active and inactive profiles, keep the user's intent with the old id mapped to Search v3. Promoted aliases and saved navigation effects or macros naming the old id still navigate.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C6 Deep links. `justsearch://query` searches; `justsearch://answer` prepares the named supported mode and waits (D6), cold and warm, model offline included; agent or unknown shapes never start an agent. Tauri native search summon focuses Search v3's composer on cold launch, hidden window and visible secondary pane, keeping the typing and modal protections.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A4 (finding 4, extends C4). Preference collisions: an explicitly stored Search v3 preference wins; the old window's preference is inherited only when Search v3 has none. Targets are normalised as well as keys, rail order is de-duplicated, no self-override results. Checked with conflicting visibility, order and override entries in active and inactive profiles; then changing or resetting the migrated preference and restarting proves legacy values do not return.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A5 (finding 5, extends C2). For alias-equivalent addresses with equal retained state, bookmark recognition, save and removal find the existing bookmark: its id, label and profile are kept, no duplicate is created. Distinct conversations stay distinct. An old query bookmark stays re-runnable and does not make every bare Search address look bookmarked.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/windowCutover.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selection.test.ts"
      ],
      "class": "New",
      "behaviour": "Publish the chosen current hit or result set. Deselect, replacing results, changing query and leaving clear only this window's selection. Another surface's selection is preserved.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W6 \"Find related to selection\" works from a hit selected in Search v3: Search v3 publishes the selected hit (and a chosen result set) to the shared selection as the old window did.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A8 (W6 evidence). Selection lifecycle: deselect, result replacement and leave/return clear or update the published selection; \"Find related\" never uses a stale hit.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selection.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts"
      ],
      "class": "New",
      "behaviour": "Keep every existing native focus/modal/typing/secondary-pane protection with Search as the canonical destination. Ctrl+Shift+A prepares Ask with chat ready, otherwise Search.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C6 Deep links. `justsearch://query` searches; `justsearch://answer` prepares the named supported mode and waits (D6), cold and warm, model offline included; agent or unknown shapes never start an agent. Tauri native search summon focuses Search v3's composer on cold launch, hidden window and visible secondary pane, keeping the typing and modal protections.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W5 Ctrl+Shift+A opens Search v3 in Ask mode with focus in the composer (Search mode when chat is unavailable, with its reason).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiSelection.test.ts"
      ],
      "class": "New",
      "behaviour": "Capture the chosen primary selection through the existing wire projection before plugin compose navigates, so the chosen file or passage cannot be lost or replaced by a later selection.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A7 (finding 7, extends C5). For each materially different preparation form, Send is pressed and the dispatched shape, exact text and chosen inputs are checked, then the recorded result after reload; exclusion is proved with an unchosen file holding a unique answer. All through the production catalog as fetched from the Engine. Also: open conversation A, then B, and check the address and Back/Forward after promotion.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/HostApiSelection.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/router/intentRouter.test.ts"
      ],
      "class": "New",
      "behaviour": "Default query and answer navigation targets Search. Answer carries the named shape and does not park an automatic send. Answer links retain supplied file scope and invalid task data for visible validation, while the named shape and prompt outrank conflicting extra state. Custom hosts and all other resolution checks stay unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C2 Old addresses. Old-window, retired Search and retired Agent URLs and bookmarks (browser and in-app) open Search v3, keeping supported state: a query arrives as in HO1 (encoded punctuation, non-Latin, long, blank runs nothing); a conversation id opens that conversation; files and forced shape arrive as the hand-offs define; unknown or invalid state is dropped without error. The in-app bookmark star recognises an old bookmark of the same place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C6 Deep links. `justsearch://query` searches; `justsearch://answer` prepares the named supported mode and waits (D6), cold and warm, model offline included; agent or unknown shapes never start an agent. Tauri native search summon focuses Search v3's composer on cold launch, hidden window and visible secondary pane, keeping the typing and modal protections.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W3 Compatibility shapes: Ask, FreeChat and Extract interaction-shape links open Search v3 prepared in the matching mode (Ask, Ask without files, Structured), never sending. Workflow-run has no shipped entry point (`sandbox-coverage.v1.json:56`): its link opens Search v3 unprepared with a visible, announced \"Workflow runs are not available here\" notice; never Delegate.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A2 (finding 2, replaces C2's \"unknown or invalid state is dropped without error\"). Irrelevant unknown fields are ignored. Invalid task-defining data (files, selection, mode, forced shape) is refused visibly or takes the agreed visible fallback (HO13, P2-4a); the text is kept and scope is never silently removed or widened. Checked through old aliases with malformed, empty and missing-file scope while an unrelated selection is active.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/router/intentRouter.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.test.ts"
      ],
      "class": "New",
      "behaviour": "Keep the V1 fixture and all its profile migration checks; its stored retired rail entry normalizes to Search.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C4 Draft and preferences. W2 for drafts (both cases). Saved split pane, rail order and visibility, surface overrides and override targets, in the active and inactive profiles, keep the user's intent with the old id mapped to Search v3. Promoted aliases and saved navigation effects or macros naming the old id still navigate.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A4 (finding 4, extends C4). Preference collisions: an explicitly stored Search v3 preference wins; the old window's preference is inherited only when Search v3 has none. Targets are normalised as well as keys, rail order is de-duplicated, no self-override results. Checked with conflicting visibility, order and override entries in active and inactive profiles; then changing or resetting the migrated preference and restarting proves legacy values do not return.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/UserStateDocument.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/compose.test.ts"
      ],
      "class": "New",
      "behaviour": "The universal compose sender targets Search. An ordinary unscoped request omits docIds rather than inventing an empty requested file scope; all existing prompt, chosen-file and selection expectations remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C2 Old addresses. Old-window, retired Search and retired Agent URLs and bookmarks (browser and in-app) open Search v3, keeping supported state: a query arrives as in HO1 (encoded punctuation, non-Latin, long, blank runs nothing); a conversation id opens that conversation; files and forced shape arrive as the hand-offs define; unknown or invalid state is dropped without error. The in-app bookmark star recognises an old bookmark of the same place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A2 (finding 2, replaces C2's \"unknown or invalid state is dropped without error\"). Irrelevant unknown fields are ignored. Invalid task-defining data (files, selection, mode, forced shape) is refused visibly or takes the agreed visible fallback (HO13, P2-4a); the text is kept and scope is never silently removed or widened. Checked through old aliases with malformed, empty and missing-file scope while an unrelated selection is active.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/compose.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Keep existing dispatch, scope, streaming, locked-store and reload checks. Add durable one-time legacy draft transfer, collision preservation, arrival precedence, write-failure recovery and permanent no-legacy disposition against a late old-tab save; compatibility mode presets and workflow notice; visible malformed-task refusal; one shared inspector reader retaining passage and close state; a retained composer owns migrated text before its next render so a lifecycle flush cannot lose it; outgoing compatibility mounts cannot consume a main-window arrival; shared line passages show their highlight without claiming a missing citation position. Explain omits an absent scope while keeping its exact submitted prompt/trace checks. The existing D7 Structured typed-text/no-files form remains allowed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C2 Old addresses. Old-window, retired Search and retired Agent URLs and bookmarks (browser and in-app) open Search v3, keeping supported state: a query arrives as in HO1 (encoded punctuation, non-Latin, long, blank runs nothing); a conversation id opens that conversation; files and forced shape arrive as the hand-offs define; unknown or invalid state is dropped without error. The in-app bookmark star recognises an old bookmark of the same place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C4 Draft and preferences. W2 for drafts (both cases). Saved split pane, rail order and visibility, surface overrides and override targets, in the active and inactive profiles, keep the user's intent with the old id mapped to Search v3. Promoted aliases and saved navigation effects or macros naming the old id still navigate.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C7 Arrival races. Two rapid hand-offs, a late search or record response, navigation or reload mid-arrival, and arrival during streaming, approval or a locked store leave one correct prepared task: no duplicate send, no scope leak, no change to the open conversation (HO8).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C8 Compatibility shapes per W3, including the workflow-run notice.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A3 (finding 3, new C9). Opening a document through the plugin inspector API or the shared citation-select path while Search v3 is shown opens exactly one visible reader on the right file, keeping a supplied passage highlight; closing it updates the shared inspector state. Wide, narrow and split layouts, and return after navigation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A1 (finding 1, replaces W2 and C4's draft clause). The old window's draft is one shared, unscoped value. On the first Search v3 mount after upgrade with no incoming hand-off: if Search v3's composer draft is empty, the legacy text becomes it; if both hold text, Search v3's stays and the legacy value is left in storage untouched (limit: no recovery UI; before the flip Search v3 is a developer-only window, so ordinary users have no Search v3 draft). An incoming hand-off takes precedence and the legacy value stays for a later mount without one. The legacy value is removed only after the transfer is durably written, and a migration marker makes the disposition permanent: Send, Clear, New session, a later empty draft, reload and restart never bring it back. Checks: legacy only, both present, explicit arrival, two tabs, storage write failure, second restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A2 (finding 2, replaces C2's \"unknown or invalid state is dropped without error\"). Irrelevant unknown fields are ignored. Invalid task-defining data (files, selection, mode, forced shape) is refused visibly or takes the agreed visible fallback (HO13, P2-4a); the text is kept and scope is never silently removed or widened. Checked through old aliases with malformed, empty and missing-file scope while an unrelated selection is active.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A3 (finding 3, new C9). Opening a document through the plugin inspector API or the shared citation-select path while Search v3 is shown opens exactly one visible reader on the right file, keeping a supplied passage highlight; closing it updates the shared inspector state. Wide, narrow and split layouts, and return after navigation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W3 Compatibility shapes: Ask, FreeChat and Extract interaction-shape links open Search v3 prepared in the matching mode (Ask, Ask without files, Structured), never sending. Workflow-run has no shipped entry point (`sandbox-coverage.v1.json:56`): its link opens Search v3 unprepared with a visible, announced \"Workflow runs are not available here\" notice; never Delegate.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts"
      ],
      "class": "New",
      "behaviour": "The existing capability-degradation arbitration checks use the promoted Search identity; all verdict, tone and non-banner controls are preserved.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C1 Main window. A fresh USER launch shows one Search window in the rail (no old chat window, no \"Search v3\" developer entry), opens in Search mode, and searching works with chat unavailable; Ask, Structured and Delegate are offered when ready, with the existing reason when not. Fallbacks (Settings close, no saved surface) land in it. Library's split pairing points at it and it reciprocates.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/actions/actions.test.ts"
      ],
      "class": "New",
      "behaviour": "The existing Go to Search action resolves to the promoted Search identity and still has no duplicate Go to Chat action.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/actions/actions.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "class": "New",
      "behaviour": "The catalog registration check now requires USER/RAIL, preserving the mount tag, lazy activation and component-vocabulary checks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C1 Main window. A fresh USER launch shows one Search window in the rail (no old chat window, no \"Search v3\" developer entry), opens in Search mode, and searching works with chat unavailable; Ask, Structured and Delegate are offered when ready, with the existing reason when not. Fallbacks (Settings close, no saved surface) land in it. Library's split pairing points at it and it reciprocates.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/SearchWindowCutoverTest.java"
      ],
      "class": "New",
      "behaviour": "The production catalog advertises the USER/RAIL Search mount and keeps its conversation URL binding when fetched, without restoring retired shared stores.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C1 Main window. A fresh USER launch shows one Search window in the rail (no old chat window, no \"Search v3\" developer entry), opens in Search mode, and searching works with chat unavailable; Ask, Structured and Delegate are offered when ready, with the existing reason when not. Fallbacks (Settings close, no saved surface) land in it. Library's split pairing points at it and it reciprocates.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C3 Upgrade restore. With saved state from the previous version naming the old window (last surface, journal, Back/Forward, Recents, per-tab conversation pointer), the app opens Search v3 at the intended place; the address outranks the pointer; a deleted or locked conversation shows its existing state and never adopts another tab's conversation; nothing runs a question or agent on restore.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A7 (finding 7, extends C5). For each materially different preparation form, Send is pressed and the dispatched shape, exact text and chosen inputs are checked, then the recorded result after reload; exclusion is proved with an unchosen file holding a unique answer. All through the production catalog as fetched from the Engine. Also: open conversation A, then B, and check the address and Back/Forward after promotion.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover-java.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/SearchWindowCutoverTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/NavigationJournal.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "Rename canonical journaling/traversal test subjects to Search v3. Query and history oracles are kept. Retired-address migration is checked separately in windowCutover.test.ts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C3 Upgrade restore. With saved state from the previous version naming the old window (last surface, journal, Back/Forward, Recents, per-tab conversation pointer), the app opens Search v3 at the intended place; the address outranks the pointer; a deleted or locked conversation shows its existing state and never adopts another tab's conversation; nothing runs a question or agent on restore.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/surface/CoreSurfaceCatalogTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The existing single-interaction-window consumed-shape assertion uses the renamed Search constant; its shapes, audience and cardinality oracles remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C1 Main window. A fresh USER launch shows one Search window in the rail (no old chat window, no \"Search v3\" developer entry), opens in Search mode, and searching works with chat unavailable; Ask, Structured and Delegate are offered when ready, with the existing reason when not. Fallbacks (Settings close, no saved surface) land in it. Library's split pairing points at it and it reciprocates.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Rail.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "Use the promoted canonical Search identity in the existing altitude, TRUST adjacency and rail expansion tests; all ordering and operability oracles remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C1 Main window. A fresh USER launch shows one Search window in the rail (no old chat window, no \"Search v3\" developer entry), opens in Search mode, and searching works with chat unavailable; Ask, Structured and Delegate are offered when ready, with the existing reason when not. Fallbacks (Settings close, no saved surface) land in it. Library's split pairing points at it and it reciprocates.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W1 Surface identity: Search v3 keeps its id (`core.search-v3-surface` or as on main) and persisted keys; the old id `core.unified-chat-surface` and the retired `core.search-surface` and `core.agent-surface` become direct aliases to it (aliases do not chain).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selection.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve the unchanged before-state obligations named by these checks: C5 A8 leaving Search does not clear another surface selection.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C5 Real senders. On the exact candidate, every in-app sender reaches Search v3 with the right mode, text and files and waits for Send: palette search and fallback search button, palette Search/Chat aliases and \"Go to Search\", \"Find related to selection\" (W6, after selecting a Search v3 hit), Ctrl+Shift+A (W5), Browse Ask/Summarize, floating selection actions, \"Explain why this result\", plugin compose, backend/agent navigate-to-surface. Search hand-offs run the search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "W6 \"Find related to selection\" works from a hit selected in Search v3: Search v3 publishes the selected hit (and a chosen result set) to the shared selection as the old window did.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A8 (W6 evidence). Selection lifecycle: deselect, result replacement and leave/return clear or update the published selection; \"Find related\" never uses a stale hit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-cutover-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-selection.test.ts#C5 A8 leaving Search does not clear another surface selection"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve the unchanged before-state obligations named by these checks: C4 A1 keeps an existing Search draft and permanently leaves the legacy value untouched; C4 A1 a first clean mount with no legacy text cannot later revive a stale old tab draft.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "C4 Draft and preferences. W2 for drafts (both cases). Saved split pane, rail order and visibility, surface overrides and override targets, in the active and inactive profiles, keep the user's intent with the old id mapped to Search v3. Promoted aliases and saved navigation effects or macros naming the old id still navigate.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "A1 (finding 1, replaces W2 and C4's draft clause). The old window's draft is one shared, unscoped value. On the first Search v3 mount after upgrade with no incoming hand-off: if Search v3's composer draft is empty, the legacy text becomes it; if both hold text, Search v3's stays and the legacy value is left in storage untouched (limit: no recovery UI; before the flip Search v3 is a developer-only window, so ordinary users have no Search v3 draft). An incoming hand-off takes precedence and the legacy value stays for a later mount without one. The legacy value is removed only after the transfer is durably written, and a migration marker makes the disposition permanent: Send, Clear, New session, a later empty draft, reload and restart never bring it back. Checks: legacy only, both present, explicit arrival, two tabs, storage write failure, second restart.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-cutover-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#C4 A1 keeps an existing Search draft and permanently leaves the legacy value untouched",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts#C4 A1 a first clean mount with no legacy text cannot later revive a stale old tab draft"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts"
      ],
      "class": "New",
      "behaviour": "Cold Shell startup settles URL/native/journal ingress before legacy draft disposition; preserve exact legacy text through incoming edit/navigation/reload/restart, invalid scope, rapid arrivals, and disconnected lifecycle callbacks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted FC15 clarification 2026-10-25 in agreed/wp1.5.md",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "cold ingress and draft migration repair (agent-adopted 2026-10-25; C4/A1 clarification)\n\nA1 already requires incoming hand-off precedence and leaving legacy text for a later mount without ingress. Independent real browser counterexample at67944077cd: a cold old alias or answer task mounts before ingress hydration, migration consumes legacy, then edit/navigation overwrites the transferred copy. Expected class:\n\n| Case | Expected |\n|---|---|\n| Cold alias with query/files/mode/conversation or cold answer with supported forced shape | Establish whether mount has incoming work before disposition; prepare incoming task, send nothing, retain exact legacy key without transferred disposition |\n| Edit incoming text; leave/return; reload and full restart | Incoming edited text behaves normally; original legacy value remains recoverable under A1, never silently consumed/overwritten |\n| No ingress, empty successor | Existing durable one-time transfer; delete legacy only after destination persists; Send/Clear/New/reload never resurrect |\n| Successor already has text | Successor wins; legacy stays untouched; marker disposition remains durable |\n| Late/two rapid ingress, component first mount before router/restore settles | No eager transfer, duplicate send or scope drop; latest legitimate incoming task owns preparation |\n| Invalid task-defining ingress | Visible refusal/fallback with incoming text; legacy not silently transferred or deleted as a side effect |\n| Failed native destination persistence or unavailable storage; concurrent tabs; stale old-tab write | Never remove sole old copy; retry/disposition remains honest; completed migration never resurrects; ownership races do not erase independent draft |\n\nPreserve #947 live single/tree deletion safeguards, all C1-C9/A1-A9 contracts and existing interaction modes. No extra draft-history store/recovery UI, no permanent quarantine of ordinary no-ingress transfer, no arbitrary timeout to guess ingress completion. Unverified original variants remain gaps, not passes; actual boundary fault instrumentation may be labeled honestly and cannot be called a native quota failure.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/uiux-cutover-cold-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'old alias Ask' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'answer FreeChat' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'retired Search query' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'retired Agent files' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'answer Structured files' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'invalid empty scope' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'invalid selection' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold 'invalid mode' preserves legacy until routing has prepared the task",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold conversation address outranks the pointer without disposing of the old draft",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold incoming edits survive leave return reload and restart while legacy stays saved",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold native link is settled before ordinary boot can transfer legacy",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold queued native delivery waits for the initial drain before draft disposition",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold no-ingress transfer waits for native absence then remains one time after Clear",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold journal task is applied before legacy disposition",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold rapid arrivals before hydration preserve only the latest prepared scope",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold reconnect ignores startup completion from the disconnected Shell lifecycle",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 cold stale native startup cannot replace a newer task after Shell reconnects"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve A1 successor collisions and durable-write refusal recovery through actual Shell/Search mounts; concurrent mounts and a pending tab closing cannot erase another tab durable transfer.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request; agent-adopted FC15 clarification 2026-10-25 in agreed/wp1.5.md",
          "delegationScope": "Design and implement the remaining JustSearch UI/UX work, including Search v3 cutover compatibility.",
          "choice": "cold ingress and draft migration repair (agent-adopted 2026-10-25; C4/A1 clarification)\n\nA1 already requires incoming hand-off precedence and leaving legacy text for a later mount without ingress. Independent real browser counterexample at67944077cd: a cold old alias or answer task mounts before ingress hydration, migration consumes legacy, then edit/navigation overwrites the transferred copy. Expected class:\n\n| Case | Expected |\n|---|---|\n| Cold alias with query/files/mode/conversation or cold answer with supported forced shape | Establish whether mount has incoming work before disposition; prepare incoming task, send nothing, retain exact legacy key without transferred disposition |\n| Edit incoming text; leave/return; reload and full restart | Incoming edited text behaves normally; original legacy value remains recoverable under A1, never silently consumed/overwritten |\n| No ingress, empty successor | Existing durable one-time transfer; delete legacy only after destination persists; Send/Clear/New/reload never resurrect |\n| Successor already has text | Successor wins; legacy stays untouched; marker disposition remains durable |\n| Late/two rapid ingress, component first mount before router/restore settles | No eager transfer, duplicate send or scope drop; latest legitimate incoming task owns preparation |\n| Invalid task-defining ingress | Visible refusal/fallback with incoming text; legacy not silently transferred or deleted as a side effect |\n| Failed native destination persistence or unavailable storage; concurrent tabs; stale old-tab write | Never remove sole old copy; retry/disposition remains honest; completed migration never resurrects; ownership races do not erase independent draft |\n\nPreserve #947 live single/tree deletion safeguards, all C1-C9/A1-A9 contracts and existing interaction modes. No extra draft-history store/recovery UI, no permanent quarantine of ordinary no-ingress transfer, no arbitrary timeout to guess ingress completion. Unverified original variants remain gaps, not passes; actual boundary fault instrumentation may be labeled honestly and cannot be called a native quota failure.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/uiux-cutover-cold-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 neighboring successor collision keeps its text and the exact old value after restart",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 neighboring destination write refusal keeps the old copy for a later clean mount",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 neighboring two pending Shell mounts retain the durable transfer when the last tab closes",
        "modules/ui-web/src/shell-v0/chrome/Shell.coldDraft.test.ts#FC15 neighboring a tab closing before its ingress settles cannot erase another tab transfer"
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
  "session": "6ma6q1yy",
  "verdict": "accept",
  "digest": "sha256:7f5c50c98f9b1d2787988a60e32f1916abcd6373d873e2ab54cef311813cf0e4",
  "note": "Expectation changes match delegated C1-C9/A1-A9 scope; independent declared digest and default-link before/after reruns inspected. This accepts test intent only. Product verdict is fail: real cold incoming alias/answer overwrites the migrated legacy draft after edit/navigation (C4/A1); see candidate-cold-draft-repro and after-edit-navigation evidence. Native focus and unexercised live variants remain limits."
}
```
