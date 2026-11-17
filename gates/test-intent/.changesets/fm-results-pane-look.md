---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-pa-builder-59691a49
---

# Search results and the side pane look as drawn in fm-04 and fm-05

The design review of the packaged app by the author of the pictures (2026-11-16) found the found
words drawn as solid orange blocks, no selected look on the row whose file is in the pane, the pane's
text grey, its found words unmarked, its name drawn as a tab and its path breaking mid-word. The
orchestrator's delegated decisions PA1-PA5 say how each is drawn. The two new test files are isolated
new sources; they carry an entry so their sources and evidence are on record. The changed existing
files the gate flags are the generated token-name union and one test's stub host.

Base: `2d51325e394b753a415eaa4ae5821dc940b6225d`. The evidence runs each new file whole (one check per
file) on the candidate. The same two files were also run on a shared clone of the base: every
new-behaviour case fails there by assertion (the unit file 5 of 5: `highlightSegments` and
`splitFolderTail` are not functions; the browser file 16 of 19: the mark computes to the solid
`oklch(0.5 0.11 70)` / `oklch(0.88 0.12 70)` instead of the picture's tint, the pane has no marks, the
open row has `aria-current="false"` and no outline in forced colours, the pane's text computes to the
muted grey instead of the theme's `--text-primary`, the title keeps its underline, the close button
says "Close the cited document" and the path runs to two lines). The three preservation cases (a
cited document keeps no marks and its own close label; a short path is shown whole and uncut; the
pane keeps its actions and the list its snippet size) pass on the base and on the candidate.

The colour checks compare with the normal text colour itself: `--text-primary` resolved by a probe
inside the checked element's own tree, in both themes, never with another element's colour. The
selected row's fill, painted over what is under it, must match the picture's raised tone `--r2`
(dark `rgba(255,255,255,.06)`, light `rgba(0,0,0,.05)`) within 2 of 255 per channel; the app has no
token of that name and draws the tone as `--text-primary` at 6 %, which paints within that margin of the
picture. The cut start of the path must end in an ellipsis. Mutations on the candidate, each failing
its case in both themes: the pane's text grey (`--text-secondary`), the title alone grey, the found
words grey, the path cut without an ellipsis (`text-overflow: clip`), the selected row on
`--surface-active`.

