---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-wide-builder-f1410f49
---

# Wide and maximised windows follow the page-width rule

The owner's decision Q1 (15 November) gives the rule; the orchestrator's delegated choices W1', W2'
and W2'' (2026-11-16) say how Search's pane and boundary arithmetic read it. Both new test files are
isolated new sources; they carry an entry so their sources and evidence are on record. The only
changed existing file the gate flags is the generated token-name union.

Base: `30f367f53c03a40ca92ea169ae4c43ee05f2f54f` (origin/main of this worktree). The evidence runs
each file whole (one check per file) on the candidate and on that base. On the base the new
behaviour fails by assertion (the column checks see content at the window edge, 16-40 px from the
left, instead of on the column from 480 px; the automatic pane is 540 px, not 560; a wheel in the
gutter after the pane does not move the list); the preservation cases in the same files (980 switch,
1040/1100 pane widths, paddings at 960 and 800, list heights, gutter wheel with the pane closed,
split stage, remembered 360/450/600) pass on the base and on the candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.page-width.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.wide-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "At 1920x1080 every area's content (Library Folders and Browse, AI Brain and Memory, System Health, Logs and Activity, Help, Search results) lies on one centred column of at most 960 px including the area's own padding; tab-row lines, headers' lines and scrollers span the window, and a wheel in either gutter moves the same scroller as one over the content. A details pane opened in Library stays at the window edge and the column centres in what it leaves. Search with its pane open: the sidebar stays at the left edge outside the cap, main column and pane are centred together in at most 1280 px, the automatic pane is the room the main column's 640 leaves (400-560, 560 at 1920, 460 at 1100, 400 at 1040/1088), the main column keeps 640, a wheel in either gutter moves the list, and live resize recomputes. A remembered width wins and is clamped 360-560 as before; absent, invalid or unreadable storage is automatic; reflow writes nothing; drag clamps 360-560 and reset returns to automatic and forgets. At or below 960 px each area keeps its own padding; the 980 inline/overlay switch measures the whole window; split stages are unchanged and the single stage returns to the column after one. Extra height goes to lists (Search, Library, Browse reach the window bottom and scroll to their end); first-launch, empty-Search and zero-match blocks are centred. Light, dark, forced colours and 200% text keep the column; a 1920x500 window keeps the last result reachable by focus.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "wide/maximised windows: top bar spans the window as drawn; below it each area's content is one centred column of at most 960 px (tab-row lines span the window, content on the column); Search with side pane: list+pane centred at most 1280 px, pane 400 px growing to at most 560; extra height to lists; first-launch/empty-search blocks centred as drawn. ONE small PR of its own after slice 8, in the frame's page container, with maximised screenshots of every area. Until then leave wide behaviour alone.",
          "location": "task t-20261113-fmimpl agreement, owner decisions 2026-11-15, Q1"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "W1': the frame publishes --surface-content-max-width (960 px) and Search's list+pane width (1280 px) as the one page-width token; stage, hosts, tab-bar borders and scrollports stay full width and consumers centre their content with inline padding. W2': Search's sidebar stays outside the cap at the left edge; main column and pane are centred together in at most 1280 px; a remembered drag width wins (clamped 360-560, storage untouched); with none, the pane is the room left after the main column's 640, clamped 400-560, recomputed on resize; reset returns to automatic; no pane-floor change for any window; pane closed, Search's column is 960. W2'': the 980 switch measures the window's full width, the boundary arithmetic the capped box; the constrained-window fallback is kept. W5: at or below 960 px nothing changes; split stage unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fm-wide-windows.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-boundaries.page-width.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.wide-lockdown.test.ts"
      ]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/themes/token-names.generated.ts"],
      "class": "Incidental",
      "assessment": {
        "purpose": "Regenerated token-name union after adding --surface-content-with-pane-max-width and --surface-content-gutter to styles/tokens.css (the page-width rule's list+pane width and the derived gutter); --surface-content-max-width keeps its name with the value 960px.",
        "callersAndStateOwners": "scripts/ci/gen-token-names.mjs writes it from tokens.css; tests and the theme editor import the union. No runtime state.",
        "indirectBehaviours": "Two names added; no name removed or renamed.",
        "evidence": "node scripts/ci/gen-token-names.mjs reported 254 tokens; gen-token-names --check passes; npx tsc --noEmit passes; node scripts/ci/run-ui-web-gates.mjs passes the contrast matrix and the style-literal ratchet.",
        "unresolvedRisks": "None known."
      },
      "sources": [
        {
          "kind": "owner-words",
          "quote": "below it each area's content is one centred column of at most 960 px (tab-row lines span the window, content on the column); Search with side pane: list+pane centred at most 1280 px",
          "location": "task t-20261113-fmimpl agreement, owner decisions 2026-11-15, Q1"
        }
      ]
    }
  ]
}
```

## Acceptance records
