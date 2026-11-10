---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s5-builder
---

# S5: paint before the app; a static failure page without application code

The two isolated new test sources are exempt from entries. They are durable checks:
`src/boot/boot-frame-lockdown.test.ts` covers SA1–SA18, X1/X2, startup races, privacy,
real production assets and hashed inline CSP in Chromium; `bootAppearanceRestore.test.ts`
covers restore success/failure, forward-schema preservation, migration and history.
`gates/test-intent/evidence/shell-s5-new-behavior.json` records every check in both files,
including passing preservation cases. Current assertion fail-before evidence is recorded in
`shell-s5-frame-inert.json`.
The extra production-build entry, native IPC and HTTP doubles, blocked resources and fake
clock are test scaffolding only. The deadline also survives a connecting interval longer than
60 seconds: it cannot replace connecting, but still applies when the frame returns. This check
failed by assertion on the pre-repair S5 candidate in an isolated shared clone. The standard build contains no test hook or timeout override.
The 60-second production deadline is tested with Playwright's clock. Light and dark failure
pages run axe at ordinary and enlarged text. A light-mode check also failed on the pre-repair
S5 candidate: the early token closure omitted the white ink read by the inline contrast probe;
including that existing canonical primitive fixes the Reload button without adding a colour. The two-window check
allows 15 seconds for two complete fixture boots and their initial settings reads; its cache
assertions are unchanged by that setup allowance. All nine CI flow names remain unchanged.

SA6 exercises real shell controls rendered beneath the frame while the first-render
completion promise is held. Inert remains on the application; a window capture guard,
registered before attachment, stops immediate propagation. The input checks require no
application effect for every covered event; only F5 and Ctrl+R are required to retain
uncancelled browser defaults. Other covered defaults may be cancelled by a correct implementation.
The listener inventory in `modules/ui-web/src` confirms the specified key, pointer, mouse,
wheel, touch, input and drag events, including document mouseenter/mouseleave in
`HoverPreviewHost.ts`, window scroll there, mouse Back/Forward in `Shell.ts`, window
keydown in `KeybindingRegistry.ts`, and window drag/drop in `dragDetect.ts`. There are no
additional native input types in document/window listeners. Contextmenu and paste have no
current document/window listener but are also guarded to cover the requested input table.
Lifecycle, storage and application CustomEvents remain outside this input inventory.

The failure function replaces every root child: a shell that attached but never completed
its first render is discarded at the unchanged deadline. No live shell sits beneath the
failure page. A shell mounting after failure replaces that page immediately, without inert,
a guard or a render wait. Recovery/reset replacements remove the guard; its per-event
attachment check also releases it synchronously if a replacement has just happened.
The frame markup, deadline and recovery/reset implementations are unchanged.

The earlier “late failure-page hand-off” branch was retired because it required a shell
finishing beneath that page, a state the binding redesign forbids. The never-rendering
check now requires no shell at the deadline, working Reload and no reclamation by a late
render. The earlier SA10 attached-shell deadline check likewise requires disposal, while
the stalled-catalog check now holds rendering and requires a genuinely late mount to
replace failure before its first render. These expectations implement the settled wording:
SA10: “A boot that stalls with the frame still showing raises the failure page after
60 seconds. If boot then finishes and mounts the shell, the shell replaces the page at once.”
SA17: “The frame stays until the shell has painted its first render, so no blank interval
opens between frame and shell. If the shell attaches but never renders, the 60-second
deadline discards that shell and raises the failure page, so no live shell ever sits beneath
the page; Reload starts again. What happens after that first render (navigation stuck,
Search loading) is the shell's own loading state, a named limit here.”

The complete frame file passed all 67 checks on the repaired tree. Against isolated
candidate `5ef0ec6cc32d53dfadc84f12ced4e43b7335d979`, all seven named regression checks still
failed by assertion: every input path leaked at window/document, shortcuts reached the
shell, pointer events reached global listeners, failure retained rendered or unrendered
shells, and late mounting waited for rendering. `shell-s5-frame-inert.json` records the
whole-file result and each of these seven named assertion failures. It replaces the older
inert-only evidence, whose scenarios allowed an attached shell beneath failure.
`shell-s5-new-behavior.json` records fresh execution of every check in the changed frame
file and the unchanged appearance-restoration checks.

