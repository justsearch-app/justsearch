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

A follow-up decision, PJ2′ (the orchestrator's, answering the builder's question that a pane at
the automatic 576 or 592 jumped to 560 on the first nudge): the automatic width and a drag measure the
same outer box. Drags and nudges now clamp to 392-592 outer (360-560 of content plus the pane's 16 px
each side), cut to the room beside the main column's 640; where that room is under 392 the pane's 360
floor stays, so a window just above the 980 switch drags as before. The remembered width has always
stored the pane's OUTER width (`--pane-width`, the track jf-sv3-pane takes), so a value remembered
before is read unchanged: 360, 450 and 560 open at 360, 450 and 560 with a 331, 421 and 531 px text
column, the same as on the base. Reading a remembered width keeps the 360 floor and now allows up to
592, so a drag to 592 reopens at 592. PJ2′ supersedes the assertions that pinned a 560 ceiling or a
360 drag floor in five files, named in the Obsolete entry. On a shared clone of 26ae540014 (the dock
without PJ2′) the new PJ2′ cases fail by assertion: a nudge wider from the automatic 576 lands on
560, a nudge narrower from 592 lands on 560, a drag to the floor lands on 360; in the unit file a
remembered 600 reads 560 and the drag clamp does not exist yet.

Base: `5a2ac8226e`. The evidence runs each new file whole (one check per file) on the candidate. The
same files were run on a shared clone of the base: `sv3-boundaries.pane-dock.test.ts` fails 17 of 24
by assertion (the automatic pane is 400 at 1400-3840 px where the rule says 420-592, because the base
reads the third argument as a 1280-style cap); `Shell.paneDock-lockdown.test.ts` fails 22 of 28 by
assertion (the pane ends at 1600 / 1728 / 1624 px instead of 1920 with no, an expanded and a collapsed
sidebar, in dark and light; the automatic pane is 460 at 1100 and 560 at 1400-2400; a remembered 450
ends at 1600; the list's column starts 192-400 px away from the start of the 960 column centred
between sidebar and pane; the frame still publishes `--surface-content-with-pane-max-width: 1280px`).
The preservation cases pass on the base and on the candidate: at 1024x700 the pane (x 640, 384 wide),
its grip, the main column and the list keep their pixel geometry; a stacked split at 1920 keeps the
540 px pane; a wheel in either gutter beside the list moves the list; and in the unit file the
constrained-window widths below 1040 and the unpublished-share fallback; in the lockdown file a
remembered 360, 450 and 560 open at 360, 450 and 560 with a 331, 421 and 531 px text column.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.pane-dock.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.paneDock-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "With Search's pane open inline (980 px of window and up), the pane is the window's last track: its right edge, its surface, the title row with its bottom line and its close button, and the foot actions with their top line all reach the window's right edge (1920 at 1920 with no, an expanded or a collapsed sidebar, in dark and light); the last pixel column is painted by the pane; the grip straddles its start edge. With no remembered width the pane is max(400, 30 % of the window width) and at most 592 (560 of content plus 16 px padding each side), never past the room the main column's 640 leaves beside the sidebar: 384 at 1024, 400 at 1100-1333, 420 at 1400, 480 at 1600, 576 at 1920 with any sidebar, 592 from 1973; at 2400 the document's text lies at least 16 px in from each side and at most 560 wide. Live resize follows the window and writes nothing to the remembered width; a remembered width still wins (450 stays 450 at the edge). PJ2′: the automatic width and a drag measure the same outer box; a drag or nudge clamps to 392-592 outer (360-560 of content plus 16 px padding each side), cut to the room beside the main column's 640 (404 at 1300 with an expanded sidebar), with the pane's 360 floor kept where that room is under 392 (360-384 at 1024 with no sidebar, as before); a nudge from the automatic width moves exactly one 24 px step (576 -> 552 / 592 at 1920, 592 -> 568 / 592 at 2400); Home returns to automatic and forgets; a drag to 392 and to 592 is remembered as '392' and '592', and a remembered 592 reopens at 592. A width remembered before PJ2′ is read as stored, the same outer width: 360, 450 and 560 open at 360, 450 and 560 with a 331, 421 and 531 px text column, as on the base, and storage is not rewritten on read; a remembered 600 reads as 592. There is no list+pane cap: the frame publishes no --surface-content-with-pane-max-width; the main column runs from the sidebar (or the window's left edge) to the pane, and the list keeps its plain-results 960 column centred there (also at 2400); a wheel in either gutter beside the list moves the list. At 1024x700 the pane, grip, main column and list keep the base's pixel geometry; with no published share (a split stage, a DOM without layout) the automatic width is the one from before the rule, and a stacked split at 1920 keeps the 540 px pane.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PJ2′: the automatic width and the drag limits measure the same box. The pane's outer width (including its 16 px padding each side) is what both the automatic rule and dragging set: automatic max(400, 30% of the window) up to 592; dragging and arrow-key nudges clamp to 392-592 outer (= 360-560 content), so a nudge from any automatic width moves by one step without a jump. Stored values keep their meaning; a value remembered before this change keeps giving the same visible content width; storage is not rewritten on read.",
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
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.page-width.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.pane.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.test.ts",
        "modules/ui-web/src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts"
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
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "PJ2′: the automatic width and the drag limits measure the same box. The pane's outer width (including its 16 px padding each side) is what both the automatic rule and dragging set: automatic max(400, 30% of the window) up to 592; dragging and arrow-key nudges clamp to 392-592 outer (= 360-560 content), so a nudge from any automatic width moves by one step without a jump. Stored values keep their meaning; a value remembered before this change keeps giving the same visible content width; storage is not rewritten on read.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.ts#sv3PageGutter"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions has no record on the page width or the side pane). Shell.wide-lockdown.test.ts: the WITH_PANE (1280) constant is gone; 'SW-2 … with no sidebar / an expanded sidebar / a collapsed sidebar' expected a 560 px pane ending (1920 - sidebar - 1280) / 2 px from the edge and the list starting on that centred box; now a 576 px pane at the window edge and the list starting on the 960 column centred between the sidebar and the pane (the main column's 640 check kept). 'SW-2 a wheel in either gutter moves the list' wheeled at x=1800 (the gutter after the pane, now inside the pane); now at 40 px before the pane, the list's own right gutter (the left-gutter wheel at x=100 kept). 'SW-2 live resize' expected 460 at 1100 and 560 ending at 1600 at 1920; now 400 and 576 ending at 1920. 'SW-4 at 1100 px … the automatic pane is 460' now 400 (the 1040 -> 400 case kept). 'SW-5 stored absent / invalid / storage unavailable' expected 560 at 1920; now 576 (360, 450 and 600 -> 560 kept, as are the no-writes checks). 'SW-5 dragging … reset returns to automatic' expected 560 after reset; now 576 (the drag to 360 and the forget kept). sv3-boundaries.page-width.test.ts: the automatic pane's third argument is the share (0.3) instead of the cap (1280); '1920 with 0 / 48 / 256 px sidebar is 560' now 576; '1280 is 560' now 400; '1100 is 460' now 400 (1088/48 and 1040 -> 400 kept); 'grows from 400 at the 1040 / 1088 boundaries by the room it gains, up to 560' removed (the pane no longer grows from 1040; the new file pins where it grows); the 'gutter beside the list and the pane' describe (five gutter widths, 'list plus pane fill at most the cap', 'is zero for an unmeasured window or without a published cap') removed with sv3PageGutter; 'with no published cap' now passes no share (null) with the same expectation; 'below 1040 keeps the existing constrained-window ceiling' and 'what the rule leaves alone' unchanged. PJ2′ (the drag limits measure the pane's outer box, 392-592; a remembered width reads from 360 up to 592) supersedes these assertions: Shell.wide-lockdown.test.ts 'SW-5 dragging clamps to 360-560 and remembers ...' renamed '... clamps to 392-592 outer ...', the drag to the floor expected 360 and '360', now 392 and '392' (the reset to 576 and the forget kept); 'SW-5 stored 600' expected 560, now 592 (no base code path stores above 560; absent, 360, 450, invalid and unavailable kept). sv3-boundaries.page-width.test.ts 'a remembered or dragged width is clamped as before, 360-560' renamed 'a remembered width is read as stored, from 360 up to the outer 592 (PJ2′)': 600 -> 560 and clampSv3PaneWidth 900 -> 560 now 592 (360, 450 and 300 -> 360 kept). sv3-boundaries.test.ts: 'stops at the details pane’s 560 ...' sv3PaneMaxWidth(2400, 0) and clampSv3PaneWidth(5000, 2400, 0) now SV3_PANE_DOCKED_MAX_PX / 592; 'clamps a drag to the spec floor and to the ceiling' clampSv3PaneWidth(100000, 1568, 256) now SV3_PANE_DOCKED_MAX_PX (floor 360, 500 and the 1300/256 room kept); 'opens at the spec default ...' resolveInitialSv3PaneWidth(700, 1920, 256) now SV3_PANE_DOCKED_MAX_PX (default, 500, 200 -> 360 and 900 at 1400 -> 504 kept); 'treats an unmeasured box as unknown ...' clampSv3PaneWidth(900, Infinity, 0) now SV3_PANE_DOCKED_MAX_PX. SearchV3View.pane.test.ts 'reopens at a remembered width the current box still allows' (900 at 1400 with no conversation column) expected 560, now 592 (the 360 floor check kept). In the new sv3-boundaries.pane-dock.test.ts the PJ4 case 'a remembered or dragged width is still clamped 360-560' is renamed 'a remembered width reads as stored, from 360 up to the outer 592 (PJ2′)' with 600 and 900 -> 592 and 360 / 560 added. DetailsPane.frame-lockdown.test.ts 'the grip keeps the width inside the limits and the main column its 640' (Search's grip at 1600, twenty nudges each way) expected 560 and 360, now 592 and 392 (the box and the nudging kept). The shared details pane's own 360-560 (WindowDetails, Activity) is untouched."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/token-names.generated.ts"
      ],
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
