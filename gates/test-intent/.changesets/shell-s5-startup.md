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
including assertion failures on the isolated baseline and passing preservation cases.
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

SA6 also exercises real shell controls rendered beneath the frame while the first-render
completion promise is held. The application is inert before attachment, and its hand-off
releases inert when it removes either the frame or a failure page. Six Tabs and a coordinate
click reach no shell input during that hold; after hand-off, Tab/Enter and a coordinate click
open the real More menu. A shell held before rendering remains inert through the deadline,
and the failure page's Reload takes focus. Late completion replaces that page without
moving focus into the shell. These three checks fail by assertion on isolated candidate
`a8bc1ed1f814cee97e3b935356ee6519779a450d`: the rendered shell receives five key events
and one click, and its More menu opens; the never-rendering shell also receives a click.
`gates/test-intent/evidence/shell-s5-frame-inert.json` records those fail-before results.
`shell-s5-new-behavior.json` has been regenerated for every check in the changed file,
including its recovery/reset takeover, deadline, appearance, accessibility and CSP checks.
The source is SA6: “The frame is inert: nothing focusable or clickable, keys do nothing,
and assistive technology hears \"Starting\" once.” (task t-20261103-shell, SA6 repair request).

The serial repair-evidence run passed all 57 frame checks; the same file on `a8bc1ed1`
passed 54 and failed precisely the three new SA6 checks, by assertion. An earlier overlapping
evidence invocation reported all three selected checks passing but its full-file subprocess
exited 1; the producer discarded the unselected case details. The separate whole-file evidence
run and the serial replay both passed all 57. No test or application change was made between
these runs. The serial replay retained its raw Vitest reports under the session's temporary
folder for inspection.

The proportion register's capture-state note deliberately records the transient frame/failure
coverage at 1280x800, 900x800 and 600x400. Every persistent-chrome floor and ceiling is retained.

Evidence used the unchanged official producer through temporary command wrappers: Vitest
runs received `--maxWorkers=4`; before checkouts were shared clones under TMPDIR rather than
worktrees registered in the source repository. Dependencies were linked package by package,
leaving each clone its own writable cache directories. No test or result was intercepted.

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
