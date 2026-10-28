---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp24b-builder-p8xact41
---

# Test-intent entries

The expectations are agent choices under the quoted design delegation, not owner-agreed words.
The orchestrator produces D4 execution evidence and obtains independent acceptance; this builder did not run run-evidence.mjs.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts"
      ],
      "class": "New",
      "behaviour": "Y1\u2013Y7 mounted production Search checks: empty/focus and keyboard/IME/modifier routing; explicit-only attempts, cap, trim, exact dedup, failed/invalid attempts and palette; operator replay with current restrictions, conversation navigation and retained nonempty/empty preparation; Clear population/time/inflight/no-write behavior; fresh view/profile ABA and stale replies; lock/423/cached labels/unlock/untitled/deletion; named accessible list across states and themes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing the remaining Search window work, areas 1\u20135; WP2.4b recents, R1\u2013R7 and Y1\u2013Y8 in settled scenarios revision 3.",
          "choice": "R1\u2013R7: focused empty Search shows up to eight exact-deduplicated explicit session searches and five conversations sorted by reported activity. Replay uses current restrictions and existing draft/navigation guards. Clear uses an in-memory timestamp and does not mutate history. Fresh windows and profile switches reset transient state; previous-profile responses are dropped. Observed lock/423 hides conversation labels; unlock refreshes. Untitled conversations use explicit fallback copy. Historical SEARCH readers and other Recent surfaces are preserved; no new SEARCH events. Y7 adds measured hero/docked, normal/narrow and light/dark/forced-colours captures.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/wp24b-recents.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y1 opens only for an empty focused Search bar and owns selection and dismiss keys",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y2 records only accepted explicit Search actions with exact dedup and an eight item cap",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y3 replays operators with current restrictions and selects conversations through navigation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y4 Clear hides the full population with no backfill, rename reactivation or mutation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y4 Clear before a first delayed list response suppresses every old row",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y5 fresh windows forget searches and Clear while profile ABA drops pending responses",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y6 observed lock hides cached optimistic and legacy labels and pre-lock responses until unlock refresh",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y6 a history 423 immediately suppresses conversation disclosure",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y7 exposes a named list and active descendant in hero, docked and narrow themes",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y3 protects a retained preparation with text \"retained question\" during search and conversation replay",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y3 protects a retained preparation with text \"\" during search and conversation replay"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts"
      ],
      "class": "New",
      "behaviour": "Y8 preservation: explicit Search keeps existing saved conversations and historical SEARCH projection readable and writes no thread events. Existing sidebar, Recent views and deletion suites remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing the remaining Search window work, areas 1\u20135; WP2.4b recents, R1\u2013R7 and Y1\u2013Y8 in settled scenarios revision 3.",
          "choice": "R1\u2013R7: focused empty Search shows up to eight exact-deduplicated explicit session searches and five conversations sorted by reported activity. Replay uses current restrictions and existing draft/navigation guards. Clear uses an in-memory timestamp and does not mutate history. Fresh windows and profile switches reset transient state; previous-profile responses are dropped. Observed lock/423 hides conversation labels; unlock refreshes. Untitled conversations use explicit fallback copy. Historical SEARCH readers and other Recent surfaces are preserved; no new SEARCH events. Y7 adds measured hero/docked, normal/narrow and light/dark/forced-colours captures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp24b-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#Y8 keeps saved conversations and historical SEARCH readable without writing events"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts"
      ],
      "class": "New",
      "behaviour": "R5a Settings workspace-profile activation, including A\u2192B\u2192A with pending replies, resets searches and Clear while registry-only edits preserve them. R1a keeps keyboard selection in view by scrolling only the Recent list. Real headless full-list fit and reachability at 1280\u00d7720 and 600\u00d7400 are additionally checked by scripts/jseval/tests/test_ui_recents.py; measured axe/proportion recipes add 1280\u00d7720, 760\u00d7720 and 600\u00d7400 in hero/docked and all three themes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing the remaining Search window work, areas 1\u20135; WP2.4b recents, R1\u2013R7 and Y1\u2013Y8 in settled scenarios revision 4, including R5a and R1a/Y7a.",
          "choice": "R5a: both shipped Settings workspace-profile activation and the user-state profile signal reset transient recents and discard old replies. Registry edits without an active ID change do not reset recents. R1a/Y7a: keep the heading/bar and every row reachable down to the supported 600\u00d7400 window; one scroller inside the Recent list, with active-option visibility. Reuse existing compact Search spacing in short hero windows while Recent is open; the corpus introduction may be covered.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/wp24b-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#R5a Settings workspace profile activation resets searches and Clear and drops pending replies",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.recents.test.ts#R1a keyboard selection scrolls the Recent list without scrolling workspace ancestors"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "class": "New",
      "behaviour": "R1a/Y7a replaces the prior blanket no-scroller assertion with a narrow bounded Recent listbox exception, keeps host/column and all other region no-scroller assertions, and requires list-only overscroll containment.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing the remaining Search window work, areas 1\u20135; WP2.4b recents, R1\u2013R7 and Y1\u2013Y8 in settled scenarios revision 4, including R5a and R1a/Y7a.",
          "choice": "R5a: both shipped Settings workspace-profile activation and the user-state profile signal reset transient recents and discard old replies. Registry edits without an active ID change do not reset recents. R1a/Y7a: keep the heading/bar and every row reachable down to the supported 600\u00d7400 window; one scroller inside the Recent list, with active-option visibility. Reuse existing compact Search spacing in short hero windows while Recent is open; the corpus introduction may be covered.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/wp24b-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#the window never scrolls; one inner region does keeps one transcript scroller and bounds separate recovery data and Recent contents"
      ]
    }
  ]
}
```

## Acceptance records
