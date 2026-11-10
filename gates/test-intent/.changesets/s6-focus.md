---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-focus-builder-20261110
---

# Built-in palette keyboard focus contrast

Discover every CSS/JSON palette in public/themes, excluding manifest metadata, instead of the
previous three-file subset. Preserve the existing accent checks and add a 3:1 focus-ring floor
against each surface-0 through surface-4 the palette defines. If it defines no surfaces, check
the inherited base surfaces. Resolve palette focus-ring overrides, accent fallback and inherited
base values in both light and dark appearances, including token aliases and base hue seeds.

On origin/main (fa0f2213444bd62de138981efcae72a9bc8ed059), with only the candidate test overlaid
in a shared clone, the new assertions fail ten times: Sepia's five surfaces in both appearances.
Its original accent-based ring measures 2.62, 2.41, 2.12, 1.81 and 1.51:1 on surfaces 0–4.
All other checks pass (102 passed, 10 failed). The runner-produced record is
`gates/test-intent/evidence/s6-focus-before.json`; its command exits 1 for those assertions.

Only Sepia needs a palette change: `--focus-ring-color: #79551f`, a deeper amber, measures
5.6329, 5.1958, 4.5551, 3.8926 and 3.2499:1 on surfaces 0–4. No other token changes.
The candidate has 112 passing checks, including all 56 focus/surface/appearance checks.
The forced-colors system Highlight rules remain in place. CSS palettes use the existing direct
injection path; no theme validation or conformance rule changes.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/themes/builtinPaletteContrast.test.ts"],
      "class": "New",
      "behaviour": "Every built-in CSS/JSON palette has an effective keyboard focus ring with at least 3:1 contrast against every surface it defines, or inherited base surfaces if it defines none. Palette ring overrides win, otherwise the palette accent and appearance base apply. Both appearances are checked. Existing accent-on-color checks extend to every discovered palette without weakening their expectations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "the shell redesign's slices including follow-up defects it owns",
          "choice": "built-in palettes keep a focus ring of at least 3:1 against their surfaces",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s6-focus.json",
      "newChecks": ["modules/ui-web/src/shell-v0/themes/builtinPaletteContrast.test.ts"]
    }
  ]
}
```
