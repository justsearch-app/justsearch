---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-palette-b-builder
---

# Candidate B — one palette overlay, Tab actions, Search palette retirement, chord ownership, keyboard and accessibility

No acceptance is claimed; independent verification is pending. Fail-before evidence ran on 545232c4d8 (candidate A).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/CommandPalette.overlay.test.ts"
      ],
      "class": "New",
      "behaviour": "SP5 Tab shows each result kind's actions in the decided inventory (Action: Run; Settings row: Open; Folder: Open in Library, Remove from Library; Recent conversation: Open; Area: Open); Escape or Shift Tab returns with query, selected identity and scroll kept and a second Escape closes; a folder's Remove hands off to Library's confirmation request and issues no deleting request; with no result Tab opens no actions and keeps focus in the dialog; keys during IME composition (isComposing or keyCode 229) invoke, move, show actions or dismiss nothing. SP4/SP9 in the actions view: a removed or same-id replacement registration runs nothing and is announced as no longer available. SP8: modal dialog, labelled combobox with aria-controls and aria-activedescendant, listbox with groups labelled by their headers, aria-posinset and aria-setsize over every match, the active option scrolled into view; count, empty, loading and folder-error states announced in a status region; every match reachable by arrow keys alone; forced-colours outline on the active option and the field, and a short-window rule that keeps field and list (style mechanism; the rendered check is the palette-forced-colors and palette-600x400-text-200 harness steps). SP9: dismissal by Escape or backdrop returns focus to the invoker, else to the main region; the palette's Escape reaches no listener beneath. SP10c: an Alt peek is dismissed once on Alt release, leaving the row, removal of the row, dismissal and disconnect, and leaves no keyup listener. Fail-before on 545232c4d8: every case fails with an assertion except the SP10c Alt release and leaving-the-row cases, which pass before (today's behaviour, kept).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Build the actions view, announcements, dismissal focus and peek cleanup in CommandPalette.ts, the one palette element, as the orchestrator decided; keep the field focused and drive options through aria-activedescendant."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-b.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/commands/CommandPalette.overlay.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.palette.test.ts"
      ],
      "class": "New",
      "behaviour": "SP1 with the real Shell: one Ctrl K press from Search's composer, Library, AI Brain and a contributed window invokes shell.toggle-palette exactly once and opens exactly one palette; the next press closes it and focus returns to where the chord was pressed; Search's topbar button opens the same palette and Escape returns focus to the button; under Settings or a confirmation dialog the chord leaves the dialog in control, and the toggle action itself, run while a modal is open, does not open; IME composition, key repeat, Shift, Alt, AltGraph and Cmd on Linux do not toggle; on macOS Cmd K toggles and Ctrl K does not. Fail-before on 545232c4d8 (assertions): closing did not return focus to the invoker, the topbar opened Search's own palette, the toggle opened over Settings, and Search's own chord matcher opened its palette under composition, repeat and extra modifiers.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The keybinding registry's mod+k binding for the toggle action is the only chord owner; the toggle reads modality when it runs, in Shell's togglePalette, so every caller is covered."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-b.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.palette.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.paletteActions.test.ts"
      ],
      "class": "New",
      "behaviour": "SP7: cmd-everything ('Search everything') and cmd-memory ('Open AI Brain › Memory') are registered actions under their ids while a Search window is connected; none of the seven inert entries is offered and Search holds no palette, dialog or combobox of its own; the topbar button invokes shell.toggle-palette once per press; exactly one owner across a preview mount (never owns), split-layout re-parenting (same registration), departure (removed) and retained return (restored), with a second window owning and searching its own draft and an older window's teardown leaving the newer owner's registration; 'Search everything' searches the composer draft, 'Search for' navigates with the palette text, and opening, filtering or cancelling submits neither. Fail-before on 545232c4d8: assertions (the actions are not registered; the topbar raised Search's own palette event).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Search's two working commands become actions owned by the latest connected non-preview Search window; leaving removes only that window's own registrations and hands them to the previous owner."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-b.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.paletteActions.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Palette.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP7/SP1/SP9 (settled amendments): Search's window-scoped palette, its fixture groups and filtering, wraparound, window-only chord, focus trap, focusout dismissal, invoker restore, modality and sv3-palette-request/sv3-palette-run event names are retired; the replacing checks are Shell.palette.test.ts (SP1), CommandPalette.overlay.test.ts (SP5, SP8, SP9) and SearchV3View.paletteActions.test.ts (SP7). Its empty-state cases moved unchanged to Sv3Empty.test.ts."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Empty.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "The four empty-state cases of the retired Sv3Palette.test.ts moved verbatim into their own file with the same mount, region, fetch stub and modality reset; only the palette's imports and helpers were left behind.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep the Sv3Empty oracles intact while the palette file they shared is retired."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Empty.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP7: the component vocabulary no longer names jf-sv3-palette and the palette's exported event names no longer exist; 'names the palette and the empty state' and 'keeps the exported event names equal to the literals the templates bind' are replaced by the vocabulary case below."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Empty.test.ts"
      ],
      "class": "New",
      "behaviour": "SP7: the regenerated component vocabulary names jf-sv3-empty and no longer jf-sv3-palette. Fail-before on 545232c4d8: assertion (the baseline vocabulary contains jf-sv3-palette).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Regenerate the vocabulary with scripts/ci/gen-component-vocabulary.mjs after removing the element."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-b.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Empty.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.memory.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP7: the case drove the retired Search palette element; it is replaced by the same Memory-navigation oracle through the app palette's registered cmd-memory."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.memory.test.ts"
      ],
      "class": "New",
      "behaviour": "SP7: with Search connected, typing 'memory' in the app palette selects 'Open AI Brain › Memory' first, and Enter navigates to core.memory-surface once and closes the palette. Fail-before on 545232c4d8: assertion (the first option is a Settings row; no Memory action exists in the app palette).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "cmd-memory keeps its id and its Memory destination, labelled 'Open AI Brain › Memory'."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-b.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.memory.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP7/SP1: 'HO12 keyboard palette search after a hand-off returns focus to the composer' pressed Search's own chord and drove its palette's field; replaced by the same oracle through the app palette and its one chord binding."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "New",
      "behaviour": "SP7 HO12: after a hand-off, Ctrl K in the composer opens the app palette; filtering 'Search everything' and Enter closes it, switches the composer to Search mode, returns focus to the composer field and dispatches nothing. Fail-before on 545232c4d8: assertion (the composer stays in Ask: the app palette has no Search everything).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "cmd-everything keeps the Search palette's draft permission, preparation switch and composer focus."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-b.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP9 (settled): Escape with the palette open closes the palette before anything beneath; 'closes the pane and leaves an open palette open' and 'leaves the palette its Escape once the pane is closed' pinned the retired pane-before-palette order of Search's own palette."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "class": "New",
      "behaviour": "SP9 with a pane open: one chord in the composer opens only the app palette (no second palette in Search) and its Escape closes the palette while the pane stays open. Fail-before on 545232c4d8: assertion (Search's own palette element is present under the same chord).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "The app palette sits outside Search; its Escape stops at the palette, so Search's pane-first Escape order applies only to keys raised inside Search."
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-palette-b.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-tokens.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP7: 'the palette is built from the geometry tokens, not re-typed numbers' pinned the retired Sv3Palette's style mechanism and the three palette-only tokens (--command-popup-max-height, --command-popup-max-width, --command-scroll-fade-height), retired with it; the dialog-elevation case's clause about the palette component's colour literals goes with the component."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-tokens.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "split"
      ],
      "whatMoved": "The dialog-elevation token assertions moved out of the retired palette describe into 'the dialog material is set in the token sheet' as 'inverts the dialog elevation in the token sheet'; their token oracles are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep pinning the dialog tokens the export dialog, results and window still use."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP7: the 'search palette' arm drove the retired Sv3Palette; the one-overlay-at-a-time case keeps its app palette arm, renamed from 'either palette' to 'the palette'."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The kept shell-palette arm of the one-overlay case is unchanged; the case title now says 'the palette' instead of 'either palette'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Keep the status panel's overlay oracle for the one palette."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.navigation.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The modal under which 'j' must stand down is now the app's palette (jf-command-palette, its first option focused) instead of the retired Search palette; the provider reads it starts are answered empty. Same oracle: no transcript jump and an unclaimed key while open, one jump after hide.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Pin the L10 modal guard against the palette that remains."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchMode.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "'has no developer \"Search this text\" entry, and \"Search everything\" shows results' now opens the app palette, filters 'Search' and clicks the 'Search everything' option instead of clicking the retired palette's fixture row by id; the absence check reads option text instead of a fixture element id. Same oracles: no Search this text entry, Search mode, the draft's results.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Drive Search everything through the palette that remains."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchPreservation.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.urlConversation.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The palette search is triggered by invoking the registered cmd-everything action (what the app palette runs) instead of dispatching the retired sv3-palette-run event with id cmd-everything; drafts, faults and expected outcomes are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Reach the same Search seam through its registered action."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.honesty.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "'replaces the Markdown-only palette action with the conversation export control' reads the absence of 'Copy this conversation as Markdown' from the app palette with every result shown instead of from the retired Search palette.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "Check the retired Markdown action stays absent from the palette that remains."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.export.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including Settings and secondary pages",
          "label": "agent choice",
          "choice": "SP7: the helper's fallback route dispatched the retired Search palette's cmd-export-markdown run event and was reached only on older baselines; the candidate route (the export control) and every expectation are unchanged."
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i \"palette|ctrl\\+k|ctrl k|focus trap|peek\": only an unrelated manual-CI four-palette contrast sweep matched (0026-manual-ci-triggering.md). decision records searched for a conflict: none"
    }
  ]
}
```

## Acceptance records
