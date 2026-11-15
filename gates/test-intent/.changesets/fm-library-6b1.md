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
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Reach forced rereading through the page-foot action and in-place Read again confirmation; retain accepted/completed/failed outcomes, force:true and offline-folder feedback. With no watched folders the action is not shown (replacing the disabled state titled No folders yet); while a read-again request is in flight it is blocked (aria-disabled, activation ignored) and keeps keyboard focus.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Reach forced rereading through the page-foot action and in-place Read again confirmation; retain accepted/completed/failed outcomes, force:true and offline-folder feedback. With no watched folders the action is not shown (replacing the disabled state titled No folders yet); while a read-again request is in flight it is blocked (aria-disabled, activation ignored) and keeps keyboard focus.",
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
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
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
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
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
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
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
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
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
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
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
        "modules/ui-web/e2e/type-ladder-chromium.mjs"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Audit the new in-place Read everything again ask in the desktop-CSP typography walk. The walker opens every collapsed disclosure; its fixture had no watched folder, so Library correctly offered no reread (before this repair, a disabled control the walker could not open). The fixture now answers /api/indexing-roots/substrate with one watched folder matching its existing /api/indexing/roots folder, so the walker reaches the folder row and the ask in both themes. The walker itself is unchanged.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The typography audit fixture supplies one watched Library folder so the folder row and the Read everything again ask are captured and checked in dark and light.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "The Library fixture has no watched folder (substrate items: []).",
          "newAssertion": "The Library fixture has one watched folder (csp-root), the same folder the fixture already lists for Browse.",
          "reason": "Observed run of this candidate (headless Chromium 149): 168 surfaces, 0 violations; main's count is 164. Instrumented copy of the walker (labels only) shows dark and light library-tab-1-expanded-5 = Read everything again and library-tab-1-expanded-2 = the watched folder row; all library checks pass. On dac30b0694 the audit reported 4 violations: dark/light library-tab-1-expanded-4 and library-developer-expanded-1, elementHandle.click timeout, element is not enabled."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/type-ladder-lockdown.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Audit the new in-place Read everything again ask in the desktop-CSP typography walk. The walker opens every collapsed disclosure; its fixture had no watched folder, so Library correctly offered no reread (before this repair, a disabled control the walker could not open). The fixture now answers /api/indexing-roots/substrate with one watched folder matching its existing /api/indexing/roots folder, so the walker reaches the folder row and the ask in both themes. The walker itself is unchanged.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The desktop-CSP typography audit must capture exactly 168 surfaces with zero violations; the count cites the observed run.",
      "conflictSearch": "Repository docs/decisions searched for Library, Browse, menus and reading again: no presentation decision conflicts. Private records were not read, per the builder brief.",
      "assertionChanges": [
        {
          "oldAssertion": "Exactly 164 surfaces and zero violations.",
          "newAssertion": "Exactly 168 surfaces and zero violations (the folder row disclosure and the Read everything again ask, each in two themes).",
          "reason": "Observed run of this candidate (headless Chromium 149): 168 surfaces, 0 violations; main's count is 164. Instrumented copy of the walker (labels only) shows dark and light library-tab-1-expanded-5 = Read everything again and library-tab-1-expanded-2 = the watched folder row; all library checks pass. On dac30b0694 the audit reported 4 violations: dark/light library-tab-1-expanded-4 and library-developer-expanded-1, elementHandle.click timeout, element is not enabled."
        }
      ]
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
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Implement contract 6b1: remove Library More and picture-only waiting work; use an announced, named, in-page reread ask with Read again / Cancel and focus restoration; keep forced reindex consent/history tracking; Escape on either answer cancels like Cancel, runs nothing and returns focus to the action, while Escape outside the ask leaves it open; with no watched folders the action is absent from the whole Library tree, menus included; while a read-again request is in flight the action is aria-disabled, ignores activation and keeps focus; singular 1 document from other apps and plural otherwise; no-AI reread completion says Up to date once; Browse loading/failure stays below column heads, hides raw browser errors, names the folder in each folder failure line and offers working Try again; focused toolbar Resume updates to enabled Pause on confirmed running status. Preserve top-bar and Browse context-menu outside-click/Escape dismissal.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Nineteen happy-dom cases cover the slice-6b1 obligations, including literal wording, focus, operation arguments and recorded completion, retry recovery, and preserved shared menus. 6b1-8 checks that with no watched folders no element anywhere in the Library shadow tree, including an opened menu, says Read everything again; that the ask closes when the last folder leaves; and that while a read-again request is in flight the focused action is aria-disabled, keeps focus and ignores activation. 6b1-2 Escape cases check that Escape on Read again or Cancel closes the ask, is consumed there, runs nothing and returns focus to the action, and that Escape outside the ask leaves it open. 6b1-5 checks that two failing folders give two failure lines naming Documents and Photos. On baseline 777b598043 fifteen fail by assertion and four pass (zero/multiple other-app documents and the two shared-menu dismissal paths); the no-folders case finds the disabled item in the opened old menu. On 5a21ba3003 the in-flight, both Escape-on-answer and several-failures cases fail by assertion.",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/fm-library-6b1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b1.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b1-lockdown.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the Library \"more\" button is removed together with the Library menu; \"Read everything again\" becomes a quiet text action at the page foot beside \"Activity\" and asks in place before starting. The shared menu component stays for the top bar and Browse's context menu.",
          "location": "task t-20261113-fmimpl agreement, owner decision Q2 of 2026-11-15 (delegated answer via session justsearch-fd)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Check the in-place Read everything again ask in a real browser, where disabling a focused button drops focus to the page: after confirming with the keyboard, focus stays on the action through a held request and after it settles, the action ignores Enter/Space while blocked, and Escape on either answer closes the ask, runs nothing and returns focus to the action.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Two Chromium cases mount Library over a stubbed backend with one watched folder and a held read-again request. One confirms with the keyboard and asserts focus is on Read everything again while the request is held and after it settles, that it is aria-disabled meanwhile and that Enter/Space neither reopen the ask nor invoke again. The other presses Escape on Read again and on Cancel and asserts the ask closes, focus returns to the action and nothing is invoked. On 5a21ba3003 they fail by assertion (focus is on the page body; the ask stays open after Escape). On baseline 777b598043 both fail by assertion because there is no Read everything again button.",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/fm-library-6b1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/library-first-minutes/slice-6b1-lockdown.test.ts"
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

