---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-pj-builder-2282a993
---

# In wide windows Search's side pane docks to the window's right edge

The author of the pictures reviewed the maximised build of the page-width rule (#1274) and, with the
owner's delegation, decided that Search's pane docks to the window's right edge, is 400 px at the
default window and then 30 % of the window up to 592 px, and that the 1280 list+pane cap is dropped,
the list keeping its 960 column centred in what the pane leaves. The orchestrator's delegated
decisions PJ1-PJ5 say how it is built. The two new test files are isolated new sources; they carry an
entry so their sources and evidence are on record. Two files from #1274 pinned the 1280 centring and
the automatic width derived from it; PJ5 supersedes exactly those assertions, named below. The
generated token-name union follows the token change.

Base: `5a2ac8226e`. The evidence runs each new file whole (one check per file) on the candidate. The
same files were run on a shared clone of the base: `sv3-boundaries.pane-dock.test.ts` fails 9 of 17
by assertion (the automatic pane is 400 at 1400-3840 px where the rule says 420-592, because the base
reads the third argument as a 1280-style cap); `Shell.paneDock-lockdown.test.ts` fails 19 of 22 by
assertion (the pane ends at 1600 / 1728 / 1624 px instead of 1920 with no, an expanded and a collapsed
sidebar, in dark and light; the automatic pane is 460 at 1100 and 560 at 1400-2400; a remembered 450
ends at 1600; the list's column starts 192-400 px away from the start of the 960 column centred
between sidebar and pane; the frame still publishes `--surface-content-with-pane-max-width: 1280px`).
The preservation cases pass on the base and on the candidate: at 1024x700 the pane (x 640, 384 wide),
its grip, the main column and the list keep their pixel geometry; a stacked split at 1920 keeps the
540 px pane; a wheel in either gutter beside the list moves the list; and in the unit file the
constrained-window widths below 1040, the unpublished-share fallback and the 360-560 clamp of a
remembered width.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.pane-dock.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.paneDock-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "With Search's pane open inline (980 px of window and up), the pane is the window's last track: its right edge, its surface, the title row with its bottom line and its close button, and the foot actions with their top line all reach the window's right edge (1920 at 1920 with no, an expanded or a collapsed sidebar, in dark and light); the last pixel column is painted by the pane; the grip straddles its start edge. With no remembered width the pane is max(400, 30 % of the window width) and at most 592 (560 of content plus 16 px padding each side), never past the room the main column's 640 leaves beside the sidebar: 384 at 1024, 400 at 1100-1333, 420 at 1400, 480 at 1600, 576 at 1920 with any sidebar, 592 from 1973; at 2400 the document's text lies at least 16 px in from each side and at most 560 wide. Live resize follows the window and writes nothing to the remembered width; a remembered width still wins (450 stays 450 at the edge) and is clamped 360-560 as before. There is no list+pane cap: the frame publishes no --surface-content-with-pane-max-width; the main column runs from the sidebar (or the window's left edge) to the pane, and the list keeps its plain-results 960 column centred there (also at 2400); a wheel in either gutter beside the list moves the list. At 1024x700 the pane, grip, main column and list keep the base's pixel geometry; with no published share (a split stage, a DOM without layout) the automatic width is the one from before the rule, and a stacked split at 1920 keeps the 540 px pane.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the pair is centred as I specified, but the pane ends at 1600 px with empty window to its right and its lines stopping mid-air. Better: the pane's surface and lines run to the window's right edge, its content stays at most 560 px, the list is centred in what is left.",
          "location": "task t-20261113-fmimpl request, review of the maximised build by the author of the pictures, quoted in the supplied PJ builder brief"
        },
        {
          "kind": "owner-words",
          "quote": "just ask the other agent to make the decisions",
          "location": "task t-20261113-fmimpl request, the owner's turn delegating the pane's decisions to the author of the pictures, quoted in the supplied PJ builder brief"
        },
        {
          "kind": "owner-words",
          "quote": "The pane docks to the window's right edge. Pane width: 400 px at the default window, then 30 % of the window width, up to 592 px (560 of content plus 16 padding each side). Title, close button, lines and foot actions all span that width, so nothing stops mid-air. The 1280 cap: dropped. The list keeps the maximum width it already has for plain results and is centred in the space left of the pane. At 1024×700: nothing changes; the picture stays valid. ... Accepted cost: on very wide screens the gap between list and pane grows.",
          "location": "task t-20261113-fmimpl request, the decision of the author of the pictures under the owner's delegation, quoted in the supplied PJ builder brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PJ1: with the pane open inline, the pane sits against the window's right edge; its surface, borders and lines span its full width. PJ2: automatic pane width max(400, 30% of the window width), at most 592 px; a remembered drag width still wins, clamped as today, storage untouched; reset returns to automatic. PJ3: the 1280 list+pane cap is removed (the token and the code only it needed); the results column keeps its plain-results maximum (the 960 page column) and is centred between the sidebar (or the window's left edge) and the pane. PJ4: at 1024x700 and at or below today's switch points nothing changes; the 980 overlay switch, the split stage and other areas are unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-pane-dock.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.pane-dock.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.paneDock-lockdown.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.wide-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.page-width.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The pane docks to the window's right edge. Pane width: 400 px at the default window, then 30 % of the window width, up to 592 px (560 of content plus 16 padding each side). ... The 1280 cap: dropped. The list keeps the maximum width it already has for plain results and is centred in the space left of the pane.",
          "location": "task t-20261113-fmimpl request, the decision of the author of the pictures under the owner's delegation (\"just ask the other agent to make the decisions\"), quoted in the supplied PJ builder brief"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PJ5: the tests from #1274 that pin the 1280 centring are superseded; change only those assertions, name each; keep every other wide-window check.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": ["modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.ts#sv3PageGutter"]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions has no record on the page width or the side pane). Shell.wide-lockdown.test.ts: the WITH_PANE (1280) constant is gone; 'SW-2 … with no sidebar / an expanded sidebar / a collapsed sidebar' expected a 560 px pane ending (1920 - sidebar - 1280) / 2 px from the edge and the list starting on that centred box; now a 576 px pane at the window edge and the list starting on the 960 column centred between the sidebar and the pane (the main column's 640 check kept). 'SW-2 a wheel in either gutter moves the list' wheeled at x=1800 (the gutter after the pane, now inside the pane); now at 40 px before the pane, the list's own right gutter (the left-gutter wheel at x=100 kept). 'SW-2 live resize' expected 460 at 1100 and 560 ending at 1600 at 1920; now 400 and 576 ending at 1920. 'SW-4 at 1100 px … the automatic pane is 460' now 400 (the 1040 -> 400 case kept). 'SW-5 stored absent / invalid / storage unavailable' expected 560 at 1920; now 576 (360, 450 and 600 -> 560 kept, as are the no-writes checks). 'SW-5 dragging … reset returns to automatic' expected 560 after reset; now 576 (the drag to 360 and the forget kept). sv3-boundaries.page-width.test.ts: the automatic pane's third argument is the share (0.3) instead of the cap (1280); '1920 with 0 / 48 / 256 px sidebar is 560' now 576; '1280 is 560' now 400; '1100 is 460' now 400 (1088/48 and 1040 -> 400 kept); 'grows from 400 at the 1040 / 1088 boundaries by the room it gains, up to 560' removed (the pane no longer grows from 1040; the new file pins where it grows); the 'gutter beside the list and the pane' describe (five gutter widths, 'list plus pane fill at most the cap', 'is zero for an unmeasured window or without a published cap') removed with sv3PageGutter; 'with no published cap' now passes no share (null) with the same expectation; 'below 1040 keeps the existing constrained-window ceiling' and 'what the rule leaves alone' unchanged."
    },
    {
      "items": ["modules/ui-web/src/shell-v0/themes/token-names.generated.ts"],
      "class": "Incidental",
      "assessment": {
        "purpose": "Regenerated token-name union after styles/tokens.css replaced --surface-content-with-pane-max-width (the dropped 1280 list+pane width) with --surface-pane-share (30 %, the docked pane's automatic share of the window, reset to none in a split stage like the old token).",
        "callersAndStateOwners": "scripts/ci/gen-token-names.mjs writes it from tokens.css; tests and the theme editor import the union. No runtime state. No other source reads either name (grep over modules/ui-web/src).",
        "indirectBehaviours": "One name removed, one added. No stored theme or user setting carries either name: the page-width tokens are frame layout, not theme colours.",
        "evidence": "node scripts/ci/gen-token-names.mjs reported 260 tokens; npx tsc --noEmit passes; Shell.paneDock-lockdown.test.ts 'the frame publishes no list+pane width any more' reads '' on the candidate and '1280px' on the base; its split case keeps the 540 px pane, so the share's split reset still takes effect.",
        "unresolvedRisks": "None known."
      },
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The 1280 cap: dropped.",
          "location": "task t-20261113-fmimpl request, the decision of the author of the pictures under the owner's delegation, quoted in the supplied PJ builder brief"
        }
      ]
    }
  ]
}
```

## Acceptance records
