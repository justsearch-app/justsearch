---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-palette-a-builder
---

# Candidate A — palette providers, dispatch, matching, live updates and arrivals

No acceptance is claimed; independent verification is pending. Conversation opening follows the
orchestrator decision: one navigation to Search carrying the conversation in its address; Search's
existing address path selects it with its own draft departure checks once connected.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Wait for the existing Library loading flag to settle before injecting the existing rendering fixtures. Extracting the shared roots read adds an asynchronous boundary; fixture values and rendering oracles are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep the folder rendering oracles and fixtures intact while moving their readiness wait across the shared read boundary."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Replace contribution categories and literal metadata shortcut hints with fixed provider groups and hints from winning dispatch bindings under SP2 and SP3."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|shortcut.*hint|command.*category\". Only an unrelated manual-CI palette contrast reference matched. The settled SP2-SP3 amendments explicitly replace these two oracles."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts",
        "modules/ui-web/src/shell-v0/commands/CommandPalette.providers.test.ts"
      ],
      "class": "New",
      "behaviour": "SP2 core wording and winning-binding hints; SP3 word-start matching, tiers, recency within a tier, deterministic name/identity ties, contiguous groups ordered by best tier then provider order (including an exact tie created by suffix cleanup); SP4 Settings rows with the real viewer, SP4a the loaded-twenty roster and the 21st conversation described as outside the recent list, SP4c lock/late-read/unlock, SP4d untrusted denial; SP4/SP9 registration, replacement and eligibility changes in an open palette and on activation; SP5 provider action inventory; SP5/SP9 folder disappearance and failed refresh; SP6 prefixes; SP6/SP8 bounded rendering, late folder labels and older roots reads; SP10a actual targets, macros and collisions; SP10b template slots; SP10d profile switch drops the previous scope and pending reads; SP10 saved state: failed, cancelled and shortcut recency, overrides and multiple bindings across reload, no title cache.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Check the five fixed providers, explicit targets, tier matching, live updates and preserved persistence under the settled candidate A contract."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts",
        "modules/ui-web/src/shell-v0/commands/CommandPalette.providers.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"
      ],
      "class": "New",
      "behaviour": "SP4b opening a recent conversation from the palette is one navigation to Search with the conversation in its address; from active, cold and retained Search it opens exactly that conversation with one history entry and correct Back, Forward and reload; the navigation journal gains one entry, for the opened conversation, marking the current history entry, and Back and Forward move its cursor without adding entries. The refused-departure cases moved to Shell.paletteConversationRefusal.test.ts with the repaired oracles.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Per the orchestrator decision: the palette never selects on a detached or retained Search host; Search's existing address path performs the selection with its own departure checks once connected."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.navigation.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.paletteConversationRefusal.test.ts"
      ],
      "class": "New",
      "behaviour": "SP4b from active and retained Search, a palette opening that Search's draft departure refuses leaves nothing of itself: Search keeps the conversation, text, structured preparation and schema and shows its refused-save explanation (not the mismatched-address notice); the address, the conversation store and the reload pointer name the shown conversation; active Search adds no history entry and retained Search only its arrival entry; reload reopens the shown conversation with its draft; Back goes where it went before the attempt.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "An in-app opening reaches Search as an open (not a traversal restore) before its address is pushed; a refusing Search puts its own conversation back and the handler pushes the address Search accepted. A retained Search refuses the same opening when it reconnects, and the projector replaces the arrival address. Traversal refusals keep their current behaviour."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-a-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.paletteConversationRefusal.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.paletteConversationRefusal.test.ts"
      ],
      "class": "New",
      "behaviour": "SP4b from active and retained Search, after a palette opening that Search's draft departure refuses, the navigation journal's latest entry names the conversation Search shows and marks the history entry the app is on; no journal entry names the refused conversation; active Search adds no journal entry and retained Search only its arrival in Search; a bare launch (no address) reopens Search on that conversation with its draft.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The navigation handler journals an in-app conversation opening with the address Search accepted, just before pushing it, instead of the Shell journalling the requested address before distribution. When Search puts its conversation back after the arrival was pushed (retained Search), the projector's in-place address write also writes the journal entry that marks that history entry. Browser arrivals, accepted openings and Back and Forward journal as before."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-a-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.paletteConversationRefusal.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.paletteConversationRefusal.test.ts"
      ],
      "class": "New",
      "behaviour": "SP4b in a split layout showing two Search windows (a contributed window mounting the Search element), a palette opening reaches the Search window the address names although the second window refuses to depart; when that first window refuses an opening it keeps its conversation and draft, the address, conversation store and journal name its conversation, the reload pointer is unchanged, no history entry is added, and the second window is not moved.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "An in-app opening is followed, and may be refused, only by the Search window mounted for Search's address; a refusing window puts its conversation back as the opening, which no other Search window follows. Other Search windows keep following traversal restores and keep their own claims, as before."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-a-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.paletteConversationRefusal.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/CommandPalette.selection.test.ts"
      ],
      "class": "New",
      "behaviour": "SP6/SP8 with SP4/SP9: expanding a group with Show more keeps the selected result by identity (the Settings row General stays selected and Enter opens General, no action runs); a selection that disappears after expansion leaves no selection and Enter invokes nothing.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep the selection by result identity in the one place the shown results are rebuilt, for both live refreshes and Show more."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-a-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/commands/CommandPalette.selection.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "class": "New",
      "behaviour": "SP5 folder opening and confirmation handoff, plus SP5/SP9 disappearance during Library arrival.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Use the existing Library confirmation and assert that opening or removal never substitutes a neighbouring root."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-a.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.altitudeBands.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "clampReorderToAltitudeBands moved with the frame-window eligibility it belongs to, from chrome/Shell.ts to layout/frameAreas.ts (shared by the frame and the palette). The test imports it from its new module; fixtures and all three oracles are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Move the frame-window eligibility, including the altitude clamp, into the owning layout module instead of re-exporting it from Shell."
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
  "session": "verifier-14rpihz5",
  "verdict": "accept",
  "digest": "sha256:616f2025d5d0072a35a1b84be6967ec5d4d5988bda411f0dac8fd3bf18bcdfa3"
}
```