The input checks include all requested shell shortcuts, Tab and Shift+Tab; trusted pointer
movement/click/wheel/context menu; mouse Back/Forward; document and direct-window dispatch
of every inventoried event, drag/drop and paste; failure-page Tab/Shift+Tab, Enter and Space
on Copy report and Reload; and recovery/reset keyboard actions after replacing either cover.
After normal hand-off, every chord reaches the shell again, real Library/Help navigation
works, and Tab/Enter and a coordinate click open More. F5 and Ctrl+R retain uncancelled
native-event defaults in the frame, failure and painted states; these browser checks do
not claim to exercise an operating system's physical keyboard routing.

The proportion register's capture-state note deliberately records the transient frame/failure
coverage at 1280x800, 900x800 and 600x400. Every persistent-chrome floor and ceiling is retained.

Evidence used the unchanged official producer through temporary command wrappers: Vitest
runs received `--maxWorkers=4`; before checkouts were shared clones under TMPDIR rather than
worktrees registered in the source repository. Dependencies were linked package by package,
leaving each clone its own writable cache directories. No test or result was intercepted.

Earlier validation after removing the extra default-cancellation constraints:
`npm run typecheck` and `npm run lint` exited 0.
The single final-tree `npx vitest run --maxWorkers=4` passed 735 files / 9,354 tests
in 153.05 seconds. Both official evidence producer invocations exited 0. Each ran the
frame suite with `vitest.config.lockdown.ts` against its own production build: 67/67
passed in both runs. The unchanged appearance-restoration file passed 7/7. The before
run on `5ef0ec6cc32d53dfadc84f12ced4e43b7335d979` retained assertion failures in all seven
named regression checks, including application input reaching window/document listeners.
The F5 and Ctrl+R default assertions remain unchanged and passed at all three stages.
`.agents/preflight` is absent. No product file changed.
All browser runs were headless. Checks owned their preview servers, browser contexts and
HTTP fixtures; no shared dev stack was started or used, and no dependency cache was cleared.
The governance check against `276da8de3f` reports only the missing independent acceptance
record. Its non-blocking reconsider-rate warning reports reviewer disagreements or third
reviewer tie-breaks in 12/48 audited PRs (25.0%); the orchestrator must carry that finding
into delivery. Independent verification and acceptance remain the orchestrator's work.

CI clock determinism follow-up on `c58ffb350205865438803e18fe39b083559f0b63`:
Playwright's installed clock continues advancing with real elapsed time. In an isolated
shared clone of that head, adding a 1,200 ms setup delay before the original 59,000 ms
advance reproduced the reported assertion failure: `expected 1 to be +0`. A separate
clock probe measured 60,200 ms after the real delay and 59,000 ms advance; with the
clock paused it measured exactly 59,000 ms.

All nine clock setup sites now install and pause at a known time before navigation,
including the shared held-shell setup used by input and replacement checks. Reloads
in the rendered-controls deadline check also pause before navigating again. Time spent
loading assets, probing controls or waiting for HTTP can no longer consume the deadline.
The attached-shell check requires absence of failure at 59,999 ms and its presence at
60,001 ms, retaining shell disposal and late-render checks. Resume still releases held
rendering and discovery where those scenarios require it. No product code changed.

The revised deadline check passed with the same 1,200 ms setup delay in the shared
clone. With the candidate test unchanged and only that clone's production HTML deadline
changed from 60,000 to 59,999 ms, it failed at the pre-deadline absence assertion:
`expected 1 to be +0` (the failure page already existed). Thus pausing removes wall-time
drift without accepting even a one-millisecond-early deadline. Each check built its own
production assets and used headless Chromium; the main worktree's product files were
never changed. The existing independent acceptance record below is deliberately retained
and becomes stale for the orchestrator to replace.

The complete boot-frame suite then passed five consecutive production-build runs under
four owned CPU busy loops: 67 passed, zero failed and zero skipped in every run. The
commands were `node node_modules/vitest/vitest.mjs run src/boot/boot-frame-lockdown.test.ts
--config vitest.config.lockdown.ts --reporter=json --outputFile=<per-run temporary report>`
from `modules/ui-web`; elapsed times were 43.96, 42.40, 44.96, 41.80 and 39.74 seconds.
All four load processes were alive through each run and were stopped and waited for by
their owner afterwards. `npm run typecheck` and `npm run lint` both exited 0.
`.agents/preflight` is absent. Checks touched the machine's CPU and their own temporary
builds, preview servers and headless browsers. No shared application, dev stack or GPU was
used; dependencies in temporary clones were independent copies, and no cache was cleared.

