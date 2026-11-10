---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: s6-focus-builder-20261110
---

# Built-in palette keyboard focus contrast

All six built-in CSS/JSON palettes are checked against surface-0 through surface-4 in both
appearances, merging each palette value over the inherited appearance value independently.
The source guard scans CSS and JS/TS templates for focus outline and box-shadow token bypasses.
Non-painting resets and transparent outlines repainted by forced colours remain; explicit system
colour overrides remain. Painted focus outlines, the session halo, composer frame, grips and the
focus-ring utility now read --focus-ring-color. No accent or other token values change.
Search keeps its window-scoped --ring, whose contrast is pinned by sv3-tokens.test.ts;
the guard permits that token only inside views/search-v3/.

The runner-produced `evidence/s6-focus.json` records both checks failing assertions before on
fa0f221344 and passing on the candidate. `evidence/s6-focus-highvis.json` additionally records
the extended palette check failing on 3efe7c90d1: High Vis inherits light surface-3 and surface-4,
where the old ring measures 1.1671:1 and 1.0938:1. Candidate checks are overlaid into temporary
checkouts with private dependency copies. The evidence runner itself runs from a shared clone,
so its temporary worktree registration does not write into the source repository.

Sepia keeps its committed deeper amber --focus-ring-color #79551f: 5.6329, 5.1958, 4.5551,
3.8926 and 3.2499:1 on surfaces 0–4 in either appearance. High Vis adds a cyan
--focus-ring-color #008890. Its ratios on surfaces 0–4 are 4.6883, 4.4758, 4.2098, 3.7997,
3.3370:1 in dark and 4.6883, 4.4758, 4.2098, 3.5895, 3.3640:1 in light.
The palette and guard checks pass together (117 assertions).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/builtinPaletteContrast.test.ts",
        "modules/ui-web/src/shell-v0/themes/focusRingAuthority.test.ts"
      ],
      "class": "New",
      "behaviour": "Every built-in CSS/JSON palette has an effective keyboard focus ring with at least 3:1 contrast against all five surfaces in both appearances. Each palette value overrides the appearance base independently, including partially inherited surfaces. Focus outline and box-shadow rules read --focus-ring-color instead of accents or local ring aliases; only views/search-v3/ may keep its window-scoped --ring, pinned by its own contrast tests. Forced-colour system colours and non-painting resets remain. Existing accent checks keep their expectations.",
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
      "newChecks": [
        "modules/ui-web/src/shell-v0/themes/builtinPaletteContrast.test.ts",
        "modules/ui-web/src/shell-v0/themes/focusRingAuthority.test.ts"
      ],
      "repaired": true
    }
  ]
}
```