Repair after the real-app verifier (decision PA1'): with Nord, High Vis and Cyanotype picked in
Settings and Light chosen, found words fell to 2.9-4.2:1 and the pane's text to about 1.1-1.3:1.
"Both themes" now means every built-in palette in Light and in Dark, and user themes. Causes: (1)
the wash was chosen by the light/dark setting while a palette keeps its own ground, so Nord in Light
put the pale light-mode wash under its light ink; (2) the Search window paints the palette's ground
(`--background: var(--surface-0)`) but the pane re-pointed `--text-primary` to the window's own
`--foreground`, which follows only light/dark: dark ink on Nord's dark ground in Light, light ink on
Sepia Focus's cream ground in Dark. Repair: the appearance writer derives `--highlight-wash` from each
theme's own `--surface-0` and `--text-primary` like the accent pairs (the authored wash for the
ground's side, by luminance; less opacity where it would still sink the word; a derived ink only where
the theme's own text does not read on its own ground), and the pane keeps the theme's `--text-primary`.
The check iterates the registry `public/themes/manifest.json` (plus the default and a user theme) in
Light and Dark, and high contrast on top for the found words. Run on a shared clone of 585eaa5070
with only the new check file, it failed by assertion (list / pane word / pane text): Nord light 2.76
/ 4.20 / 1.19; High Vis light 4.25 / 3.23 / 1.34; Cyanotype light 3.20 / 3.66 / 1.10; Night Registry
light 3.44 / 3.38 / 1.29; Sepia Focus dark 9.85 / 1.20 / 1.09; Registrar's Ledger dark 13.36 / 1.07 /
1.06; the user theme light 2.70 / 5.25 / 2.13; with high contrast in Dark, Sepia Focus 1.30 / 1.20 and
Registrar's Ledger 1.17 / 1.07. After: every built-in palette in both modes at least 5.63 / 6.38 /
9.90 (Nord), the user theme 4.70 / 5.20 / 7.00, high contrast at least 4.70 / 5.20. The default themes
keep the picture's tint exactly (the earlier PA1 cases still compare with the picture's values). Also:
a search result whose file a later search left out of the list keeps "Close <file name>"; the check
failed on the clone with "Close the cited document". Mutations on the candidate, each failing the
palette check: the pane re-pointed to `--foreground` again (pane text 1.06-1.34 in six palette/mode
pairs, 2.13 in the user theme); the derived wash switched off, so the wash follows light/dark again
(Nord, High Vis, Night Registry, Cyanotype and the user theme in Light 2.70-4.25; Sepia Focus and
Registrar's Ledger with high contrast in Dark 1.17-1.31).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.paneMarks.test.ts",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.resultsPane-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/themes/roleForegrounds.highlightWash.test.ts"
      ],
      "class": "New",
      "behaviour": "Found words in result snippets and in the pane's text of a search result are a faint warm tint behind the word (dark rgba(240,184,96,.22), light oklch(86% .14 85 / .55)) with the text in the normal text colour, 4 px corners, padding 0 2px in the list and 1px 2px plus the picture's 3 px ring in the pane; the marked text reads at 4.5:1 or more on its tint, and the pane's document text at 4.5:1 or more on the pane, in every built-in palette of the registry (public/themes/manifest.json) and the default, in Light and in Dark, and in a user theme, in the list (open and plain rows) and in the pane; with high contrast on top the found words still read at 4.5:1 or more. The wash is derived from each theme's own ground and ink: the authored wash for the ground's side by luminance (so the default themes paint exactly the picture's tint), less opacity where that one would sink the word, a derived ink only where the theme's own text does not read on its own ground; forced colours use the system Mark/MarkText pair. The pane marks the same words the snippet marks (one matching: highlightSegments), in plain text and in rendered Markdown, only for the search result it shows; a cited document shows no marks and keeps 'Close the cited document'. The row whose file is in the pane has the picture's raised tone (--r2, painted within 2/255) with 8 px corners, no line under it, aria-current=true, the same look whether opened by pointer or Enter, its text where it was, and in forced colours a Highlight outline. The pane's document text and the title are the theme's normal text colour (--text-primary, resolved in their own tree), as are the found words; a document shown on its own has a plain title (no underline, also in forced colours) and a close button named 'Close <file name>', which it keeps when a later search leaves the file out of the list. The path is one line shortened in the middle (the last folder and the facts stay whole, the cut start ends in an ellipsis), the whole folder stays in the text and the full path in the title; a short path shows whole: its text is the whole folder and the facts, and nothing in the line is cut off. The pane's actions, the tab count and the snippet's size are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "found words: solid bright orange blocks, several per row; the list reads as orange speckle before it reads as text. Picture: a faint warm tint behind the word, text in its normal colour. The row whose file is in the pane has no selected look ... Picture: tinted row, 8 px corners. Pane: the file's text is grey and reads as disabled; it is the content and should be the normal text colour. The found words are not marked in it. The name is drawn as a tab with an underline (picture: a plain title, no tab). The path breaks mid-word; shorten it from the middle.",
          "location": "task t-20261113-fmimpl request, design review of the packaged app by the author of the pictures (2026-11-16), quoted (abridged) in the supplied PA builder brief"
        },
        {
          "kind": "owner-words",
          "quote": "--mark:rgba(240,184,96,.22); --mark-ring:rgba(240,184,96,.08); html.light{ --mark:oklch(86% .14 85 / .55); --mark-ring:oklch(86% .14 85 / .2) } .doc mark{background:var(--mark);color:var(--fg);border-radius:4px;padding:1px 2px;box-shadow:0 0 0 3px var(--mark-ring)} .rr p mark{background:var(--mark);color:var(--fg);border-radius:4px;padding:0 2px} .rr.sel{background:var(--r2);border-radius:8px;border-bottom-color:transparent} .dptabs .one{display:flex;align-items:center;gap:8px;padding:0 14px;font-size:13px;font-weight:500;color:var(--fg)}",
          "location": "task t-20261113-fmimpl agreement, agreed picture fm-05-results-details (tmp/design/fm-05-results-details.html; the pictures are not in git, the words are quoted here)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PA1: the picture's mark through theme tokens (--highlight-wash, --highlight-wash-ring), text colour unchanged, radius 4 px, padding 0 2px (list) / 1px 2px (pane, with the ring); forced colours Mark/MarkText; contrast at least 4.5:1 in both themes. PA2: the pane marks the query's found words with the snippet's matching, only for the search result it shows. PA3: the row whose file is open in the pane gets the picture's selected look (raised tone, 8 px radius, bottom line hidden; a system-colour outline in forced colours) and aria-current as the list already uses; keyboard and pointer give the same look. PA4: the pane's text is the normal text colour; the name is a plain title; close is 'Close' plus the file name for a search result and stays 'Close the cited document' for a cited document; the path is shortened from the middle to one line with the full path in the title and available to screen readers. PA5: nothing else in the pane or list changes. PA1' (repair after the real-app verifier): 'both themes' means every built-in palette in both Light and Dark, and user themes: found-word text on its tint >= 4.5:1 and the pane's document text >= 4.5:1 against the pane background, in list and pane; the tint and text pair derived from each palette's own tokens as the theme system does for the contrast-floored accent pairs, the default palettes keeping the picture's tint. When the open file leaves the result list, the close button keeps 'Close <file name>'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-results-pane-look.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/searchResults/resultRowPresentation.paneMarks.test.ts",
        "modules/ui-web/src/shell-v0/chrome/FirstMinutes.resultsPane-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/themes/roleForegrounds.highlightWash.test.ts"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/themes/token-names.generated.ts"],
      "class": "Incidental",
      "assessment": {
        "purpose": "Regenerated token-name union after adding --highlight-wash and --highlight-wash-ring to styles/tokens.css (the found-word tint and the reader's ring), and the authored values they are derived from, --highlight-wash-on-dark / -on-light and --highlight-wash-ring-on-dark / -on-light (the dark and light values from the fm-05 picture).",
        "callersAndStateOwners": "scripts/ci/gen-token-names.mjs writes it from tokens.css; tests and the theme editor import the union. No runtime state.",
        "indirectBehaviours": "Six names added; no name removed or renamed.",
        "evidence": "node scripts/ci/gen-token-names.mjs reported 260 tokens; npx tsc --noEmit passes; the full npx vitest run passes.",
        "unresolvedRisks": "None known."
      },
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Picture: a faint warm tint behind the word, text in its normal colour.",
          "location": "task t-20261113-fmimpl request, design review of the packaged app by the author of the pictures (2026-11-16), quoted in the supplied PA builder brief"
        }
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/views/search-v3/first-minutes/HelpMatchesLifecycle.test.ts"],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "The test's stub host for Sv3SearchController gains paneDocPath: () => null. The host interface already declared paneDocPath; the result list's view now reads it (the row whose file is in the pane), so a stub without it threw a TypeError inside view(). Inputs, the refinement failures injected, the observation (quick rows and the Help line stay visible) and the expected outcomes are unchanged; null is 'no pane open', the state these cases always ran in."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "985a9628-fae2-4fd2-8652-641a7e5a9c50",
  "verdict": "accept",
  "digest": "sha256:8b6c120b2799310213110026501fc08615f24bbf1986714c083ec45465a3141c"
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "dc77580d-ea00-4b41-997d-e652fb2efca8",
  "verdict": "accept",
  "digest": "sha256:8b6c120b2799310213110026501fc08615f24bbf1986714c083ec45465a3141c"
}
```
