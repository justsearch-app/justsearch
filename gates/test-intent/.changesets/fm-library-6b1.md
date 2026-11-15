---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: justsearch-fm-s6b
---

# Slice 6b-1 test intent

The entries cite the supplied owner decision and the implementation delegation. Each modified
file and the reason for changing its old assertions is listed individually. Existing reading
hours, skip rules, settings persistence and operation implementations are outside this slice.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl, owner decision Q2 of 2026-11-15 via session justsearch-fd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Reach forced rereading through the page-foot action and in-place Read again confirmation; retain accepted/completed/failed outcomes, force:true, empty-scope disabled state (No folders yet moves to the action title), and offline-folder feedback.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Reach forced rereading through the page-foot action and in-place Read again confirmation; retain accepted/completed/failed outcomes, force:true, empty-scope disabled state (No folders yet moves to the action title), and offline-folder feedback.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. The earlier fm-library changeset cites the old Library menu; the supplied later owner decision Q2 supersedes it. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl, owner decision Q2 of 2026-11-15 via session justsearch-fd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Retire the Library-only picture-off refusal check because Library no longer offers Read waiting files now. Check absence and zero invocations instead. The operation and its entries outside Library remain unchanged.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Retire the Library-only picture-off refusal check because Library no longer offers Read waiting files now. Check absence and zero invocations instead. The operation and its entries outside Library remain unchanged.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. The earlier fm-library changeset cites the old Library menu; the supplied later owner decision Q2 supersedes it. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/presentation.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl, owner decision Q2 of 2026-11-15 via session justsearch-fd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Replace Library-menu and waiting-work expectations with the foot action, no Library menu or waiting action, and resetting the in-place ask when hidden. Keep the top-bar outside-click/Escape checks and Browse recovery, now using Couldn't load this folder / Try again.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Replace Library-menu and waiting-work expectations with the foot action, no Library menu or waiting action, and resetting the in-place ask when hidden. Keep the top-bar outside-click/Escape checks and Browse recovery, now using Couldn't load this folder / Try again.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. The earlier fm-library changeset cites the old Library menu; the supplied later owner decision Q2 supersedes it. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl, owner decision Q2 of 2026-11-15 via session justsearch-fd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Replace the removed menu count-detail assertion with the same literal singular 1 file in the retained folder row. Enter rereading through the foot action and Read again; preserve forced-read outcomes, refreshed file counts and plain failure wording. Browse retry uses Couldn't load this folder / Try again while keeping cached-row and recovery checks.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Replace the removed menu count-detail assertion with the same literal singular 1 file in the retained folder row. Enter rereading through the foot action and Read again; preserve forced-read outcomes, refreshed file counts and plain failure wording. Browse retry uses Couldn't load this folder / Try again while keeping cached-row and recovery checks.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. The earlier fm-library changeset cites the old Library menu; the supplied later owner decision Q2 supersedes it. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/browse-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl, owner decision Q2 of 2026-11-15 via session justsearch-fd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Browse folder failures offer Try again; retain the failed-load recovery-state check and never claim the failed folder is empty.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Browse folder failures offer Try again; retain the failed-load recovery-state check and never claim the failed folder is empty.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. The earlier fm-library changeset cites the old Library menu; the supplied later owner decision Q2 supersedes it. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl, owner decision Q2 of 2026-11-15 via session justsearch-fd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Remove only the obsolete Library More-menu overlay hit test and assert its absence. Keep the real Pause dropdown hit-testing over Browse column heads and both light and dark cases unchanged.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Remove only the obsolete Library More-menu overlay hit test and assert its absence. Keep the real Pause dropdown hit-testing over Browse column heads and both light and dark cases unchanged.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. The earlier fm-library changeset cites the old Library menu; the supplied later owner decision Q2 supersedes it. Private records were not read, per the builder brief."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b1.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl, owner decision Q2 of 2026-11-15 via session justsearch-fd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Implement contract 6b1: remove Library More and picture-only waiting work; use an announced, named, in-page reread ask with Read again / Cancel and focus restoration; keep forced reindex consent/history tracking; singular 1 document from other apps and plural otherwise; no-AI reread completion says Up to date once; Browse loading/failure stays below column heads, hides raw browser errors and offers working Try again; focused toolbar Resume updates to enabled Pause on confirmed running status. Preserve top-bar and Browse context-menu outside-click/Escape dismissal.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Thirteen happy-dom cases cover the seven slice-6b1 obligations, including literal wording, focus, operation arguments and recorded completion, retry recovery, and preserved shared menus. Nine assertion failures on baseline identify the repaired behavior; four cases preserve existing behavior (zero/multiple other-app documents and the two shared-menu dismissal paths).",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/fm-library-6b1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b1.test.ts"
      ]
    }
  ]
}
```

## Execution and follow-up

The formal evidence path above is reserved for the orchestrator's run; it does not exist yet.
Do not treat this changeset as accepted or its prose as generated D4 evidence. The builder cannot
write source Git metadata, so `run-evidence.mjs --before` and publication/scoring gates require the
orchestrator's committed candidate. Node child-process Git calls and `cli.mjs accept` are also
unavailable in this sandbox; acceptance must be independent of the builder.

Manual execution evidence (2026-11-15 UTC; baseline `777b598043`):

- A shared clone under `$TMPDIR/before` received only the new test file. Run from its
  `modules/ui-web`: `node node_modules/vitest/vitest.mjs run --cache=false --maxWorkers=2
  src/shell-v0/views/library-first-minutes/slice-6b1.test.ts`.
- The baseline has nine assertion failures and four preservation passes: the More button and
  waiting action still exist; foot action / ask are absent; one document says documents; completed
  no-AI rereading shows Up to date twice; the root failure is above the list; folder failure exposes
  `Could not load Documents. Load failed Retry`; focused Resume remains Resume. Presence is asserted
  before dereference. The two plural cases and both shared-menu dismissal cases already pass.
- The candidate runs the same command: 13 passed, no skips. Rereading is invoked through today's
  operation client with `core.reindex`, `{args:{force:true}}`, and a consent callback. Completion is
  supplied through the operation-history interface and the displayed completion is asserted.
- `node node_modules/vitest/vitest.mjs run --cache=false --maxWorkers=2
  src/shell-v0/views/library-first-minutes`: 62 passed, no skips.
- The Library*/Browse*/IndexingControls*/Menu*/TopBar*/ContextMenu* default-unit selection:
  24 files / 212 tests passed, no skips. There are no separate Browse* or Menu* unit files;
  their checks are in library-first-minutes and ContextMenu. TopBar and ContextMenu source is unchanged.
- `npx tsc --noEmit`: exit 0 from modules/ui-web.
- `BulkOperationImmediateRefusal.test.ts`: 21 tests fail before assertions with
  `listen EPERM: operation not permitted 127.0.0.1`, on both baseline and candidate. No check was
  skipped or altered to bypass this. The orchestrator must rerun this changed file.
- `.agents/preflight` is absent. `git diff --check` and Python parsing of the changed harness pass.
- `node scripts/docs/llmstxt-generate.mjs` regenerated docs/llms.txt deterministically (no diff).
- `npm --prefix modules/ui-web run knip:report` on a temporary candidate clone fails:
  `knip-report: failed to spawn knip: spawnSync /usr/bin/node EPERM`. Running its installed Knip
  JS entry directly produces valid JSON (exit 1, the expected issue-report status).
- The dead-code gate with that report returns `dead-code: pass` (12 informational findings),
  but warns that baseline `HEAD~1` is unreachable and disables pin-bump detection in this sandbox.
  That incomplete baseline comparison still requires the orchestrator's normal gate run.

The candidate clone used for Knip and its report, the baseline clone, and all logs are under
`$TMPDIR`. Temporary clones linked this worktree's installed dependencies; Vite also used its
normal `.vite-temp` config directory there (empty after each run). No other session's dependencies
were touched. No Engine, shared dev stack, browser, desktop window, or GPU was used. No commits, staging, fetching or
publishing occurred.

## Real-browser reruns for the orchestrator

These lockdown files directly reference Library, Browse or the reading toolbar. They were located
by searching for the surface classes/tags and Pause/Resume labels; none was executed in this sandbox.
The two changed browser files are included. Run the relevant full application checks in light
and dark, including foot-action keyboard/announcement behavior and toolbar focus.

- `modules/ui-web/src/boot/boot-frame-lockdown.test.ts`
- `modules/ui-web/src/boot/boot-lockdown.test.ts`
- `modules/ui-web/src/boot/desktopChrome-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/FirstMinutes.app-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/FirstMinutes.countWords-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/FirstMinutes.repairs-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/FirstUse.engine-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/Shell.details-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/Shell.notice-layout-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/components/ActionButton.pendingFocus-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/LibrarySurface.bandFocus-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/LibrarySurface.interruptedImports-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/LibrarySurface.refresh-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/SurfaceReach-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/library-first-minutes/browse-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/library-first-minutes/review-repairs-lockdown.test.ts`

Also preserve the shared top-bar menu in:

- `modules/ui-web/src/boot/boot-topbar-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/chrome/TopBar.placement-lockdown.test.ts`

## Acceptance records

Pending independent verification and acceptance on the committed candidate.