Repair for the typography audit (2026-11-15 UTC, on dac30b0694 with uncommitted changes):

- Cause: the audit fixture answered `/api/indexing-roots/substrate` with no folders, so Library
  rendered Read everything again disabled and the walker's click timed out (4 violations).
- Product: with a confirmed empty folder list the action and its ask are not rendered; the ask
  closes if the last folder leaves; the action is disabled only while a read-again request is in flight.
- `npx vitest run -c vitest.config.lockdown.ts src/boot/type-ladder-lockdown.test.ts`: 1 passed
  (direct script run: 168 surfaces, 0 violations).
- `npx vitest run src/shell-v0/views/library-first-minutes src/shell-v0/views/LibrarySurface.folderActions.test.ts
  --exclude '**/*-lockdown.test.ts'`: 90 passed. Lockdown config on library-first-minutes: 10 passed.
  `npx tsc --noEmit`: exit 0.

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

## Review repairs (2026-11-16 UTC, on 5a21ba3003 with uncommitted changes)

- Escape on either answer of the in-place ask cancels it (handler on the ask's region, consumed there);
  the action is aria-disabled rather than disabled while a confirmed request is in flight, so focus stays.
- Browse folder failure lines name their folder (`Documents: Couldn't load this folder`).
- The harness step index and the a11y/proportion baselines use `library-reread(-light)`; the proportion
  rows assert the ask region, the expanded action and no `More Library actions`, and Browse failure rows
  require `Try again`. `scripts/jsharness/tests/test_ui_step_index.py` now also checks that every
  baseline row names a registered step (out of test-intent scope: scripts/ self-tests).
- `run-evidence.mjs --before origin/main` covers slice-6b1.test.ts and slice-6b1-lockdown.test.ts:
  both pass on the candidate and fail by assertion on 777b598043.
