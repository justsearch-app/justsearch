---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s5a-commit-race-builder
---

# Startup frame observations and search-summon timer cleanup

The startup frame suite keeps every existing product expectation, fault injection,
input, deadline and selection. No product file changes. All five `commit` navigation
sites now wait up to 4 seconds for the inline script's observable theme/frame state,
including cached accent restoration after reload. A committed response alone does
not establish that the parser has reached the inline script.

The test harness counts completed shell renders so late-render preservation checks
observe completion before checking that failure/recovery/reset still owns the root.
Frame sampling checks wait for actual animation-frame samples rather than elapsed
milliseconds; the menu assertion polls for the opened menu. No fixed sleeps remain.
The previously repaired, paused fake clocks and their exact deadline assertions stay.

The unchanged official evidence producer exited 0:
`node scripts/governance/gates/test-intent/run-evidence.mjs --out gates/test-intent/evidence/shell-s5a-commit-race.json --check modules/ui-web/src/boot/boot-frame-lockdown.test.ts`.
Its evidence records all 67 checks executing and passing, with zero skips, against a
fresh production build and headless Chromium. `npm run typecheck` and `npm run lint`
both exited 0. `.agents/preflight` is absent.

The SA3 diagnosis and negative control used an isolated shared clone of
`8531170e065ad25495dd62c3d4ff1136b883a25d` under TMPDIR, with an independent copy
of node_modules and a separate production build for each run. Commands in that
clone's `modules/ui-web` were:
`node node_modules/vitest/vitest.mjs run src/boot/boot-frame-lockdown.test.ts --config vitest.config.lockdown.ts -t 'SA3 query theme wins' --reporter=json --outputFile=<per-run-report>`.

For the race reproduction, the SA3 test routed only `?theme=dark` HTML and inserted
`<script src="/parser-gate.js"></script>` immediately before the existing inline
script. Its route fulfilled an empty JavaScript response after 750 ms. The unmodified
baseline observation failed by assertion: `expected null to be 'dark'`. With the
candidate's wait, the same delayed-parser check passed. This isolates parsing after
commit; it does not simulate an incorrect product theme.

For the negative control, the candidate test ran without the parser delay, and only
the clone's `index.html` changed `var query = new URLSearchParams(location.search).get('theme');`
to `var query = null;`. The existing saved light theme then wins incorrectly. SA3
failed at its required dark-theme wait: `page.waitForFunction: Timeout 4000ms exceeded`.
The main worktree's product files were never changed. No test or result was intercepted.

The exact candidate then passed ten consecutive production-build suite runs under
four owned Python CPU busy loops, using the same Vitest command without the SA3
filter and with a separate JSON report per run. Every run passed 67/67, failed zero
and skipped zero. Elapsed seconds in order: 48.49, 37.08, 35.92, 50.9, 53.4, 40.81, 48.93, 49.95, 41.2, 38.94.
All four load processes were alive and accumulated CPU time across every run;
they were terminated and waited for by their owner after run ten. The repeat
runner exited 0. Checks touched this machine's CPU, their own temporary production
builds, preview servers, HTTP fixtures and headless browsers. No owner's app,
shared dev stack or GPU was used, and no cache was cleared.

The governance command, `node scripts/governance/gates/test-intent/cli.mjs
--base origin/main`, initially resolved base `8531170e065ad25495dd62c3d4ff1136b883a25d`
and exited 1 with the file covered and only the missing independent acceptance
record. The final invocation instead resolved `982069c83b34`: the shared origin/main
reference advanced externally during this session. That invocation exited 1 with
nine unrelated uncovered files as well as the missing acceptance record:

- `modules/ui-web/src/shell-v0/components/FailedFilesList.buttons-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/components/rebuildLabel.test.ts`
- `modules/ui-web/src/shell-v0/display/format-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts`
- `modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts`
- `modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts`
- `modules/ui-web/src/shell-v0/views/HelpSurface.test.ts`
- `modules/ui-web/src/shell-v0/views/LibrarySurface.refresh-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts`

The requested final origin/main gate result is therefore blocked by the changed
base. No fetch, rebase, commit or source-index change was performed. No entries were
invented for unrelated files. The final diff, using an isolated temporary index to
include the two untracked additions, contains exactly the frame test, this changeset
and its newly produced evidence. Independent verification and acceptance remain
the orchestrator's work. Both gate invocations also emit the existing non-blocking
reconsider-rate warning: 12 of 48 audited PRs (25.0%); the orchestrator must carry this
finding into delivery.

