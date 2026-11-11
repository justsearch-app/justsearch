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
      "behaviour": "SP4b opening a recent conversation from the palette is one navigation to Search with the conversation in its address; from active, cold and retained Search it opens exactly that conversation with one history entry and correct Back, Forward and reload; from active and retained Search a refused draft departure keeps text, preparation, the shown conversation and the reload pointer, and Back returns to the origin address.",
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