Both unchanged official evidence-producer invocations exited 0 on this candidate.
`shell-s5-frame-inert.json` and `shell-s5-new-behavior.json` each record all 67 frame
checks passing with the current test content digest; the latter also records the unchanged
seven appearance-restoration checks passing. The former retains assertion failures for
all seven named regressions on `5ef0ec6cc32d53dfadc84f12ced4e43b7335d979`. In particular,
the attached-shell check now fails there at shell disposal after 60,001 ms, rather than
at the wall-time-sensitive pre-deadline assertion. The producer's temporary Git adapter
used shared clones for its before checkout; all clone dependencies were copied, keeping
writable caches separate. No source-repository worktree registration was created.

The required `node scripts/governance/gates/test-intent/cli.mjs --base origin/main`
exited 1 against `1d4755a67f6c90b8957c28f9e717db823039c05e`: alongside the expected
stale acceptance, it reports seven uncovered items. This is an existing branch/base
problem, reproduced by running the unchanged CLI against a fresh shared clone of the
unmodified head `c58ffb350205865438803e18fe39b083559f0b63` with that same explicit base:
its original acceptance is valid, but the same seven uncovered items already fail.
They are:

- `modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationMoveSafetyTest.java`
- `modules/app-agent/src/test/java/io/justsearch/agent/tools/FileOperationsToolMoveResultTest.java`
- `modules/ui-web/src/boot/boot-frame-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/state/bootAppearanceRestore.test.ts`
- `modules/ui-web/src/shell-v0/views/BrainSurface.removalBand-lockdown.test.ts`
- `modules/ui-web/src/shell-v0/views/BrainSurface.remove-files-witness.test.ts`
- `modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts`

The main-worktree gate also emits the non-blocking reconsider-rate warning: 12 of 48
audited PRs (25.0%) had reviewer disagreements or third-reviewer tie-breaks. The
orchestrator must report that finding. Resolving the pre-existing coverage failures is
outside this clock assignment; no entries were invented to cover unrelated changes,
and no fetch or rebase was performed. Only the frame test, its two evidence files and
this changeset differ from HEAD; the existing acceptance record is byte-identical.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "With no engine address in a browser, call the static boot failure function with the fixed no-engine-address kind. The desktop IPC/listener assertions remain unchanged. The former plain alert is intentionally replaced by SA8.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SA8",
          "quote": "Failure page (FL4) appears for: entry script or static dependency failing to load or parse; an uncaught exception in bootstrap; browser with no engine address (replaces the plain alert).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Implement the design.",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "Implement S5 within the settled scenarios and binding design, including its acceptance checks.",
          "choice": "Use a single Vite HTML transform for the release version and the static frame token closure; report appearance restore success explicitly and route browser no-address failure to the static page.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s5-browser-no-address.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopRecovery.test.ts#keeps browser unresolved behavior and never invokes native recovery commands"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/themeState.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The unreachable-settings result is now false rather than undefined because restoreAppearanceOnBoot reports whether restoration succeeded. The same rejected fetch, no-throw obligation and unchanged data-theme assertion remain. No appearance behavior is removed. All checks in the file executed again; see shell-s5-preservation.json.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "restoreAppearanceOnBoot catches failures and currently gives no signal, so have it report whether it truly succeeded",
          "location": "task t-20261103-shell request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Implement the design.",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "Implement S5 within the settled scenarios and binding design, including its acceptance checks.",
          "choice": "Use a single Vite HTML transform for the release version and the static frame token closure; report appearance restore success explicitly and route browser no-address failure to the static page.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/vite.config.js"
      ],
      "class": "Structural rule kept",
      "rule": "Vitest keeps exactly the existing environment, include/exclude patterns, globals and setup files; default and lockdown suites keep their separate selection.",
      "whySurvives": "The new Vite plugin transforms served/built HTML only. The test configuration is unchanged. The scope scanner conservatively flags the local variable version. Full default suite: 735 files and 9354 checks passed. The original production boot lockdown suite: 5 checks passed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "SA13",
          "quote": "The version shown is the release version from the build (gradle.properties is canonical), never 0.0.0.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Implement the design.",
          "delegationLocation": "task t-20261103-shell request",
          "delegationScope": "Implement S5 within the settled scenarios and binding design, including its acceptance checks.",
          "choice": "Use a single Vite HTML transform for the release version and the static frame token closure; report appearance restore success explicitly and route browser no-address failure to the static page.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1c293-f36f-7ab1-b5e0-d663a75de0ae",
  "verdict": "accept",
  "digest": "sha256:1cd4073433f1cb6c1d80ecf82ed5c5a9dcd1e846d08e5935d78c9bd68c624640"
}
```