The continuation pins the unhandled-rejection source to SC2's real Search v3
composer draft test: its input event calls the shared search store's `setQuery`,
which starts a 200 ms debounce. Removing the shell does not own that store's
lifetime; without reset, a quick response schedules a refined pass 600 ms later
and also leaves the slow-hint timer pending. Later probe-only tests never change
the query. The refined callback can run after happy-dom removes `window`.

The existing `resetSearchState` cancels debounce, settle and slow timers, aborts
in-flight requests and advances the generation. Cleanup now invokes it after
shell removal. The fake-clock check confirms the draft really scheduled work,
advances through the quick pass, and checks zero timers after cleanup. This pins
the reported refined-pass path deterministically without suppressing any error.

The continuation's before state is a shared clone of `8531170e06` inside TMPDIR,
with an independent ordinary copy of node_modules. Only the candidate test's
instrumentation is transplanted there, omitting the cleanup reset. Command:
`node node_modules/vitest/vitest.mjs run src/shell-v0/chrome/Shell.searchSummon.test.ts
--reporter=json --outputFile=<temporary report>`.
It exits 1: 17 pass, and the real-composer case fails by assertion in afterEach:
`AssertionError: expected 2 to be +0`. The candidate passes 18/18 with zero skips.
No production code was changed in either checkout.

The candidate's isolated search-summon command, `npx vitest run
src/shell-v0/chrome/Shell.searchSummon.test.ts`, then passed ten consecutive times:
18/18 cases each, zero skips, exit 0, no unhandled errors. Elapsed seconds:
5.47, 6.62, 6.00, 6.54, 5.58, 5.24, 4.74, 4.26, 4.22, 4.56.
`npm run typecheck`, `npm run lint` and `git diff --check` also exit 0.
The official producer regenerated all 18 cases in
`gates/test-intent/evidence/shell-s5a-search-summon.json`, exit 0.

The required full run, `npx vitest run --maxWorkers=4`, exits 0:
736/736 files and 9,381/9,381 tests pass in 145.91 seconds. Its complete log has
no `Unhandled Errors`, `Unhandled Rejection`, `Uncaught Exception` or
`ReferenceError: window is not defined` reports.

The continuation checks use this machine's CPU and disk, isolated temporary
checkouts and test-owned loopback HTTP fixtures, production builds, preview servers
and headless browsers. The shared dev stack, owner's apps and GPU were not used.
No caches were cleared, no browser was installed, and no commit, push, fetch or
rebase was performed. `.agents/preflight` remains absent.

The startup-frame suite was rerun through the unchanged official evidence
producer with its own `vitest.config.lockdown.ts`, fresh production build and
headless Chromium. It exits 0, all 67 checks pass, zero skips, and overwrites
`gates/test-intent/evidence/shell-s5a-commit-race.json` with fresh execution evidence.
Both changed test files therefore have regenerated evidence for this candidate.

The continuation uses `--base 8531170e06` for the final test-intent check, as
requested, even though the current local origin/main resolves to `982069c83b34`.
The previous origin/main-based gate result above is historical and superseded by
this explicit base. No fetch or rebase is performed.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/boot-frame-lockdown.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Observe the inline script's theme/frame output after commit with explicit 4000 ms bounds; observe completed held-shell renders, actual animation-frame samples and opened menus instead of assuming elapsed time is sufficient. Every original expected value, input, fault injection, precondition, tag and deadline is retained. The official producer regenerated execution evidence for all 67 checks in gates/test-intent/evidence/shell-s5a-commit-race.json. A parser-blocked baseline SA3 fails with null instead of dark; the candidate passes that delay, and fails when the isolated clone's inline script ignores the query theme."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.searchSummon.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Keep every summon/focus/draft/URL/journal expectation. Own setTimeout/clearTimeout with a fake clock in the real Search v3 composer test, assert typing schedules work, and advance through the quick pass so the refined-pass and slow-hint timers are pending. After shell removal and existing cleanup, reset the shared search store through its existing resetSearchState API; assert zero pending timers before restoring the real clock. The settle helper advances the fake clock only when active. No product code or Vitest error settings change. Execution evidence for all 18 checks is in gates/test-intent/evidence/shell-s5a-search-summon.json. The baseline shared clone with this instrumentation but without the cleanup reset fails the zero-timer assertion with 2 pending timers; the candidate passes."
    }
  ]
}
```

## Acceptance records
